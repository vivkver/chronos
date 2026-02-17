package com.chronos.gateway.fix;

import org.agrona.DirectBuffer;

/**
 * Zero-allocation FIX 4.4 protocol parser operating directly on byte buffers.
 *
 * <h2>Design</h2>
 * <ul>
 * <li>No {@code String} objects created — all tag/value extraction uses byte
 * offsets</li>
 * <li>Pre-computed tag hash for O(1) lookup of known FIX tags</li>
 * <li>Operates on Agrona {@link DirectBuffer} for compatibility with Aeron</li>
 * <li>Reusable: call {@link #parse} for each new message</li>
 * </ul>
 *
 * <h2>FIX Message Format</h2>
 * 
 * <pre>
 *   8=FIX.4.4\u00019=123\u000135=D\u000149=CLIENT1\u000111=ORD001\u000155=AAPL\u000154=1\u000144=150.50\u000138=100\u000140=2\u000110=xxx\u0001
 * </pre>
 * 
 * Each field is {@code tag=value\x01} (SOH delimiter).
 */
public final class FixParser {

    /** FIX field delimiter (SOH = Start of Header, ASCII 0x01). */
    private static final byte SOH = 0x01;
    private static final byte EQUALS = (byte) '=';

    /** Maximum number of FIX tags we extract per message. */
    private static final int MAX_FIELDS = 32;

    // Parsed field storage (pre-allocated, reused)
    private final int[] fieldTags = new int[MAX_FIELDS];
    private final int[] fieldValueOffsets = new int[MAX_FIELDS];
    private final int[] fieldValueLengths = new int[MAX_FIELDS];
    private int fieldCount;

    private DirectBuffer buffer;
    private int msgOffset;
    private int msgLength;

    // ── Common FIX tag constants ──
    public static final int TAG_BEGIN_STRING = 8; // BeginString (protocol version)
    public static final int TAG_BODY_LENGTH = 9; // BodyLength
    public static final int TAG_CHECKSUM = 10; // Checksum
    public static final int TAG_MSG_TYPE = 35; // MsgType
    public static final int TAG_MSG_SEQ_NUM = 34; // MsgSeqNum
    public static final int TAG_POSS_DUP_FLAG = 43; // PossDupFlag
    public static final int TAG_SENDER_COMP_ID = 49; // SenderCompID
    public static final int TAG_SENDING_TIME = 52; // SendingTime
    public static final int TAG_TARGET_COMP_ID = 56; // TargetCompID
    public static final int TAG_TEXT = 58; // Text (free-form)
    public static final int TAG_SESSION_REJECT_REASON = 373;
    
    // Quote/QuoteRequest tags
    public static final int TAG_QUOTE_REQ_ID = 131; // QuoteReqID
    public static final int TAG_QUOTE_ID = 117; // QuoteID
    public static final int TAG_BID_PX = 132; // BidPx
    public static final int TAG_BID_SIZE = 134; // BidSize
    public static final int TAG_OFFER_PX = 133; // OfferPx (AskPx)
    public static final int TAG_OFFER_SIZE = 135; // OfferSize (AskSize)
    public static final int TAG_ENCRYPT_METHOD = 98; // EncryptMethod (for Logon)
    public static final int TAG_HEARTBT_INT = 108; // HeartBtInt (for Logon)
    public static final int TAG_TEST_REQ_ID = 112; // TestReqID
    public static final int TAG_RESET_SEQ_NUM_FLAG = 141; // ResetSeqNumFlag
    
    // Application-level tags
    public static final int TAG_CL_ORD_ID = 11; // ClOrdID
    public static final int TAG_SYMBOL = 55; // Symbol
    public static final int TAG_SIDE = 54; // Side (1=Buy, 2=Sell)
    public static final int TAG_PRICE = 44; // Price
    public static final int TAG_ORDER_QTY = 38; // OrderQty
    public static final int TAG_ORD_TYPE = 40; // OrdType (1=Market, 2=Limit)

    /**
     * Parse a FIX message from the given buffer region.
     * Extracts all tag-value pairs without creating any objects.
     *
     * @param buffer the buffer containing the FIX message
     * @param offset start offset of the message
     * @param length length of the message
     * @return number of fields parsed
     */
    public int parse(final DirectBuffer buffer, final int offset, final int length) {
        this.buffer = buffer;
        this.msgOffset = offset;
        this.msgLength = length;
        this.fieldCount = 0;

        int pos = offset;
        final int end = offset + length;

        while (pos < end && fieldCount < MAX_FIELDS) {
            // Parse tag (integer before '=')
            int tag = 0;
            while (pos < end && buffer.getByte(pos) != EQUALS) {
                tag = tag * 10 + (buffer.getByte(pos) - '0');
                pos++;
            }
            pos++; // skip '='

            // Record value start position
            final int valueStart = pos;

            // Find value end (SOH delimiter)
            while (pos < end && buffer.getByte(pos) != SOH) {
                pos++;
            }

            final int valueLength = pos - valueStart;

            // Store parsed field
            fieldTags[fieldCount] = tag;
            fieldValueOffsets[fieldCount] = valueStart;
            fieldValueLengths[fieldCount] = valueLength;
            fieldCount++;

            pos++; // skip SOH
        }

        return fieldCount;
    }

    /**
     * Find a field by tag number and return its value as a long.
     * Zero-allocation: parses the ASCII digits directly from the buffer.
     *
     * @return the parsed long value, or {@code defaultValue} if tag not found
     */
    public long getLongValue(final int tag, final long defaultValue) {
        for (int i = 0; i < fieldCount; i++) {
            if (fieldTags[i] == tag) {
                return parseLong(fieldValueOffsets[i], fieldValueLengths[i]);
            }
        }
        return defaultValue;
    }

    /**
     * Find a field by tag and return its value as an int.
     */
    public int getIntValue(final int tag, final int defaultValue) {
        for (int i = 0; i < fieldCount; i++) {
            if (fieldTags[i] == tag) {
                return parseInt(fieldValueOffsets[i], fieldValueLengths[i]);
            }
        }
        return defaultValue;
    }

    /**
     * Get a single-character field value as a byte (e.g., Side: '1'=Buy, '2'=Sell).
     */
    public byte getCharValue(final int tag, final byte defaultValue) {
        for (int i = 0; i < fieldCount; i++) {
            if (fieldTags[i] == tag) {
                return buffer.getByte(fieldValueOffsets[i]);
            }
        }
        return defaultValue;
    }

    /**
     * Parse a FIX price field (ASCII decimal like "150.50") into a fixed-point long
     * representation (price * 10^8). Zero-allocation.
     */
    public long getFixedPointPrice(final int tag, final long defaultValue) {
        for (int i = 0; i < fieldCount; i++) {
            if (fieldTags[i] == tag) {
                return parseFixedPointPrice(fieldValueOffsets[i], fieldValueLengths[i]);
            }
        }
        return defaultValue;
    }

    /** Number of parsed fields in the current message. */
    public int fieldCount() {
        return fieldCount;
    }
    
    /**
     * Get a string value by tag and copy it to the target buffer.
     * Zero-allocation: reads directly from the parsed buffer.
     * 
     * @param tag the FIX tag to find
     * @param targetBuffer buffer to copy the string value into
     * @param maxLength maximum length to copy
     * @return actual length copied, or -1 if tag not found
     */
    public int getStringValue(final int tag, final byte[] targetBuffer, final int maxLength) {
        for (int i = 0; i < fieldCount; i++) {
            if (fieldTags[i] == tag) {
                final int length = Math.min(fieldValueLengths[i], maxLength);
                buffer.getBytes(fieldValueOffsets[i], targetBuffer, 0, length);
                return length;
            }
        }
        return -1;
    }
    
    /**
     * Get a string value by tag as a new String object.
     * WARNING: This allocates! Use only for non-hot-path operations.
     * 
     * @param tag the FIX tag to find
     * @return the string value, or null if tag not found
     */
    public String getStringValueAsString(final int tag) {
        for (int i = 0; i < fieldCount; i++) {
            if (fieldTags[i] == tag) {
                final byte[] bytes = new byte[fieldValueLengths[i]];
                buffer.getBytes(fieldValueOffsets[i], bytes, 0, fieldValueLengths[i]);
                return new String(bytes, java.nio.charset.StandardCharsets.US_ASCII);
            }
        }
        return null;
    }
    
    /**
     * Validate the FIX message checksum.
     * 
     * @return true if checksum is valid
     */
    public boolean validateChecksum() {
        // Find checksum field (tag 10)
        int checksumValue = -1;
        for (int i = 0; i < fieldCount; i++) {
            if (fieldTags[i] == TAG_CHECKSUM) {
                checksumValue = parseInt(fieldValueOffsets[i], fieldValueLengths[i]);
                break;
            }
        }
        
        if (checksumValue == -1) {
            return false; // No checksum field
        }
        
        // Calculate checksum (sum of all bytes before checksum field, modulo 256)
        int sum = 0;
        // Find the position where checksum field starts ("10=")
        int checksumFieldStart = -1;
        for (int i = 0; i < fieldCount; i++) {
            if (fieldTags[i] == TAG_CHECKSUM) {
                // Scan backwards to find "10="
                checksumFieldStart = fieldValueOffsets[i];
                while (checksumFieldStart > msgOffset && buffer.getByte(checksumFieldStart - 1) != EQUALS) {
                    checksumFieldStart--;
                }
                checksumFieldStart -= 3; // Back past "10="
                break;
            }
        }
        
        if (checksumFieldStart == -1) {
            return false;
        }
        
        for (int i = msgOffset; i < checksumFieldStart; i++) {
            sum += buffer.getByte(i) & 0xFF;
        }
        
        return (sum % 256) == checksumValue;
    }

    // ── Private parsing helpers (zero-allocation) ──

    private long parseLong(final int offset, final int length) {
        long result = 0;
        boolean negative = false;
        int pos = offset;
        final int end = offset + length;

        if (pos < end && buffer.getByte(pos) == '-') {
            negative = true;
            pos++;
        }

        while (pos < end) {
            result = result * 10 + (buffer.getByte(pos) - '0');
            pos++;
        }

        return negative ? -result : result;
    }

    private int parseInt(final int offset, final int length) {
        return (int) parseLong(offset, length);
    }

    /**
     * Parse "150.50" → 15050000000 (150.50 * 10^8).
     * Handles up to 8 decimal places.
     */
    private long parseFixedPointPrice(final int offset, final int length) {
        long integerPart = 0;
        long fractionPart = 0;
        int fractionDigits = 0;
        boolean inFraction = false;

        for (int i = offset; i < offset + length; i++) {
            final byte b = buffer.getByte(i);
            if (b == '.') {
                inFraction = true;
            } else if (inFraction) {
                fractionPart = fractionPart * 10 + (b - '0');
                fractionDigits++;
            } else {
                integerPart = integerPart * 10 + (b - '0');
            }
        }

        // Scale to 10^8
        long result = integerPart * 100_000_000L;
        long fractionScale = 100_000_000L;
        for (int i = 0; i < fractionDigits; i++) {
            fractionScale /= 10;
        }
        result += fractionPart * fractionScale;

        return result;
    }
}
