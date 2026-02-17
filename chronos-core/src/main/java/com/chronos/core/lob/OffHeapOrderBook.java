package com.chronos.core.lob;

import org.agrona.BufferUtil;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;

/**
 * Off-heap Limit Order Book using Structure-of-Arrays (SOA) layout for
 * SIMD-friendly scanning.
 *
 * <h2>Design Rationale</h2>
 * <ul>
 * <li><b>SOA Layout</b>: Price arrays stored contiguously for Vector API SIMD
 * scanning.
 * 8 price levels fit in a single 512-bit SIMD register (8 × 64-bit longs).</li>
 * <li><b>On-Heap Price Arrays</b>: Small {@code long[]} arrays (8 KB for 1024
 * levels)
 * are L1-cache-resident and compatible with
 * {@code LongVector.fromArray()}.</li>
 * <li><b>Off-Heap Order Storage</b>: Bulk order data stored in off-heap
 * {@link UnsafeBuffer}
 * to avoid GC pressure from large allocations.</li>
 * <li><b>Pre-Allocated</b>: All memory allocated at construction — zero
 * allocation on the hot path.</li>
 * <li><b>Single-Threaded</b>: Designed for pinned-core, single-threaded
 * operation inside Aeron Cluster.</li>
 * </ul>
 *
 * <h2>Memory Layout per Side (Bid/Ask)</h2>
 * 
 * <pre>
 *   prices[MAX_LEVELS]      : long[]  — price at each level (on-heap for SIMD)
 *   quantities[MAX_LEVELS]  : long[]  — total quantity at each level
 *   orderCounts[MAX_LEVELS] : int[]   — number of orders at each level
 *   levelCount              : int     — current number of active levels
 * </pre>
 *
 * <h2>Order Slot Layout (off-heap, 64 bytes per slot, cache-line aligned)</h2>
 * 
 * <pre>
 *   orderId    : long  @ offset 0
 *   price      : long  @ offset 8
 *   clientId   : long  @ offset 16
 *   timestampNs: long  @ offset 24
 *   quantity   : int   @ offset 32
 *   remaining  : int   @ offset 36
 *   instrId    : int   @ offset 40
 *   side       : byte  @ offset 44
 *   orderType  : byte  @ offset 45
 *   nextSlot   : int   @ offset 48  (intrusive linked list for per-level order queue)
 *   prevSlot   : int   @ offset 52
 *   levelIndex : int   @ offset 56  (index into the price level array)
 *   [pad to 64]: bytes @ offset 60
 * </pre>
 */
public final class OffHeapOrderBook {

    /**
     * Maximum number of price levels per side (bid/ask). Fits in L1 cache at 8 ×
     * 1024 = 8 KB.
     */
    public static final int MAX_LEVELS = 1024;

    /** Maximum number of live orders in the book. */
    public static final int MAX_ORDERS = 1_048_576; // 1M orders

    /** Bytes per order slot — cache-line aligned at 64 bytes. */
    public static final int ORDER_SLOT_SIZE = 64;

    // ── Order slot field offsets ──
    private static final int SLOT_ORDER_ID = 0;
    private static final int SLOT_PRICE = 8;
    private static final int SLOT_CLIENT_ID = 16;
    private static final int SLOT_TIMESTAMP = 24;
    private static final int SLOT_QUANTITY = 32;
    private static final int SLOT_REMAINING = 36;
    private static final int SLOT_INSTRUMENT_ID = 40;
    private static final int SLOT_SIDE = 44;
    private static final int SLOT_ORDER_TYPE = 45;
    private static final int SLOT_NEXT = 48;
    private static final int SLOT_PREV = 52;
    private static final int SLOT_LEVEL_INDEX = 56;

    /** Sentinel value indicating no next/prev order in linked list. */
    public static final int NULL_SLOT = -1;

    // ── Bid side (sorted descending: best bid at index 0) ──
    private final long[] bidPrices;
    private final long[] bidQuantities;
    private final int[] bidOrderCounts;
    private final int[] bidHeadSlots; // head of order linked list per level
    private int bidLevelCount;

    // ── Ask side (sorted ascending: best ask at index 0) ──
    private final long[] askPrices;
    private final long[] askQuantities;
    private final int[] askOrderCounts;
    private final int[] askHeadSlots;
    private int askLevelCount;

    // ── Off-heap order storage ──
    private final UnsafeBuffer orderBuffer;
    private final ByteBuffer orderDirectBuffer;

    // ── Free list (intrusive, using nextSlot field) ──
    private int freeListHead;
    private int liveOrderCount;

    // ── Instrument this book serves ──
    private final int instrumentId;

    /**
     * Construct a new order book pre-allocating all memory.
     *
     * @param instrumentId the instrument identifier this book serves
     */
    public OffHeapOrderBook(final int instrumentId) {
        this.instrumentId = instrumentId;

        // On-heap SOA arrays for SIMD-friendly scanning
        this.bidPrices = new long[MAX_LEVELS];
        this.bidQuantities = new long[MAX_LEVELS];
        this.bidOrderCounts = new int[MAX_LEVELS];
        this.bidHeadSlots = new int[MAX_LEVELS];

        this.askPrices = new long[MAX_LEVELS];
        this.askQuantities = new long[MAX_LEVELS];
        this.askOrderCounts = new int[MAX_LEVELS];
        this.askHeadSlots = new int[MAX_LEVELS];

        // Initialize head slots to NULL
        java.util.Arrays.fill(bidHeadSlots, NULL_SLOT);
        java.util.Arrays.fill(askHeadSlots, NULL_SLOT);

        // Off-heap order pool — cache-line aligned
        this.orderDirectBuffer = BufferUtil.allocateDirectAligned(
                (int) ((long) MAX_ORDERS * ORDER_SLOT_SIZE), 64);
        this.orderBuffer = new UnsafeBuffer(orderDirectBuffer);

        // Initialize free list: chain all slots together
        for (int i = 0; i < MAX_ORDERS - 1; i++) {
            orderBuffer.putInt(slotOffset(i) + SLOT_NEXT, i + 1);
        }
        orderBuffer.putInt(slotOffset(MAX_ORDERS - 1) + SLOT_NEXT, NULL_SLOT);
        this.freeListHead = 0;
        this.liveOrderCount = 0;
    }

    // ══════════════════════════════════════════════════════════════
    // Price Level Access (for SIMD scanning)
    // ══════════════════════════════════════════════════════════════

    /** Returns the bid price array for SIMD scanning. */
    public long[] bidPrices() {
        return bidPrices;
    }

    /** Returns the ask price array for SIMD scanning. */
    public long[] askPrices() {
        return askPrices;
    }

    public long[] bidQuantities() {
        return bidQuantities;
    }

    public long[] askQuantities() {
        return askQuantities;
    }

    public int bidLevelCount() {
        return bidLevelCount;
    }

    public int askLevelCount() {
        return askLevelCount;
    }

    public int instrumentId() {
        return instrumentId;
    }

    public int liveOrderCount() {
        return liveOrderCount;
    }

    /** Best bid price (highest), or {@code Long.MIN_VALUE} if empty. */
    public long bestBid() {
        return bidLevelCount > 0 ? bidPrices[0] : Long.MIN_VALUE;
    }

    /** Best ask price (lowest), or {@code Long.MAX_VALUE} if empty. */
    public long bestAsk() {
        return askLevelCount > 0 ? askPrices[0] : Long.MAX_VALUE;
    }

    // ══════════════════════════════════════════════════════════════
    // Order Operations (zero-allocation hot path)
    // ══════════════════════════════════════════════════════════════

    /**
     * Allocate a slot from the free list and populate it with order data.
     *
     * @return slot index, or {@code NULL_SLOT} if the pool is exhausted
     */
    public int addOrder(final long orderId, final long price, final long clientId,
            final long timestampNs, final int quantity, final int instrumentId,
            final byte side, final byte orderType) {
        if (freeListHead == NULL_SLOT) {
            return NULL_SLOT; // pool exhausted
        }

        final int slot = freeListHead;
        final int base = slotOffset(slot);

        // Unlink from free list BEFORE writing (read next pointer first)
        freeListHead = orderBuffer.getInt(base + SLOT_NEXT);

        // Write order data to the slot
        orderBuffer.putLong(base + SLOT_ORDER_ID, orderId);
        orderBuffer.putLong(base + SLOT_PRICE, price);
        orderBuffer.putLong(base + SLOT_CLIENT_ID, clientId);
        orderBuffer.putLong(base + SLOT_TIMESTAMP, timestampNs);
        orderBuffer.putInt(base + SLOT_QUANTITY, quantity);
        orderBuffer.putInt(base + SLOT_REMAINING, quantity);
        orderBuffer.putInt(base + SLOT_INSTRUMENT_ID, instrumentId);
        orderBuffer.putByte(base + SLOT_SIDE, side);
        orderBuffer.putByte(base + SLOT_ORDER_TYPE, orderType);
        orderBuffer.putInt(base + SLOT_NEXT, NULL_SLOT);
        orderBuffer.putInt(base + SLOT_PREV, NULL_SLOT);
        orderBuffer.putInt(base + SLOT_LEVEL_INDEX, NULL_SLOT);

        // Insert into the appropriate price level
        insertIntoLevel(slot, price, side);
        liveOrderCount++;

        return slot;
    }

    /**
     * Remove an order by its slot index. Returns quantity remaining for execution
     * reports.
     */
    public int removeOrder(final int slot) {
        if (slot < 0 || slot >= MAX_ORDERS) {
            return 0;
        }

        final int base = slotOffset(slot);
        final int remaining = orderBuffer.getInt(base + SLOT_REMAINING);
        final byte side = orderBuffer.getByte(base + SLOT_SIDE);
        final int levelIdx = orderBuffer.getInt(base + SLOT_LEVEL_INDEX);

        if (levelIdx != NULL_SLOT) {
            // Unlink from level's order queue
            unlinkFromLevel(slot, side, levelIdx);
        }

        // Return to free list
        orderBuffer.putInt(base + SLOT_NEXT, freeListHead);
        freeListHead = slot;
        liveOrderCount--;

        return remaining;
    }

    /**
     * Reduce the remaining quantity of an order. Used during partial fills.
     *
     * @return the new remaining quantity
     */
    public int reduceQuantity(final int slot, final int fillQty) {
        final int base = slotOffset(slot);
        final int current = orderBuffer.getInt(base + SLOT_REMAINING);
        final int newRemaining = current - fillQty;
        orderBuffer.putInt(base + SLOT_REMAINING, newRemaining);

        // Update level aggregate quantity
        final byte side = orderBuffer.getByte(base + SLOT_SIDE);
        final int levelIdx = orderBuffer.getInt(base + SLOT_LEVEL_INDEX);
        if (side == com.chronos.core.domain.Side.BUY) {
            bidQuantities[levelIdx] -= fillQty;
        } else {
            askQuantities[levelIdx] -= fillQty;
        }

        return newRemaining;
    }

    /**
     * Returns the head order slot for a given price level and side.
     */
    public int headOrderSlot(final byte side, final int levelIndex) {
        return side == com.chronos.core.domain.Side.BUY
                ? bidHeadSlots[levelIndex]
                : askHeadSlots[levelIndex];
    }

    // ── Order slot field readers ──

    public long slotOrderId(final int slot) {
        return orderBuffer.getLong(slotOffset(slot) + SLOT_ORDER_ID);
    }

    public long slotPrice(final int slot) {
        return orderBuffer.getLong(slotOffset(slot) + SLOT_PRICE);
    }

    public long slotClientId(final int slot) {
        return orderBuffer.getLong(slotOffset(slot) + SLOT_CLIENT_ID);
    }

    public long slotTimestamp(final int slot) {
        return orderBuffer.getLong(slotOffset(slot) + SLOT_TIMESTAMP);
    }

    public int slotQuantity(final int slot) {
        return orderBuffer.getInt(slotOffset(slot) + SLOT_QUANTITY);
    }

    public int slotRemaining(final int slot) {
        return orderBuffer.getInt(slotOffset(slot) + SLOT_REMAINING);
    }

    public byte slotSide(final int slot) {
        return orderBuffer.getByte(slotOffset(slot) + SLOT_SIDE);
    }

    public int slotNext(final int slot) {
        return orderBuffer.getInt(slotOffset(slot) + SLOT_NEXT);
    }

    // ══════════════════════════════════════════════════════════════
    // Price Level Management
    // ══════════════════════════════════════════════════════════════

    /**
     * Find or create a price level for the given price and insert the order slot.
     * Bids are sorted descending (highest first), asks ascending (lowest first).
     */
    private void insertIntoLevel(final int slot, final long price, final byte side) {
        final long[] prices;
        final long[] quantities;
        final int[] counts;
        final int[] heads;
        int levelCount;

        if (side == com.chronos.core.domain.Side.BUY) {
            prices = bidPrices;
            quantities = bidQuantities;
            counts = bidOrderCounts;
            heads = bidHeadSlots;
            levelCount = bidLevelCount;
        } else {
            prices = askPrices;
            quantities = askQuantities;
            counts = askOrderCounts;
            heads = askHeadSlots;
            levelCount = askLevelCount;
        }

        // Find the insertion point (or existing level)
        int idx = -1;
        for (int i = 0; i < levelCount; i++) {
            if (prices[i] == price) {
                idx = i;
                break;
            }
            final boolean shouldInsertBefore = (side == com.chronos.core.domain.Side.BUY) ? (price > prices[i])
                    : (price < prices[i]);
            if (shouldInsertBefore) {
                if (levelCount >= MAX_LEVELS) {
                    return; // book is full
                }

                idx = i;
                // Shift levels down to make room
                if (levelCount < MAX_LEVELS) {
                    System.arraycopy(prices, i, prices, i + 1, levelCount - i);
                    System.arraycopy(quantities, i, quantities, i + 1, levelCount - i);
                    System.arraycopy(counts, i, counts, i + 1, levelCount - i);
                    System.arraycopy(heads, i, heads, i + 1, levelCount - i);
                    // Update levelIndex for all shifted orders
                    for (int j = i + 1; j <= levelCount; j++) {
                        updateLevelIndices(heads[j], j);
                    }
                }
                prices[i] = price;
                quantities[i] = 0;
                counts[i] = 0;
                heads[i] = NULL_SLOT;
                if (side == com.chronos.core.domain.Side.BUY) {
                    bidLevelCount++;
                } else {
                    askLevelCount++;
                }
                break;
            }
        }

        // If not found, append at end
        if (idx == -1) {
            if (levelCount >= MAX_LEVELS) {
                return; // book is full
            }
            idx = levelCount;
            prices[idx] = price;
            quantities[idx] = 0;
            counts[idx] = 0;
            heads[idx] = NULL_SLOT;
            if (side == com.chronos.core.domain.Side.BUY) {
                bidLevelCount++;
            } else {
                askLevelCount++;
            }
        }

        // Append order to the level's linked list (FIFO: append at tail)
        final int base = slotOffset(slot);
        orderBuffer.putInt(base + SLOT_LEVEL_INDEX, idx);

        final int remaining = orderBuffer.getInt(base + SLOT_REMAINING);
        quantities[idx] += remaining;
        counts[idx]++;

        if (heads[idx] == NULL_SLOT) {
            heads[idx] = slot;
            orderBuffer.putInt(base + SLOT_NEXT, NULL_SLOT);
            orderBuffer.putInt(base + SLOT_PREV, NULL_SLOT);
        } else {
            // Walk to tail
            int current = heads[idx];
            int prev = NULL_SLOT;
            while (current != NULL_SLOT) {
                prev = current;
                current = orderBuffer.getInt(slotOffset(current) + SLOT_NEXT);
            }
            orderBuffer.putInt(slotOffset(prev) + SLOT_NEXT, slot);
            orderBuffer.putInt(base + SLOT_PREV, prev);
            orderBuffer.putInt(base + SLOT_NEXT, NULL_SLOT);
        }
    }

    private void unlinkFromLevel(final int slot, final byte side, final int levelIdx) {
        final int base = slotOffset(slot);
        final int next = orderBuffer.getInt(base + SLOT_NEXT);
        final int prev = orderBuffer.getInt(base + SLOT_PREV);

        final int[] heads = (side == com.chronos.core.domain.Side.BUY) ? bidHeadSlots : askHeadSlots;
        final int[] counts = (side == com.chronos.core.domain.Side.BUY) ? bidOrderCounts : askOrderCounts;
        final long[] quantities = (side == com.chronos.core.domain.Side.BUY) ? bidQuantities : askQuantities;

        // Update quantity
        final int remaining = orderBuffer.getInt(base + SLOT_REMAINING);
        quantities[levelIdx] -= remaining;
        counts[levelIdx]--;

        // Unlink from doubly-linked list
        if (prev != NULL_SLOT) {
            orderBuffer.putInt(slotOffset(prev) + SLOT_NEXT, next);
        } else {
            heads[levelIdx] = next;
        }
        if (next != NULL_SLOT) {
            orderBuffer.putInt(slotOffset(next) + SLOT_PREV, prev);
        }

        // If level is now empty, remove it
        if (counts[levelIdx] == 0) {
            removeLevel(side, levelIdx);
        }
    }

    private void removeLevel(final byte side, final int idx) {
        final long[] prices;
        final long[] quantities;
        final int[] counts;
        final int[] heads;
        int levelCount;

        if (side == com.chronos.core.domain.Side.BUY) {
            prices = bidPrices;
            quantities = bidQuantities;
            counts = bidOrderCounts;
            heads = bidHeadSlots;
            levelCount = bidLevelCount;
        } else {
            prices = askPrices;
            quantities = askQuantities;
            counts = askOrderCounts;
            heads = askHeadSlots;
            levelCount = askLevelCount;
        }

        final int remaining = levelCount - idx - 1;
        if (remaining > 0) {
            System.arraycopy(prices, idx + 1, prices, idx, remaining);
            System.arraycopy(quantities, idx + 1, quantities, idx, remaining);
            System.arraycopy(counts, idx + 1, counts, idx, remaining);
            System.arraycopy(heads, idx + 1, heads, idx, remaining);
            // Update level indices for shifted orders
            for (int i = idx; i < idx + remaining; i++) {
                updateLevelIndices(heads[i], i);
            }
        }

        if (side == com.chronos.core.domain.Side.BUY) {
            bidLevelCount--;
        } else {
            askLevelCount--;
        }
    }

    /**
     * Walk the linked list at a level and update every order's SLOT_LEVEL_INDEX.
     */
    private void updateLevelIndices(int headSlot, final int newLevelIndex) {
        while (headSlot != NULL_SLOT) {
            final int base = slotOffset(headSlot);
            orderBuffer.putInt(base + SLOT_LEVEL_INDEX, newLevelIndex);
            headSlot = orderBuffer.getInt(base + SLOT_NEXT);
        }
    }

    /**
     * Reset the book to its initial empty state — used during snapshots and
     * testing.
     */
    public void reset() {
        bidLevelCount = 0;
        askLevelCount = 0;
        liveOrderCount = 0;
        java.util.Arrays.fill(bidHeadSlots, NULL_SLOT);
        java.util.Arrays.fill(askHeadSlots, NULL_SLOT);
        for (int i = 0; i < MAX_ORDERS - 1; i++) {
            orderBuffer.putInt(slotOffset(i) + SLOT_NEXT, i + 1);
        }
        orderBuffer.putInt(slotOffset(MAX_ORDERS - 1) + SLOT_NEXT, NULL_SLOT);
        freeListHead = 0;
    }

    private static int slotOffset(final int slotIndex) {
        return slotIndex * ORDER_SLOT_SIZE;
    }
}
