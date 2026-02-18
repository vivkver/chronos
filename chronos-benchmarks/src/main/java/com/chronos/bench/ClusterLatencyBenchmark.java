package com.chronos.bench;

import com.chronos.sequencer.ChronosClusteredService;
import com.chronos.schema.sbe.MessageHeaderEncoder;
import com.chronos.schema.sbe.NewOrderSingleEncoder;
import com.chronos.core.domain.OrderType;
import com.chronos.core.domain.Side;

import io.aeron.Aeron;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.ConsensusModule;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.EgressListener;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Benchmarks the latency of the Aeron Cluster Consensus + Service path.
 *
 * Measures Round Trip Time (RTT):
 * Client -> Consensus Module (Raft) -> Service (Chronos) -> Application Match
 * -> Client
 */
public class ClusterLatencyBenchmark {
    private static final Logger LOG = LoggerFactory.getLogger(ClusterLatencyBenchmark.class);

    // Low latency configuration
    private static final String AERON_DIR = System.getProperty("java.io.tmpdir") + "/chronos-bench-aeron-v5";
    private static final String CLUSTER_DIR = System.getProperty("java.io.tmpdir") + "/chronos-bench-cluster-v5";

    // Signal for reception
    private static final AtomicBoolean responseReceived = new AtomicBoolean(false);

    public static void main(String[] args) {
        try {
            LOG.info("Cleaning dirs...");
            deleteDir(new File(AERON_DIR));
            deleteDir(new File(CLUSTER_DIR));

            LOG.info("Starting Aeron Cluster Benchmark...");
            LOG.info("Setting up Contexts...");

            // 1. Media Driver
            // 1. Media Driver
            MediaDriver.Context driverCtx = new MediaDriver.Context()
                    .aeronDirectoryName(AERON_DIR)
                    .threadingMode(ThreadingMode.DEDICATED)
                    .conductorIdleStrategy(new org.agrona.concurrent.BusySpinIdleStrategy())
                    .senderIdleStrategy(new org.agrona.concurrent.BusySpinIdleStrategy())
                    .receiverIdleStrategy(new org.agrona.concurrent.BusySpinIdleStrategy())
                    .termBufferSparseFile(true)
                    .dirDeleteOnStart(true);

            // 2. Archive Service Context
            Archive.Context archiveCtx = new Archive.Context()
                    .aeronDirectoryName(AERON_DIR)
                    .archiveDir(new File(CLUSTER_DIR, "archive"))
                    .controlChannel("aeron:udp?endpoint=localhost:8050") // UDP required
                    .localControlChannel("aeron:ipc")
                    .recordingEventsChannel("aeron:ipc")
                    .replicationChannel("aeron:udp?endpoint=localhost:0") // Dummy
                    .threadingMode(ArchiveThreadingMode.DEDICATED)
                    .deleteArchiveOnStart(true);

            // 3. Consensus Module needs an AeronArchive.Context (Client context)
            AeronArchive.Context aeronArchiveCtx = new AeronArchive.Context()
                    .controlRequestChannel("aeron:ipc")
                    .controlResponseChannel("aeron:ipc")
                    .recordingEventsChannel("aeron:ipc")
                    .aeronDirectoryName(AERON_DIR);

            // Cluster Config: Single Mode
            File consensusDir = new File(CLUSTER_DIR, "consensus"); // Shared with Service
            ConsensusModule.Context consensusCtx = new ConsensusModule.Context()
                    .errorHandler(t -> LOG.error("CONSENSUS ERROR", t))
                    .clusterMemberId(0)
                    .clusterMembers("0,localhost:20510,localhost:20511,localhost:20512,localhost:0,localhost:8050")
                    .aeronDirectoryName(AERON_DIR)
                    .clusterDir(consensusDir)
                    .ingressChannel("aeron:ipc")
                    .logChannel("aeron:ipc")
                    .replicationChannel("aeron:ipc")
                    .archiveContext(aeronArchiveCtx)
                    .deleteDirOnStart(true);

            // 4. Clustered Service
            // MUST point to the SAME directory as ConsensusModule to find the Mark File
            ClusteredServiceContainer.Context serviceCtx = new ClusteredServiceContainer.Context()
                    .aeronDirectoryName(AERON_DIR)
                    .clusterDir(consensusDir)
                    .clusteredService(new ChronosClusteredService())
                    .errorHandler(t -> LOG.error("SERVICE ERROR", t));

            LOG.info("Launching components...");
            try (MediaDriver driver = MediaDriver.launch(driverCtx);
                    Archive archive = Archive.launch(archiveCtx);
                    ConsensusModule consensusModule = ConsensusModule.launch(consensusCtx);
                    ClusteredServiceContainer container = ClusteredServiceContainer.launch(serviceCtx)) {

                LOG.info("Cluster started. Connecting client...");

                // 5. Connect Client
                // Egress Listener
                EgressListener listener = new EgressListener() {
                    @Override
                    public void onMessage(long clusterSessionId, long timestamp, DirectBuffer buffer, int offset,
                            int length, Header header) {
                        responseReceived.set(true);
                    }

                    @Override
                    public void onSessionEvent(long correlationId, long clusterSessionId, long leadershipTermId,
                            int leaderMemberId, io.aeron.cluster.codecs.EventCode code, String detail) {
                    }

                    @Override
                    public void onNewLeader(long clusterSessionId, long leadershipTermId, int leaderMemberId,
                            String ingressEndpoints) {
                    }
                };

                try (AeronCluster cluster = AeronCluster.connect(
                        new AeronCluster.Context()
                                .aeronDirectoryName(AERON_DIR)
                                .ingressChannel("aeron:ipc")
                                .egressChannel("aeron:ipc")
                                .egressListener(listener))) {

                    LOG.info("Client connected. Leader ID: {}", cluster.leaderMemberId());

                    runBenchmark(cluster);
                }
            }
        } catch (Throwable t) {
            LOG.error("FATAL ERROR IN BENCHMARK", t);
        }
    }

    private static void runBenchmark(AeronCluster cluster) {
        org.HdrHistogram.Histogram histogram = new org.HdrHistogram.Histogram(3);
        UnsafeBuffer buffer = new UnsafeBuffer(new byte[1024]);
        MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
        NewOrderSingleEncoder orderEncoder = new NewOrderSingleEncoder();

        int WARMUP = 2_000;
        int MESSAGES = 20_000;

        LOG.info("Starting Ping-Pong Latency Benchmark ({} messages)...", MESSAGES);

        for (int i = 0; i < MESSAGES + WARMUP; i++) {
            long startNs = System.nanoTime();
            responseReceived.set(false);

            // Encode
            headerEncoder.wrap(buffer, 0)
                    .blockLength(NewOrderSingleEncoder.BLOCK_LENGTH)
                    .templateId(NewOrderSingleEncoder.TEMPLATE_ID)
                    .schemaId(NewOrderSingleEncoder.SCHEMA_ID)
                    .version(NewOrderSingleEncoder.SCHEMA_VERSION);

            orderEncoder.wrap(buffer, MessageHeaderEncoder.ENCODED_LENGTH)
                    .orderId(i)
                    .price(1000)
                    .quantity(10)
                    .side(Side.BUY)
                    .orderType(OrderType.LIMIT)
                    .instrumentId(1)
                    .clientId(1);

            int length = MessageHeaderEncoder.ENCODED_LENGTH + NewOrderSingleEncoder.BLOCK_LENGTH;

            while (cluster.offer(buffer, 0, length) < 0) {
                Thread.yield();
            }

            // Wait for response
            boolean gotResponse = false;
            long waitStart = System.nanoTime();
            while (!gotResponse) {
                if (cluster.pollEgress() > 0) {
                    if (responseReceived.get()) {
                        gotResponse = true;
                    }
                } else {
                    if (System.nanoTime() - waitStart > 10_000_000_000L) { // 10s timeout
                        LOG.warn("Timeout waiting for response to order {}", i);
                        break;
                    }
                    Thread.onSpinWait();
                }
            }

            long latency = System.nanoTime() - startNs;
            if (i >= WARMUP && gotResponse) {
                histogram.recordValue(Math.max(1, latency));
            }

            if (i % 5000 == 0) {
                LOG.info("Proceeded {}/{} messages", i, MESSAGES + WARMUP);
            }
        }

        LOG.info("Benchmark complete.");
        if (histogram.getTotalCount() > 0) {
            LOG.info("═══════════════════════════════════════════════════════");
            LOG.info("  LATENCY REPORT: Aeron Cluster (IPC)");
            LOG.info("═══════════════════════════════════════════════════════");
            LOG.info("  Mean        : {} us", String.format("%.2f", histogram.getMean() / 1000.0));
            LOG.info("  P50         : {} us", String.format("%.2f", histogram.getValueAtPercentile(50.0) / 1000.0));
            LOG.info("  P90         : {} us", String.format("%.2f", histogram.getValueAtPercentile(90.0) / 1000.0));
            LOG.info("  P99         : {} us", String.format("%.2f", histogram.getValueAtPercentile(99.0) / 1000.0));
            LOG.info("  P99.9       : {} us", String.format("%.2f", histogram.getValueAtPercentile(99.9) / 1000.0));
            LOG.info("  Max         : {} us", String.format("%.2f", histogram.getMaxValue() / 1000.0));
            LOG.info("═══════════════════════════════════════════════════════");
        } else {
            LOG.error("No successful messages recorded!");
        }
    }

    private static void deleteDir(File dir) {
        if (dir.exists()) {
            try (java.util.stream.Stream<Path> stream = java.nio.file.Files.walk(dir.toPath())) {
                stream.sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            } catch (Exception e) {
                LOG.error("Failed to delete directory: " + dir, e);
            }
        }
    }
}
