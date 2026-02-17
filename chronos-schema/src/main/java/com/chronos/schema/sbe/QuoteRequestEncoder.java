package com.chronos.schema.sbe;

import org.agrona.MutableDirectBuffer;

import java.nio.ByteOrder;

/**
 * Flyweight encoder for QuoteRequest messages — zero-allocation hot-path encoding.
 *
 * <p>Wire format (30 bytes, little-endian):
 * <pre>
 *   quoteReqID    : uint64 @ offset 0   (8 bytes)
 *   clientId      : uint64 @ offset 8   (8 bytes)
 *   instrumentId  : uint32 @ offset 16  (4 bytes)
 *   quantity      : uint32 @ offset 20  (4 bytes)
 *   side          : uint8  @ offset 24  (0=BUY, 1=SELL)
 *   timestampNs   : int64  @ offset 25  (8 bytes) - PADDING: aligned to 8-byte boundary at offset 32
 * </pre>
 * 
 * Note: Actual layout with padding for alignment:
 * <pre>
 *   quoteReqID    : uint64 @ offset 0   (8 bytes)
 *   clientId      : uint64 @ offset 8   (8 bytes)
 *   instrumentId  : uint32 @ offset 16  (4 bytes)
 *   quantity      : uint32 @ offset 20  (4 bytes)
 *   side          : uint8  @ offset 24  (1 byte)
 *   [padding]     : 7 bytes @ offset 25
 *   timestampNs   : int64  @ offset 32  (8 bytes)
 * </pre>
 */
public final class QuoteRequestEncoder {

    public static final int TEMPLATE_ID = 4;
    public static final int BLOCK_LENGTH = 40; // 32 + 8 for aligned timestampNs
    public static final int SCHEMA_ID = 1;
    public static final int SCHEMA_VERSION = 1;

    private static final int QUOTE_REQ_ID_OFFSET = 0;
    private static final int CLIENT_ID_OFFSET = 8;
    private static final int INSTRUMENT_ID_OFFSET = 16;
    private static final int QUANTITY_OFFSET = 20;
    private static final int SIDE_OFFSET = 24;
    private static final int TIMESTAMP_NS_OFFSET = 32; // Aligned to 8-byte boundary

    private MutableDirectBuffer buffer;
    private int offset;

    public QuoteRequestEncoder wrap(final MutableDirectBuffer buffer, final int offset) {
        this.buffer = buffer;
        this.offset = offset;
        return this;
    }

    public int encodedLength() {
        return BLOCK_LENGTH;
    }

    public QuoteRequestEncoder quoteReqID(final long value) {
        buffer.putLong(offset + QUOTE_REQ_ID_OFFSET, value, ByteOrder.LITTLE_ENDIAN);
        return this;
    }

    public QuoteRequestEncoder clientId(final long value) {
        buffer.putLong(offset + CLIENT_ID_OFFSET, value, ByteOrder.LITTLE_ENDIAN);
        return this;
    }

    public QuoteRequestEncoder instrumentId(final int value) {
        buffer.putInt(offset + INSTRUMENT_ID_OFFSET, value, ByteOrder.LITTLE_ENDIAN);
        return this;
    }

    public QuoteRequestEncoder quantity(final int value) {
        buffer.putInt(offset + QUANTITY_OFFSET, value, ByteOrder.LITTLE_ENDIAN);
        return this;
    }

    public QuoteRequestEncoder side(final byte value) {
        buffer.putByte(offset + SIDE_OFFSET, value);
        return this;
    }

    public QuoteRequestEncoder timestampNs(final long value) {
        buffer.putLong(offset + TIMESTAMP_NS_OFFSET, value, ByteOrder.LITTLE_ENDIAN);
        return this;
    }
}
