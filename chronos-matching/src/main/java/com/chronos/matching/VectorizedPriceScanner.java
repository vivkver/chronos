package com.chronos.matching;

import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * SIMD-vectorized price level scanner using the Vector API (JDK Incubator).
 *
 * <h2>How It Works</h2>
 * <p>
 * Instead of comparing one price at a time (scalar), this scanner broadcasts
 * the
 * target price into a SIMD register and compares it against 8 price levels
 * simultaneously
 * using AVX-512 (or 4 with AVX2). The resulting bitmask tells us which lanes
 * matched,
 * and {@code Long.numberOfTrailingZeros()} extracts the first match in one
 * instruction.
 * </p>
 *
 * <h2>Performance Characteristics</h2>
 * <ul>
 * <li><b>AVX-512</b>: 8 price comparisons per cycle (512-bit / 64-bit
 * longs)</li>
 * <li><b>AVX2 fallback</b>: 4 price comparisons per cycle (256-bit)</li>
 * <li><b>Expected speedup</b>: 3x–8x over scalar loop for 1024 price
 * levels</li>
 * <li><b>Zero allocation</b>: Vector API uses stack-allocated value types</li>
 * </ul>
 *
 * <h2>CPU Targeting</h2>
 * <p>
 * Uses {@code LongVector.SPECIES_PREFERRED} which auto-selects the widest
 * available
 * SIMD register on the current CPU. On Intel Xeon (Skylake-SP+), this is
 * typically
 * AVX-512. On AMD Zen 4+, it selects AVX-512. On older CPUs, AVX2 at 256-bit.
 * </p>
 */
public final class VectorizedPriceScanner implements PriceScanner {

    /**
     * The preferred SIMD species for this CPU — auto-selects AVX-512 (8 longs)
     * or AVX2 (4 longs) based on hardware capability.
     */
    private static final VectorSpecies<Long> SPECIES = LongVector.SPECIES_PREFERRED;

    /** Number of long elements per SIMD register. */
    private static final int LANE_COUNT = SPECIES.length();

    @Override
    public int findInsertionPoint(final long[] prices, final int count,
            final long newPrice, final boolean descending) {
        final LongVector broadcast = LongVector.broadcast(SPECIES, newPrice);
        int i = 0;

        // SIMD loop: process LANE_COUNT prices per iteration
        for (; i + LANE_COUNT <= count; i += LANE_COUNT) {
            final LongVector priceLane = LongVector.fromArray(SPECIES, prices, i);
            final VectorMask<Long> mask = descending
                    ? priceLane.compare(VectorOperators.LT, broadcast) // bid: find first price < newPrice
                    : priceLane.compare(VectorOperators.GT, broadcast); // ask: find first price > newPrice

            if (mask.anyTrue()) {
                return i + firstTrueIndex(mask);
            }
        }

        // Scalar remainder for elements that don't fill a full SIMD register
        for (; i < count; i++) {
            if (descending ? prices[i] < newPrice : prices[i] > newPrice) {
                return i;
            }
        }

        return count; // append at end
    }

    @Override
    public int countMatchableLevels(final long[] prices, final int count,
            final long limitPrice, final boolean isBuySide) {
        // For BUY sweeping asks (ascending): ask[i] <= limitPrice
        // For SELL sweeping bids (descending): bid[i] >= limitPrice
        final LongVector broadcast = LongVector.broadcast(SPECIES, limitPrice);
        int matchCount = 0;

        int i = 0;
        for (; i + LANE_COUNT <= count; i += LANE_COUNT) {
            final LongVector priceLane = LongVector.fromArray(SPECIES, prices, i);
            final VectorMask<Long> mask = isBuySide
                    ? priceLane.compare(VectorOperators.LE, broadcast)
                    : priceLane.compare(VectorOperators.GE, broadcast);

            if (mask.allTrue()) {
                matchCount += LANE_COUNT;
            } else {
                // Partial match — count true lanes and stop
                matchCount += mask.trueCount();
                return matchCount;
            }
        }

        // Scalar remainder
        for (; i < count; i++) {
            if (isBuySide ? prices[i] <= limitPrice : prices[i] >= limitPrice) {
                matchCount++;
            } else {
                break;
            }
        }

        return matchCount;
    }

    @Override
    public int findFirstMatchableLevel(final long[] prices, final int count,
            final long limitPrice, final boolean isBuySide) {
        // Since prices are sorted, the first matchable level is always at index 0
        // if it satisfies the price condition. This method is primarily useful for
        // non-sorted or partially-sorted scenarios.
        if (count == 0) {
            return -1;
        }

        final LongVector broadcast = LongVector.broadcast(SPECIES, limitPrice);
        int i = 0;

        for (; i + LANE_COUNT <= count; i += LANE_COUNT) {
            final LongVector priceLane = LongVector.fromArray(SPECIES, prices, i);
            final VectorMask<Long> mask = isBuySide
                    ? priceLane.compare(VectorOperators.LE, broadcast)
                    : priceLane.compare(VectorOperators.GE, broadcast);

            if (mask.anyTrue()) {
                return i + firstTrueIndex(mask);
            }
        }

        for (; i < count; i++) {
            if (isBuySide ? prices[i] <= limitPrice : prices[i] >= limitPrice) {
                return i;
            }
        }

        return -1;
    }

    /**
     * Extract the index of the first true lane in a vector mask.
     * Uses {@code Long.numberOfTrailingZeros()} on the mask's long representation
     * for a single-instruction extraction on modern CPUs (TZCNT / BSF).
     */
    private static int firstTrueIndex(final VectorMask<Long> mask) {
        return Long.numberOfTrailingZeros(mask.toLong());
    }
}
