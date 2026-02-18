# Unsafe Memory Usage in Chronos

Chronos uses `sun.misc.Unsafe` (via Agrona's `UnsafeBuffer`) for zero-allocation performance. This document outlines where and why unsafe memory is used, and the safety protocols in place.

## Why Unsafe?

1. **Zero Garbage Collection**: By allocating memory off-heap, valid long-lived data (like the Order Book) is invisible to the Garbage Collector. reducing GC pause times.
2. **Cache Locality**: Structure of Arrays (SOA) layout in `OffHeapOrderBook` ensures price levels are contiguous in memory, maximizing CPU cache hits during traversal.
3. **SIMD Optimization**: The Java Vector API works best with on-heap arrays or direct buffers, allowing us to process 8 price levels in a single CPU cycle.

## Key Components

### 1. `OffHeapOrderBook`
- **Location**: `com.chronos.core.lob.OffHeapOrderBook`
- **Allocations**: 
  - `ByteBuffer.allocateDirectAligned(size, 64)` ensures 64-byte cache-line alignment to prevent false sharing.
- **Access Pattern**: 
  - Uses `UnsafeBuffer.putLong/getInt` etc.
  - Manual memory layout logic (offsets) defined as constants (e.g., `SLOT_PRICE = 8`).
- **Safety**:
  - Bounds checking is performed by `UnsafeBuffer` in debug mode, but unchecked in release.
  - Memory is allocated once at startup. No dynamic resizing.

### 2. Message Flyweights
- **Location**: `com.chronos.schema.sbe.*` (Generated Code)
- **Usage**:
  - SBE Encoders/Decoders wrap a `DirectBuffer`.
  - They read/write values at specific offsets without creating objects.
- **Safety**:
  - SBE ensures strict schema compliance.
  - `blockLength` and `version` fields guard against schema mismatches.

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| **Buffer Overflow** | `UnsafeBuffer` checks bounds if `agrona.disable.bounds.checks=false`. In production, rigorous testing and fixed-size messages prevent this. |
| **Memory Leaks** | Buffers are allocated at startup and never released until process exit. No dynamic `malloc/free` cycles. |
| **Segfaults** | Access is strictly encapsulated within the OrderBook and SBE classes. No arbitrary pointer arithmetic is allowed in user code. |
| **False Sharing** | Critical data structures (counters, sequence numbers) are padded to 64 bytes (cache line size) using `PaddedAllocations` or `@Contended`. |

## Development Guidelines

1. **Never use `Unsafe` directly**. Always use Agrona's `UnsafeBuffer` or `MutableDirectBuffer` wrappers.
2. **Verify Offsets**. When modifying `OffHeapOrderBook`, verify that field offsets do not overlap and are aligned types (e.g., `long` at offset divisible by 8).
3. **Run with Bounds Checks**. In development, run with `-Daeron.dir.validate=true` and default Agrona settings to catch out-of-bounds access.
