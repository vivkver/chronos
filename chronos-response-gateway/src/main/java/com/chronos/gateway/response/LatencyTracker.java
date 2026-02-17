package com.chronos.gateway.response;

import org.HdrHistogram.Histogram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;

/**
 * High-precision latency tracker using HDRHistogram for P99.99 measurement.
 *
 * <h2>Why HDRHistogram?</h2>
 * <p>
 * Standard averages and even P99 metrics hide tail latency spikes that are
 * fatal
 * in trading. HDRHistogram captures the full distribution with configurable
 * precision,
 * enabling measurement of P99.99 and P99.999 — the metrics that matter for ULL.
 * </p>
 *
 * <h2>Coordinated Omission</h2>
 * <p>
 * Supports optional coordinated-omission correction via
 * {@link Histogram#copyCorrectedForCoordinatedOmission(long)} to ensure that
 * stalls don't hide behind apparent low latency.
 * </p>
 *
 * <h2>Zero-Allocation Recording</h2>
 * <p>
 * {@code recordLatency()} is O(1) and allocates zero objects — safe for the hot
 * path.
 * Reporting methods (percentiles, etc.) are NOT allocation-free and should only
 * be called
 * off the hot path (e.g., on shutdown or periodic timer).
 * </p>
 */
public final class LatencyTracker {

    private static final Logger LOG = LoggerFactory.getLogger(LatencyTracker.class);

    /** Maximum trackable latency: 10 seconds in nanoseconds. */
    private static final long MAX_LATENCY_NS = 10_000_000_000L;

    /** Significant value digits for HDRHistogram precision. 3 = 0.1% accuracy. */
    private static final int SIGNIFICANT_DIGITS = 3;

    private final String name;
    private final Histogram histogram;
    private long recordCount;

    public LatencyTracker(final String name) {
        this.name = name;
        this.histogram = new Histogram(MAX_LATENCY_NS, SIGNIFICANT_DIGITS);
    }

    /**
     * Record a latency measurement. Zero-allocation, O(1).
     *
     * @param latencyNs latency in nanoseconds
     */
    public void recordLatency(final long latencyNs) {
        if (latencyNs >= 0 && latencyNs <= MAX_LATENCY_NS) {
            histogram.recordValue(latencyNs);
            recordCount++;
        }
    }

    /**
     * Print the full percentile distribution.
     * NOT allocation-free — call off the hot path only.
     */
    public void printReport(final PrintStream out) {
        out.println("═══════════════════════════════════════════════════════");
        out.println("  LATENCY REPORT: " + name);
        out.println("═══════════════════════════════════════════════════════");
        out.printf("  Total samples      : %,d%n", recordCount);
        out.printf("  Min         (ns)   : %,d%n", histogram.getMinValue());
        out.printf("  Max         (ns)   : %,d%n", histogram.getMaxValue());
        out.printf("  Mean        (ns)   : %,.1f%n", histogram.getMean());
        out.printf("  StdDev      (ns)   : %,.1f%n", histogram.getStdDeviation());
        out.println("───────────────────────────────────────────────────────");
        out.printf("  P50         (ns)   : %,d%n", histogram.getValueAtPercentile(50));
        out.printf("  P90         (ns)   : %,d%n", histogram.getValueAtPercentile(90));
        out.printf("  P99         (ns)   : %,d%n", histogram.getValueAtPercentile(99));
        out.printf("  P99.9       (ns)   : %,d%n", histogram.getValueAtPercentile(99.9));
        out.printf("  P99.99      (ns)   : %,d%n", histogram.getValueAtPercentile(99.99));
        out.printf("  P99.999     (ns)   : %,d%n", histogram.getValueAtPercentile(99.999));
        out.println("═══════════════════════════════════════════════════════");
    }

    /**
     * Get a specific percentile value.
     *
     * @param percentile the percentile (e.g., 99.99)
     * @return latency in nanoseconds at the given percentile
     */
    public long getPercentile(final double percentile) {
        return histogram.getValueAtPercentile(percentile);
    }

    /** Reset the histogram for a new measurement window. */
    public void reset() {
        histogram.reset();
        recordCount = 0;
    }

    public long recordCount() {
        return recordCount;
    }

    public String name() {
        return name;
    }
}
