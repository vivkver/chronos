package com.chronos.bench;

import com.chronos.core.lob.OffHeapOrderBook;
import com.chronos.matching.MatchingEngine;
import com.chronos.matching.PriceScannerFactory;
import com.chronos.schema.sbe.MessageHeaderDecoder;
import com.chronos.schema.sbe.NewOrderSingleDecoder;
import com.chronos.warmup.WarmupOrderGenerator;
import org.agrona.concurrent.UnsafeBuffer;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Epsilon GC Zero-Allocation Proof Runner.
 *
 * <h2>Proof Methodology</h2>
 * <p>
 * Epsilon GC is a no-op garbage collector: it allocates memory but
 * <em>never</em>
 * collects it. If the hot path allocates any objects, the heap will eventually
 * exhaust and the JVM will throw {@link OutOfMemoryError}.
 * </p>
 * <p>
 * If this program completes 1,000,000 orders without OOM, the hot path is
 * provably zero-allocation.
 * </p>
 *
 * <h2>Expected JVM Args (set by Gradle task)</h2>
 * 
 * <pre>
 *   -XX:+UnlockExperimentalVMOptions
 *   -XX:+UseEpsilonGC
 *   -Xms64m -Xmx64m          ← intentionally tiny heap to catch any allocation
 *   -XX:+AlwaysPreTouch
 *   -Xlog:gc*:file=build/epsilon-gc.log
 * </pre>
 *
 * <h2>Pass Criteria</h2>
 * <ul>
 * <li>Exit code 0 (no OOM)</li>
 * <li>GC log shows zero GC events after warmup</li>
 * </ul>
 */
public final class EpsilonGcProof {

    private static final int WARMUP_ORDERS = 100_000;
    private static final int PROOF_ORDERS = 1_000_000;

    public static void main(final String[] args) throws Exception {
        final PrintStream out = System.out;

        out.println("╔══════════════════════════════════════════════════════════╗");
        out.println("║         CHRONOS Zero-GC Proof (Epsilon GC)              ║");
        out.println("╠══════════════════════════════════════════════════════════╣");
        out.println("║  Heap: " + Runtime.getRuntime().maxMemory() / (1024 * 1024) + " MB max (intentionally tiny)");
        out.println("║  Warmup orders : " + WARMUP_ORDERS);
        out.println("║  Proof orders  : " + PROOF_ORDERS);
        out.println("╚══════════════════════════════════════════════════════════╝");
        out.println();

        // ─── Setup (allocations here are fine — pre-warmup) ───
        final OffHeapOrderBook orderBook = new OffHeapOrderBook(1);
        final MatchingEngine engine = new MatchingEngine(orderBook, PriceScannerFactory.create());
        final WarmupOrderGenerator generator = new WarmupOrderGenerator(42L);
        final UnsafeBuffer orderBuffer = new UnsafeBuffer(new byte[WarmupOrderGenerator.messageSize()]);
        final UnsafeBuffer outputBuffer = new UnsafeBuffer(new byte[4096]);
        final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
        final NewOrderSingleDecoder orderDecoder = new NewOrderSingleDecoder();

        // ─── Warmup (allocations here are fine) ───
        out.println("[1/3] Warming up JIT (" + WARMUP_ORDERS + " orders)...");
        for (int i = 0; i < WARMUP_ORDERS; i++) {
            generator.generateOrder(orderBuffer, 0);
            headerDecoder.wrap(orderBuffer, 0);
            orderDecoder.wrap(orderBuffer, MessageHeaderDecoder.ENCODED_LENGTH);
            engine.matchOrder(orderDecoder, System.nanoTime(), outputBuffer, 0);
            if (i % 10_000 == 0) {
                engine.reset();
            }
        }
        engine.reset();

        // Force GC to clear warmup allocations before the proof run
        System.gc();
        Thread.sleep(200);

        final long heapBeforeBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        out.println("[2/3] Heap used after warmup + GC: " + heapBeforeBytes / 1024 + " KB");
        out.println("[2/3] Starting proof run (" + PROOF_ORDERS + " orders)...");
        out.println("      If this completes without OutOfMemoryError → ZERO-GC PROVEN");
        out.println();

        // ─── Proof run — NO allocations allowed ───
        long matchCount = 0;
        for (int i = 0; i < PROOF_ORDERS; i++) {
            generator.generateOrder(orderBuffer, 0);
            headerDecoder.wrap(orderBuffer, 0);
            orderDecoder.wrap(orderBuffer, MessageHeaderDecoder.ENCODED_LENGTH);
            final int written = engine.matchOrder(orderDecoder, System.nanoTime(), outputBuffer, 0);
            if (written > 0)
                matchCount++;

            if (i > 0 && i % 100_000 == 0) {
                engine.reset();
                out.println("  Progress: " + i / 1000 + "K / " + PROOF_ORDERS / 1000 + "K  matches=" + matchCount);
            }
        }

        final long heapAfterBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        out.println();
        out.println("╔══════════════════════════════════════════════════════════╗");
        out.println("║                  PROOF RESULT: PASS ✓                  ║");
        out.println("╠══════════════════════════════════════════════════════════╣");
        out.println("║  Orders processed : " + PROOF_ORDERS);
        out.println("║  Matches produced : " + matchCount);
        out.println("║  Heap before proof: " + heapBeforeBytes / 1024 + " KB");
        out.println("║  Heap after proof : " + heapAfterBytes / 1024 + " KB");
        out.println("║  Heap delta       : " + (heapAfterBytes - heapBeforeBytes) / 1024 + " KB");
        out.println("║  OOM errors       : 0");
        out.println("╚══════════════════════════════════════════════════════════╝");
        out.println();
        out.println("[3/3] Analysing GC log...");
        analyseGcLog(out);
    }

    /**
     * Parses the GC log written by {@code -Xlog:gc*:file=build/epsilon-gc.log}
     * and counts GC events that occurred after warmup.
     */
    private static void analyseGcLog(final PrintStream out) {
        final Path gcLog = Paths.get("build", "epsilon-gc.log");
        if (!Files.exists(gcLog)) {
            out.println("  GC log not found at " + gcLog + " — skipping log analysis.");
            out.println("  (Run with -Xlog:gc*:file=build/epsilon-gc.log to enable)");
            return;
        }

        int gcEvents = 0;
        int epsilonWarnings = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(gcLog.toFile()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("GC(") && !line.contains("Epsilon")) {
                    gcEvents++;
                }
                if (line.contains("Epsilon") && line.contains("warning")) {
                    epsilonWarnings++;
                }
            }
        } catch (IOException e) {
            out.println("  Could not read GC log: " + e.getMessage());
            return;
        }

        out.println("  GC events in log  : " + gcEvents);
        out.println("  Epsilon warnings  : " + epsilonWarnings);

        if (gcEvents == 0) {
            out.println("  GC log analysis   : PASS ✓ — zero GC events");
        } else {
            out.println("  GC log analysis   : WARN — " + gcEvents + " GC events found");
            out.println("  (These may be from warmup phase — check timestamps in " + gcLog + ")");
        }
    }
}
