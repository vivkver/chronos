package com.chronos.gateway.fix;

import com.chronos.gateway.fix.protection.CircuitBreaker;
import com.chronos.gateway.fix.validation.FIXValidator;
import com.chronos.gateway.fix.validation.ValidationResult;
import io.aeron.Publication;
import io.aeron.logbuffer.BufferClaim;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.channels.SocketChannel;

/**
 * Represents a single FIX connection session.
 * Managed by FixGatewayMain, handles state and sequence numbers.
 */
public class FixSession {
    private static final Logger LOG = LoggerFactory.getLogger(FixSession.class);

    // FIX 4.4 Message Types (1-char)
    public static final byte MSG_TYPE_HEARTBEAT = '0';
    public static final byte MSG_TYPE_TEST_REQUEST = '1';
    public static final byte MSG_TYPE_RESEND_REQUEST = '2';
    public static final byte MSG_TYPE_REJECT = '3';
    public static final byte MSG_TYPE_SEQUENCE_RESET = '4';
    public static final byte MSG_TYPE_LOGOUT = '5';
    public static final byte MSG_TYPE_LOGON = 'A';
    public static final byte MSG_TYPE_NEW_ORDER_SINGLE = 'D';
    public static final byte MSG_TYPE_ORDER_CANCEL_REQUEST = 'F';

    private enum State {
        CONNECTED,
        LOGON_RECEIVED,
        LOGGED_ON,
        DISCONNECTED
    }

    private final SocketChannel channel;
    private final FixToSbeEncoder encoder;
    private final Publication[] publications; // Array indexed by Shard ID
    private final InstrumentRouter router;
    private final UnsafeBuffer sbeBuffer;
    private final BufferClaim bufferClaim = new BufferClaim(); // Reused for zero-copy
    private final FIXValidator validator;
    private final CircuitBreaker circuitBreaker;

    private State state = State.CONNECTED;
    private int nextExpectedMsgSeqNum = 1;
    private int nextSenderMsgSeqNum = 1;

    // CompIDs for validation
    private String senderCompId;
    private String targetCompId;

    public FixSession(SocketChannel channel, FixToSbeEncoder encoder, Publication[] publications,
            InstrumentRouter router, UnsafeBuffer sbeBuffer) {
        this.channel = channel;
        this.encoder = encoder;
        this.publications = publications;
        this.router = router;
        this.sbeBuffer = sbeBuffer;
        this.validator = new FIXValidator(); // Default limits
        this.circuitBreaker = new CircuitBreaker(); // Default: 100 failures in 10s
    }

    public void onMessage(FixParser parser) {
        // Check circuit breaker first
        if (!circuitBreaker.allowRequest()) {
            LOG.warn("Circuit breaker OPEN, rejecting message");
            disconnect();
            return;
        }

        final int msgSeqNum = parser.getIntValue(FixParser.TAG_MSG_SEQ_NUM, 0);
        final byte msgType = parser.getCharValue(FixParser.TAG_MSG_TYPE, (byte) '0');

        // Validate FIX message BEFORE processing
        ValidationResult validationResult = validator.validate(parser);
        if (!validationResult.isValid()) {
            LOG.warn("FIX validation failed: {}", validationResult);
            com.chronos.core.util.ChronosMetrics.onOrderRejected();

            // Record failure in circuit breaker
            boolean circuitTripped = circuitBreaker.recordFailure();

            // Send FIX Reject (35=3) to client
            sendReject(msgSeqNum, validationResult);

            // Disconnect if circuit tripped
            if (circuitTripped) {
                LOG.error("Circuit breaker tripped, disconnecting client");
                sendLogout("Too many validation failures");
                disconnect();
            }
            return;
        }

        // Record success for circuit breaker
        circuitBreaker.recordSuccess();

        if (!validateSequence(msgSeqNum, msgType)) {
            return;
        }

        if (msgType == MSG_TYPE_LOGON) {
            handleLogon(parser, msgSeqNum);
            return;
        }

        dispatchMessage(parser, msgType);
    }

    private boolean validateSequence(int msgSeqNum, byte msgType) {
        if (state == State.CONNECTED && msgType != MSG_TYPE_LOGON) {
            LOG.warn("First message must be Logon (A). Received: {}", (char) msgType);
            disconnect();
            return false;
        }

        if (msgType == MSG_TYPE_LOGON)
            return true;

        if (msgSeqNum < nextExpectedMsgSeqNum) {
            LOG.error("MsgSeqNum too low. Expected: {}, Received: {}", nextExpectedMsgSeqNum, msgSeqNum);
            disconnect();
            return false;
        }

        if (msgSeqNum > nextExpectedMsgSeqNum) {
            // Gap detected
            nextExpectedMsgSeqNum = msgSeqNum + 1;
        } else {
            nextExpectedMsgSeqNum++;
        }
        return true;
    }

    private void dispatchMessage(FixParser parser, byte msgType) {
        switch (msgType) {
            case MSG_TYPE_HEARTBEAT:
            case MSG_TYPE_TEST_REQUEST:
                handleTestRequest(parser);
                break;
            case MSG_TYPE_RESEND_REQUEST:
                handleResendRequest(parser);
                break;
            case MSG_TYPE_SEQUENCE_RESET:
                handleSequenceReset(parser);
                break;
            case MSG_TYPE_LOGOUT:
                LOG.info("Received Logout.");
                disconnect();
                break;
            case MSG_TYPE_NEW_ORDER_SINGLE:
                com.chronos.core.util.ChronosMetrics.onOrderProcessed();
                processNewOrderSingle(parser);
                break;
            case MSG_TYPE_ORDER_CANCEL_REQUEST:
                processOrderCancelRequest(parser);
                break;
            default:
                LOG.debug("Ignored message type: {}", (char) msgType);
        }
    }

    private void handleTestRequest(FixParser parser) {
        String testReqId = parser.getStringValueAsString(112); // TestReqID
        sendHeartbeat(testReqId);
    }

    private void handleResendRequest(FixParser parser) {
        // Tag 7 (BeginSeqNo) and 16 (EndSeqNo)
        int beginSeqNo = parser.getIntValue(7, 0);
        int endSeqNo = parser.getIntValue(16, 0);
        LOG.info("Received ResendRequest: {}-{}", beginSeqNo, endSeqNo);

        // MVP: We assume we don't have a message store yet to replay.
        // To remain compliant without a store, we send a Sequence Reset (Gap Fill)
        // covering the requested range.
        // In a real system, we would replay messages from the store.

        if (endSeqNo == 0)
            endSeqNo = nextSenderMsgSeqNum - 1; // 0 means infinity/current

        sendSequenceReset(endSeqNo + 1, true); // GapFill=Y
    }

    private void handleSequenceReset(FixParser parser) {
        // Tag 36 (NewSeqNo) and 123 (GapFillFlag)
        int newSeqNo = parser.getIntValue(36, 0);
        boolean gapFill = parser.getCharValue(123, (byte) 'N') == 'Y';

        if (newSeqNo > nextExpectedMsgSeqNum) {
            LOG.info("Sequence Reset (GapFill={}) to {}", gapFill, newSeqNo);
            nextExpectedMsgSeqNum = newSeqNo;
        } else if (newSeqNo < nextExpectedMsgSeqNum && !gapFill) {
            LOG.warn("Sequence Reset to lower sequence number ignored (GapFill=N).");
            // Valid logic might define this as an error or ignore.
        }
    }

    private void sendSequenceReset(int newSeqNo, boolean gapFill) {
        fixBuilder.wrap(sbeBuffer, 0);
        appendHeader((char) MSG_TYPE_SEQUENCE_RESET);

        fixBuilder.append(36, newSeqNo); // NewSeqNo
        if (gapFill) {
            fixBuilder.append(123, 'Y'); // GapFillFlag
        } else {
            fixBuilder.append(123, 'N');
        }

        sendBuffer();
    }

    private final FixMessageBuilder fixBuilder = new FixMessageBuilder();

    private void handleLogon(FixParser parser, int msgSeqNum) {
        LOG.info("Received Logon (MsgSeqNum={})", msgSeqNum);
        state = State.LOGGED_ON;
        nextExpectedMsgSeqNum = msgSeqNum + 1;
        sendLogonResponse();
    }

    private void sendLogonResponse() {
        fixBuilder.wrap(sbeBuffer, 0);
        appendHeader((char) MSG_TYPE_LOGON);
        fixBuilder.append(98, 0); // EncryptMethod (None)
        fixBuilder.append(108, 30); // HeartBtInt
        sendBuffer();
    }

    private void sendHeartbeat() {
        sendHeartbeat(null);
    }

    private void sendHeartbeat(String testReqId) {
        fixBuilder.wrap(sbeBuffer, 0);
        appendHeader((char) MSG_TYPE_HEARTBEAT);

        if (testReqId != null) {
            fixBuilder.append(112, testReqId);
        }

        sendBuffer();
    }

    public void sendExecutionReport(long orderId, long price, int quantity, char side, char orderStatus) {
        if (state != State.LOGGED_ON)
            return;

        fixBuilder.wrap(sbeBuffer, 0);
        appendHeader('8'); // MsgType=8 (ExecutionReport)

        fixBuilder.append(37, orderId); // OrderID
        fixBuilder.append(17, System.nanoTime()); // ExecID
        fixBuilder.append(150, orderStatus); // ExecType
        fixBuilder.append(39, orderStatus); // OrdStatus
        fixBuilder.append(55, "SYMBOL"); // Symbol (TODO)
        fixBuilder.append(54, side); // Side

        fixBuilder.append(38, quantity); // OrderQty

        if (orderStatus == '2') { // Filled
            fixBuilder.append(151, 0); // LeavesQty
            fixBuilder.append(14, quantity); // CumQty
            fixBuilder.appendFixedPoint(6, price, 8);
        } else {
            fixBuilder.append(151, quantity);
            fixBuilder.append(14, 0);
            fixBuilder.append(6, "0.00");
        }

        sendBuffer();
    }

    private void appendHeader(char msgType) {
        fixBuilder.append(35, msgType);
        fixBuilder.append(49, "CHRONOS");
        fixBuilder.append(56, "CLIENT");
        fixBuilder.append(34, nextSenderMsgSeqNum++);
        fixBuilder.append(52, getUtcTimestamp());
    }

    private void sendBuffer() {
        int length = fixBuilder.finish();
        try {
            java.nio.ByteBuffer duplicate = sbeBuffer.byteBuffer().duplicate();
            duplicate.position(0);
            duplicate.limit(length);
            while (duplicate.hasRemaining()) {
                channel.write(duplicate);
            }
        } catch (Exception e) {
            LOG.error("Failed to send message", e);
            disconnect();
        }
    }

    private String getUtcTimestamp() {
        java.time.format.DateTimeFormatter dtf = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS");
        return java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).format(dtf);
    }

    private void processNewOrderSingle(FixParser parser) {
        if (state != State.LOGGED_ON)
            return;

        // Route based on Symbol (Tag 55)
        String symbol = parser.getStringValueAsString(55);
        int shardId = router.getShardId(symbol);
        int instrumentId = router.getInstrumentId(symbol); // Correctly using instrumentId

        if (shardId < 0 || shardId >= publications.length) {
            LOG.error("Invalid shard ID {} for symbol {}", shardId, symbol);
            // Could send Reject here
            return;
        }

        Publication pub = publications[shardId];
        int length = FixToSbeEncoder.encodedSize();

        // ─── Zero-Copy Ingress ───
        long result = pub.tryClaim(length, bufferClaim);
        if (result > 0) {
            try {
                // Encode directly into the claimed buffer in the Aeron log
                encoder.encodeNewOrderSingle(parser, bufferClaim.buffer(), bufferClaim.offset(), instrumentId);
                bufferClaim.commit();
            } catch (Exception e) {
                bufferClaim.abort();
                LOG.error("Failed to encode NewOrderSingle", e);
            }
        } else {
            LOG.warn("Aeron tryClaim failed: {}", result);
            // In a real system, we'd handle backpressure here (e.g., block or
            // drop-and-alert)
        }
    }

    private void processOrderCancelRequest(FixParser parser) {
        if (state != State.LOGGED_ON)
            return;

        String symbol = parser.getStringValueAsString(55);
        int shardId = router.getShardId(symbol);
        int instrumentId = router.getInstrumentId(symbol);

        if (shardId < 0 || shardId >= publications.length) {
            LOG.error("Invalid shard ID {} for symbol {} in Cancel Request", shardId, symbol);
            return;
        }

        Publication pub = publications[shardId];

        // For CancelOrder, we stick to 'offer' for now as it's not the primary hot path
        // and sizing is slightly different.
        int encodedSize = encoder.encodeCancelOrder(parser, sbeBuffer, 0, instrumentId);
        long result = pub.offer(sbeBuffer, 0, encodedSize);
        if (result < 0) {
            LOG.warn("Aeron offer failed (Cancel): {}", result);
        }
    }

    /**
     * Send FIX Reject (35=3) message for validation failures.
     */
    private void sendReject(int refSeqNum, ValidationResult validationResult) {
        fixBuilder.wrap(sbeBuffer, 0);
        appendHeader((char) MSG_TYPE_REJECT);

        fixBuilder.append(45, refSeqNum); // RefSeqNum
        if (validationResult.getRefTagId() > 0) {
            fixBuilder.append(371, validationResult.getRefTagId()); // RefTagID
        }
        fixBuilder.append(372, 'D'); // RefMsgType (TODO: get actual msg type from parser if possible, or pass it in)
        fixBuilder.append(373, validationResult.getSessionRejectReason()); // SessionRejectReason

        String text = validationResult.getText();
        if (text != null) {
            fixBuilder.append(58, text); // Text
        }

        sendBuffer();
    }

    private void sendLogout(String reason) {
        fixBuilder.wrap(sbeBuffer, 0);
        appendHeader((char) MSG_TYPE_LOGOUT);

        if (reason != null) {
            fixBuilder.append(58, reason);
        }

        sendBuffer();
    }

    private void disconnect() {
        state = State.DISCONNECTED;
        try {
            channel.close();
        } catch (Exception e) {
            // ignore
        }
    }
}
