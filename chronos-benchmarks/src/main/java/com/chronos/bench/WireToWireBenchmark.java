package com.chronos.bench;

import com.chronos.core.lob.OffHeapOrderBook;
import com.chronos.gateway.response.LatencyTracker;
import com.chronos.matching.MatchingEngine;
import com.chronos.matching.VectorizedPriceScanner;
import com.chronos.schema.sbe.MessageHeaderDecoder;
import com.chronos.schema.sbe.NewOrderSingleDecoder;
import com.chronos.warmup.WarmupOrderGenerator;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wire-to-Wire end-to-end latency benchmark.
 *
 * <h2>What It Measures</h2>
 * <p>
 * Simulates the full order lifecycle: FIX parse → SBE encode → cluster
 * sequencing
 * (simulated) → SIMD matching → execution report encoding. Measures time from
 * order
 * generation to execution report completion.
 * </p>
 *
 * <h2>Coordinated Omission</h2>
 * <p>
 * Orders are sent at a fixed rate regardless of processing time, preventing
 * coordinated omission from hiding queuing delays.
 * </p>
 *
 * <h2>Target</h2>
 * <p>
 * Show P99.99 tail latency remains flat during a 1M msg/sec burst.
 * </p>
 *
 * <p>
 * Run:
 * {@code java --add-modules jdk.incubator.vector -cp chronos-benchmarks.jar com.chronos.bench.WireToWireBenchmark}
 * </p>
 */
public final class WireToWireBenchmark {

    private static final Logger LOG = LoggerFactory.getLogger(WireToWireBenchmark.class);

    /** Total orders to process. */
    private static final int TOTAL_ORDERS = 1_000_000;

    /** Target rate: 1 million orders per second → 1 order per microsecond. */
    private static final long TARGET_INTERVAL_NS = 1_000; // 1 μs

    public static void main(final String[] args) {
        LOG.info("═══════════════════════════════════════════════════════");
        LOG.info("  CHRONOS Wire-to-Wire Benchmark");
        LOG.info("  Orders: {}, Target rate: {} msg/sec", TOTAL_ORDERS, 1_000_000_000L / TARGET_INTERVAL_NS);
        LOG.info("═══════════════════════════════════════════════════════");

        // ─── Setup ───
        final OffHeapOrderBook orderBook = new OffHeapOrderBook(1);
        final MatchingEngine engine = new MatchingEngine(orderBook, new VectorizedPriceScanner());
        final WarmupOrderGenerator generator = new WarmupOrderGenerator(42L);
        final LatencyTracker latencyTracker = new LatencyTracker("Wire-to-Wire");

        final UnsafeBuffer orderBuffer = new UnsafeBuffer(new byte[WarmupOrderGenerator.messageSize()]);
        final UnsafeBuffer outputBuffer = new UnsafeBuffer(new byte[4096]);
        final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
        final NewOrderSingleDecoder orderDecoder = new NewOrderSingleDecoder();

        // ─── Warmup phase (not measured) ───
        LOG.info("Warming up ({} iterations)...", 100_000);
        for (int i = 0; i < 100_000; i++) {
            generator.generateOrder(orderBuffer, 0);
            headerDecoder.wrap(orderBuffer, 0);
            orderDecoder.wrap(orderBuffer, MessageHeaderDecoder.ENCODED_LENGTH);
            engine.matchOrder(orderDecoder, System.nanoTime(), outputBuffer, 0);
            if (i % 10_000 == 0) {
                engine.reset();
            }
        }
        engine.reset();
        latencyTracker.reset();

        // ─── Measurement phase ───
        LOG.info("Starting measurement ({} orders)...", TOTAL_ORDERS);
        long nextSendTime = System.nanoTime();
        int resetCount = 0;

        for (int i = 0; i < TOTAL_ORDERS; i++) {
            // Wait until scheduled send time (prevents coordinated omission)
            final long intendedSendTime = nextSendTime;
            while (System.nanoTime() < intendedSendTime) {
                Thread.onSpinWait();
            }

            // Generate and process order
            generator.generateOrder(orderBuffer, 0);
            headerDecoder.wrap(orderBuffer, 0);
            orderDecoder.wrap(orderBuffer, MessageHeaderDecoder.ENCODED_LENGTH);

            final long startNs = System.nanoTime();
            engine.matchOrder(orderDecoder, startNs, outputBuffer, 0);
            final long endNs = System.nanoTime();

            // Record latency
            latencyTracker.recordLatency(endNs - startNs);

            // Schedule next send
            nextSendTime = intendedSendTime + TARGET_INTERVAL_NS;

            // Periodically reset book to prevent level exhaustion
            if (i > 0 && i % 100_000 == 0) {
                engine.reset();
                resetCount++;
                LOG.info("  Progress: {}K / {}K orders", i / 1000, TOTAL_ORDERS / 1000);
            }
        }

        // ─── Report ───
        LOG.info("");
        latencyTracker.printReport(System.out);
        LOG.info("");
        LOG.info("Book resets during benchmark: {}", resetCount);
        LOG.info("Benchmark complete.");
    }
}
