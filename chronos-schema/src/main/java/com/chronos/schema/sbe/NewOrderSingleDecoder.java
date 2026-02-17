package com.chronos.schema.sbe;

import org.agrona.DirectBuffer;

import java.nio.ByteOrder;

/**
 * Flyweight decoder for NewOrderSingle messages — zero-allocation hot-path decoding.
 * Mirrors {@link NewOrderSingleEncoder} wire format.
 */
public final class NewOrderSingleDecoder {

    public static final int TEMPLATE_ID = 1;
    public static final int BLOCK_LENGTH = 42;

    private static final int ORDER_ID_OFFSET = 0;
    private static final int PRICE_OFFSET = 8;
    private static final int CLIENT_ID_OFFSET = 16;
    private static final int TIMESTAMP_NS_OFFSET = 24;
    private static final int INSTRUMENT_ID_OFFSET = 32;
    private static final int QUANTITY_OFFSET = 36;
    private static final int SIDE_OFFSET = 40;
    private static final int ORDER_TYPE_OFFSET = 41;

    private DirectBuffer buffer;
    private int offset;

    public NewOrderSingleDecoder wrap(final DirectBuffer buffer, final int offset) {
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

    public long price() {
        return buffer.getLong(offset + PRICE_OFFSET, ByteOrder.LITTLE_ENDIAN);
    }

    public long clientId() {
        return buffer.getLong(offset + CLIENT_ID_OFFSET, ByteOrder.LITTLE_ENDIAN);
    }

    public long timestampNs() {
        return buffer.getLong(offset + TIMESTAMP_NS_OFFSET, ByteOrder.LITTLE_ENDIAN);
    }

    public int instrumentId() {
        return buffer.getInt(offset + INSTRUMENT_ID_OFFSET, ByteOrder.LITTLE_ENDIAN);
    }

    public int quantity() {
        return buffer.getInt(offset + QUANTITY_OFFSET, ByteOrder.LITTLE_ENDIAN);
    }

    public byte side() {
        return buffer.getByte(offset + SIDE_OFFSET);
    }

    public byte orderType() {
        return buffer.getByte(offset + ORDER_TYPE_OFFSET);
    }
}
