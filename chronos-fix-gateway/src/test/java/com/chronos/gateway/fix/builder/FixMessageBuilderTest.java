package com.chronos.gateway.fix.builder;

import com.chronos.gateway.fix.FixParser;
import com.chronos.gateway.fix.FixMessageBuilder;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link FixMessageBuilder}.
 */
class FixMessageBuilderTest {
    
    private FixMessageBuilder builder;
    private UnsafeBuffer buffer;
    private FixParser parser;
    
    @BeforeEach
    void setUp() {
        builder = new FixMessageBuilder();
        buffer = new UnsafeBuffer(new byte[2048]);
        parser = new FixParser();
    }
    
    @Test
    void testBuildLogon() {
        final int length = builder.buildLogon(
                buffer, 0, "CLIENT1", "SERVER1", 1, 30);
        
        assertTrue(length > 0, "Message should have non-zero length");
        
        // Parse the built message
        final int fieldCount = parser.parse(buffer, 0, length);
        assertTrue(fieldCount > 0, "Should parse successfully");
        
        // Verify key fields
        assertEquals('A', parser.getCharValue(FixParser.TAG_MSG_TYPE, (byte) '?'), 
                "MsgType should be 'A' (Logon)");
        assertEquals(1, parser.getIntValue(FixParser.TAG_MSG_SEQ_NUM, -1), 
                "MsgSeqNum should be 1");
        assertEquals(30, parser.getIntValue(FixParser.TAG_HEARTBT_INT, -1), 
                "HeartBtInt should be 30");
        assertEquals(0, parser.getIntValue(FixParser.TAG_ENCRYPT_METHOD, -1), 
                "EncryptMethod should be 0 (None)");
        
        // Verify CompIDs
        assertEquals("CLIENT1", parser.getStringValueAsString(FixParser.TAG_SENDER_COMP_ID));
        assertEquals("SERVER1", parser.getStringValueAsString(FixParser.TAG_TARGET_COMP_ID));
        
        // Verify checksum is present
        assertTrue(parser.getIntValue(FixParser.TAG_CHECKSUM, -1) >= 0, 
                "Checksum should be present");
    }
    
    @Test
    void testBuildLogout() {
        final int length = builder.buildLogout(
                buffer, 0, "CLIENT1", "SERVER1", 2, "Session ended");
        
        assertTrue(length > 0);
        
        final int fieldCount = parser.parse(buffer, 0, length);
        assertTrue(fieldCount > 0);
        
        assertEquals('5', parser.getCharValue(FixParser.TAG_MSG_TYPE, (byte) '?'), 
                "MsgType should be '5' (Logout)");
        assertEquals(2, parser.getIntValue(FixParser.TAG_MSG_SEQ_NUM, -1));
        assertEquals("Session ended", parser.getStringValueAsString(FixParser.TAG_TEXT));
    }
    
    @Test
    void testBuildHeartbeat() {
        final int length = builder.buildHeartbeat(
                buffer, 0, "CLIENT1", "SERVER1", 3, null);
        
        assertTrue(length > 0);
        
        final int fieldCount = parser.parse(buffer, 0, length);
        assertTrue(fieldCount > 0);
        
        assertEquals('0', parser.getCharValue(FixParser.TAG_MSG_TYPE, (byte) '?'), 
                "MsgType should be '0' (Heartbeat)");
        assertEquals(3, parser.getIntValue(FixParser.TAG_MSG_SEQ_NUM, -1));
    }
    
    @Test
    void testBuildHeartbeatWithTestReqID() {
        final int length = builder.buildHeartbeat(
                buffer, 0, "CLIENT1", "SERVER1", 4, "TEST123");
        
        assertTrue(length > 0);
        
        final int fieldCount = parser.parse(buffer, 0, length);
        assertTrue(fieldCount > 0);
        
        assertEquals('0', parser.getCharValue(FixParser.TAG_MSG_TYPE, (byte) '?'));
        assertEquals("TEST123", parser.getStringValueAsString(FixParser.TAG_TEST_REQ_ID));
    }
    
    @Test
    void testBuildTestRequest() {
        final int length = builder.buildTestRequest(
                buffer, 0, "CLIENT1", "SERVER1", 5, "REQ456");
        
        assertTrue(length > 0);
        
        final int fieldCount = parser.parse(buffer, 0, length);
        assertTrue(fieldCount > 0);
        
        assertEquals('1', parser.getCharValue(FixParser.TAG_MSG_TYPE, (byte) '?'), 
                "MsgType should be '1' (TestRequest)");
        assertEquals("REQ456", parser.getStringValueAsString(FixParser.TAG_TEST_REQ_ID));
    }
    
    @Test
    void testMessageReuse() {
        // Build first message
        final int length1 = builder.buildLogon(
                buffer, 0, "CLIENT1", "SERVER1", 1, 30);
        
        // Build second message (reuse builder)
        final int length2 = builder.buildLogout(
                buffer, 0, "CLIENT1", "SERVER1", 2, null);
        
        assertTrue(length1 > 0);
        assertTrue(length2 > 0);
        
        // Verify second message is correct
        final int fieldCount = parser.parse(buffer, 0, length2);
        assertEquals('5', parser.getCharValue(FixParser.TAG_MSG_TYPE, (byte) '?'));
    }
    
    @Test
    void testSequenceNumberIncrement() {
        // Build messages with incrementing sequence numbers
        for (int i = 1; i <= 10; i++) {
            final int length = builder.buildHeartbeat(
                    buffer, 0, "CLIENT1", "SERVER1", i, null);
            
            parser.parse(buffer, 0, length);
            assertEquals(i, parser.getIntValue(FixParser.TAG_MSG_SEQ_NUM, -1));
        }
    }
}
