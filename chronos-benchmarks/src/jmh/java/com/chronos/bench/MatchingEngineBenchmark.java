package com.chronos.bench;

import com.chronos.matching.ScalarPriceScanner;
import com.chronos.matching.VectorizedPriceScanner;
import com.chronos.matching.PriceScanner;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark: SIMD Vector API price scanner vs scalar loop.
 *
 * <p>
 * Demonstrates the 3x–8x speedup from vectorized price scanning on AVX-512
 * hardware.
 * This is a "Hot Path" microbenchmark isolating the price comparison logic.
 * </p>
 *
 * <p>
 * Run with: {@code ./gradlew :chronos-benchmarks:jmh}
 * </p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 2, jvmArgs = { "--add-modules", "jdk.incubator.vector",
        "--add-opens", "java.base/sun.misc=ALL-UNNAMED",
        "-XX:+UseZGC", "-XX:+ZGenerational" })
public class MatchingEngineBenchmark {

    @Param({ "64", "256", "512", "1024" })
    private int levelCount;

    private long[] prices;
    private long limitPrice;

    private PriceScanner vectorizedScanner;
    private PriceScanner scalarScanner;

    @Setup(Level.Trial)
    public void setup() {
        vectorizedScanner = new VectorizedPriceScanner();
        scalarScanner = new ScalarPriceScanner();

        // Generate sorted ask prices (ascending)
        prices = new long[levelCount];
        final long basePrice = 10_000_000_000_000L; // $100,000.00 in fixed-point
        for (int i = 0; i < levelCount; i++) {
            prices[i] = basePrice + (long) i * 100_000_000L; // $1.00 increments
        }

        // Limit price: matches roughly half the levels
        limitPrice = basePrice + (long) (levelCount / 2) * 100_000_000L;
    }

    @Benchmark
    public int simdCountMatchable(final Blackhole bh) {
        return vectorizedScanner.countMatchableLevels(prices, levelCount, limitPrice, true);
    }

    @Benchmark
    public int scalarCountMatchable(final Blackhole bh) {
        return scalarScanner.countMatchableLevels(prices, levelCount, limitPrice, true);
    }

    @Benchmark
    public int simdFindInsertion(final Blackhole bh) {
        return vectorizedScanner.findInsertionPoint(prices, levelCount, limitPrice, false);
    }

    @Benchmark
    public int scalarFindInsertion(final Blackhole bh) {
        return scalarScanner.findInsertionPoint(prices, levelCount, limitPrice, false);
    }

    @Benchmark
    public int simdFindFirstMatch(final Blackhole bh) {
        return vectorizedScanner.findFirstMatchableLevel(prices, levelCount, limitPrice, true);
    }

    @Benchmark
    public int scalarFindFirstMatch(final Blackhole bh) {
        return scalarScanner.findFirstMatchableLevel(prices, levelCount, limitPrice, true);
    }
}
