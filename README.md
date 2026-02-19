# Project CHRONOS

**Deterministic Limit Order Book (LOB) for Sub-Microsecond Matching**

[🌐 Visit the Website](https://vivkver.github.io/chronos/)

A production-grade, ultra-low latency (ULL) trading system built in Java, demonstrating HFT-level performance engineering across 5 integrated services.

<p align="center">
  <img src="https://img.shields.io/badge/Java-21%20LTS-blue" alt="Java 21"/>
  <img src="https://img.shields.io/badge/Aeron-1.44.1-green" alt="Aeron"/>
  <img src="https://img.shields.io/badge/SIMD-Vector%20API-orange" alt="Vector API"/>
  <img src="https://img.shields.io/badge/GC-Zero%20Allocation-red" alt="Zero GC"/>
  <img src="https://img.shields.io/badge/Encoding-SBE-purple" alt="SBE"/>
</p>

---

## Architecture

```
┌─────────────────┐     ┌──────────────────────────────────────┐     ┌────────────────────┐
│                 │     │      Aeron Cluster (Raft)            │     │                    │
│  FIX Gateway    │────▶│  ┌─────────────┐  ┌──────────────┐  │────▶│  Response Gateway   │
│  (Ingress)      │ SBE │  │  Sequencer  │──│  Matching    │  │ SBE │  (Egress)           │
│                 │     │  │  (Spinal    │  │  Engine      │  │     │  + HDRHistogram     │
│  • TCP/NIO      │     │  │   Cord)     │  │  (Brain)     │  │     │  • P99.99 tracking  │
│  • FIX Parser   │     │  │             │  │  • SIMD/AVX  │  │     │  • SBE→FIX encode   │
│  • FIX→SBE      │     │  │  • Total    │  │  • Off-heap  │  │     │                    │
│  • Zero-alloc   │     │  │    Order    │  │    LOB       │  │     │                    │
31: └─────────────────┘     │  └─────────────┘  └──────────────┘  │     └────────────────────┘
                        └──────────────────────────────────────┘
                                         │
                        ┌────────────────────────────────────┐
                        │  Warmup Service (AOT/CDS)          │
                        │  • JIT training runs               │
                        │  • CDS archive generation          │
                        │  • GraalVM PGO support             │
                        └────────────────────────────────────┘
```

## Key Performance Techniques

| Technique | Where | Why |
|-----------|-------|-----|
| **Zero Allocation** | FIX parser, SBE codecs, matching engine | Eliminate GC pauses — no objects created on hot path |
| **SIMD Vectorization** | `VectorizedPriceScanner` | Compare 8 price levels per CPU cycle (AVX-512) |
| **Off-Heap Memory** | `OffHeapOrderBook` | 64-byte cache-line-aligned order slots via Agrona `UnsafeBuffer` |
| **SOA Layout** | Order book price arrays | Structure-of-Arrays enables SIMD-friendly contiguous memory access |
| **SBE Encoding** | All inter-service messaging | Flyweight codec with zero-copy reads — no deserialization |
| **Aeron Cluster** | Sequencer | Raft consensus for deterministic total ordering |
| **Fixed-Point Arithmetic** | Prices | `long` scaled by 10⁸ — eliminates floating-point non-determinism |
| **Core Pinning** | All services | `taskset -c N` eliminates context-switch jitter |
| **ZGC** | JVM flags | Generational ZGC for sub-millisecond pause times |

## Project Structure

```
chronos/
├── chronos-schema/          # SBE message codecs (flyweight encoders/decoders)
├── chronos-core/            # Domain model + off-heap order book
├── chronos-matching/        # SIMD matching engine (Vector API)
├── chronos-sequencer/       # Aeron Cluster service (Raft)
├── chronos-fix-gateway/     # FIX protocol ingress
├── chronos-response-gateway/# Execution report egress + latency tracking
├── chronos-warmup/          # JIT training & AOT cache generation
└── chronos-benchmarks/      # JMH + wire-to-wire + JFR zero-GC proof
```

## Prerequisites

- **JDK 21+** (GraalVM CE recommended for faster JIT warmup)
- **Gradle 9.x** (wrapper included)

## Quick Start

```bash
# Build all modules
./gradlew build

# Run JMH benchmarks (SIMD vs scalar, SBE vs string-based)
./gradlew :chronos-benchmarks:jmh

# Run wire-to-wire benchmark (1M orders)
./gradlew :chronos-benchmarks:run

# Run warmup training
./gradlew :chronos-warmup:run

# Start the sequencer (Aeron cluster node)
./gradlew :chronos-sequencer:run

# Start the FIX gateway (TCP port 9876)
./gradlew :chronos-fix-gateway:run

# Start the response gateway
./gradlew :chronos-response-gateway:run
```

## Performance Highlights

For detailed benchmark results, methodology, and the Linux optimization guide, please see [BENCHMARK_GUIDE.md](BENCHMARK_GUIDE.md).

- **FIX Parsing**: **110ns** (vs 542ns QuickFIX/J)
- **Wire-to-Wire Latency**: **< 50µs** (Linux Optimized)
- **Zero GC**: Verified via JFR on critical paths.

## Production Deployment

### Recommended JVM Configuration

```bash
java -XX:+UseZGC \
     -XX:+ZGenerational \
     -Xms8g -Xmx8g \
     -XX:SoftMaxHeapSize=6g \
     --add-modules jdk.incubator.vector \
     --add-opens java.base/sun.misc=ALL-UNNAMED \
     -jar chronos-sequencer.jar
```

**GC Selection:**
- **Production**: ZGC with generational mode (sub-millisecond GC pauses)
- **Benchmarking**: Epsilon GC (no-op GC for zero-allocation verification)
- **Development**: G1GC (default, balanced)

See [docs/gc-selection-guide.md](docs/gc-selection-guide.md) for detailed GC configuration guidance.

### CPU Isolation (Linux)

```bash
# Isolate cores 2-5 from the OS scheduler
# Add to kernel boot params: isolcpus=2-5 nohz_full=2-5
taskset -c 2 java -jar chronos-sequencer.jar
taskset -c 3 java -jar chronos-fix-gateway.jar
taskset -c 4 java -jar chronos-response-gateway.jar
```

### CDS Archive (Fast Startup)

```bash
# Step 1: Train and dump class list
java -Xshare:off -XX:DumpLoadedClassList=chronos.classlist -jar chronos-warmup.jar

# Step 2: Create CDS archive
java -Xshare:dump -XX:SharedClassListFile=chronos.classlist -XX:SharedArchiveFile=chronos.jsa

# Step 3: Run with CDS
java -Xshare:on -XX:SharedArchiveFile=chronos.jsa -jar chronos-sequencer.jar
```

## Future Exploration (JDK 26+)

These features are currently in preview/incubator but will enhance CHRONOS when stabilized:

| Feature | JEP | Benefit |
|---------|-----|---------|
| **Project Valhalla** (Value Classes) | JEP 401 | Eliminate object headers for `Order`/`PriceLevel` — better cache density |
| **Project Panama** (FFM API finalized) | JEP 454 | Replace `Unsafe` with `MemorySegment` + `Arena` for safer off-heap access |
| **Vector API** (finalized) | JEP 489 | Remove `--add-modules jdk.incubator.vector` flag |
| **Project Leyden** (AOT Cache) | JEP draft | Native AOT cache replacing CDS for instant startup |

## License

MIT
