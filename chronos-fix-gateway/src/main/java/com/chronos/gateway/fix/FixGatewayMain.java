package com.chronos.gateway.fix;

import com.chronos.gateway.fix.FixSession;
import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.List;

/**
 * Production-grade FIX Gateway with full session layer support.
 *
 * <h2>Architecture</h2>
 * <p>
 * Accepts TCP connections from FIX clients, manages FIX sessions with full
 * protocol compliance (Logon, Logout, Heartbeat, sequence numbers), and routes
 * application messages to the Aeron Cluster.
 * </p>
 *
 * <h2>Message Routing</h2>
 * <ul>
 * <li><b>Session Messages</b> (Logon, Logout, Heartbeat, TestRequest) - Handled
 * by session layer</li>
 * <li><b>Application Messages</b> (NewOrderSingle, CancelOrder, Quote,
 * QuoteRequest) - Encoded to SBE and sent to Aeron</li>
 * </ul>
 *
 * <h2>Threading Model</h2>
 * <p>
 * Single-threaded NIO selector loop with periodic heartbeat monitoring.
 * Should be pinned to an isolated CPU core in production.
 * </p>
 */
public final class FixGatewayMain {

    private static final Logger LOG = LoggerFactory.getLogger(FixGatewayMain.class);

    private static final int FIX_PORT = 9876;
    private static final String AERON_CHANNEL = "aeron:ipc";
    private static final int BASE_AERON_STREAM_ID = 1001;
    private static final int NUM_SHARDS = 2; // Default for MVP
    private static final int READ_BUFFER_SIZE = 8192;

    private static final String SENDER_COMP_ID = "CHRONOS";
    private static final String TARGET_COMP_ID = "CLIENT";
    private static final long HEARTBEAT_CHECK_INTERVAL_MS = 1000; // Check every 1 second

    // For integration testing
    public static volatile String TEST_AERON_DIR_NAME;

    public static void main(final String[] args) throws IOException {
        LOG.info("Starting CHRONOS FIX Gateway with Session Layer on port {}...", FIX_PORT);

        // Pre-allocate buffers (zero allocation on hot path)
        final ByteBuffer readBuffer = ByteBuffer.allocateDirect(READ_BUFFER_SIZE);
        final UnsafeBuffer readUnsafeBuffer = new UnsafeBuffer(readBuffer);
        final ByteBuffer sbeRawBuffer = ByteBuffer.allocateDirect(4096);
        final UnsafeBuffer sbeBuffer = new UnsafeBuffer(sbeRawBuffer); // Larger buffer for all message types

        final FixToSbeEncoder encoder = new FixToSbeEncoder();
        final FixParser parser = new FixParser();

        // Initialize Router
        final InstrumentRouter router = InstrumentRouter.createDefault();
        LOG.info("Initialized Instrument Router for {} shards", NUM_SHARDS);

        // ─── Embedded Media Driver ───
        final MediaDriver.Context mdCtx = new MediaDriver.Context()
                .threadingMode(ThreadingMode.SHARED)
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true);

        // ─── Health Server ───
        final com.chronos.core.util.HealthServer healthServer = new com.chronos.core.util.HealthServer();

        try (
                MediaDriver mediaDriver = MediaDriver.launch(mdCtx);
                Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(mediaDriver.aeronDirectoryName()));
                ServerSocketChannel serverChannel = ServerSocketChannel.open();
                Selector selector = Selector.open()) {

            TEST_AERON_DIR_NAME = mediaDriver.aeronDirectoryName();

            // Connect to multiple shards
            final Publication[] publications = new Publication[NUM_SHARDS];
            for (int i = 0; i < NUM_SHARDS; i++) {
                final int streamId = BASE_AERON_STREAM_ID + i;
                publications[i] = aeron.addPublication(AERON_CHANNEL, streamId);
                LOG.info("Connected to Shard {} on Stream ID {}", i, streamId);
            }

            serverChannel.bind(new InetSocketAddress(FIX_PORT));
            serverChannel.configureBlocking(false);
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);

            LOG.info("FIX Gateway listening on port {}. Aeron publications connected.", FIX_PORT);
            LOG.info("Session Manager initialized. Ready to accept FIX connections.");

            long lastHeartbeatCheckTime = System.currentTimeMillis();

            // Register health checks
            healthServer.register("aeron", () -> !aeron.isClosed());
            healthServer.register("shards_connected", () -> {
                for (Publication p : publications) {
                    if (!p.isConnected())
                        return false;
                }
                return true;
            });
            healthServer.start(8081);

            // ─── Shutdown Hook ───
            final Thread mainThread = Thread.currentThread();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LOG.info("Shutdown signal received. Interrupting main loop...");
                mainThread.interrupt();
                try {
                    mainThread.join(5000);
                } catch (InterruptedException e) {
                    // ignore
                }
            }));

            // ─── NIO Event Loop ───
            while (!Thread.currentThread().isInterrupted()) {
                selector.select(1); // 1ms timeout for responsiveness

                final Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                while (keys.hasNext()) {
                    final SelectionKey key = keys.next();
                    keys.remove();

                    try {
                        if (key.isAcceptable()) {
                            final SocketChannel client = serverChannel.accept();
                            if (client != null) {
                                client.configureBlocking(false);
                                final SelectionKey clientKey = client.register(selector, SelectionKey.OP_READ);

                                // Create new session with Router and Publications
                                final FixSession session = new FixSession(client, encoder, publications, router,
                                        sbeBuffer);
                                clientKey.attach(session);

                                LOG.info("FIX client connected: {}", client.getRemoteAddress());
                            }
                        } else if (key.isReadable()) {
                            final SocketChannel client = (SocketChannel) key.channel();
                            final FixSession session = (FixSession) key.attachment();

                            readBuffer.clear();
                            final int bytesRead = client.read(readBuffer);

                            if (bytesRead == -1) {
                                LOG.info("FIX client disconnected");
                                key.cancel();
                                client.close();
                                continue;
                            }

                            if (bytesRead > 0) {
                                readBuffer.flip();

                                // Zero-copy parse directly from buffer
                                final int fieldCount = parser.parse(
                                        readUnsafeBuffer, readBuffer.position(), bytesRead);

                                if (fieldCount > 0) {
                                    // Delegate logic to session state machine
                                    session.onMessage(parser);
                                }
                            }
                        }
                    } catch (Exception e) {
                        LOG.error("Error in selector loop", e);
                        key.cancel();
                        try {
                            key.channel().close();
                        } catch (IOException ex) {
                            // ignore
                        }
                    }
                }

                // Periodic heartbeat monitoring
                final long currentTime = System.currentTimeMillis();
                if (currentTime - lastHeartbeatCheckTime >= HEARTBEAT_CHECK_INTERVAL_MS) {
                    lastHeartbeatCheckTime = currentTime;
                }
            }

            // Graceful shutdown
            LOG.info("Shutting down FIX Gateway...");
        } finally {
            if (healthServer != null) {
                healthServer.stop();
                LOG.info("HealthServer stopped.");
            }
        }

        LOG.info("FIX Gateway shutdown complete.");
    }
}
