# GC Selection Guide for Project Chronos

## Overview

This guide helps you select the optimal Garbage Collector (GC) for your Chronos deployment based on workload characteristics and operational requirements.

---

## Quick Decision Tree

```
Is your workload truly zero-allocation?
├─ YES → Use Epsilon GC (no-op GC)
│   └─ Requires: Heap sizing based on uptime, monitoring
│
└─ NO → Do you need predictable low latency?
    ├─ YES → Use ZGC (low-pause concurrent GC)
    │   └─ Best for: Long-running services, production deployments
    │
    └─ NO → Do you prioritize throughput over latency?
        ├─ YES → Use Parallel GC
        │   └─ Best for: Batch processing, high-throughput scenarios
        │
        └─ NO → Use G1GC (default balanced GC)
            └─ Best for: General-purpose workloads

```

---

## GC Configurations

### 1. Epsilon GC (No-Op Garbage Collector)

**Use When:**
- Hot path is truly zero-allocation (verified via JFR)
- Short-lived processes (benchmarks, warmup runners)
- You can size heap based on maximum uptime

**JVM Flags:**
```bash
-XX:+UnlockExperimentalVMOptions
-XX:+UseEpsilonGC
-Xms4g -Xmx4g  # Fixed heap size
```

**Pros:**
- Zero GC overhead (no pauses at all)
- Lowest possible latency
- Best for benchmarking true performance

**Cons:**
- ⚠️ **No garbage collection** - heap fills up until OOM
- Requires precise heap sizing
- Not suitable for long-running services with any allocation

**Heap Sizing Formula:**
```
Required Heap = (Allocation Rate × Uptime) + Working Set Size

Example:
- Allocation rate: 1 MB/sec (from JFR)
- Uptime: 60 seconds (benchmark duration)
- Working set: 100 MB (order book + buffers)
→ Heap = (1 MB/s × 60s) + 100 MB = 160 MB
→ Set -Xmx256m (with safety margin)
```

**Recommended For:**
- `chronos-benchmarks` (JMH benchmarks, wire-to-wire tests)
- `chronos-warmup` (JIT training runs)

---

### 2. ZGC (Z Garbage Collector)

**Use When:**
- Production deployment with long uptime
- Need consistent low latency (P99.99 < 1ms GC pauses)
- Some allocation is acceptable (e.g., logging, metrics)

**JVM Flags:**
```bash
# ZGC with Generational mode (JDK 21+)
-XX:+UseZGC
-XX:+ZGenerational
-Xms8g -Xmx8g  # Fixed heap recommended
-XX:SoftMaxHeapSize=6g  # Soft limit for proactive GC
```

**Pros:**
- Sub-millisecond pause times (typically < 1ms)
- Scales to multi-TB heaps
- Concurrent collection (minimal STW pauses)
- Generational mode improves throughput (JDK 21+)

**Cons:**
- Higher CPU overhead (~10-15% for GC threads)
- Slightly higher memory footprint
- Requires JDK 15+ (generational requires JDK 21+)

**Tuning Tips:**
- Use fixed heap size (`-Xms = -Xmx`) for predictable behavior
- Set `SoftMaxHeapSize` to 75% of max heap for proactive GC
- Monitor with `-Xlog:gc*:file=gc.log:time,uptime,level,tags`

**Recommended For:**
- `chronos-sequencer` (Aeron Cluster service)
- `chronos-fix-gateway` (FIX session management)
- `chronos-response-gateway` (execution report routing)

---

### 3. Parallel GC

**Use When:**
- Throughput is more important than latency
- Batch processing or offline analysis
- Can tolerate occasional multi-millisecond pauses

**JVM Flags:**
```bash
-XX:+UseParallelGC
-Xms4g -Xmx4g
-XX:ParallelGCThreads=8  # Number of GC threads
```

**Pros:**
- Highest throughput (least CPU overhead)
- Simple and predictable
- Good for high-allocation workloads

**Cons:**
- Stop-the-world pauses (can be 10-100ms)
- Not suitable for latency-sensitive workloads

**Recommended For:**
- Offline analysis tools
- Data migration scripts
- Non-latency-critical batch jobs

---

### 4. G1GC (Garbage-First Garbage Collector)

**Use When:**
- General-purpose workload
- Unsure about allocation patterns
- Need balanced latency and throughput

**JVM Flags:**
```bash
-XX:+UseG1GC
-Xms4g -Xmx4g
-XX:MaxGCPauseMillis=200  # Target pause time
-XX:G1HeapRegionSize=16m  # Tune based on heap size
```

**Pros:**
- Balanced latency and throughput
- Predictable pause times (target-based)
- Default in JDK 9+

**Cons:**
- Higher pause times than ZGC (typically 10-200ms)
- More complex tuning than Parallel GC

**Recommended For:**
- Development and testing
- Services with mixed workloads
- When ZGC is not available (JDK < 15)

---

## Chronos-Specific Recommendations

### Production Deployment (Recommended)

**Sequencer & Matching Engine:**
```bash
java -XX:+UseZGC \
     -XX:+ZGenerational \
     -Xms8g -Xmx8g \
     -XX:SoftMaxHeapSize=6g \
     --add-modules jdk.incubator.vector \
     -jar chronos-sequencer.jar
```

**FIX Gateway:**
```bash
java -XX:+UseZGC \
     -XX:+ZGenerational \
     -Xms4g -Xmx4g \
     -XX:SoftMaxHeapSize=3g \
     -jar chronos-fix-gateway.jar
```

---

### Benchmarking (Epsilon GC)

**Wire-to-Wire Benchmark:**
```bash
java -XX:+UnlockExperimentalVMOptions \
     -XX:+UseEpsilonGC \
     -Xms512m -Xmx512m \
     --add-modules jdk.incubator.vector \
     -jar chronos-benchmarks.jar
```

**JMH Benchmarks:**
```bash
./gradlew :chronos-benchmarks:jmh \
  -Pjmh.jvmArgs="-XX:+UnlockExperimentalVMOptions,-XX:+UseEpsilonGC,-Xms256m,-Xmx256m"
```

---

### Development (G1GC - Default)

**No special flags needed** - G1GC is the default in JDK 9+:
```bash
./gradlew :chronos-sequencer:run
```

---

## Verification & Monitoring

### Verify Zero-Allocation (for Epsilon GC)

**Step 1: Run with JFR**
```bash
java -XX:StartFlightRecording=filename=chronos.jfr,settings=profile \
     -XX:+UnlockExperimentalVMOptions \
     -XX:+UseEpsilonGC \
     -Xms512m -Xmx512m \
     -jar chronos-benchmarks.jar
```

**Step 2: Analyze JFR**
```bash
# Open in JDK Mission Control or use jfr CLI
jfr print --events jdk.ObjectAllocationInNewTLAB chronos.jfr

# Expected output: Zero allocations in hot path
```

### Monitor GC Performance

**Enable GC Logging:**
```bash
-Xlog:gc*:file=gc.log:time,uptime,level,tags
```

**Key Metrics to Monitor:**
- **Pause Time**: P99.99 should be < 1ms for ZGC, < 10ms for G1GC
- **GC Frequency**: Should be infrequent (< 1/sec for ZGC)
- **Heap Usage**: Should stay below 75% to avoid aggressive GC

**Prometheus Metrics** (if using Micrometer):
```
jvm_gc_pause_seconds{gc="ZGC"}
jvm_gc_memory_allocated_bytes_total
jvm_memory_used_bytes{area="heap"}
```

---

## Troubleshooting

### Epsilon GC: OutOfMemoryError

**Symptom:** `java.lang.OutOfMemoryError: Java heap space`

**Cause:** Heap too small or unexpected allocations

**Solution:**
1. Increase heap size: `-Xmx1g` → `-Xmx2g`
2. Run JFR to find allocation sources
3. Eliminate allocations or switch to ZGC

### ZGC: High CPU Usage

**Symptom:** GC threads consuming 10-15% CPU

**Cause:** Normal for ZGC (concurrent collection)

**Solution:**
- This is expected behavior
- If unacceptable, consider Parallel GC for throughput
- Tune `SoftMaxHeapSize` to reduce GC frequency

### G1GC: Long Pause Times

**Symptom:** GC pauses > 200ms

**Cause:** Heap too small or aggressive pause target

**Solution:**
1. Increase heap size
2. Adjust `-XX:MaxGCPauseMillis=500` (more realistic target)
3. Consider switching to ZGC

---

## Performance Impact Summary

| GC Type | Pause Time (P99.99) | CPU Overhead | Memory Overhead | Best For |
|---------|---------------------|--------------|-----------------|----------|
| **Epsilon** | 0 µs | 0% | Minimal | Benchmarks, short-lived |
| **ZGC** | < 1 ms | 10-15% | +10-20% | Production, low-latency |
| **Parallel** | 10-100 ms | 2-5% | Minimal | Throughput, batch |
| **G1GC** | 10-200 ms | 5-10% | Moderate | General-purpose |

---

## Next Steps

1. **Verify zero-allocation** with JFR before using Epsilon GC
2. **Start with ZGC** for production deployments
3. **Run benchmarks** with Epsilon GC to measure true performance
4. **Monitor GC logs** and adjust based on actual behavior

**After completing all 7 architectural improvements, run the full benchmark suite to measure actual GC impact.**
