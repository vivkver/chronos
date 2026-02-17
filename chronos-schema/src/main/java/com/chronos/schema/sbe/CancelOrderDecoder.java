package com.chronos.schema.sbe;

import org.agrona.DirectBuffer;

import java.nio.ByteOrder;

/**
 * Flyweight decoder for CancelOrder messages (20 bytes).
 */
public final class CancelOrderDecoder {

    public static final int TEMPLATE_ID = 2;
    public static final int BLOCK_LENGTH = 20;

    private static final int ORDER_ID_OFFSET = 0;
    private static final int CLIENT_ID_OFFSET = 8;
    private static final int INSTRUMENT_ID_OFFSET = 16;

    private DirectBuffer buffer;
    private int offset;

    public CancelOrderDecoder wrap(final DirectBuffer buffer, final int offset) {
        this.buffer = buffer;
        this.offset = offset;
        return this;
    }

    public int encodedLength() {
        return BLOCK_LENGTH;
    }

    public long orderId() {
        return buffer.getLong(offset + ORDER_ID_OFFSET, ByteOrder.LITTLE_ENDIAN);
    }

    public long clientId() {
        return buffer.getLong(offset + CLIENT_ID_OFFSET, ByteOrder.LITTLE_ENDIAN);
    }

    public int instrumentId() {
        return buffer.getInt(offset + INSTRUMENT_ID_OFFSET, ByteOrder.LITTLE_ENDIAN);
    }
}
