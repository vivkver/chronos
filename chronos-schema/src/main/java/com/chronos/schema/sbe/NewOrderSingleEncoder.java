package com.chronos.schema.sbe;

import org.agrona.MutableDirectBuffer;

import java.nio.ByteOrder;

/**
 * Flyweight encoder for NewOrderSingle messages — zero-allocation hot-path encoding.
 *
 * <p>Wire format (42 bytes, little-endian):
 * <pre>
 *   orderId       : uint64 @ offset 0   (8 bytes)
 *   price         : int64  @ offset 8   (fixed-point: raw_price * 10^8)
 *   clientId      : uint64 @ offset 16  (8 bytes)
 *   timestampNs   : int64  @ offset 24  (cluster-assigned nanosecond timestamp)
 *   instrumentId  : uint32 @ offset 32  (4 bytes)
 *   quantity      : uint32 @ offset 36  (4 bytes)
 *   side          : uint8  @ offset 40  (0=BUY, 1=SELL)
 *   orderType     : uint8  @ offset 41  (0=LIMIT, 1=MARKET)
 * </pre>
 */
public final class NewOrderSingleEncoder {

    public static final int TEMPLATE_ID = 1;
    public static final int BLOCK_LENGTH = 42;
    public static final int SCHEMA_ID = 1;
    public static final int SCHEMA_VERSION = 1;

    private static final int ORDER_ID_OFFSET = 0;
    private static final int PRICE_OFFSET = 8;
    private static final int CLIENT_ID_OFFSET = 16;
    private static final int TIMESTAMP_NS_OFFSET = 24;
    private static final int INSTRUMENT_ID_OFFSET = 32;
    private static final int QUANTITY_OFFSET = 36;
    private static final int SIDE_OFFSET = 40;
    private static final int ORDER_TYPE_OFFSET = 41;

    private MutableDirectBuffer buffer;
    private int offset;

    public NewOrderSingleEncoder wrap(final MutableDirectBuffer buffer, final int offset) {
        this.buffer = buffer;
        this.offset = offset;
        return this;
    }

    public int encodedLength() {
        return BLOCK_LENGTH;
    }

    public NewOrderSingleEncoder orderId(final long value) {
        buffer.putLong(offset + ORDER_ID_OFFSET, value, ByteOrder.LITTLE_ENDIAN);
        return this;
    }

    public NewOrderSingleEncoder price(final long value) {
        buffer.putLong(offset + PRICE_OFFSET, value, ByteOrder.LITTLE_ENDIAN);
        return this;
    }

    public NewOrderSingleEncoder clientId(final long value) {
        buffer.putLong(offset + CLIENT_ID_OFFSET, value, ByteOrder.LITTLE_ENDIAN);
        return this;
    }

    public NewOrderSingleEncoder timestampNs(final long value) {
        buffer.putLong(offset + TIMESTAMP_NS_OFFSET, value, ByteOrder.LITTLE_ENDIAN);
        return this;
    }

    public NewOrderSingleEncoder instrumentId(final int value) {
        buffer.putInt(offset + INSTRUMENT_ID_OFFSET, value, ByteOrder.LITTLE_ENDIAN);
        return this;
    }

    public NewOrderSingleEncoder quantity(final int value) {
        buffer.putInt(offset + QUANTITY_OFFSET, value, ByteOrder.LITTLE_ENDIAN);
        return this;
    }

    public NewOrderSingleEncoder side(final byte value) {
        buffer.putByte(offset + SIDE_OFFSET, value);
        return this;
    }

    public NewOrderSingleEncoder orderType(final byte value) {
        buffer.putByte(offset + ORDER_TYPE_OFFSET, value);
        return this;
    }
}
