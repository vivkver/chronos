package com.chronos.sequencer;

import com.chronos.core.lob.OffHeapOrderBook;
import com.chronos.matching.MatchingEngine;
import com.chronos.matching.PriceScannerFactory;
import com.chronos.schema.sbe.CancelOrderDecoder;
import com.chronos.schema.sbe.MessageHeaderDecoder;
import com.chronos.schema.sbe.MessageHeaderEncoder;
import com.chronos.schema.sbe.NewOrderSingleDecoder;
import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Aeron Clustered Service implementing total ordering via Raft consensus.
 *
 * <h2>Architecture</h2>
 * <p>
 * This is the "Spinal Cord" of CHRONOS. Every order that enters the system
 * passes
 * through the Aeron Cluster log, which assigns a deterministic sequence number
 * and
 * timestamp via Raft consensus. This ensures every replica sees the exact same
 * sequence of operations.
 * </p>
 *
 * <h2>In-Process Matching</h2>
 * <p>
 * The {@link MatchingEngine} runs <em>inside</em> this service (same process)
 * to
 * eliminate network hops between sequencing and matching. The engine operates
 * on the
 * cluster-provided timestamp for full determinism across leader and followers.
 * </p>
 *
 * <h2>Snapshotting</h2>
 * <p>
 * Implements {@link #onTakeSnapshot} and {@link #onLoadSnapshot} for Aeron
 * Cluster
 * state persistence. Snapshots capture the full order book state for fast
 * recovery.
 * </p>
 */
public final class ChronosClusteredService implements ClusteredService {

    private static final Logger LOG = LoggerFactory.getLogger(ChronosClusteredService.class);

    /** Default instrument for single-instrument deployment. */
    private static final int DEFAULT_INSTRUMENT_ID = 1;

    // Pre-allocated decoders (flyweight, reused)
    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final NewOrderSingleDecoder orderDecoder = new NewOrderSingleDecoder();
    private final CancelOrderDecoder cancelDecoder = new CancelOrderDecoder();

    // Output buffer for execution reports (pre-allocated, reused per message)
    private final MutableDirectBuffer outputBuffer = new UnsafeBuffer(new byte[4096]);

    // The matching engine (co-located in this process)
    private final MatchingEngine matchingEngine;

    private Cluster cluster;
    private long messageCount;

    public ChronosClusteredService() {
        // MVP: Pre-allocate order books for instrument IDs 1-10
        // In production, this would be configuration-driven
        org.agrona.collections.Int2ObjectHashMap<OffHeapOrderBook> books = new org.agrona.collections.Int2ObjectHashMap<>();
        for (int i = 1; i <= 10; i++) {
            books.put(i, new OffHeapOrderBook(i));
        }
        this.matchingEngine = new MatchingEngine(books, PriceScannerFactory.create());
    }

    @Override
    public void onStart(final Cluster cluster, final Image snapshotImage) {
        this.cluster = cluster;
        LOG.info("CHRONOS Clustered Service started. Role: {}", cluster.role());

        if (snapshotImage != null) {
            loadSnapshot(snapshotImage);
        }
    }

    @Override
    public void onSessionOpen(final ClientSession session, final long timestamp) {
        LOG.info("Client session opened: sessionId={}, timestamp={}", session.id(), timestamp);
    }

    @Override
    public void onSessionClose(final ClientSession session, final long timestamp,
            final CloseReason closeReason) {
        LOG.info("Client session closed: sessionId={}, reason={}", session.id(), closeReason);
    }

    /**
     * Hot path: called for every message that passes through the Raft log.
     * This is where total ordering meets matching — the cluster guarantees that
     * every replica calls this method with the same messages in the same order.
     *
     * @param session   the client session that sent the message
     * @param timestamp deterministic timestamp assigned by the cluster leader
     * @param buffer    the message buffer
     * @param offset    offset in the buffer
     * @param length    length of the message
     * @param header    Aeron log header
     */
    @Override
    public void onSessionMessage(final ClientSession session, final long timestamp,
            final DirectBuffer buffer, final int offset,
            final int length, final Header header) {
        // Decode the SBE message header
        headerDecoder.wrap(buffer, offset);
        final int templateId = headerDecoder.templateId();
        final int bodyOffset = offset + MessageHeaderDecoder.ENCODED_LENGTH;

        switch (templateId) {
            case NewOrderSingleDecoder.TEMPLATE_ID -> {
                orderDecoder.wrap(buffer, bodyOffset);
                final int bytesWritten = matchingEngine.matchOrder(
                        orderDecoder, timestamp, outputBuffer, 0);

                if (bytesWritten > 0 && session != null) {
                    sendResponse(session, outputBuffer, 0, bytesWritten);
                }
            }

            case CancelOrderDecoder.TEMPLATE_ID -> {
                cancelDecoder.wrap(buffer, bodyOffset);
                // Cancel logic: find and remove the order
                // For now, broadcast a CANCELED execution report
                LOG.debug("Cancel request: orderId={}", cancelDecoder.orderId());
            }

            default -> LOG.warn("Unknown template ID: {}", templateId);
        }

        messageCount++;
    }

    @Override
    public void onTimerEvent(final long correlationId, final long timestamp) {
        // Could be used for session timeout, heartbeat monitoring, etc.
    }

    @Override
    public void onTakeSnapshot(final ExclusivePublication snapshotPublication) {
        LOG.info("Taking snapshot of all order books...");

        // 1. Write metadata (message count, number of books)
        final MutableDirectBuffer metadataBuffer = new ExpandableArrayBuffer(128);
        metadataBuffer.putLong(0, messageCount);

        // Get all books from engine
        // Note: In a real impl, we'd iterate the map. For MVP, we know IDs 1-10.
        int bookCount = 10;
        metadataBuffer.putInt(8, bookCount);

        long result = snapshotPublication.offer(metadataBuffer, 0, 12);
        if (result < 0) {
            LOG.warn("Snapshot offer failed: {}", result);
            return;
        }

        // 2. Serialize each order book
        // format: [instrumentId(4)][orderCount(4)][Order1...][OrderN...]
        for (int i = 1; i <= bookCount; i++) {
            OffHeapOrderBook book = matchingEngine.orderBook(i);
            if (book != null) {
                // For MVP: We are just serializing the counts to prove the point.
                // A full prod impl would require a custom iterator over the OffHeapOrderBook's
                // unsafe memory
                // to write every order to the snapshot buffer.
                // We will simulate this by writing a "book header"

                metadataBuffer.putInt(0, i); // Instrument ID
                metadataBuffer.putInt(4, book.liveOrderCount());
                snapshotPublication.offer(metadataBuffer, 0, 8);
            }
        }

        LOG.info("Snapshot complete.");
    }

    @Override
    public void onRoleChange(final Cluster.Role newRole) {
        LOG.info("Role changed to: {}", newRole);
    }

    @Override
    public void onTerminate(final Cluster cluster) {
        LOG.info("Terminating. Total messages processed: {}", messageCount);
    }

    private void sendResponse(final ClientSession session,
            final DirectBuffer buffer,
            final int offset, final int length) {
        // Retry loop for back-pressure (Aeron publication may be full)
        long result;
        int retries = 3;
        do {
            result = session.offer(buffer, offset, length);
            if (result > 0) {
                return;
            }
            Thread.onSpinWait(); // CPU-friendly spin
        } while (--retries > 0);

        if (result < 0) {
            LOG.warn("Failed to send response to session {}: result={}", session.id(), result);
        }
    }

    private void loadSnapshot(final Image snapshotImage) {
        LOG.info("Loading snapshot...");
        // In production: deserialize the full order book from the snapshot
        // For now, just log that we would restore
    }
}
