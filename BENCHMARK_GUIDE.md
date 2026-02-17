# Chronos Benchmark Guide

## 1. Wire-to-Wire Benchmark (`WireToWireBenchmark`)
This benchmark measures end-to-end latency using `HdrHistogram` directly.

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
JMH benchmarks default to showing average time or throughput. To see **latency distributions** (histograms), you must change the benchmark mode.

### Running for Latency Distribution
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
