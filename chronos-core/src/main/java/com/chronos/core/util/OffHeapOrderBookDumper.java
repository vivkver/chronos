package com.chronos.core.util;

import com.chronos.core.lob.OffHeapOrderBook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility for inspecting the internal state of an OffHeapOrderBook.
 * Useful for debugging and verification.
 */
public final class OffHeapOrderBookDumper {
    private static final Logger LOG = LoggerFactory.getLogger(OffHeapOrderBookDumper.class);

    private OffHeapOrderBookDumper() {
    }

    /**
     * Dumps the current state of the order book to the logger.
     * WARNING: This is a slow operation and should not be used on the hot path.
     */
    public static void dump(OffHeapOrderBook book) {
        LOG.info("=== Order Book Dump (Instrument ID: {}) ===", book.instrumentId());

        LOG.info("--- BIDS ---");
        dumpSide(book, com.chronos.core.domain.Side.BUY);

        LOG.info("--- ASKS ---");
        dumpSide(book, com.chronos.core.domain.Side.SELL);

        LOG.info("Total Live Orders: {}", book.liveOrderCount());
        LOG.info("==========================================");
    }

    private static void dumpSide(OffHeapOrderBook book, byte side) {
        long[] prices = (side == com.chronos.core.domain.Side.BUY) ? book.bidPrices() : book.askPrices();
        int levelCount = (side == com.chronos.core.domain.Side.BUY) ? book.bidLevelCount() : book.askLevelCount();

        if (levelCount == 0) {
            LOG.info("  (Empty)");
            return;
        }

        for (int i = 0; i < levelCount; i++) {
            long price = prices[i];
            int orderCount = 0;
            long totalQty = 0;

            // Iterate orders at this level
            // Note: OffHeapOrderBook doesn't expose level iteration directly in public API
            // easily
            // without modifying it. Assuming we can iterate via headOrderSlot if we knew
            // the level index?
            // Actually, the public API `headOrderSlot` takes a side and level index.

            int slot = book.headOrderSlot(side, i);
            while (slot != OffHeapOrderBook.NULL_SLOT) {
                totalQty += book.slotRemaining(slot);
                orderCount++;
                slot = book.slotNext(slot);
            }

            LOG.info("  Level {}: Price={}, Orders={}, TotalQty={}",
                    i, String.format("%.2f", price / 100000000.0), orderCount, totalQty);
        }
    }
}
