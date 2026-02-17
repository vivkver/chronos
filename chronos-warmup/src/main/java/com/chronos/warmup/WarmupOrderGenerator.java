package com.chronos.warmup;

import com.chronos.core.domain.OrderType;
import com.chronos.core.domain.Side;
import org.agrona.MutableDirectBuffer;
import com.chronos.schema.sbe.MessageHeaderEncoder;
import com.chronos.schema.sbe.NewOrderSingleEncoder;

/**
 * Deterministic order flow generator for JIT warmup training runs.
 *
 * <h2>Purpose</h2>
 * <p>
 * Generates a realistic mix of BUY/SELL LIMIT/MARKET orders across a
 * configurable price range to exercise all hot paths in the matching engine,
 * ensuring the JIT compiler fully optimizes the critical code before live
 * trading.
 * </p>
 *
 * <h2>Determinism</h2>
 * <p>
 * Uses a simple linear congruential generator (LCG) seeded with a fixed value,
 * ensuring reproducible order sequences across runs — critical for AOT cache
 * consistency.
 * </p>
 */
public final class WarmupOrderGenerator {

    // LCG parameters (Numerical Recipes)
    private static final long LCG_A = 6364136223846793005L;
    private static final long LCG_C = 1442695040888963407L;

    private long seed;
    private long orderId;

    // Pre-allocated encoders
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final NewOrderSingleEncoder orderEncoder = new NewOrderSingleEncoder();

    /**
     * Price range: centered around 10000.00 (= 1_000_000_000_000 in fixed-point *
     * 10^8)
     */
    private static final long BASE_PRICE = 1_000_000_000_000L; // $10,000.00
    private static final long PRICE_RANGE = 50_000_000_000L; // ±$500.00

    public WarmupOrderGenerator(final long seed) {
        this.seed = seed;
        this.orderId = 1;
    }

    /**
     * Generate a single warmup order into the target buffer.
     *
     * @return total bytes written (header + body)
     */
    public int generateOrder(final MutableDirectBuffer buffer, final int offset) {
        // Advance PRNG
        seed = seed * LCG_A + LCG_C;

        // Derive order attributes from PRNG state
        final byte side = ((seed >>> 32) & 1) == 0 ? Side.BUY : Side.SELL;
        final byte orderType = ((seed >>> 33) & 0x7) == 0 ? OrderType.MARKET : OrderType.LIMIT;

        // Price: base ± random offset (only meaningful for LIMIT orders)
        final long priceOffset = (seed >>> 16) % PRICE_RANGE;
        final long price = (orderType == OrderType.MARKET)
                ? 0L
                : BASE_PRICE + priceOffset - (PRICE_RANGE / 2);

        // Quantity: 1-1000 lots
        final int quantity = (int) ((Math.abs(seed >>> 48) % 1000) + 1);

        // Encode header
        headerEncoder.wrap(buffer, offset)
                .blockLength(NewOrderSingleEncoder.BLOCK_LENGTH)
                .templateId(NewOrderSingleEncoder.TEMPLATE_ID)
                .schemaId(NewOrderSingleEncoder.SCHEMA_ID)
                .version(NewOrderSingleEncoder.SCHEMA_VERSION);

        // Encode order body
        final int bodyOffset = offset + MessageHeaderEncoder.ENCODED_LENGTH;
        orderEncoder.wrap(buffer, bodyOffset)
                .orderId(orderId++)
                .price(price)
                .clientId(1L)
                .timestampNs(System.nanoTime())
                .instrumentId(1)
                .quantity(quantity)
                .side(side)
                .orderType(orderType);

        return MessageHeaderEncoder.ENCODED_LENGTH + NewOrderSingleEncoder.BLOCK_LENGTH;
    }

    /** Total encoding size for one order message. */
    public static int messageSize() {
        return MessageHeaderEncoder.ENCODED_LENGTH + NewOrderSingleEncoder.BLOCK_LENGTH;
    }
}
