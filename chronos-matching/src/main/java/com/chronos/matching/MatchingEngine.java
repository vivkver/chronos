package com.chronos.matching;

import com.chronos.core.domain.ExecType;
import com.chronos.core.domain.OrderType;
import com.chronos.core.domain.Side;
import com.chronos.core.lob.OffHeapOrderBook;
import com.chronos.core.util.ChronosMetrics;
import com.chronos.schema.sbe.ExecutionReportEncoder;
import com.chronos.schema.sbe.MessageHeaderEncoder;
import com.chronos.schema.sbe.NewOrderSingleDecoder;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.Int2ObjectHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single-threaded, zero-allocation matching engine designed for pinned-core
 * execution inside an Aeron Cluster {@code ClusteredService}.
 *
 * <h2>Architecture</h2>
 * <ul>
 * <li>Receives decoded NewOrderSingle messages from the cluster sequencer</li>
 * <li>Uses {@link VectorizedPriceScanner} for SIMD price level scanning</li>
 * <li>Fills orders greedily (price-time priority) across multiple levels</li>
 * <li>Emits ExecutionReport SBE messages into a pre-allocated output
 * buffer</li>
 * <li>Uses only cluster-provided timestamps for full determinism across
 * replicas</li>
 * </ul>
 *
 * <h2>Zero-Allocation Guarantee</h2>
 * <p>
 * All buffers, encoders, and decoders are pre-allocated at construction.
 * The hot path ({@link #matchOrder}) performs zero heap allocations.
 * </p>
 */
public final class MatchingEngine {

    private static final Logger LOG = LoggerFactory.getLogger(MatchingEngine.class);

    /**
     * Maximum bytes the engine can write per {@link #matchOrder} call.
     * Sized for the worst-case multi-level sweep (many execution reports)
     * plus the incoming order's own report.
     */
    static final int OUTPUT_BUFFER_CAPACITY = 4096;

    /** SBE size of one full execution report (header + body). */
    private static final int EXEC_REPORT_SIZE = MessageHeaderEncoder.ENCODED_LENGTH
            + ExecutionReportEncoder.BLOCK_LENGTH;

    private final Int2ObjectHashMap<OffHeapOrderBook> orderBooks;
    private final PriceScanner priceScanner;

    // Pre-allocated encoders (flyweight, reused per call)
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final ExecutionReportEncoder execEncoder = new ExecutionReportEncoder();

    // Monotonic execution ID counter
    private long nextExecId = 1;

    public MatchingEngine(final Int2ObjectHashMap<OffHeapOrderBook> orderBooks,
            final PriceScanner priceScanner) {
        this.orderBooks = orderBooks;
        this.priceScanner = priceScanner;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Public API
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Process a decoded NewOrderSingle. Attempts to match against resting orders,
     * then rests any remaining quantity as a passive order.
     *
     * @param decoder          pre-wrapped decoder over the incoming message
     * @param clusterTimestamp deterministic timestamp from the Aeron cluster
     * @param outputBuffer     pre-allocated buffer to write ExecutionReport
     *                         messages into
     * @param outputOffset     starting offset in the output buffer
     * @return total bytes written to the output buffer (may contain multiple
     *         reports)
     */
    public int matchOrder(final NewOrderSingleDecoder decoder,
            final long clusterTimestamp,
            final MutableDirectBuffer outputBuffer,
            final int outputOffset) {

        assert outputOffset + OUTPUT_BUFFER_CAPACITY <= outputBuffer.capacity()
                : "Output buffer too small for worst-case fill reports";

        final long orderId = decoder.orderId();
        final long price = decoder.price();
        final long clientId = decoder.clientId();
        final int instrumentId = decoder.instrumentId();
        final int originalQty = decoder.quantity();
        final byte side = decoder.side();
        final byte orderType = decoder.orderType();

        // ─── Guard: unknown instrument ───
        final OffHeapOrderBook orderBook = orderBooks.get(instrumentId);
        if (orderBook == null) {
            LOG.error("Unknown instrument ID: {}", instrumentId);
            ChronosMetrics.onOrderRejected();
            return writeExecReport(outputBuffer, outputOffset,
                    orderId, price, clientId, clusterTimestamp,
                    instrumentId, 0, originalQty, side, ExecType.REJECTED);
        }

        // ─── Aggressive matching phase ───
        final int[] aggressiveResult = sweepAggressively(
                orderBook, price, originalQty, side, orderType,
                clusterTimestamp, outputBuffer, outputOffset, clientId, orderId, instrumentId);
        final int filledQty = aggressiveResult[0];
        int bytesWritten = aggressiveResult[1];

        // ─── Emit report for the incoming order itself ───
        bytesWritten += emitIncomingOrderReport(
                orderBook, outputBuffer, outputOffset + bytesWritten,
                orderId, price, clientId, clusterTimestamp,
                instrumentId, originalQty, filledQty, side, orderType);

        // ─── Metrics ───
        ChronosMetrics.onOrderProcessed();

        return bytesWritten;
    }

    /**
     * Reset the engine state — used during snapshot restore and testing.
     * Clears all order books and resets the execution ID counter.
     */
    public void reset() {
        for (final OffHeapOrderBook book : orderBooks.values()) {
            book.reset();
        }
        nextExecId = 1;
    }

    /**
     * Returns the order book for a specific instrument, or {@code null} if not
     * found.
     */
    public OffHeapOrderBook orderBook(final int instrumentId) {
        return orderBooks.get(instrumentId);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Private helpers
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Sweep aggressively through opposite-side price levels in price-time priority
     * order.
     * Fills the incoming order quantity as much as possible, emitting one execution
     * report per resting order that is (partially) filled.
     *
     * @return int[2] where [0] = total quantity filled, [1] = bytes written for
     *         resting reports
     */
    private int[] sweepAggressively(
            final OffHeapOrderBook orderBook,
            final long price,
            final int originalQty,
            final byte side,
            final byte orderType,
            final long clusterTimestamp,
            final MutableDirectBuffer outputBuffer,
            final int startOffset,
            final long clientId,
            final long orderId,
            final int instrumentId) {

        final boolean isBuySide = (side == Side.BUY);
        final long[] oppositePrices = isBuySide ? orderBook.askPrices() : orderBook.bidPrices();
        final int oppositeLevelCount = isBuySide ? orderBook.askLevelCount() : orderBook.bidLevelCount();
        final byte oppositeSide = isBuySide ? Side.SELL : Side.BUY;

        int remainingQty = originalQty;
        int bytesWritten = 0;

        if (oppositeLevelCount == 0 || remainingQty == 0) {
            return new int[] { 0, 0 };
        }

        final long topPrice = oppositePrices[0];
        final boolean canTrade = isBuySide
                ? (orderType == OrderType.MARKET || topPrice <= price)
                : (orderType == OrderType.MARKET || topPrice >= price);

        if (!canTrade) {
            return new int[] { 0, 0 };
        }

        final long effectiveLimit = (orderType == OrderType.MARKET)
                ? (isBuySide ? Long.MAX_VALUE : Long.MIN_VALUE)
                : price;

        final int matchableLevels = priceScanner.countMatchableLevels(
                oppositePrices, oppositeLevelCount, effectiveLimit, isBuySide);

        // Walk each matchable price level in order (best price first)
        for (int lvl = 0; lvl < matchableLevels && remainingQty > 0; lvl++) {
            int slot = orderBook.headOrderSlot(oppositeSide, lvl); // BUG FIX: was always 0

            while (slot != OffHeapOrderBook.NULL_SLOT && remainingQty > 0) {
                final int restingRemaining = orderBook.slotRemaining(slot);
                final int fillQty = Math.min(remainingQty, restingRemaining);
                final long fillPrice = orderBook.slotPrice(slot);
                final long restingClientId = orderBook.slotClientId(slot);
                final long restingOrderId = orderBook.slotOrderId(slot);
                final int nextSlot = orderBook.slotNext(slot);

                final int newRestingRemaining = orderBook.reduceQuantity(slot, fillQty);
                final byte restingExecType = (newRestingRemaining == 0)
                        ? ExecType.FILL
                        : ExecType.PARTIAL_FILL;

                bytesWritten += writeExecReport(
                        outputBuffer, startOffset + bytesWritten,
                        restingOrderId, fillPrice, restingClientId, clusterTimestamp,
                        instrumentId, fillQty, newRestingRemaining,
                        oppositeSide, restingExecType);

                if (newRestingRemaining == 0) {
                    orderBook.removeOrder(slot);
                }

                ChronosMetrics.onMatchFound();
                remainingQty -= fillQty;
                slot = nextSlot;
            }
        }

        return new int[] { originalQty - remainingQty, bytesWritten };
    }

    /**
     * Emit the execution report for the incoming order based on how much was
     * filled.
     * Also rests any unfilled quantity as a passive limit order.
     *
     * @return bytes written for the incoming order's own execution report
     */
    private int emitIncomingOrderReport(
            final OffHeapOrderBook orderBook,
            final MutableDirectBuffer outputBuffer,
            final int offset,
            final long orderId,
            final long price,
            final long clientId,
            final long clusterTimestamp,
            final int instrumentId,
            final int originalQty,
            final int filledQty,
            final byte side,
            final byte orderType) {

        final int remainingQty = originalQty - filledQty;

        if (remainingQty == 0) {
            // Fully filled
            return writeExecReport(outputBuffer, offset,
                    orderId, price, clientId, clusterTimestamp,
                    instrumentId, filledQty, 0, side, ExecType.FILL);
        }

        if (filledQty > 0) {
            // Partially filled — rest the remainder as a passive limit order
            restPassiveOrder(orderBook, orderId, price, clientId,
                    clusterTimestamp, remainingQty, instrumentId, side, orderType);
            return writeExecReport(outputBuffer, offset,
                    orderId, price, clientId, clusterTimestamp,
                    instrumentId, filledQty, remainingQty, side, ExecType.PARTIAL_FILL);
        }

        // No fill at all
        if (orderType == OrderType.LIMIT) {
            restPassiveOrder(orderBook, orderId, price, clientId,
                    clusterTimestamp, originalQty, instrumentId, side, orderType);
            return writeExecReport(outputBuffer, offset,
                    orderId, price, clientId, clusterTimestamp,
                    instrumentId, 0, originalQty, side, ExecType.NEW);
        }

        // Market order with no liquidity — reject
        ChronosMetrics.onOrderRejected();
        return writeExecReport(outputBuffer, offset,
                orderId, price, clientId, clusterTimestamp,
                instrumentId, 0, originalQty, side, ExecType.REJECTED);
    }

    /**
     * Add a passive (resting) order to the book. Only called for LIMIT orders.
     */
    private static void restPassiveOrder(
            final OffHeapOrderBook orderBook,
            final long orderId,
            final long price,
            final long clientId,
            final long clusterTimestamp,
            final int quantity,
            final int instrumentId,
            final byte side,
            final byte orderType) {
        orderBook.addOrder(orderId, price, clientId, clusterTimestamp,
                quantity, instrumentId, side, orderType);
    }

    /**
     * Write a single ExecutionReport (SBE header + body) into the output buffer.
     *
     * @return total bytes written ({@code EXEC_REPORT_SIZE})
     */
    private int writeExecReport(
            final MutableDirectBuffer buffer,
            final int offset,
            final long orderId,
            final long price,
            final long clientId,
            final long matchTimestamp,
            final int instrumentId,
            final int filledQty,
            final int remainingQty,
            final byte side,
            final byte execType) {

        headerEncoder.wrap(buffer, offset)
                .blockLength(ExecutionReportEncoder.BLOCK_LENGTH)
                .templateId(ExecutionReportEncoder.TEMPLATE_ID)
                .schemaId(ExecutionReportEncoder.SCHEMA_ID)
                .version(ExecutionReportEncoder.SCHEMA_VERSION);

        execEncoder.wrap(buffer, offset + MessageHeaderEncoder.ENCODED_LENGTH)
                .orderId(orderId)
                .execId(nextExecId++)
                .price(price)
                .clientId(clientId)
                .matchTimestampNs(matchTimestamp)
                .instrumentId(instrumentId)
                .filledQuantity(filledQty)
                .remainingQuantity(remainingQty)
                .side(side)
                .execType(execType);

        return EXEC_REPORT_SIZE;
    }
}
