package com.chronos.warmup;

import com.chronos.core.lob.OffHeapOrderBook;
import com.chronos.matching.MatchingEngine;
import com.chronos.matching.VectorizedPriceScanner;
import com.chronos.schema.sbe.MessageHeaderDecoder;
import com.chronos.schema.sbe.NewOrderSingleDecoder;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Training runner for JIT warmup and CDS/AOT cache generation.
 *
 * <h2>What This Does</h2>
 * <ol>
 * <li>Allocates all off-heap structures (identical to production layout)</li>
 * <li>Runs N iterations of realistic order flow through the matching
 * engine</li>
 * <li>Triggers JIT compilation of all hot paths (C2 tier-4 compilation)</li>
 * <li>Optionally dumps a CDS (Class Data Sharing) archive for fast startup</li>
 * </ol>
 *
 * <h2>GraalVM Integration</h2>
 * <p>
 * When running on GraalVM, the JIT compiler (Graal) warms up faster than C2,
 * making this training run shorter. For Profile-Guided Optimization (PGO):
 * </p>
 * 
 * <pre>
 *   # Step 1: Instrument & collect profile
 *   java -Dgraal.PGOInstrument=chronos.iprof -jar chronos-warmup.jar
 *
 *   # Step 2: Rebuild native image with PGO
 *   native-image --pgo=chronos.iprof -jar chronos-sequencer.jar
 * </pre>
 *
 * <h2>CDS Archive Generation</h2>
 * 
 * <pre>
 *   # Step 1: Dump class list
 *   java -Xshare:off -XX:DumpLoadedClassList=chronos.classlist -jar chronos-warmup.jar
 *
 *   # Step 2: Create CDS archive
 *   java -Xshare:dump -XX:SharedClassListFile=chronos.classlist -XX:SharedArchiveFile=chronos.jsa
 *
 *   # Step 3: Run with CDS
 *   java -Xshare:on -XX:SharedArchiveFile=chronos.jsa -jar chronos-sequencer.jar
 * </pre>
 */
public final class TrainingRunner {

    private static final Logger LOG = LoggerFactory.getLogger(TrainingRunner.class);

    /**
     * Number of warmup iterations — enough to trigger C2 compilation of all hot
     * methods.
     */
    private static final int WARMUP_ITERATIONS = 500_000;

    /** Seed for deterministic order generation. */
    private static final long SEED = 0xDEAD_BEEF_CAFE_BABEL;

    public static void main(final String[] args) {
        LOG.info("Starting CHRONOS Training Run ({} iterations)...", WARMUP_ITERATIONS);

        // ─── Allocate production-identical structures ───
        final OffHeapOrderBook orderBook = new OffHeapOrderBook(1);
        final MatchingEngine engine = new MatchingEngine(orderBook, new VectorizedPriceScanner());
        final WarmupOrderGenerator generator = new WarmupOrderGenerator(SEED);

        // Pre-allocate buffers
        final UnsafeBuffer orderBuffer = new UnsafeBuffer(new byte[WarmupOrderGenerator.messageSize()]);
        final UnsafeBuffer outputBuffer = new UnsafeBuffer(new byte[4096]);
        final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
        final NewOrderSingleDecoder orderDecoder = new NewOrderSingleDecoder();

        // ─── Training loop ───
        final long startNs = System.nanoTime();

        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            // Generate a warmup order
            generator.generateOrder(orderBuffer, 0);

            // Decode (mimics the cluster onSessionMessage path)
            headerDecoder.wrap(orderBuffer, 0);
            orderDecoder.wrap(orderBuffer, MessageHeaderDecoder.ENCODED_LENGTH);

            // Match
            engine.matchOrder(orderDecoder, System.nanoTime(), outputBuffer, 0);

            // Periodically reset the book to prevent level exhaustion
            if (i > 0 && i % 50_000 == 0) {
                engine.reset();
                LOG.info("  Progress: {}/{} iterations ({} μs elapsed)",
                        i, WARMUP_ITERATIONS,
                        (System.nanoTime() - startNs) / 1_000);
            }
        }

        final long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
        LOG.info("Training complete: {} iterations in {} ms ({} orders/sec)",
                WARMUP_ITERATIONS, elapsedMs,
                WARMUP_ITERATIONS * 1000L / Math.max(elapsedMs, 1));

        LOG.info("JIT compilation should now be complete for all hot paths.");
        LOG.info("To generate CDS archive, re-run with: -XX:DumpLoadedClassList=chronos.classlist");
    }
}
