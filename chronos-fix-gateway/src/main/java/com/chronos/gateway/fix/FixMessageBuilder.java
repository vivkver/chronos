package com.chronos.gateway.fix;

import org.agrona.MutableDirectBuffer;

import java.nio.charset.StandardCharsets;

/**
 * Zero-allocation builder for outbound FIX messages using a Fixed-Width Header strategy.
 */
public class FixMessageBuilder {

    private static final byte[] FIX_BEGIN_STRING = "8=FIX.4.4\0019=".getBytes(StandardCharsets.US_ASCII);
    
    // Header Length: "8=FIX.4.4|9=" (12 bytes) + 5 digits (5 bytes) + SOH (1 byte) = 18 bytes
    // We pad Length to 5 digits (e.g. 00150).
    public static final int HEADER_LENGTH = 18;

    private MutableDirectBuffer buffer;
    private int startOffset;
    private int cursor;
    private int checksum;

    public FixMessageBuilder wrap(MutableDirectBuffer buffer, int offset) {
        this.buffer = buffer;
        this.startOffset = offset;
        // Start writing body AFTER the header space
        this.cursor = offset + HEADER_LENGTH;
        this.checksum = 0;
        return this;
    }

    public FixMessageBuilder append(int tag, long value) {
        appendTag(tag);
        appendLong(value);
        appendSoh();
        return this;
    }
    
    public FixMessageBuilder append(int tag, int value) {
        return append(tag, (long) value);
    }

    public FixMessageBuilder append(int tag, String value) {
        appendTag(tag);
        for (int i = 0; i < value.length(); i++) {
            byte b = (byte) value.charAt(i);
            buffer.putByte(cursor++, b);
            checksum += (b & 0xFF);
        }
        appendSoh();
        return this;
    }
    
    public FixMessageBuilder append(int tag, char value) {
        appendTag(tag);
        buffer.putByte(cursor++, (byte) value);
        checksum += (value & 0xFF);
        appendSoh();
        return this;
    }
    
    // Append raw byte value to body
    public FixMessageBuilder append(int tag, byte value) {
        appendTag(tag);
        buffer.putByte(cursor++, value);
        checksum += (value & 0xFF);
        appendSoh();
        return this;
    }

    /**
     * Finalizes the message by writing the Header (BodyLength) and Trailer (Checksum).
     * @return Total length of the encoded message
     */
    public int finish() {
        // 1. Calculate Body Length
        final int bodyLength = cursor - (startOffset + HEADER_LENGTH);

        // 2. Write Header at startOffset
        // "8=FIX.4.4|9="
        int hCursor = startOffset;
        for (byte b : FIX_BEGIN_STRING) {
            buffer.putByte(hCursor++, b);
            checksum += (b & 0xFF); // Add to checksum
        }
        
        // Write 5-digit Body Length (padded with '0')
        int tempValues = bodyLength;
        for (int i = 4; i >= 0; i--) {
            int digit = tempValues % 10;
            byte b = (byte) ('0' + digit);
            // Write digits from right to left in the 5-byte space
            // 9=XXXXX|
            //   ^ offset + 12
            //   offset + 12 + 4 = last digit
            buffer.putByte(startOffset + 12 + i, b);
            checksum += b;
            tempValues /= 10;
        }
        
        // Write SOH after length
        buffer.putByte(startOffset + 17, (byte) 1);
        checksum += 1;
        
        // 3. Append Checksum "10=XXX|"
        // Note: Tag 10 itself is NOT included in checksum calculation.
        // We calculate checksum up to this point.
        final int finalChecksum = checksum % 256;
        
        buffer.putByte(cursor++, (byte) '1');
        buffer.putByte(cursor++, (byte) '0');
        buffer.putByte(cursor++, (byte) '=');
        
        // Write 3 digits for checksum
        int d1 = (finalChecksum / 100) % 10;
        int d2 = (finalChecksum / 10) % 10;
        int d3 = finalChecksum % 10;
        
        buffer.putByte(cursor++, (byte) ('0' + d1));
        buffer.putByte(cursor++, (byte) ('0' + d2));
        buffer.putByte(cursor++, (byte) ('0' + d3));
        
        buffer.putByte(cursor++, (byte) 1); // SOH
        
        // Return total length
        return cursor - startOffset;
    }

    public FixMessageBuilder appendFixedPoint(int tag, long value, int scale) {
        appendTag(tag);
        if (value < 0) {
            buffer.putByte(cursor++, (byte) '-');
            checksum += '-';
            value = -value;
        }
        
        long divisor = 1;
        for (int i = 0; i < scale; i++) divisor *= 10;
        
        long integerPart = value / divisor;
        long fractionalPart = value % divisor;
        
        appendLongNoTag(integerPart);
        buffer.putByte(cursor++, (byte) '.');
        checksum += '.';
        
        appendFractional(fractionalPart, scale);
        
        appendSoh();
        return this;
    }

    private void appendLongNoTag(long value) {
        if (value == 0) {
            buffer.putByte(cursor++, (byte) '0');
            checksum += '0';
            return;
        }
        
        long temp = value;
        int digits = 0;
        while (temp > 0) {
            temp /= 10;
            digits++;
        }
        
        int index = cursor + digits - 1;
        cursor += digits;
        
        temp = value;
        do {
            byte b = (byte) ('0' + (temp % 10));
            buffer.putByte(index--, b);
            checksum += b;
            temp /= 10;
        } while (temp > 0);
    }
    
    private void appendFractional(long value, int scale) {
        int index = cursor + scale - 1;
        cursor += scale;
        
        long temp = value;
        for (int i = 0; i < scale; i++) {
            byte b = (byte) ('0' + (temp % 10));
            buffer.putByte(index--, b);
            checksum += b;
            temp /= 10;
        }
    }

    private void appendTag(int tag) {
        if (tag < 10) {
            byte b = (byte) ('0' + tag);
            buffer.putByte(cursor++, b);
            checksum += b;
        } else if (tag < 100) {
            byte b1 = (byte) ('0' + (tag / 10));
            byte b2 = (byte) ('0' + (tag % 10));
            buffer.putByte(cursor++, b1); checksum += b1;
            buffer.putByte(cursor++, b2); checksum += b2;
        } else if (tag < 1000) {
            byte b1 = (byte) ('0' + (tag / 100));
            byte b2 = (byte) ('0' + ((tag / 10) % 10));
            byte b3 = (byte) ('0' + (tag % 10));
            buffer.putByte(cursor++, b1); checksum += b1;
            buffer.putByte(cursor++, b2); checksum += b2;
            buffer.putByte(cursor++, b3); checksum += b3;
        } else {
             // Fallback: Use manual print to avoid Integer.toString allocation
             int digits = 0;
             int temp = tag;
             while(temp > 0) { temp/=10; digits++; }
             
             int index = cursor + digits - 1;
             cursor += digits;
             temp = tag;
             while(temp > 0) {
                 byte b = (byte) ('0' + (temp % 10));
                 buffer.putByte(index--, b);
                 checksum += b;
                 temp /= 10;
             }
        }
        
        buffer.putByte(cursor++, (byte) '=');
        checksum += '=';
    }

    private void appendLong(long value) {
        if (value < 0) {
            buffer.putByte(cursor++, (byte) '-');
            checksum += '-';
            value = -value;
        }
        
        // Find number of digits
        long temp = value;
        int digits = 0;
        if (temp == 0) {
            digits = 1;
        } else {
            // Unroll loop for common sizes?
            while (temp > 0) {
                temp /= 10;
                digits++;
            }
        }
        
        int index = cursor + digits - 1;
        cursor += digits;
        
        // Write backwards
        temp = value;
        do {
            byte b = (byte) ('0' + (temp % 10));
            buffer.putByte(index--, b);
            checksum += b;
            temp /= 10;
        } while (temp > 0);
    }
    
    private void appendSoh() {
        buffer.putByte(cursor++, (byte) 1);
        checksum += 1;
    }
    
    // ── Convenience Methods ──

    public int buildLogon(MutableDirectBuffer buffer, int offset, String senderCompId, String targetCompId, int msgSeqNum, int heartBtInt) {
        wrap(buffer, offset);
        append(35, "A");
        append(49, senderCompId);
        append(56, targetCompId);
        append(34, msgSeqNum);
        append(52, "20260101-00:00:00.000"); // Dummy for test
        append(98, 0);
        append(108, heartBtInt);
        return finish();
    }

    public int buildLogout(MutableDirectBuffer buffer, int offset, String senderCompId, String targetCompId, int msgSeqNum, String text) {
        wrap(buffer, offset);
        append(35, "5");
        append(49, senderCompId);
        append(56, targetCompId);
        append(34, msgSeqNum);
        append(52, "20260101-00:00:00.000");
        if (text != null) {
            append(58, text);
        }
        return finish();
    }

    public int buildHeartbeat(MutableDirectBuffer buffer, int offset, String senderCompId, String targetCompId, int msgSeqNum, String testReqId) {
        wrap(buffer, offset);
        append(35, "0");
        append(49, senderCompId);
        append(56, targetCompId);
        append(34, msgSeqNum);
        append(52, "20260101-00:00:00.000");
        if (testReqId != null) {
            append(112, testReqId);
        }
        return finish();
    }

    public int buildTestRequest(MutableDirectBuffer buffer, int offset, String senderCompId, String targetCompId, int msgSeqNum, String testReqId) {
        wrap(buffer, offset);
        append(35, "1");
        append(49, senderCompId);
        append(56, targetCompId);
        append(34, msgSeqNum);
        append(52, "20260101-00:00:00.000");
        append(112, testReqId);
        return finish();
    }
}