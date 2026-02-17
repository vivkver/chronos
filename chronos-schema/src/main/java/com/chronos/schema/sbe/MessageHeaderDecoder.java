package com.chronos.schema.sbe;

import org.agrona.DirectBuffer;

import java.nio.ByteOrder;

/**
 * SBE message header decoder — reads the 8-byte header to identify message type and size.
 */
public final class MessageHeaderDecoder {

    public static final int ENCODED_LENGTH = 8;

    private static final int BLOCK_LENGTH_OFFSET = 0;
    private static final int TEMPLATE_ID_OFFSET = 2;
    private static final int SCHEMA_ID_OFFSET = 4;
    private static final int VERSION_OFFSET = 6;

    private DirectBuffer buffer;
    private int offset;

    public MessageHeaderDecoder wrap(final DirectBuffer buffer, final int offset) {
        this.buffer = buffer;
        this.offset = offset;
        return this;
    }

    public int blockLength() {
        return buffer.getShort(offset + BLOCK_LENGTH_OFFSET, ByteOrder.LITTLE_ENDIAN) & 0xFFFF;
    }

    public int templateId() {
        return buffer.getShort(offset + TEMPLATE_ID_OFFSET, ByteOrder.LITTLE_ENDIAN) & 0xFFFF;
    }

    public int schemaId() {
        return buffer.getShort(offset + SCHEMA_ID_OFFSET, ByteOrder.LITTLE_ENDIAN) & 0xFFFF;
    }

    public int version() {
        return buffer.getShort(offset + VERSION_OFFSET, ByteOrder.LITTLE_ENDIAN) & 0xFFFF;
    }

    public int encodedLength() {
        return ENCODED_LENGTH;
    }
}
