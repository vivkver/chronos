package com.chronos.gateway.response;

import io.aeron.Aeron;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.ShutdownSignalBarrier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Response Gateway — the egress door for CHRONOS.
 *
 * <p>
 * Subscribes to the Aeron cluster egress channel, processes execution reports
 * through {@link EgressHandler}, and tracks P99.99 latency via
 * {@link LatencyTracker}.
 * Prints a latency report on shutdown.
 * </p>
 */
public final class ResponseGatewayMain {

    private static final Logger LOG = LoggerFactory.getLogger(ResponseGatewayMain.class);

    private static final String AERON_CHANNEL = "aeron:ipc";
    private static final int AERON_STREAM_ID = 1002; // egress stream

    public static void main(final String[] args) {
        LOG.info("Starting CHRONOS Response Gateway...");

        final ShutdownSignalBarrier barrier = new ShutdownSignalBarrier();

        final LatencyTracker latencyTracker = new LatencyTracker("Wire-to-Wire");
        final EgressHandler egressHandler = new EgressHandler(latencyTracker);

        final MediaDriver.Context mdCtx = new MediaDriver.Context()
                .threadingMode(ThreadingMode.SHARED)
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true);

        try (
                MediaDriver mediaDriver = MediaDriver.launch(mdCtx);
                Aeron aeron = Aeron.connect(
                        new Aeron.Context().aeronDirectoryName(mediaDriver.aeronDirectoryName()));
                Subscription subscription = aeron.addSubscription(AERON_CHANNEL, AERON_STREAM_ID)) {
            LOG.info("Response Gateway subscribed to egress stream.");

            final IdleStrategy idleStrategy = new BusySpinIdleStrategy();

            // Fragment handler: delegates to EgressHandler
            final FragmentHandler fragmentHandler = (buffer, offset, length, header) -> egressHandler
                    .onExecutionReport(buffer, offset, length, null);

            // ─── Poll Loop ───
            final Thread pollThread = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    final int fragmentsRead = subscription.poll(fragmentHandler, 10);
                    idleStrategy.idle(fragmentsRead);
                }
            }, "response-gateway-poll");
            pollThread.setDaemon(true);
            pollThread.start();

            barrier.await();

            LOG.info("Shutdown signal received. Printing latency report...");
            latencyTracker.printReport(System.out);

        }

        LOG.info("Response Gateway shutdown complete.");
    }
}
