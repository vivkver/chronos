package com.chronos.matching;

/**
 * Strategy interface for price-level scanning.
 * Implementations include SIMD-vectorized and scalar baselines.
 */
public interface PriceScanner {

    /**
     * Find the insertion point for a new price in a sorted price array.
     *
     * <p>
     * For bids (descending): returns the first index where
     * {@code prices[i] < newPrice}.<br>
     * For asks (ascending): returns the first index where
     * {@code prices[i] > newPrice}.
     *
     * @param prices     sorted price array
     * @param count      number of active levels
     * @param newPrice   price to insert
     * @param descending true for bid side (descending sort), false for ask side
     *                   (ascending)
     * @return insertion index, or {@code count} if the price should be appended
     */
    int findInsertionPoint(long[] prices, int count, long newPrice, boolean descending);

    /**
     * Count how many contiguous price levels from the top of book can be swept
     * by an aggressive order at the given limit price.
     *
     * <p>
     * For a BUY sweeping asks (ascending): counts levels where
     * {@code askPrice <= limitPrice}.<br>
     * For a SELL sweeping bids (descending): counts levels where
     * {@code bidPrice >= limitPrice}.
     *
     * @param prices     sorted price array (bid or ask side)
     * @param count      number of active levels
     * @param limitPrice the aggressive order's limit price
     * @param isBuySide  true if the incoming order is a BUY (sweeps asks)
     * @return number of matchable levels from index 0
     */
    int countMatchableLevels(long[] prices, int count, long limitPrice, boolean isBuySide);

    /**
     * Find the first price level that matches (is tradeable against) an incoming
     * order.
     *
     * @param prices     sorted price array
     * @param count      number of active levels
     * @param limitPrice incoming order's limit price
     * @param isBuySide  true if incoming order is BUY
     * @return index of first matchable level, or -1 if none
     */
    int findFirstMatchableLevel(long[] prices, int count, long limitPrice, boolean isBuySide);
}
