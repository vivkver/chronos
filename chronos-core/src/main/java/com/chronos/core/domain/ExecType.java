package com.chronos.core.domain;

/**
 * Execution type constants — byte values matching SBE wire format.
 */
public final class ExecType {

    public static final byte NEW = 0;
    public static final byte PARTIAL_FILL = 1;
    public static final byte FILL = 2;
    public static final byte CANCELED = 3;
    public static final byte REJECTED = 4;

    private ExecType() {
    }

    public static String name(final byte type) {
        return switch (type) {
            case NEW -> "NEW";
            case PARTIAL_FILL -> "PARTIAL_FILL";
            case FILL -> "FILL";
            case CANCELED -> "CANCELED";
            case REJECTED -> "REJECTED";
            default -> "UNKNOWN(" + type + ")";
        };
    }
}
