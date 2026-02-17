package com.chronos.schema.sbe;

import org.agrona.MutableDirectBuffer;

import java.nio.ByteOrder;

/**
 * Flyweight encoder for ExecutionReport messages.
 *
 * <p>
 * Wire format (54 bytes, little-endian):
 * 
 * <pre>
 *   orderId           : uint64 @ offset 0
 *   execId            : uint64 @ offset 8
 *   price             : int64  @ offset 16  (fixed-point * 10^8)
 *   clientId          : uint64 @ offset 24
 *   matchTimestampNs  : int64  @ offset 32
 *   instrumentId      : uint32 @ offset 40
 *   filledQuantity    : uint32 @ offset 44
 *   remainingQuantity : uint32 @ offset 48
 *   side              : uint8  @ offset 52
 *   execType          : uint8  @ offset 53
 * </pre>
 */
public final class ExecutionReportEncoder {

    public static final int TEMPLATE_ID = 3;
    public static final int BLOCK_LENGTH = 54;
    public static final int SCHEMA_ID = 1;
    public static final int SCHEMA_VERSION = 1;

    private static final int ORDER_ID_OFFSET = 0;
    private static final int EXEC_ID_OFFSET = 8;
    private static final int PRICE_OFFSET = 16;
    private static final int CLIENT_ID_OFFSET = 24;
    private static final int MATCH_TIMESTAMP_NS_OFFSET = 32;
    private static final int INSTRUMENT_ID_OFFSET = 40;
    private static final int FILLED_QUANTITY_OFFSET = 44;
    private static final int REMAINING_QUANTITY_OFFSET = 48;
    private static final int SIDE_OFFSET = 52;
    private static final int EXEC_TYPE_OFFSET = 53;

    private MutableDirectBuffer buffer;
    private int offset;

    public ExecutionReportEncoder wrap(final MutableDirectBuffer buffer, final int offset) {
        this.buffer = buffer;
        this.offset = offset;
        return this;
    }

    public int encodedLength() {
        return BLOCK_LENGTH;
    }

    public ExecutionReportEncoder orderId(final long value) {
        buffer.putLong(offset + ORDER_ID_OFFSET, value, ByteOrder.LITTLE_ENDIAN);
        return this;
    }

    public ExecutionReportEncoder execId(final long value) {
        buffer.putLong(offset + EXEC_ID_OFFSET, value, ByteOrder.LITTLE_ENDIAN);
        return this;
    }

    public ExecutionReportEncoder price(final long value) {
        buffer.putLong(offset + PRICE_OFFSET, value, ByteOrder.LITTLE_ENDIAN);
        return this;
    }

    public ExecutionReportEncoder clientId(final long value) {
        buffer.putLong(offset + CLIENT_ID_OFFSET, value, ByteOrder.LITTLE_ENDIAN);
        return this;
    }

    public ExecutionReportEncoder matchTimestampNs(final long value) {
        buffer.putLong(offset + MATCH_TIMESTAMP_NS_OFFSET, value, ByteOrder.LITTLE_ENDIAN);
        return this;
    }

    public ExecutionReportEncoder instrumentId(final int value) {
        buffer.putInt(offset + INSTRUMENT_ID_OFFSET, value, ByteOrder.LITTLE_ENDIAN);
        return this;
    }

    public ExecutionReportEncoder filledQuantity(final int value) {
        buffer.putInt(offset + FILLED_QUANTITY_OFFSET, value, ByteOrder.LITTLE_ENDIAN);
        return this;
    }

    public ExecutionReportEncoder remainingQuantity(final int value) {
        buffer.putInt(offset + REMAINING_QUANTITY_OFFSET, value, ByteOrder.LITTLE_ENDIAN);
        return this;
    }

    public ExecutionReportEncoder side(final byte value) {
        buffer.putByte(offset + SIDE_OFFSET, value);
        return this;
    }

    public ExecutionReportEncoder execType(final byte value) {
        buffer.putByte(offset + EXEC_TYPE_OFFSET, value);
        return this;
    }
}
