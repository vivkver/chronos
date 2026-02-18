package com.chronos.bench;

import com.chronos.core.lob.OffHeapOrderBook;
import com.chronos.matching.MatchingEngine;
import com.chronos.matching.PriceScannerFactory;
import com.chronos.schema.sbe.MessageHeaderDecoder;
import com.chronos.schema.sbe.NewOrderSingleDecoder;
import com.chronos.warmup.WarmupOrderGenerator;
import org.agrona.concurrent.UnsafeBuffer;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Zero-GC Proof Benchmark.
 *
 * <h2>Purpose</h2>
 * <p>
 * Proves that the matching engine hot path ({@code matchOrder}) allocates
 * <strong>zero bytes</strong> on the heap after JIT warmup. Run with the
 * {@code -prof gc} profiler to see allocation rates per operation.
 * </p>
 *
 * <h2>Pass Criteria</h2>
 * <ul>
 * <li>{@code gc.alloc.rate.norm} = 0.0 B/op (zero allocation per
 * operation)</li>
 * <li>No GC events in the measurement window</li>
 * </ul>
 *
 * <h2>How to Run</h2>
 * 
 * <pre>
 *   ./gradlew :chronos-benchmarks:jmh -PjmhInclude=ZeroGcProofBenchmark
 * </pre>
 *
 * <p>
 * For Epsilon GC (hard proof — OOM = allocations exist):
 * </p>
 * 
 * <pre>
 *   ./gradlew :chronos-benchmarks:verifyZeroGc
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = {
        "--add-modules", "jdk.incubator.vector",
        "--add-opens", "java.base/sun.misc=ALL-UNNAMED",
        "-XX:+UseZGC", "-XX:+ZGenerational",
        "-XX:+AlwaysPreTouch",
        // Enable GC logging to verify zero GC events during measurement
        "-Xlog:gc*:file=build/zero-gc-proof.log:time,uptime,level,tags"
})
public class ZeroGcProofBenchmark {

    private MatchingEngine engine;
    private WarmupOrderGenerator generator;
    private UnsafeBuffer orderBuffer;
    private UnsafeBuffer outputBuffer;
    private MessageHeaderDecoder headerDecoder;
    private NewOrderSingleDecoder orderDecoder;

    @Setup(Level.Trial)
    public void setup() {
        final OffHeapOrderBook orderBook = new OffHeapOrderBook(1);
        engine = new MatchingEngine(orderBook, PriceScannerFactory.create());
        generator = new WarmupOrderGenerator(42L);
        orderBuffer = new UnsafeBuffer(new byte[WarmupOrderGenerator.messageSize()]);
        outputBuffer = new UnsafeBuffer(new byte[4096]);
        headerDecoder = new MessageHeaderDecoder();
        orderDecoder = new NewOrderSingleDecoder();

        // Pre-warm the JIT and order book so measurement is clean
        for (int i = 0; i < 200_000; i++) {
            generator.generateOrder(orderBuffer, 0);
            headerDecoder.wrap(orderBuffer, 0);
            orderDecoder.wrap(orderBuffer, MessageHeaderDecoder.ENCODED_LENGTH);
            engine.matchOrder(orderDecoder, System.nanoTime(), outputBuffer, 0);
            if (i % 10_000 == 0) {
                engine.reset();
            }
        }
        engine.reset();
    }

    /**
     * Hot path under test.
     *
     * <p>
     * After warmup, this method must allocate ZERO bytes on the heap.
     * The JMH {@code -prof gc} profiler will report {@code gc.alloc.rate.norm}
     * which must be 0.0 B/op to pass the zero-GC proof.
     * </p>
     */
    @Benchmark
    public void matchOrder_zeroAlloc(Blackhole bh) {
        generator.generateOrder(orderBuffer, 0);
        headerDecoder.wrap(orderBuffer, 0);
        orderDecoder.wrap(orderBuffer, MessageHeaderDecoder.ENCODED_LENGTH);
        final int written = engine.matchOrder(orderDecoder, System.nanoTime(), outputBuffer, 0);
        bh.consume(written);
    }

    @TearDown(Level.Trial)
    public void teardown() {
        engine.reset();
    }
}
