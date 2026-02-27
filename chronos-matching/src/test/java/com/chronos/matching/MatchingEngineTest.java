package com.chronos.matching;

import com.chronos.core.domain.ExecType;
import com.chronos.core.domain.OrderType;
import com.chronos.core.domain.Side;
import com.chronos.core.lob.OffHeapOrderBook;
import com.chronos.core.util.ChronosMetrics;
import com.chronos.schema.sbe.ExecutionReportDecoder;
import com.chronos.schema.sbe.MessageHeaderDecoder;
import com.chronos.schema.sbe.MessageHeaderEncoder;
import com.chronos.schema.sbe.NewOrderSingleDecoder;
import com.chronos.schema.sbe.NewOrderSingleEncoder;
import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MatchingEngine} covering all major execution paths
 * through
 * {@code matchOrder}: resting, rejecting, full fills, partial fills,
 * multi-level
 * sweeps, and state reset.
 */
class MatchingEngineTest {

    private static final int INSTRUMENT_ID = 1;
    private static final long TIMESTAMP = 1_000_000L;

    /** Bytes for one SBE execution report (header + body). */
    private static final int EXEC_REPORT_SIZE = MessageHeaderEncoder.ENCODED_LENGTH
            + ExecutionReportDecoder.BLOCK_LENGTH;

    private MatchingEngine engine;
    private OffHeapOrderBook book;

    // Pre-allocated buffers reused across tests
    private final UnsafeBuffer msgBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
    private final UnsafeBuffer outputBuffer = new UnsafeBuffer(
            ByteBuffer.allocateDirect(MatchingEngine.OUTPUT_BUFFER_CAPACITY));

    private final NewOrderSingleEncoder encoder = new NewOrderSingleEncoder();
    private final NewOrderSingleDecoder decoder = new NewOrderSingleDecoder();
    private final ExecutionReportDecoder reportDecoder = new ExecutionReportDecoder();
    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();

    @BeforeEach
    void setUp() {
        book = new OffHeapOrderBook(INSTRUMENT_ID);
        final Int2ObjectHashMap<OffHeapOrderBook> books = new Int2ObjectHashMap<>();
        books.put(INSTRUMENT_ID, book);
        engine = new MatchingEngine(books, PriceScannerFactory.create());
        ChronosMetrics.reset();
    }

    // ══════════════════════════════════════════════════════════════
    // 1. Limit order on empty book — rests as NEW
    // ══════════════════════════════════════════════════════════════

    @Test
    void limitOrderWithNoMatchRestsAsNew() {
        final int bytesWritten = matchOrder(1L, 100L, 1L, 100, Side.BUY, OrderType.LIMIT);

        assertEquals(EXEC_REPORT_SIZE, bytesWritten, "Should emit exactly one report");

        final ExecutionReportDecoder report = decodeReport(0);
        assertEquals(1L, report.orderId());
        assertEquals(ExecType.NEW, report.execType());
        assertEquals(0, report.filledQuantity());
        assertEquals(100, report.remainingQuantity());
        assertEquals(1, book.liveOrderCount(), "Order should be resting in book");
    }

    // ══════════════════════════════════════════════════════════════
    // 2. Market order on empty book — REJECTED
    // ══════════════════════════════════════════════════════════════

    @Test
    void marketOrderWithNoLiquidityIsRejected() {
        final int bytesWritten = matchOrder(2L, 0L, 1L, 50, Side.BUY, OrderType.MARKET);

        assertEquals(EXEC_REPORT_SIZE, bytesWritten);

        final ExecutionReportDecoder report = decodeReport(0);
        assertEquals(ExecType.REJECTED, report.execType());
        assertEquals(0, book.liveOrderCount(), "Rejected market order must not rest");
    }

    // ══════════════════════════════════════════════════════════════
    // 3. Unknown instrument — REJECTED, nothing touches the book
    // ══════════════════════════════════════════════════════════════

    @Test
    void unknownInstrumentIsRejected() {
        final int unknownInstrument = 999;
        final int bytesWritten = matchOrderOnInstrument(3L, 100L, 1L, 50, Side.BUY, OrderType.LIMIT, unknownInstrument);

        assertEquals(EXEC_REPORT_SIZE, bytesWritten);
        final ExecutionReportDecoder report = decodeReport(0);
        assertEquals(ExecType.REJECTED, report.execType());
    }

    // ══════════════════════════════════════════════════════════════
    // 4. Aggressive BUY fully fills a resting SELL (same quantity)
    // ══════════════════════════════════════════════════════════════

    @Test
    void aggressiveBuyFullyFillsRestingAsk() {
        // Rest a SELL 100@100
        matchOrder(10L, 100L, 99L, 100, Side.SELL, OrderType.LIMIT);

        // Aggressive BUY 100@100
        outputBuffer.setMemory(0, outputBuffer.capacity(), (byte) 0);
        final int bytesWritten = matchOrder(11L, 100L, 1L, 100, Side.BUY, OrderType.LIMIT);

        // Two reports: one for the resting SELL, one for the incoming BUY
        assertEquals(2 * EXEC_REPORT_SIZE, bytesWritten);

        final ExecutionReportDecoder restingReport = decodeReport(0);
        assertEquals(10L, restingReport.orderId());
        assertEquals(ExecType.FILL, restingReport.execType());
        assertEquals(100, restingReport.filledQuantity());
        assertEquals(0, restingReport.remainingQuantity());

        final ExecutionReportDecoder incomingReport = decodeReport(1);
        assertEquals(11L, incomingReport.orderId());
        assertEquals(ExecType.FILL, incomingReport.execType());
        assertEquals(100, incomingReport.filledQuantity());
        assertEquals(0, incomingReport.remainingQuantity());

        assertEquals(0, book.liveOrderCount(), "Book must be empty after full fill");
    }

    // ══════════════════════════════════════════════════════════════
    // 5. Incoming BUY smaller than resting SELL — PARTIAL_FILL on resting side
    // ══════════════════════════════════════════════════════════════

    @Test
    void aggressiveBuyPartiallyFillsRestingAsk() {
        // Rest a SELL 200@100
        matchOrder(20L, 100L, 99L, 200, Side.SELL, OrderType.LIMIT);

        outputBuffer.setMemory(0, outputBuffer.capacity(), (byte) 0);
        // Aggressive BUY 50@100
        final int bytesWritten = matchOrder(21L, 100L, 1L, 50, Side.BUY, OrderType.LIMIT);

        assertEquals(2 * EXEC_REPORT_SIZE, bytesWritten);

        final ExecutionReportDecoder restingReport = decodeReport(0);
        assertEquals(20L, restingReport.orderId());
        assertEquals(ExecType.PARTIAL_FILL, restingReport.execType());
        assertEquals(50, restingReport.filledQuantity());
        assertEquals(150, restingReport.remainingQuantity());

        final ExecutionReportDecoder incomingReport = decodeReport(1);
        assertEquals(21L, incomingReport.orderId());
        assertEquals(ExecType.FILL, incomingReport.execType());

        assertEquals(1, book.liveOrderCount(), "Resting order with remaining qty should still be in book");
    }

    // ══════════════════════════════════════════════════════════════
    // 6. Aggressive SELL fully fills a resting BUY
    // ══════════════════════════════════════════════════════════════

    @Test
    void aggressiveSellFullyFillsRestingBid() {
        // Rest a BUY 75@200
        matchOrder(30L, 200L, 99L, 75, Side.BUY, OrderType.LIMIT);

        outputBuffer.setMemory(0, outputBuffer.capacity(), (byte) 0);
        final int bytesWritten = matchOrder(31L, 200L, 1L, 75, Side.SELL, OrderType.LIMIT);

        assertEquals(2 * EXEC_REPORT_SIZE, bytesWritten);

        final ExecutionReportDecoder restingReport = decodeReport(0);
        assertEquals(30L, restingReport.orderId());
        assertEquals(ExecType.FILL, restingReport.execType());

        final ExecutionReportDecoder incomingReport = decodeReport(1);
        assertEquals(31L, incomingReport.orderId());
        assertEquals(ExecType.FILL, incomingReport.execType());

        assertEquals(0, book.liveOrderCount());
    }

    // ══════════════════════════════════════════════════════════════
    // 7. Multi-level sweep: BUY sweeps across two ASK price levels
    // ══════════════════════════════════════════════════════════════

    @Test
    void multiLevelSweepFillsTwoAskLevels() {
        // Two resting SELLs at different prices
        matchOrder(40L, 100L, 99L, 50, Side.SELL, OrderType.LIMIT); // SELL 50@100
        matchOrder(41L, 101L, 98L, 50, Side.SELL, OrderType.LIMIT); // SELL 50@101

        outputBuffer.setMemory(0, outputBuffer.capacity(), (byte) 0);

        // BUY 100@110 — should sweep both levels
        final int bytesWritten = matchOrder(42L, 110L, 1L, 100, Side.BUY, OrderType.LIMIT);

        // 2 resting FILLs + 1 incoming FILL = 3 reports
        assertEquals(3 * EXEC_REPORT_SIZE, bytesWritten);

        final ExecutionReportDecoder level0Report = decodeReport(0);
        assertEquals(40L, level0Report.orderId());
        assertEquals(ExecType.FILL, level0Report.execType());

        final ExecutionReportDecoder level1Report = decodeReport(1);
        assertEquals(41L, level1Report.orderId());
        assertEquals(ExecType.FILL, level1Report.execType());

        final ExecutionReportDecoder incomingReport = decodeReport(2);
        assertEquals(42L, incomingReport.orderId());
        assertEquals(ExecType.FILL, incomingReport.execType());
        assertEquals(100, incomingReport.filledQuantity());

        assertEquals(0, book.liveOrderCount());
    }

    // ══════════════════════════════════════════════════════════════
    // 8. ExecId monotonically increments; reset() restores initial state
    // ══════════════════════════════════════════════════════════════

    @Test
    void execIdIncrementsAndResetClearsState() {
        // First order → execId should be 1
        matchOrder(50L, 100L, 1L, 10, Side.BUY, OrderType.LIMIT);
        ExecutionReportDecoder first = decodeReport(0);
        assertEquals(1L, first.execId());

        // Second order → execId should be 2
        outputBuffer.setMemory(0, outputBuffer.capacity(), (byte) 0);
        matchOrder(51L, 100L, 1L, 10, Side.BUY, OrderType.LIMIT);
        ExecutionReportDecoder second = decodeReport(0);
        assertEquals(2L, second.execId());

        // After reset: book empty, execId back to 1
        engine.reset();
        assertEquals(0, book.liveOrderCount());

        outputBuffer.setMemory(0, outputBuffer.capacity(), (byte) 0);
        matchOrder(52L, 100L, 1L, 10, Side.BUY, OrderType.LIMIT);
        ExecutionReportDecoder afterReset = decodeReport(0);
        assertEquals(1L, afterReset.execId(), "execId should restart from 1 after reset");
    }

    // ══════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════

    /**
     * Encode and dispatch a NewOrderSingle on {@code INSTRUMENT_ID}. Returns bytes
     * written.
     */
    private int matchOrder(final long orderId, final long price, final long clientId,
            final int quantity, final byte side, final byte orderType) {
        return matchOrderOnInstrument(orderId, price, clientId, quantity, side, orderType, INSTRUMENT_ID);
    }

    private int matchOrderOnInstrument(final long orderId, final long price, final long clientId,
            final int quantity, final byte side, final byte orderType, final int instrumentId) {
        msgBuffer.setMemory(0, 64, (byte) 0);
        encoder.wrap(msgBuffer, 0)
                .orderId(orderId)
                .price(price)
                .clientId(clientId)
                .timestampNs(TIMESTAMP)
                .instrumentId(instrumentId)
                .quantity(quantity)
                .side(side)
                .orderType(orderType);
        decoder.wrap(msgBuffer, 0);
        outputBuffer.setMemory(0, outputBuffer.capacity(), (byte) 0);
        return engine.matchOrder(decoder, TIMESTAMP, outputBuffer, 0);
    }

    /**
     * Decode the Nth execution report from {@code outputBuffer}.
     * Reports are written sequentially, each preceded by an SBE message header.
     */
    private ExecutionReportDecoder decodeReport(final int reportIndex) {
        final int reportStart = reportIndex * EXEC_REPORT_SIZE;
        final int bodyOffset = reportStart + MessageHeaderDecoder.ENCODED_LENGTH;
        assertTrue(bodyOffset + ExecutionReportDecoder.BLOCK_LENGTH <= outputBuffer.capacity(),
                "Report index " + reportIndex + " is out of bounds");
        return reportDecoder.wrap(outputBuffer, bodyOffset);
    }
}
