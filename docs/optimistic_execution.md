# Optimistic Execution Design

This document outlines the design for "Optimistic Execution" in Chronos to minimize perceived latency for clients by speculatively confirming orders before the global consensus (Raft) is fully committed.

## Problem Statement

In the standard Raft architecture:
1. Client sends order -> Gateway
2. Gateway -> Aeron Cluster (Leader)
3. Leader sequences -> Appends to Log -> Replicates to Followers
4. **Consensus Achieved**
5. Leader processes (Matching Engine)
6. Leader sends Execution Report -> Gateway -> Client

Steps 2-4 introduce "Consensus Latency" (typically 20-100µs depending on network/config). For ultra-low latency trading, this round-trip is significant.

## Proposed Solution: Gateway Shadow Matching

We propose pushing a "Shadow Matching Engine" to the FIX Gateway.

### Architecture

1.  **Shadow Order Book**: The Gateway maintains a local `OffHeapOrderBook` for each instrument, updated via the Cluster's broadcast stream (MDS).
2.  **Speculative Match**:
    *   When an order arrives, the Gateway validates it.
    *   It *immediately* runs the order against its local Shadow Book.
    *   If a match is found (e.g., Buy vs Sell at $100), the Gateway sends a **Tentative Execution Report** (MsgType=8, ExecType=F - Trade) to the Client.
3.  **Async Commit**:
    *   The Gateway forwards the order to the Cluster.
    *   The Cluster (the "Source of Truth") processes the order.
4.  **Reconciliation**:
    *   **Success**: The Cluster sends an Execution Report which matches the speculative one. The Gateway swallows it (or confirms it).
    *   **Failure (Race Condition)**: If the shadow liquidity was taken by another order (seq num race), the Cluster sends a different result (e.g., specific price missed, or partial fill).
    *   **Correction**: The Gateway must send a **Trade Correct** or **Trade Bust** (ExecType=G/H) to the client.

### Risks & Mitigations

*   **Risk**: Client acts on tentative fill (e.g., hedges) but the trade is busted.
    *   *Mitigation*: Clients must opt-in to "Optimistic" mode and implement Trade Bust handling.
*   **Risk**: Shadow Book drift.
    *   *Mitigation*: Gateway must process the cluster output stream to keep the Shadow Book synchronized.

## Implementation Phases

### Phase 1: Local Pre-Validation (Completed)
- Strict validation at Gateway to prevent "Poison Pills" from hitting the Cluster.

### Phase 2: Pending New Acceleration
- Gateway sends `ExecType=PendingNew` (A) immediately upon passing validation, before sending to Cluster.
- Reduces "Order Ack" latency to near-zero (~5-10µs).

### Phase 3: Shadow Matching (Complex)
- Implement `ShadowMatchingEngine` in Gateway.
- Requires reliable multicast of matching events from Cluster to all Gateways.

## Recommended Hot Path Optimizations (Non-Speculative)

Before full optimistic execution, we should optimize the deterministic path:

1.  **Zero-Copy Ingress**: Ensure `FixSession` writes directly to Aeron's `Publication` buffer (using `tryClaim`). *Current implementation uses `offer` with a copy.*
2.  **Batching**: If multiple orders arrive in one TCP packet, batch them into one Aeron frame if small enough, or offer back-to-back without flushing.
3.  **Read-Read-Write concurrency**: If using Raft, specialized "Read-Only" queries (e.g. "Get Order Status") can be linearizable without a log entry (Optimization).

## Decision for MVP

For the current Chronos MVP:
1.  Implement **Phase 2 (Pending New Acceleration)**. It provides immediate feedback without the complex rollback logic of Phase 3.
2.  Refactor `FixSession` to use `tryClaim` for Ingress.

