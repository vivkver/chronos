# Project Chronos - System Architecture

## Overview
Chronos is a high-performance, low-latency Limit Order Book (LOB) exchange designed for deterministic execution using the **Aeron Cluster** framework.

## High-Level Architecture

```mermaid
graph TD
    Client[FIX Client] -->|TCP/FIX 4.4| Gateway[FIX Gateway]
    Gateway -->|Aeron IPC (SBE)| Sequencer[Aeron Cluster Leader]
    Sequencer -->|Replicated Log| Follower[Cluster Follower]
    Sequencer -->|Deterministic Stream| Matcher[Matching Engine]
    Matcher -->|Execution Reports (SBE)| Gateway
    Gateway -->|FIX 4.4| Client
```

## Components

### 1. FIX Gateway (`chronos-fix-gateway`)
-   **Role**: Connectivity endpoint for clients.
-   **Responsibilities**:
    -   Manage TCP connections (NIO Selector).
    -   Handle FIX Session logic (Logon, Heartbeats, Resend).
    -   **Zero-Copy Parsing**: Parses FIX tags directly from `DirectBuffer`.
    -   **SBE Encoding**: Translates `NewOrderSingle` to Simple Binary Encoding (SBE) for the cluster.
-   **Key Pattern**: Single-threaded event loop pinned to a CPU core.

### 2. Core Domain (`chronos-core`)
-   **Role**: Shared domain objects and primitives.
-   **Key Components**:
    -   `OrderType`, `Side`, `TimeInForce`: Enums backed by `byte` values for SBE mapping.
    -   `OffHeapOrderBook`: Memory-efficient data structure for storing orders.

### 3. Matching Engine (`chronos-matching`)
-   **Role**: Core business logic.
-   **Responsibilities**:
    -   Price-Time Priority matching.
    -   Deterministic execution (relies on Cluster Timestamp).
    -   **Zero-Allocation**: No object creation in the hot path (`matchOrder`).
-   **Data Structures**:
    -   `OffHeapOrderBook`: Stores orders in `ByteBuffer` (simulated off-heap) to reduce GC pressure.
    -   `PriceScanner`: SIMD-friendly price level iteration.

### 4. Sequencer (`chronos-sequencer`)
-   **Role**: Consensus and ordering.
-   **Responsibilities**:
    -   Aeron Cluster service container.
    -   Persists incoming commands to the Raft log.
    -   Replays commands to the `MatchingEngine` to ensure identical state across replicas.

## Architectural Decision Records (ADR)

### ADR-001: Simple Binary Encoding (SBE) over Protobuf
-   **Decision**: Use SBE for internal communication.
-   **Context**: We need deterministic, ultra-low latency serialization.
-   **Consequences**: No object allocation during serde; strict schema evolution rules.

### ADR-002: Off-Heap Memory for Order Book
-   **Decision**: Store orders in `UnsafeBuffer` / direct `ByteBuffer`.
-   **Context**: Java GC pauses are unacceptable for low-latency trading.
-   **Consequences**: Manual memory management; complex debugging; zero GC pauses.

### ADR-003: Single-Threaded Gateway
-   **Decision**: Use a single thread for all non-blocking I/O.
-   **Context**: Context switching allows for lower latency than thread-per-connection models.
-   **Consequences**: Operations must never block; one slow client can affect others (mitigated by specialized handling).
