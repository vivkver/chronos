package com.chronos.core.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * centralized metrics registry for Chronos.
 * capable of using Agrona counters (if provided) or fallback AtomicLongs.
 */
public final class ChronosMetrics {
    private static final AtomicLong ordersProcessed = new AtomicLong(0);
    private static final AtomicLong ordersRejected = new AtomicLong(0);
    private static final AtomicLong matchesFound = new AtomicLong(0);
    private static final AtomicLong latencyAccumulator = new AtomicLong(0);
    private static final AtomicLong latencyCount = new AtomicLong(0);

    private ChronosMetrics() {
    }

    public static void onOrderProcessed() {
        ordersProcessed.incrementAndGet();
    }

    public static void onOrderRejected() {
        ordersRejected.incrementAndGet();
    }

    public static void onMatchFound() {
        matchesFound.incrementAndGet();
    }

    public static void recordLatency(long nanos) {
        latencyAccumulator.addAndGet(nanos);
        latencyCount.incrementAndGet();
    }

    public static long getOrdersProcessed() {
        return ordersProcessed.get();
    }

    public static long getOrdersRejected() {
        return ordersRejected.get();
    }

    public static long getMatchesFound() {
        return matchesFound.get();
    }

    public static double getAverageLatencyNs() {
        long count = latencyCount.get();
        if (count == 0)
            return 0;
        return (double) latencyAccumulator.get() / count;
    }

    public static void reset() {
        ordersProcessed.set(0);
        ordersRejected.set(0);
        matchesFound.set(0);
        latencyAccumulator.set(0);
        latencyCount.set(0);
    }
}
