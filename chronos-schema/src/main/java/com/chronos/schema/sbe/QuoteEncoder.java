package com.chronos.schema.sbe;

import org.agrona.MutableDirectBuffer;

import java.nio.ByteOrder;

/**
 * Flyweight encoder for Quote messages — zero-allocation hot-path encoding.
 *
 * <p>Wire format (56 bytes, little-endian):
 * <pre>
 *   quoteID       : uint64 @ offset 0   (8 bytes)
 *   quoteReqID    : uint64 @ offset 8   (8 bytes)
 *   clientId      : uint64 @ offset 16  (8 bytes)
 *   instrumentId  : uint32 @ offset 24  (4 bytes)
 *   bidPrice      : int64  @ offset 28  (8 bytes) - PADDING: aligned to 8-byte boundary at offset 32
 *   bidSize       : uint32 @ offset 40  (4 bytes)
 *   askPrice      : int64  @ offset 44  (8 bytes) - PADDING: aligned to 8-byte boundary at offset 48
 *   askSize       : uint32 @ offset 56  (4 bytes)
 *   timestampNs   : int64  @ offset 60  (8 bytes) - PADDING: aligned to 8-byte boundary at offset 64
 * </pre>
 * 
 * Note: Actual layout with padding for alignment:
 * <pre>
 *   quoteID       : uint64 @ offset 0   (8 bytes)
 *   quoteReqID    : uint64 @ offset 8   (8 bytes)
 *   clientId      : uint64 @ offset 16  (8 bytes)
 *   instrumentId  : uint32 @ offset 24  (4 bytes)
 *   [padding]     : 4 bytes @ offset 28
 *   bidPrice      : int64  @ offset 32  (8 bytes)
 *   bidSize       : uint32 @ offset 40  (4 bytes)
 *   [padding]     : 4 bytes @ offset 44
 *   askPrice      : int64  @ offset 48  (8 bytes)
 *   askSize       : uint32 @ offset 56  (4 bytes)
 *   [padding]     : 4 bytes @ offset 60
 *   timestampNs   : int64  @ offset 64  (8 bytes)
 * </pre>
 */
public final class QuoteEncoder {

    public static final int TEMPLATE_ID = 5;
    public static final int BLOCK_LENGTH = 72; // 64 + 8 for aligned timestampNs
    public static final int SCHEMA_ID = 1;
    public static final int SCHEMA_VERSION = 1;

    private static final int QUOTE_ID_OFFSET = 0;
    private static final int QUOTE_REQ_ID_OFFSET = 8;
    private static final int CLIENT_ID_OFFSET = 16;
    private static final int INSTRUMENT_ID_OFFSET = 24;
    private static final int BID_PRICE_OFFSET = 32;
    private static final int BID_SIZE_OFFSET = 40;
    private static final int ASK_PRICE_OFFSET = 48;
    private static final int ASK_SIZE_OFFSET = 56;
    private static final int TIMESTAMP_NS_OFFSET = 64;

    private MutableDirectBuffer buffer;
    private int offset;

    public QuoteEncoder wrap(final MutableDirectBuffer buffer, final int offset) {
        this.buffer = buffer;
        this.offset = offset;
        return this;
    }

    public int encodedLength() {
        return BLOCK_LENGTH;
    }

    public QuoteEncoder quoteID(final long value) {
        buffer.putLong(offset + QUOTE_ID_OFFSET, value, ByteOrder.LITTLE_ENDIAN);
        return this;
    }

    public QuoteEncoder quoteReqID(final long value) {
        buffer.putLong(offset + QUOTE_REQ_ID_OFFSET, value, ByteOrder.LITTLE_ENDIAN);
        return this;
    }

    public QuoteEncoder clientId(final long value) {
        buffer.putLong(offset + CLIENT_ID_OFFSET, value, ByteOrder.LITTLE_ENDIAN);
        return this;
    }

    public QuoteEncoder instrumentId(final int value) {
        buffer.putInt(offset + INSTRUMENT_ID_OFFSET, value, ByteOrder.LITTLE_ENDIAN);
        return this;
    }

    public QuoteEncoder bidPrice(final long value) {
        buffer.putLong(offset + BID_PRICE_OFFSET, value, ByteOrder.LITTLE_ENDIAN);
        return this;
    }

    public QuoteEncoder bidSize(final int value) {
        buffer.putInt(offset + BID_SIZE_OFFSET, value, ByteOrder.LITTLE_ENDIAN);
        return this;
    }

    public QuoteEncoder askPrice(final long value) {
        buffer.putLong(offset + ASK_PRICE_OFFSET, value, ByteOrder.LITTLE_ENDIAN);
        return this;
    }

    public QuoteEncoder askSize(final int value) {
        buffer.putInt(offset + ASK_SIZE_OFFSET, value, ByteOrder.LITTLE_ENDIAN);
        return this;
    }

    public QuoteEncoder timestampNs(final long value) {
        buffer.putLong(offset + TIMESTAMP_NS_OFFSET, value, ByteOrder.LITTLE_ENDIAN);
        return this;
    }
}
