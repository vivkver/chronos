#!/bin/bash
# -----------------------------------------------------------------------------
# CHRONOS High-Performance Linux Benchmarking Script
# -----------------------------------------------------------------------------
# This script optimizes the Linux environment for Aeron Cluster testing and
# executes the latency benchmarks.
#
# Prerequisites: Java 21+, Gradle
# -----------------------------------------------------------------------------

set -e

echo "═════════════════════════════════════════════════════════════════════"
echo "  CHRONOS: Linux Optimization & Benchmarking"
echo "═════════════════════════════════════════════════════════════════════"

# 1. OS Optimization (Requires sudo)
if [[ $EUID -ne 0 ]]; then
   echo "Note: Not running as root. Skipping kernel optimizations (sysctl/cpupower)."
   echo "For best results, run: sudo sysctl -w net.core.rmem_max=16777216 net.core.wmem_max=16777216"
else
   echo "[1/3] Applying Kernel Optimizations..."
   sysctl -w net.core.rmem_max=16777216 2>/dev/null || echo "Note: Could not set rmem_max (common in containers)"
   sysctl -w net.core.wmem_max=16777216 2>/dev/null || echo "Note: Could not set wmem_max (common in containers)"
   
   if command -v cpupower &> /dev/null; then
       cpupower frequency-set -g performance 2>/dev/null || echo "Note: Could not set CPU governor"
   fi
fi

# 2. Preparation
echo "[2/3] Preparing Environment..."
# Use RAM disk for Raft log to eliminate disk I/O bottleneck
RAM_DISK="/dev/shm/chronos-bench"
rm -rf "$RAM_DISK"
mkdir -p "$RAM_DISK"

# 3. Execution
echo "[3/3] Running Cluster Latency Benchmark..."
# Ensure gradlew is executable
chmod +x ./gradlew

# Dynamic Affinity Tuning
# 1. Check for Kernel Isolation (isolcpus)
ISOLATED_CPUS=$(cat /sys/devices/system/cpu/isolated 2>/dev/null || echo "")

# 2. Detect number of available vCPUs
CPU_COUNT=$(nproc)
AFFINITY_MASK=""

if [ -n "$ISOLATED_CPUS" ]; then
    echo "DANGER: Kernel Isolation detected! Isolated Cores: $ISOLATED_CPUS"
    AFFINITY_MASK="$ISOLATED_CPUS"
    echo "Using isolated cores for benchmark pinning: $AFFINITY_MASK"
elif [ "$CPU_COUNT" -ge 8 ]; then
    # On an 8+ vCPU box, we use even-numbered cores (0, 2, 4, 6)
    # This often maps to separate physical cores, avoiding Hyperthreading contention.
    AFFINITY_MASK="0,2,4,6"
    echo "Detected 8+ vCPUs. Using physical core affinity mask: $AFFINITY_MASK"
elif [ "$CPU_COUNT" -ge 4 ]; then
    AFFINITY_MASK="0-3"
    echo "Detected 4 vCPUs. Using mask: $AFFINITY_MASK"
else
    AFFINITY_MASK="0"
    echo "Limited CPUs detected ($CPU_COUNT). Using mask: $AFFINITY_MASK"
fi

# Run the benchmark
if command -v taskset &> /dev/null; then
    echo "Binding to cores $AFFINITY_MASK via taskset..."
    taskset -c "$AFFINITY_MASK" ./gradlew :chronos-benchmarks:runClusterLatency \
        -Daeron.dir="$RAM_DISK/aeron" \
        -Daeron.cluster.dir="$RAM_DISK/cluster" \
        --info
else
    ./gradlew :chronos-benchmarks:runClusterLatency \
        -Daeron.dir="$RAM_DISK/aeron" \
        -Daeron.cluster.dir="$RAM_DISK/cluster" \
        --info
fi

echo "═════════════════════════════════════════════════════════════════════"
echo "  Benchmark Complete"
echo "  Artifacts cleaned from $RAM_DISK"
echo "═════════════════════════════════════════════════════════════════════"
