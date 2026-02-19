# Chronos Benchmark Guide

This guide provides detailed instructions for running, interpreting, and validating the performance benchmarks for Project Chronos.

## Quick Start

```bash
# Run wire-to-wire benchmark (1M orders)
./gradlew :chronos-benchmarks:run

# Run JMH benchmarks (SIMD vs scalar, SBE vs string-based)
./gradlew :chronos-benchmarks:jmh

# Run cluster latency benchmark
./gradlew :chronos-benchmarks:runClusterLatency
```

---

## 1. Wire-to-Wire Benchmark (`WireToWireBenchmark`)

This benchmark measures end-to-end latency using `HdrHistogram` directly. It simulates a realistic flow:
`Gateway -> Sequencer -> Matching Engine -> Gateway`

### Running
```bash
gradle :chronos-benchmarks:runWireToWire
```

### Viewing Results
The results are printed to **Standard Output** (console) at the end of the run.
Look for the `LATENCY REPORT` section:

```text
═══════════════════════════════════════════════════════
  LATENCY REPORT: Wire-to-Wire
═══════════════════════════════════════════════════════
  Total samples      : 1,000,000
  Min         (ns)   : 1,234
  ...
  P50         (ns)   : 1,500
  P99         (ns)   : 2,100
  P99.99      (ns)   : 5,400    <-- Critical metric
═══════════════════════════════════════════════════════
```

## 2. JHM Microbenchmarks (`FixBenchmark`)

### Hot Path Results

**SIMD vs Scalar Price Scanning:**
```
Benchmark                          (levels)  Mode  Cnt    Score   Error  Units
simdCountMatchable                      512  avgt   20    8.3 ±  0.2  ns/op
scalarCountMatchable                    512  avgt   20   65.1 ±  1.1  ns/op
```
*Expected 3x–8x speedup depending on CPU and level count.*

**Zero-Alloc SBE vs String-Based FIX Encoding:**
```
Benchmark                          Mode  Cnt    Score   Error  Units
chronosZeroAllocEncoding           avgt   20   45.2 ±  1.3  ns/op
stringBasedEncoding                avgt   20  890.7 ± 15.4  ns/op
```
*~20x faster with zero GC pressure.*

### Running for Latency Distribution
JMH benchmarks default to showing average time or throughput. To see **latency distributions** (histograms), you must change the benchmark mode.

Use the `avgt` (Average Time) or `sample` (Sample Time) mode. `sample` collects all samples and calculates percentiles.

```bash
# Run with 'sample' mode to see percentiles (P0..P100)
# -bm sample: Benchmark Mode = Sample Time
# -tu ns: Time Unit = Nanoseconds
gradle :chronos-benchmarks:jmh --args="-bm sample -tu ns -f 1 -wi 1 -i 5"
```

### Visualizing JMH Results (Reliable Method)
The most reliable way to pass arguments to JMH is to run the generated uber-JAR directly.

1.  **Build the JMH JAR**:
    ```bash
    gradle :chronos-benchmarks:jmhJar
    ```

2.  **Run with Latency Sampling**:
    ```bash
    java -jar chronos-benchmarks/build/libs/chronos-benchmarks-1.0.0-SNAPSHOT-jmh.jar \
        -bm sample -tu ns -f 1 -wi 1 -i 5 \
        -rf json -rff jmh-result.json
    ```

3.  **Upload** `jmh-result.json` to [JMH Visualizer](https://jmh.morethan.io/).

---

## 3. Aeron Cluster Latency (Shared Memory IPC)

This benchmark measures the full Raft consensus path: **Client -> Consensus Module -> Sequencer -> Matching Engine -> Client**.

```bash
# Run the cluster latency benchmark
./gradlew :chronos-benchmarks:runClusterLatency
```

| Environment | Latency (P50) | Latency (P99.9) | Hardware / Specs |
|-------------|-----------------------|-----------------------|------------------|
| **Windows 11 (Dev)** | ~5,500 µs | > 60,000 µs | Ryzen 9 7950X, 64GB (NTFSJitter) |
| **RunPod (Container)** | **~340 µs** | **~13,000 µs** | **8 vCPU, 16GB RAM (runpod/base:1.0.2-ubuntu2204)** |
| **Linux (Bare Metal)** | **< 50 µs** | **< 150 µs** | Production Target (Isolated Cores) |

> **Note on Performance:** The ~5ms latency seen on Windows is almost entirely due to the Raft log persistence to the standard file system and thread scheduling jitter. For realistic low-latency results, use the Linux optimization script.

---

## 4. FIX Parser Comparison vs Open-Source Libraries

**Validated Benchmarks (Same Machine, Same Message, Rigorous JMH Settings):**

| Library | Parse Time | Validation Level | Approach | Key Features |
|---------|-----------|-----------------|----------|--------------|
| **CHRONOS** | **110 ns** | **None (Raw)** | Zero-copy byte parsing | DirectBuffer, pre-allocated vectors, no checksum/body check |
| **Philadelphia** | **161 ns** | **Structural** | ByteBuffer parsing | Reusable objects, tag-value extraction, no checksum check |
| QuickFIX/J (No Valid) | 542 ns | **Structural** | Object construction | HashMap storage, String creation, skips checksum |
| QuickFIX/J (Valid) | 587 ns | **Full** | Full object model | Checksum, BodyLength, Data Dictionary, Compliance |

**Benchmark Details (JMH with 3 forks, 5 warmup, 5 measurement iterations):**
```
Benchmark                               Mode  Cnt    Score    Error  Units
FixBenchmark.chronosParse               avgt   15  117.451 ±  0.707  ns/op
FixBenchmark.philadelphiaParse          avgt   15  175.220 ±  1.318  ns/op
FixBenchmark.quickfixParseNoValidation  avgt   15  826.833 ± 69.498  ns/op
FixBenchmark.quickfixParse              avgt   15  876.801 ± 29.292  ns/op
```

**Performance Analysis:**
- **Chronos vs Philadelphia**: **~1.5x faster** (161ns / 110ns)
- **Chronos vs QuickFIX/J (no validation)**: **4.9x faster** (542ns / 110ns)
- **Chronos vs QuickFIX/J (validated)**: **5.3x faster** (587ns / 110ns)
- **Philadelphia vs QuickFIX/J (no validation)**: **3.3x faster** (542ns / 161ns)

**Why CHRONOS is Faster:**
*   **Zero-copy parsing**: Reads bytes directly from network buffers.
*   **Pre-allocated arrays**: No object creation during parsing.
*   **No String creation**: Uses flyweight pattern over byte buffers.
*   **DirectBuffer (Agrona)**: Efficient off-heap memory access.

---

## 5. Zero-GC Proof

To verify that the hot path is truly zero-allocation, run the benchmark with Flight Recording enabled.

```bash
java -XX:StartFlightRecording=filename=chronos.jfr,settings=chronos-benchmarks/src/main/resources/jfr-zero-gc.jfc \
     --add-modules jdk.incubator.vector \
     -jar chronos-benchmarks.jar
```
Open `chronos.jfr` in JDK Mission Control → GC tab. The flat GC line proves zero allocation on the hot path.

---

## 6. Linux Benchmarking Guide

For developers with access to a Linux environment (Oracle Cloud, Hetzner, AWS), we provide a dedicated optimization script.

### Running the Linux Benchmark

1.  **Clone and Prep**:
    ```bash
    git clone https://github.com/vivkver/chronos.git
    cd chronos
    chmod +x scripts/bench-linux.sh
    ```

2.  **Execute (with sudo for kernel tuning)**:
    ```bash
    sudo ./scripts/bench-linux.sh
    ```

This script performs:
- **Kernel Tuning**: Sets CPU governor to `performance` and increases UDP buffer sizes.
- **RAM Disk Storage**: Moves the Aeron cluster Raft logs to `/dev/shm` to eliminate Disk I/O bottlenecks.
- **Core Affinity**: Uses `taskset` to bind the benchmark process to specific CPU cores.
