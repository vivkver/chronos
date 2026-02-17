package com.chronos.gateway.response;

import com.chronos.schema.sbe.ExecutionReportDecoder;
import com.chronos.schema.sbe.MessageHeaderDecoder;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/**
 * Egress handler — subscribes to matched execution reports from the cluster
 * and routes them back to the originating FIX client.
 *
 * <h2>Latency Measurement</h2>
 * <p>
 * Records wire-to-wire latency for every execution report by comparing the
 * cluster-assigned timestamp against the current wall clock. This captures the
 * full end-to-end latency including queueing, sequencing, and matching.
 * </p>
 */
public final class EgressHandler {

    private static final Logger LOG = LoggerFactory.getLogger(EgressHandler.class);

    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final ExecutionReportDecoder execDecoder = new ExecutionReportDecoder();
    private final LatencyTracker latencyTracker;

    // Pre-allocated FIX response buffer
    private final ByteBuffer fixResponseBuffer = ByteBuffer.allocateDirect(512);
    private final UnsafeBuffer fixUnsafeBuffer = new UnsafeBuffer(fixResponseBuffer);

    public EgressHandler(final LatencyTracker latencyTracker) {
        this.latencyTracker = latencyTracker;
    }

    /**
     * Process an incoming execution report from the Aeron egress.
     *
     * @param buffer the buffer containing the SBE-encoded execution report
     * @param offset offset in the buffer
     * @param length message length
     * @param client the FIX client channel to send the response to (may be null for
     *               logging)
     */
    public void onExecutionReport(final DirectBuffer buffer, final int offset,
            final int length, final SocketChannel client) {
        // Decode SBE header
        headerDecoder.wrap(buffer, offset);
        final int templateId = headerDecoder.templateId();

        if (templateId != ExecutionReportDecoder.TEMPLATE_ID) {
            LOG.warn("Unexpected template ID on egress: {}", templateId);
            return;
        }

        // Decode execution report
        final int bodyOffset = offset + MessageHeaderDecoder.ENCODED_LENGTH;
        execDecoder.wrap(buffer, bodyOffset);

        // ─── Record latency ───
        final long matchTimestamp = execDecoder.matchTimestampNs();
        if (matchTimestamp > 0) {
            final long now = System.nanoTime();
            final long latency = now - matchTimestamp;
            latencyTracker.recordLatency(latency);
        }

        // ─── Encode FIX ExecutionReport (tag=value format) ───
        if (client != null && client.isConnected()) {
            try {
                final int fixLength = encodeFixExecutionReport(execDecoder, fixResponseBuffer);
                fixResponseBuffer.flip();
                client.write(fixResponseBuffer);
                fixResponseBuffer.clear();
            } catch (final IOException e) {
                LOG.error("Failed to send FIX response", e);
            }
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("ExecReport: orderId={}, execType={}, price={}, filledQty={}, remainQty={}",
                    execDecoder.orderId(),
                    com.chronos.core.domain.ExecType.name(execDecoder.execType()),
                    execDecoder.price(),
                    execDecoder.filledQuantity(),
                    execDecoder.remainingQuantity());
        }
    }

    /**
     * Encode an SBE ExecutionReport into FIX 4.4 tag-value format.
     *
     * @return number of bytes written to the buffer
     */
    private int encodeFixExecutionReport(final ExecutionReportDecoder decoder,
            final ByteBuffer buffer) {
        buffer.clear();

        // FIX 4.4 ExecutionReport (MsgType=8)
        appendTag(buffer, 8, "FIX.4.4"); // BeginString
        appendTag(buffer, 35, "8"); // MsgType = ExecutionReport

        // Order fields
        appendTagLong(buffer, 37, decoder.orderId()); // OrderID
        appendTagLong(buffer, 17, decoder.execId()); // ExecID
        appendTagLong(buffer, 11, decoder.orderId()); // ClOrdID

        // Execution type mapping: 0=New, 1=PartFill, 2=Fill, 3=Cancel, 4=Reject
        final byte execType = decoder.execType();
        final String fixExecType = switch (execType) {
            case 0 -> "0"; // NEW
            case 1 -> "1"; // PARTIAL_FILL
            case 2 -> "2"; // FILL
            case 3 -> "4"; // CANCELED
            case 4 -> "8"; // REJECTED
            default -> "0";
        };
        appendTag(buffer, 150, fixExecType); // ExecType

        // OrdStatus
        final String ordStatus = switch (execType) {
            case 0 -> "0"; // NEW
            case 1 -> "1"; // PARTIALLY_FILLED
            case 2 -> "2"; // FILLED
            case 3 -> "4"; // CANCELED
            case 4 -> "8"; // REJECTED
            default -> "0";
        };
        appendTag(buffer, 39, ordStatus); // OrdStatus

        // Side
        appendTag(buffer, 54, decoder.side() == 0 ? "1" : "2");

        // Quantities
        appendTagInt(buffer, 14, decoder.filledQuantity()); // CumQty
        appendTagInt(buffer, 151, decoder.remainingQuantity()); // LeavesQty

        // Price (convert from fixed-point back to decimal)
        final long priceFixedPoint = decoder.price();
        final String priceStr = formatFixedPointPrice(priceFixedPoint);
        appendTag(buffer, 31, priceStr); // LastPx

        return buffer.position();
    }

    private static void appendTag(final ByteBuffer buf, final int tag, final String value) {
        for (int i = 0; i < Integer.toString(tag).length(); i++) {
            buf.put((byte) Integer.toString(tag).charAt(i));
        }
        buf.put((byte) '=');
        for (int i = 0; i < value.length(); i++) {
            buf.put((byte) value.charAt(i));
        }
        buf.put((byte) 0x01); // SOH
    }

    private static void appendTagLong(final ByteBuffer buf, final int tag, final long value) {
        appendTag(buf, tag, Long.toString(value));
    }

    private static void appendTagInt(final ByteBuffer buf, final int tag, final int value) {
        appendTag(buf, tag, Integer.toString(value));
    }

    private static String formatFixedPointPrice(final long fixedPoint) {
        final long intPart = fixedPoint / 100_000_000L;
        final long fracPart = fixedPoint % 100_000_000L;
        if (fracPart == 0) {
            return Long.toString(intPart) + ".00";
        }
        return intPart + "." + String.format("%08d", fracPart).replaceAll("0+$", "");
    }

    public LatencyTracker latencyTracker() {
        return latencyTracker;
    }
}
