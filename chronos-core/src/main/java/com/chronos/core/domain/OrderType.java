package com.chronos.core.domain;

/**
 * Order type constants — byte values matching SBE wire format.
 */
public final class OrderType {

    public static final byte LIMIT = 0;
    public static final byte MARKET = 1;

    private OrderType() {
    }

    public static String name(final byte type) {
        return switch (type) {
            case LIMIT -> "LIMIT";
            case MARKET -> "MARKET";
            default -> "UNKNOWN(" + type + ")";
        };
    }
}
