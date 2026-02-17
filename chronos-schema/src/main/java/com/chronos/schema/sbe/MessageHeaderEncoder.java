package com.chronos.schema.sbe;

import org.agrona.MutableDirectBuffer;

/**
 * SBE message header encoder — 8-byte header prepended to every message.
 *
 * <p>Wire format:
 * <pre>
 *   blockLength : uint16 @ offset 0  (length of the message body)
 *   templateId  : uint16 @ offset 2  (message type identifier)
 *   schemaId    : uint16 @ offset 4  (schema identifier)
 *   version     : uint16 @ offset 6  (schema version)
 * </pre>
 *
 * <p>All fields are little-endian per SBE convention.
 */
public final class MessageHeaderEncoder {

    public static final int ENCODED_LENGTH = 8;

    private static final int BLOCK_LENGTH_OFFSET = 0;
    private static final int TEMPLATE_ID_OFFSET = 2;
    private static final int SCHEMA_ID_OFFSET = 4;
    private static final int VERSION_OFFSET = 6;

    private MutableDirectBuffer buffer;
    private int offset;

    public MessageHeaderEncoder wrap(final MutableDirectBuffer buffer, final int offset) {
        this.buffer = buffer;
        this.offset = offset;
        return this;
    }

    public MessageHeaderEncoder blockLength(final int value) {
        buffer.putShort(offset + BLOCK_LENGTH_OFFSET, (short) value, java.nio.ByteOrder.LITTLE_ENDIAN);
        return this;
    }

    public MessageHeaderEncoder templateId(final int value) {
        buffer.putShort(offset + TEMPLATE_ID_OFFSET, (short) value, java.nio.ByteOrder.LITTLE_ENDIAN);
        return this;
    }

    public MessageHeaderEncoder schemaId(final int value) {
        buffer.putShort(offset + SCHEMA_ID_OFFSET, (short) value, java.nio.ByteOrder.LITTLE_ENDIAN);
        return this;
    }

    public MessageHeaderEncoder version(final int value) {
        buffer.putShort(offset + VERSION_OFFSET, (short) value, java.nio.ByteOrder.LITTLE_ENDIAN);
        return this;
    }

    public int encodedLength() {
        return ENCODED_LENGTH;
    }
}
