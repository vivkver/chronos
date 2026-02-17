package com.chronos.core.domain;

/**
 * Order side constants — uses byte values matching the SBE wire format.
 * No enum overhead on the hot path.
 */
public final class Side {

    public static final byte BUY = 0;
    public static final byte SELL = 1;

    private Side() {
        // constants only
    }

    public static String name(final byte side) {
        return switch (side) {
            case BUY -> "BUY";
            case SELL -> "SELL";
            default -> "UNKNOWN(" + side + ")";
        };
    }

    public static boolean isValid(final byte side) {
        return side == BUY || side == SELL;
    }
}
