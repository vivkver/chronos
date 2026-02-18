package com.chronos.gateway.fix;

import java.util.HashMap;
import java.util.Map;

/**
 * Routes instruments (Symbols) to specific Shard IDs.
 * Used by FixSession to determine which Aeron Cluster to send messages to.
 */
public class InstrumentRouter {
    private final Map<String, Integer> symbolToShard = new HashMap<>();
    private final Map<String, Integer> symbolToInstrumentId = new HashMap<>();
    private final int defaultShardId;

    public InstrumentRouter(int defaultShardId) {
        this.defaultShardId = defaultShardId;
    }

    public void addMapping(String symbol, int shardId, int instrumentId) {
        symbolToShard.put(symbol, shardId);
        symbolToInstrumentId.put(symbol, instrumentId);
    }

    public void addMapping(String symbol, int shardId) {
        addMapping(symbol, shardId, symbol.hashCode());
    }

    public int getShardId(String symbol) {
        return symbolToShard.getOrDefault(symbol, defaultShardId);
    }

    public int getInstrumentId(String symbol) {
        return symbolToInstrumentId.getOrDefault(symbol, symbol.hashCode());
    }

    /**
     * Creates a default router for testing/development.
     * In production, this would load from config.
     */
    public static InstrumentRouter createDefault() {
        InstrumentRouter router = new InstrumentRouter(0);
        // Example mappings
        router.addMapping("AAPL", 0, 1);
        router.addMapping("MSFT", 0, 2);
        router.addMapping("GOOG", 1, 3);
        router.addMapping("TSLA", 1, 4);
        return router;
    }
}
