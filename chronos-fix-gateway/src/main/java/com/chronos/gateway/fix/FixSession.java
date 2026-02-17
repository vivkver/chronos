package com.chronos.gateway.fix;

import io.aeron.Publication;
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
    private final Publication publication;
    private final UnsafeBuffer sbeBuffer;

    private State state = State.CONNECTED;
    private int nextExpectedMsgSeqNum = 1;
    private int nextSenderMsgSeqNum = 1;

    // CompIDs for validation
    private String senderCompId;
    private String targetCompId;

    public FixSession(SocketChannel channel, FixToSbeEncoder encoder, Publication publication, UnsafeBuffer sbeBuffer) {
        this.channel = channel;
        this.encoder = encoder;
        this.publication = publication;
        this.sbeBuffer = sbeBuffer;
    }

    public void onMessage(FixParser parser) {
        final int msgSeqNum = parser.getIntValue(FixParser.TAG_MSG_SEQ_NUM, 0);
        final byte msgType = parser.getCharValue(FixParser.TAG_MSG_TYPE, (byte) '0');
        
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
        
        if (msgType == MSG_TYPE_LOGON) return true;

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
        
        if (endSeqNo == 0) endSeqNo = nextSenderMsgSeqNum - 1; // 0 means infinity/current
        
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
             // FIX 4.4: "If NewSeqNo is less than expected, ignore"
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

    // Reusable temp buffers for header strings (sender/target)
    // For MVP we just use String in append(int, String), but builder optimizes it slightly.
    
    private void handleLogon(FixParser parser, int msgSeqNum) {
        LOG.info("Received Logon (MsgSeqNum={})", msgSeqNum);
        state = State.LOGGED_ON;
        nextExpectedMsgSeqNum = msgSeqNum + 1;
        sendLogonResponse();
    }

    private void sendLogonResponse() {
        // Prepare buffer wrapper
        fixBuilder.wrap(sbeBuffer, 0);
        
        // Header functionality is manual in builder? No, finish() does it.
        // But we must write standard header tags (35, 49, 56, 34, 52) yourself?
        // Yes, 'finish' only writes 8=...|9=...|10=...
        
        appendHeader((char) MSG_TYPE_LOGON);
        fixBuilder.append(98, 0);  // EncryptMethod (None)
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
        if (state != State.LOGGED_ON) return;

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
            // Price is fixed point?
            // fixBuilder.append(6, price, 8); // Assuming price has scale 8?
            // Current code assumed price / 100_000_000.0. Scale 8.
            // But builder has append(tag, long value) or append(tag, String).
            // Let's us String format for safe MVP or improve builder later.
            // Using appendFixedPoint for zero allocation
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
        fixBuilder.append(56, "CLIENT"); // Refactor to use this.targetCompId if set?
        fixBuilder.append(34, nextSenderMsgSeqNum++);
        // Timestamp - use simpler one for now
        fixBuilder.append(52, getUtcTimestamp());
    }

    private void sendBuffer() {
        int length = fixBuilder.finish();
        // Send sbeBuffer from 0 to length
        try {
            // UnsafeBuffer to ByteBuffer adapter?
            // channel.write expects ByteBuffer.
            // FIX: sbeBuffer wraps a ByteBuffer. We can get it if we passed it in?
            // FixSession constructor took UnsafeBuffer.
            // If UnsafeBuffer.byteBuffer() is not null, use it.
            java.nio.ByteBuffer duplicate = sbeBuffer.byteBuffer().duplicate();
            duplicate.position(0);
            duplicate.limit(length);
            while(duplicate.hasRemaining()) {
                 channel.write(duplicate);
            }
        } catch (Exception e) {
            LOG.error("Failed to send message", e);
            disconnect();
        }
    }

    // Helper kept for MVP timestamp generation
    private String getUtcTimestamp() {
        java.time.format.DateTimeFormatter dtf = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS");
        return java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).format(dtf);
    }
    
    // Checksum removed (handled by builder)
    private void processNewOrderSingle(FixParser parser) {
        if (state != State.LOGGED_ON) return;
        
        int encodedSize = encoder.encodeNewOrderSingle(parser, sbeBuffer, 0, 1);
        long result = publication.offer(sbeBuffer, 0, encodedSize);
        if (result < 0) {
            LOG.warn("Aeron offer failed: {}", result);
        }
    }

    private void processOrderCancelRequest(FixParser parser) {
        if (state != State.LOGGED_ON) return;

        int encodedSize = encoder.encodeCancelOrder(parser, sbeBuffer, 0, 1);
        long result = publication.offer(sbeBuffer, 0, encodedSize);
        if (result < 0) {
            LOG.warn("Aeron offer failed (Cancel): {}", result);
        }
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
