# Chronos Troubleshooting Guide

This guide helps diagnose common issues when running Chronos, focusing on networking, memory, and performance.

## Build and Compilation Errors

### `java.lang.NoClassDefFoundError: jdk/incubator/vector/IntVector`
- **Cause**: The project requires the Incubator Vector API, which is not enabled by default in older Java versions or without flags.
- **Solution**: Ensure you are running with `--add-modules jdk.incubator.vector`. 
  - Gradle: `./gradlew run --args="--add-modules jdk.incubator.vector"`
  - IDE: Add VM options: `--add-modules jdk.incubator.vector`

### `UnsafeBuffer cannot be resolved`
- **Cause**: Missing dependency on `org.agrona:agrona`.
- **Solution**: Check `build.gradle.kts` dependencies. Ensure reliable network access to Maven Central.

### Dependency Issues (Gradle)
- **Problem**: `Could not resolve all files for configuration...`
- **Solution**: 
  1. `./gradlew --refresh-dependencies`
  2. Check proxy settings if behind a firewall.
  3. Verify `settings.gradle.kts` includes required repositories.

## Runtime Issues

### `Aeron Publication Closed` or `Publication buffer full`
- **Cause**: The Gateway is sending messages faster than the Sequencer can process them, or the Aeron Media Driver is not running correctly.
- **Solution**:
  1. Check if the Media Driver is started.
  2. Increase `aeron.publication.term.buffer.length` (default is small).
  3. Ensure the Sequencer process is pinned to a dedicated core and not starved for CPU.

### Performance Jitter / Latency Spikes
- **Cause**: GC pauses or CPU context switching.
- **Solution**:
  1. **GC Logs**: Enable GC logging (`-Xlog:gc*`) to see pause times.
  2. **Core Pinning**: Use `taskset -c <core>` (Linux) or affinity masks (Windows) to isolate the Sequencer thread.
  3. **Off-Heap**: Verify that no unexpected allocations are happening on the hot path. Use `-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints` with async-profiler.

### `java.nio.BufferOverflowException`
- **Cause**: Message size exceeds buffer capacity. 
- **Solution**:
  1. Increase buffer sizes in `MediaDriver.Context`.
  2. Check parsing logic for unbounded strings or arrays.
  3. Ensure `FixToSbeEncoder` respects buffer limits.

## Memory Leaks (Off-Heap)

Since Chronos uses `UnsafeBuffer` and `ByteBuffer.allocateDirect()`, standard heap dumps won't show leaks effectively.

- **Check**: Use Native Memory Tracking (NMT).
  - Flags: `-XX:NativeMemoryTracking=detail`
  - Command: `jcmd <pid> VM.native_memory detail`
- **Leak Source**: Often caused by creating direct buffers that are never released (though in Java they are eventually cleaned by the Cleaner/PhantomReference, it can be slow).
- **In Chronos**: Buffers are generally long-lived matching the lifecycle of the application. If memory grows unbounded, check for dynamic buffer creation in the message loop (which should be zero).

## Cluster Issues (Raft)

### Leader Election Failures / `No Leader`
- **Cause**: Network partitioning or misconfigured cluster members.
- **Solution**:
  1. Check logs for "Election in progress".
  2. Ensure all cluster members can communicate over UDP.
  3. Verify `aeron.cluster.member.endpoints` configuration.

### Replay Slowness
- **Cause**: Large log file replay at startup.
- **Solution**:
  1. Implement snapshotting more frequently.
  2. The `ChronosClusteredService` should periodically take snapshots to truncate the log.
