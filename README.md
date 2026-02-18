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
└─────────────────┘     │  └─────────────┘  └──────────────┘  │     └────────────────────┘
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
- **Gradle 8.x** (wrapper included)

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

## Benchmarks

### 1. Hot Path Microbenchmarks (JMH)

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

#### 2. Wire-to-Wire Benchmark (Actual Run)

```text
======================================================
  LATENCY REPORT: Wire-to-Wire
======================================================
  Total samples      : 1,000,000
  Mean        (ns)   : 2,336.4
  StdDev      (ns)   : 2,579.9
------------------------------------------------------
  P50         (ns)   : 2,601  (2.6 µs)
  P99         (ns)   : 9,807  (9.8 µs)
  P99.99      (ns)   : 41,631 (41.6 µs)
======================================================
```
*Note: This benchmark was performed on a single machine using shared memory IPC via Aeron. P99.99 achieved 41.6µs without core pinning. Production target is < 10µs.*

---

## Production Deployment

**Recommended JVM Configuration:**
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

---

### 3. FIX Parser Comparison vs Open-Source Libraries

**Validated Benchmarks (Same Machine, Same Message, Rigorous JMH Settings):**

| Library | Parse Time | Approach | Allocation | Key Features |
|---------|-----------|----------|------------|--------------|
| **CHRONOS** | **117 ns** | Zero-copy byte parsing | **Zero** | DirectBuffer, pre-allocated arrays, no validation |
| **Philadelphia** | **175 ns** | ByteBuffer parsing | **Low** | Reusable FIXMessage object, non-blocking I/O optimized |
| QuickFIX/J (no validation) | 827 ns | Object construction | High | HashMap storage, String creation, skip validation |
| QuickFIX/J (validated) | 877 ns | Full object model | High | Complete validation, data dictionary, FIX compliance |

**Benchmark Details (JMH with 3 forks, 5 warmup, 5 measurement iterations):**
```
Benchmark                               Mode  Cnt    Score    Error  Units
FixBenchmark.chronosParse               avgt   15  117.451 ±  0.707  ns/op
FixBenchmark.philadelphiaParse          avgt   15  175.220 ±  1.318  ns/op
FixBenchmark.quickfixParseNoValidation  avgt   15  826.833 ± 69.498  ns/op
FixBenchmark.quickfixParse              avgt   15  876.801 ± 29.292  ns/op
```

**Performance Analysis:**
- **Chronos vs Philadelphia**: **1.5x faster** (175ns / 117ns)
- **Chronos vs QuickFIX/J (no validation)**: **7.0x faster** (827ns / 117ns)
- **Chronos vs QuickFIX/J (validated)**: **7.5x faster** (877ns / 117ns)
- **Philadelphia vs QuickFIX/J (no validation)**: **4.7x faster** (827ns / 175ns)

**Why CHRONOS is Faster:**

| Technique | CHRONOS | Philadelphia | QuickFIX/J |
|-----------|---------|--------------|------------|
| Zero-copy parsing | ✅ | ⚠️ Partial | ❌ |
| Pre-allocated arrays | ✅ | ✅ | ❌ |
| No String creation | ✅ | ✅ | ❌ |
| DirectBuffer (Agrona) | ✅ | ❌ | ❌ |
| No validation overhead | ✅ | ✅ | Optional |
| SIMD-friendly layout | ✅ | ❌ | ❌ |
| Fixed-point arithmetic | ✅ | ❌ | ❌ |

**Trade-offs:**
- **CHRONOS**: Ultra-low latency (117ns), zero GC, but requires manual validation at business logic layer
- **Philadelphia**: Low latency (175ns), minimal allocation, good balance of performance and usability
- **QuickFIX/J**: Full FIX compliance, easy to use, data dictionary support, but higher latency (827-877ns) and GC pressure

**Other High-Performance FIX Libraries** *(not benchmarked in this project)*:
- **Chronicle FIX** - Commercial low-GC design with persistence
- **Falcon** - Open-source ultra-low latency with zero-heap RX/TX paths

### 4. Zero-GC Proof

```bash
java -XX:StartFlightRecording=filename=chronos.jfr,settings=chronos-benchmarks/src/main/resources/jfr-zero-gc.jfc \
     --add-modules jdk.incubator.vector \
     -jar chronos-benchmarks.jar
```
Open `chronos.jfr` in JDK Mission Control → GC tab. The flat GC line proves zero allocation on the hot path.

## Production Deployment

### JVM Flags
```bash
java \
  --add-modules jdk.incubator.vector \
  --add-opens java.base/sun.misc=ALL-UNNAMED \
  -XX:+UseZGC -XX:+ZGenerational \
  -Xms4g -Xmx4g \
  -XX:+AlwaysPreTouch \
  -XX:+UseTransparentHugePages \
  -Daeron.conductor.cpu.affinity=2 \
  -jar chronos-sequencer.jar
```

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
