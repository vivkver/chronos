package com.chronos.schema.sbe;

import org.agrona.MutableDirectBuffer;

import java.nio.ByteOrder;

/**
 * Flyweight encoder for CancelOrder messages (20 bytes).
 */
public final class CancelOrderEncoder {

    public static final int TEMPLATE_ID = 2;
    public static final int BLOCK_LENGTH = 20;
    public static final int SCHEMA_ID = 1;
    public static final int SCHEMA_VERSION = 1;

    private static final int ORDER_ID_OFFSET = 0;
    private static final int CLIENT_ID_OFFSET = 8;
    private static final int INSTRUMENT_ID_OFFSET = 16;

    private MutableDirectBuffer buffer;
    private int offset;

    public CancelOrderEncoder wrap(final MutableDirectBuffer buffer, final int offset) {
        this.buffer = buffer;
        this.offset = offset;
        return this;
    }

    public int encodedLength() {
        return BLOCK_LENGTH;
    }

    public CancelOrderEncoder orderId(final long value) {
        buffer.putLong(offset + ORDER_ID_OFFSET, value, ByteOrder.LITTLE_ENDIAN);
        return this;
    }

    public CancelOrderEncoder clientId(final long value) {
        buffer.putLong(offset + CLIENT_ID_OFFSET, value, ByteOrder.LITTLE_ENDIAN);
        return this;
    }

    public CancelOrderEncoder instrumentId(final int value) {
        buffer.putInt(offset + INSTRUMENT_ID_OFFSET, value, ByteOrder.LITTLE_ENDIAN);
        return this;
    }
}
