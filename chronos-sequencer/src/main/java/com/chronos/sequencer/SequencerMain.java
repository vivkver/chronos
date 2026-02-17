package com.chronos.sequencer;

import io.aeron.cluster.ClusteredMediaDriver;
import io.aeron.cluster.ConsensusModule;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.concurrent.ShutdownSignalBarrier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the CHRONOS sequencer node.
 *
 * <h2>Deployment</h2>
 * <p>
 * In production, this process should be pinned to an isolated CPU core
 * using {@code taskset} (Linux) or processor affinity (Windows) to eliminate
 * context-switch jitter:
 * </p>
 * 
 * <pre>
 *   # Linux: pin to core 2
 *   taskset -c 2 java -jar chronos-sequencer.jar
 *
 *   # Or via JVM: -Daeron.conductor.cpu.affinity=2
 * </pre>
 *
 * <h2>JVM Flags</h2>
 * 
 * <pre>
 *   -XX:+UseZGC -XX:+ZGenerational
 *   -Xms4g -Xmx4g
 *   -XX:+AlwaysPreTouch
 *   -XX:+UnlockExperimentalVMOptions
 *   --add-modules jdk.incubator.vector
 * </pre>
 */
public final class SequencerMain {

    private static final Logger LOG = LoggerFactory.getLogger(SequencerMain.class);

    public static void main(final String[] args) {
        LOG.info("Starting CHRONOS Sequencer...");

        final ShutdownSignalBarrier barrier = new ShutdownSignalBarrier();

        // ─── Media Driver: dedicated threading for lowest latency ───
        final MediaDriver.Context mediaDriverCtx = new MediaDriver.Context()
                .threadingMode(ThreadingMode.DEDICATED)
                .conductorIdleStrategy(new org.agrona.concurrent.BusySpinIdleStrategy())
                .receiverIdleStrategy(new org.agrona.concurrent.BusySpinIdleStrategy())
                .senderIdleStrategy(new org.agrona.concurrent.BusySpinIdleStrategy())
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true);

        // ─── Consensus Module: Raft configuration ───
        final ConsensusModule.Context consensusCtx = new ConsensusModule.Context();

        // ─── Service Container: hosts our ClusteredService ───
        final ClusteredServiceContainer.Context serviceCtx = new ClusteredServiceContainer.Context()
                .clusteredService(new ChronosClusteredService());

        try (
                ClusteredMediaDriver clusteredMediaDriver = ClusteredMediaDriver.launch(
                        mediaDriverCtx, null, consensusCtx);
                ClusteredServiceContainer container = ClusteredServiceContainer.launch(serviceCtx)) {
            LOG.info("CHRONOS Sequencer running. Waiting for shutdown signal...");
            barrier.await();
            LOG.info("Shutdown signal received. Stopping...");
        }
    }
}
