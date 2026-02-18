# Chronos Sharding Strategy

This document outlines the horizontal sharding strategy implemented in Chronos to scale the matching engine across multiple Aeron Cluster instances (Shards).

## Overview

Chronos uses a **Symbol-based Sharding** approach where each instrument (Symbol) is assigned to a specific Shard. Each Shard is an independent Aeron Cluster that sequences and processes messages for its assigned instruments.

### Goals
- **Scalability**: Distribute matching load across multiple CPU cores and machines.
- **Isolation**: Faults or latency spikes in one instrument do not affect others on different shards.
- **Simplicity**: Deterministic routing based on Symbol.

## Components

### 1. Instrument Router (`InstrumentRouter`)

The `InstrumentRouter` is a component in the FIX Gateway responsible for mapping a Symbol (e.g., "AAPL") to a Shard ID (e.g., 0, 1).

- **Implementation**: `com.chronos.gateway.fix.InstrumentRouter`
- **Logic**: 
  - Look up Symbol in a pre-configured map.
  - If found, return assigned Shard ID.
  - If not found, return default Shard ID (0).
- **Configuration**: Currently hardcoded/static factories, but designed to load from config.

### 2. FIX Gateway (`FixGatewayMain`, `FixSession`)

The FIX Gateway maintains a single TCP connection with the client but routes messages to different internal Shards based on the `InstrumentRouter`.

- **On NewOrderSingle (MsgType=D)**:
  1. Parse `Symbol` (Tag 55).
  2. Call `router.getShardId(symbol)`.
  3. Select the `Publication` corresponding to that Shard ID.
  4. Encode message to SBE and offer to the selected Publication.

- **On OrderCancelRequest (MsgType=F)**:
  1. Parse `Symbol` (Tag 55).
  2. Route to the appropriate Shard.

### 3. Matching Engine (`MatchingEngine`)

The `MatchingEngine` has been refactored to support multiple instruments within a single process (though typically one Shard handles a subset).

- **Multi-Book Support**: Uses `Int2ObjectHashMap<OffHeapOrderBook>` to store order books keyed by `instrumentId`.
- **Routing**: SBE messages contain `instrumentId`. The engine looks up the correct order book and matches the order.
- **Benefits**: Allows a single Shard to handle *multiple* instruments if needed (e.g., Shard 0 handles AAPL and MSFT).

## Configuration

### Shard Setup (MVP)

The current MVP setup assumes:
- **Number of Shards**: 2
- **Aeron Channel**: `aeron:ipc` (Shared Memory)
- **Stream IDs**:
  - Shard 0: Stream 1001
  - Shard 1: Stream 1002

### Adding Instruments

To add new instruments and assign them to shards:
1. Update `InstrumentRouter.createDefault()` or load from a config file.
2. Ensure the `MatchingEngine` on the target Shard is initialized with the corresponding `OffHeapOrderBook`s (currently dynamic or pre-provisioned).

## Future Improvements

- **Dynamic Shard Discovery**: Use Aeron Directory or a separate discovery service to find Shard streams.
- **Resharding**: Mechanism to move instruments between shards (requires state transfer).
- **Failure Handling**: If a Shard is down, the FIX Gateway should reject orders for that Shard's instruments immediately.
