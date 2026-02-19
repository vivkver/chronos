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

# We use taskset to bind the process to dedicated cores (if available)
# Cores 0-3 are often a good choice on a 4-core box
if command -v taskset &> /dev/null; then
    echo "Binding to cores 0-3 via taskset..."
    taskset -c 0-3 ./gradlew :chronos-benchmarks:runClusterLatency \
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
