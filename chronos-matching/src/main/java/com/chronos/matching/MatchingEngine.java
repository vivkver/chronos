package com.chronos.matching;

import com.chronos.core.domain.ExecType;
import com.chronos.core.domain.OrderType;
import com.chronos.core.domain.Side;
import com.chronos.core.lob.OffHeapOrderBook;
import com.chronos.schema.sbe.ExecutionReportEncoder;
import com.chronos.schema.sbe.MessageHeaderEncoder;
import com.chronos.schema.sbe.NewOrderSingleDecoder;
import org.agrona.MutableDirectBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single-threaded, zero-allocation matching engine designed for pinned-core
 * execution
 * inside an Aeron Cluster {@code ClusteredService}.
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

    // Map of instrumentId -> OrderBook
    private final org.agrona.collections.Int2ObjectHashMap<OffHeapOrderBook> orderBooks;
    private final PriceScanner priceScanner;

    // Pre-allocated encoders (flyweight, reused per call)
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final ExecutionReportEncoder execEncoder = new ExecutionReportEncoder();

    // Monotonic execution ID counter
    private long nextExecId = 1;

    public MatchingEngine(final org.agrona.collections.Int2ObjectHashMap<OffHeapOrderBook> orderBooks,
            final PriceScanner priceScanner) {
        this.orderBooks = orderBooks;
        this.priceScanner = priceScanner;
    }

    // Constructor for backward compatibility / single instrument
    public MatchingEngine(final OffHeapOrderBook singleBook, final PriceScanner priceScanner) {
        this.orderBooks = new org.agrona.collections.Int2ObjectHashMap<>();
        this.orderBooks.put(singleBook.instrumentId(), singleBook);
        this.priceScanner = priceScanner;
    }

    /**
     * Process a decoded NewOrderSingle. Attempts to match against resting orders,
     * then rests any remaining quantity as a passive order.
     *
     * @param decoder          pre-wrapped decoder over the incoming message
     * @param clusterTimestamp deterministic timestamp from the Aeron cluster
     * @param outputBuffer     buffer to write ExecutionReport messages into
     * @param outputOffset     starting offset in the output buffer
     * @return number of bytes written to the output buffer (may contain multiple
     *         execution reports)
     */
    public int matchOrder(final NewOrderSingleDecoder decoder,
            final long clusterTimestamp,
            final MutableDirectBuffer outputBuffer,
            final int outputOffset) {
        final long orderId = decoder.orderId();
        final long price = decoder.price();
        final long clientId = decoder.clientId();
        final int instrumentId = decoder.instrumentId();
        int quantity = decoder.quantity();
        final byte side = decoder.side();
        final byte orderType = decoder.orderType();

        int bytesWritten = 0;
        int currentOffset = outputOffset;

        // Lookup order book for this instrument
        final OffHeapOrderBook orderBook = orderBooks.get(instrumentId);
        if (orderBook == null) {
            LOG.error("Unknown instrument ID: {}", instrumentId);
            // Reject the order
            return writeExecReport(
                    outputBuffer, currentOffset,
                    orderId, price, clientId, clusterTimestamp,
                    instrumentId, 0, quantity,
                    side, ExecType.REJECTED);
        }

        // ─── Aggressive matching phase ───
        // A BUY matches against ASK levels; a SELL matches against BID levels
        final boolean isBuySide = (side == Side.BUY);
        final long[] oppositePrices = isBuySide ? orderBook.askPrices() : orderBook.bidPrices();
        final int oppositeLevelCount = isBuySide ? orderBook.askLevelCount() : orderBook.bidLevelCount();

        if (oppositeLevelCount > 0 && quantity > 0) {
            // Check if the top-of-book price is tradeable
            final long topPrice = oppositePrices[0];
            final boolean canTrade = isBuySide
                    ? (orderType == OrderType.MARKET || topPrice <= price)
                    : (orderType == OrderType.MARKET || topPrice >= price);

            if (canTrade) {
                // Sweep through matchable levels
                final long effectiveLimit = (orderType == OrderType.MARKET)
                        ? (isBuySide ? Long.MAX_VALUE : Long.MIN_VALUE)
                        : price;

                final int matchableLevels = priceScanner.countMatchableLevels(
                        oppositePrices, oppositeLevelCount, effectiveLimit, isBuySide);

                for (int lvl = 0; lvl < matchableLevels && quantity > 0; lvl++) {
                    // Walk orders at this level in FIFO order (price-time priority)
                    final byte oppositeSide = isBuySide ? Side.SELL : Side.BUY;
                    int slot = orderBook.headOrderSlot(oppositeSide, 0); // always level 0 (best)

                    while (slot != OffHeapOrderBook.NULL_SLOT && quantity > 0) {
                        final int restingRemaining = orderBook.slotRemaining(slot);
                        final int fillQty = Math.min(quantity, restingRemaining);
                        final long fillPrice = orderBook.slotPrice(slot);
                        final int nextSlot = orderBook.slotNext(slot);

                        // Emit execution report for the RESTING order
                        final int newRemaining = orderBook.reduceQuantity(slot, fillQty);
                        final byte restingExecType = (newRemaining == 0) ? ExecType.FILL : ExecType.PARTIAL_FILL;

                        currentOffset += writeExecReport(
                                outputBuffer, currentOffset,
                                orderBook.slotOrderId(slot), fillPrice,
                                orderBook.slotClientId(slot), clusterTimestamp,
                                instrumentId, fillQty, newRemaining,
                                oppositeSide, restingExecType);

                        if (newRemaining == 0) {
                            orderBook.removeOrder(slot);
                        }

                        quantity -= fillQty;
                        slot = nextSlot;
                    }
                }
            }
        }

        // ─── Emit execution report for the INCOMING order ───
        if (quantity == 0) {
            // Fully filled
            currentOffset += writeExecReport(
                    outputBuffer, currentOffset,
                    orderId, price, clientId, clusterTimestamp,
                    instrumentId, decoder.quantity(), 0,
                    side, ExecType.FILL);
        } else if (quantity < decoder.quantity()) {
            // Partially filled — rest the remainder
            currentOffset += writeExecReport(
                    outputBuffer, currentOffset,
                    orderId, price, clientId, clusterTimestamp,
                    instrumentId, decoder.quantity() - quantity, quantity,
                    side, ExecType.PARTIAL_FILL);

            if (orderType == OrderType.LIMIT) {
                orderBook.addOrder(orderId, price, clientId, clusterTimestamp,
                        quantity, instrumentId, side, orderType);
            }
        } else {
            // No fill — rest the full quantity (limit orders only)
            if (orderType == OrderType.LIMIT) {
                orderBook.addOrder(orderId, price, clientId, clusterTimestamp,
                        quantity, instrumentId, side, orderType);
                currentOffset += writeExecReport(
                        outputBuffer, currentOffset,
                        orderId, price, clientId, clusterTimestamp,
                        instrumentId, 0, quantity,
                        side, ExecType.NEW);
            } else {
                // Market order with no liquidity — reject
                currentOffset += writeExecReport(
                        outputBuffer, currentOffset,
                        orderId, price, clientId, clusterTimestamp,
                        instrumentId, 0, quantity,
                        side, ExecType.REJECTED);
            }
        }

        bytesWritten = currentOffset - outputOffset;
        return bytesWritten;
    }

    /**
     * Write a single ExecutionReport (header + body) into the output buffer.
     *
     * @return total bytes written (header + body)
     */
    private int writeExecReport(final MutableDirectBuffer buffer, final int offset,
            final long orderId, final long price, final long clientId,
            final long matchTimestamp, final int instrumentId,
            final int filledQty, final int remainingQty,
            final byte side, final byte execType) {
        // Write SBE message header
        headerEncoder.wrap(buffer, offset)
                .blockLength(ExecutionReportEncoder.BLOCK_LENGTH)
                .templateId(ExecutionReportEncoder.TEMPLATE_ID)
                .schemaId(ExecutionReportEncoder.SCHEMA_ID)
                .version(ExecutionReportEncoder.SCHEMA_VERSION);

        // Write execution report body
        final int bodyOffset = offset + MessageHeaderEncoder.ENCODED_LENGTH;
        execEncoder.wrap(buffer, bodyOffset)
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

        // Metrics
        if (execType == ExecType.FILL || execType == ExecType.PARTIAL_FILL) {
            com.chronos.core.util.ChronosMetrics.onMatchFound();
        } else if (execType == ExecType.REJECTED) {
            com.chronos.core.util.ChronosMetrics.onOrderRejected();
        }

        return MessageHeaderEncoder.ENCODED_LENGTH + ExecutionReportEncoder.BLOCK_LENGTH;
    }

    /** Returns the underlying order book for a specific instrument. */
    public OffHeapOrderBook orderBook(int instrumentId) {
        return orderBooks.get(instrumentId);
    }

    /** Returns the first order book (for legacy tests). */
    public OffHeapOrderBook orderBook() {
        if (orderBooks.isEmpty())
            return null;
        return orderBooks.values().iterator().next();
    }

    /** Reset the engine state — used during snapshot restore. */
    public void reset() {
        for (OffHeapOrderBook book : orderBooks.values()) {
            book.reset();
        }
        nextExecId = 1;
    }
}
