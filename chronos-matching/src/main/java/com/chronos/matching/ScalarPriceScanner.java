package com.chronos.matching;

/**
 * Scalar (non-SIMD) baseline price scanner for benchmark comparison.
 * This is the traditional approach — one comparison per iteration.
 */
public final class ScalarPriceScanner implements PriceScanner {

    @Override
    public int findInsertionPoint(final long[] prices, final int count,
            final long newPrice, final boolean descending) {
        for (int i = 0; i < count; i++) {
            if (descending ? prices[i] < newPrice : prices[i] > newPrice) {
                return i;
            }
        }
        return count;
    }

    @Override
    public int countMatchableLevels(final long[] prices, final int count,
            final long limitPrice, final boolean isBuySide) {
        int matchCount = 0;
        for (int i = 0; i < count; i++) {
            if (isBuySide ? prices[i] <= limitPrice : prices[i] >= limitPrice) {
                matchCount++;
            } else {
                break; // prices are sorted, so no more matches
            }
        }
        return matchCount;
    }

    @Override
    public int findFirstMatchableLevel(final long[] prices, final int count,
            final long limitPrice, final boolean isBuySide) {
        for (int i = 0; i < count; i++) {
            if (isBuySide ? prices[i] <= limitPrice : prices[i] >= limitPrice) {
                return i;
            }
        }
        return -1;
    }
}
