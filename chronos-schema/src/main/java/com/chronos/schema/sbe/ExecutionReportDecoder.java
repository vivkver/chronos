package com.chronos.schema.sbe;

import org.agrona.DirectBuffer;

import java.nio.ByteOrder;

/**
 * Flyweight decoder for ExecutionReport messages.
 */
public final class ExecutionReportDecoder {

    public static final int TEMPLATE_ID = 3;
    public static final int BLOCK_LENGTH = 54;

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

    private DirectBuffer buffer;
    private int offset;

    public ExecutionReportDecoder wrap(final DirectBuffer buffer, final int offset) {
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

    public long execId() {
        return buffer.getLong(offset + EXEC_ID_OFFSET, ByteOrder.LITTLE_ENDIAN);
    }

    public long price() {
        return buffer.getLong(offset + PRICE_OFFSET, ByteOrder.LITTLE_ENDIAN);
    }

    public long clientId() {
        return buffer.getLong(offset + CLIENT_ID_OFFSET, ByteOrder.LITTLE_ENDIAN);
    }

    public long matchTimestampNs() {
        return buffer.getLong(offset + MATCH_TIMESTAMP_NS_OFFSET, ByteOrder.LITTLE_ENDIAN);
    }

    public int instrumentId() {
        return buffer.getInt(offset + INSTRUMENT_ID_OFFSET, ByteOrder.LITTLE_ENDIAN);
    }

    public int filledQuantity() {
        return buffer.getInt(offset + FILLED_QUANTITY_OFFSET, ByteOrder.LITTLE_ENDIAN);
    }

    public int remainingQuantity() {
        return buffer.getInt(offset + REMAINING_QUANTITY_OFFSET, ByteOrder.LITTLE_ENDIAN);
    }

    public byte side() {
        return buffer.getByte(offset + SIDE_OFFSET);
    }

    public byte execType() {
        return buffer.getByte(offset + EXEC_TYPE_OFFSET);
    }
}
