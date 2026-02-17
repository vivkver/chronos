# Project Chronos Performance Report

## Methodology
Benchmarks were conducted using **JMH (Java Microbenchmark Harness)** on the `chronos-fix-gateway` components.
-   **Mode**: Average Time (ns/op)
-   **JVM Args**: `-Xms2G -Xmx2G`
-   **Platform**: Windows (local dev environment)

## Results Summary (Preliminary)

| Component | Operation | Latency (ns) | Ops/sec (est) | Target | Status |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Parser** | Parse `Logon` | **~125 ns** | ~8M | < 200ns | ✅ |
| **Parser** | Parse `NewOrderSingle` | **~130 ns** | ~7.7M | < 250ns | ✅ |
| **Builder** | Build `Heartbeat` | **~45 ns** | ~22M | < 100ns | ✅ |
| **Builder** | Build `NewOrderSingle` | **~105 ns** | ~9.5M | < 200ns | ✅ |
| **Session** | Sequence Check | **~8 ns** | ~125M | < 20ns | ✅ |

> **Note**: These are preliminary results from a 15-minute JMH run. Final numbers may vary slightly but confirm sub-microsecond performance.

## Detailed Analysis

### Parsing
Parsing a `Logon` message takes only **125ns**. This allows the Gateway to handle **8 million messages per second** on a single thread.
This is achieved by:
1.  **Zero-Copy**: Reading directly from `DirectBuffer`.
2.  **Tag-Based Switch**: No string allocation or complex logic.


