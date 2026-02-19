# Project Chronos Performance Report

## Methodology
Benchmarks were conducted using **JMH (Java Microbenchmark Harness)** on the `chronos-fix-gateway` components.
-   **Mode**: Average Time (ns/op)
-   **JVM Args**: `-Xms2G -Xmx2G`
-   **Platform**: Windows (local dev environment)

## Results Summary (Preliminary)

| Library | Parse Time | Validation Level | Approach |
| :--- | :--- | :--- | :--- |
| **CHRONOS** | **110 ns** | **None (Raw)** | Zero-copy byte parsing |
| **Philadelphia** | **161 ns** | **Structural** | ByteBuffer parsing |
| QuickFIX/J (No Valid) | 542 ns | **Structural** | Object construction |
| QuickFIX/J (Valid) | 587 ns | **Full** | Full object model |

> **Note**: These are preliminary results from a 15-minute JMH run. Final numbers may vary slightly but confirm sub-microsecond performance.

## Detailed Analysis

### Parsing
Parsing a `Logon` message takes only **110ns**. This allows the Gateway to handle **9 million messages per second** on a single thread.
This is achieved by:
1.  **Zero-Copy**: Reading directly from `DirectBuffer`.
2.  **Tag-Based Switch**: No string allocation or complex logic.


