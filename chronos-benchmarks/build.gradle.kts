plugins {
    id("me.champeau.jmh") version "0.7.2"
}

dependencies {
    implementation(project(":chronos-core"))
    implementation(project(":chronos-matching"))
    implementation(project(":chronos-schema"))
    implementation(project(":chronos-fix-gateway"))
    implementation(project(":chronos-response-gateway"))
    implementation(project(":chronos-warmup"))
    implementation(project(":chronos-sequencer"))
    implementation(libs.aeron.client)
    implementation(libs.aeron.cluster)
    implementation(libs.aeron.driver)
    implementation(libs.aeron.archive)
    implementation("org.hdrhistogram:HdrHistogram:2.2.2")
    implementation("org.quickfixj:quickfixj-core:2.3.1")
    implementation("com.paritytrading.philadelphia:philadelphia-core:2.0.0")
    implementation("com.paritytrading.philadelphia:philadelphia-fix44:2.0.0")
    jmh("org.openjdk.jmh:jmh-core:1.37")
    jmhAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("--add-modules", "jdk.incubator.vector"))
}

jmh {
    // Production GC: ZGC for realistic performance measurement
    // For zero-allocation verification, override with:
    // ./gradlew jmh -Pjmh.jvmArgs="-XX:+UnlockExperimentalVMOptions,-XX:+UseEpsilonGC,-Xms256m,-Xmx256m"
    jvmArgs = listOf(
        "--add-modules", "jdk.incubator.vector",
        "--add-opens", "java.base/sun.misc=ALL-UNNAMED",
        "-XX:+UseZGC", "-XX:+ZGenerational",
        "-XX:+AlwaysPreTouch"
    )
    fork = 3  // Increased from 1 for better statistical confidence
    warmupIterations = 5  // Increased from 2 for better JIT warmup
    iterations = 5  // Increased from 3 for better statistical confidence
    resultFormat = "JSON"

    if (project.hasProperty("jmhInclude")) {
        includes.add(project.property("jmhInclude") as String)
    }
    // Enable GC allocation profiler when running zero-GC proof
    if (project.hasProperty("zeroGcProof")) {
        includes.add("ZeroGcProofBenchmark")
        profilers.add("gc")
        jvmArgs = listOf(
            "--add-modules", "jdk.incubator.vector",
            "--add-opens", "java.base/sun.misc=ALL-UNNAMED",
            "-XX:+UseZGC", "-XX:+ZGenerational",
            "-XX:+AlwaysPreTouch",
            "-Xlog:gc*:file=build/zero-gc-proof.log:time,uptime,level,tags"
        )
    }
}

tasks.register<JavaExec>("runWireToWire") {
    group = "verification"
    description = "Runs the wire-to-wire benchmark with ZGC (production GC)"
    mainClass = "com.chronos.bench.WireToWireBenchmark"
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs = listOf(
        "--add-modules", "jdk.incubator.vector",
        "--add-opens", "java.base/sun.misc=ALL-UNNAMED",
        "-XX:+UseZGC", "-XX:+ZGenerational",
        "-XX:+AlwaysPreTouch",
        "-Xlog:gc*:file=build/gc-zgc.log:time,uptime,level,tags"
    )
}

tasks.register<JavaExec>("runWireToWireParallelGc") {
    group = "verification"
    description = "Runs the wire-to-wire benchmark with Parallel GC (throughput GC)"
    mainClass = "com.chronos.bench.WireToWireBenchmark"
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs = listOf(
        "--add-modules", "jdk.incubator.vector",
        "--add-opens", "java.base/sun.misc=ALL-UNNAMED",
        "-XX:+UseParallelGC",
        "-Xms512m", "-Xmx512m",
        "-XX:+AlwaysPreTouch",
        "-Xlog:gc*:file=build/gc-parallel.log:time,uptime,level,tags"
    )
}

tasks.register<JavaExec>("runWireToWireEpsilonGc") {
    group = "verification"
    description = "Runs the wire-to-wire benchmark with Epsilon GC (zero-GC baseline)"
    mainClass = "com.chronos.bench.WireToWireBenchmark"
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs = listOf(
        "--add-modules", "jdk.incubator.vector",
        "--add-opens", "java.base/sun.misc=ALL-UNNAMED",
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:+UseEpsilonGC",
        "-Xms512m", "-Xmx512m",
        "-XX:+AlwaysPreTouch",
        "-Xlog:gc*:file=build/gc-epsilon.log:time,uptime,level,tags"
    )
}

tasks.register<JavaExec>("runClusterLatency") {
    group = "verification"
    description = "Benchmarks Aeron Cluster latency (IPC mode)"
    mainClass = "com.chronos.bench.ClusterLatencyBenchmark"
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs = listOf(
        "--add-modules", "jdk.incubator.vector",
        "--add-opens", "java.base/sun.misc=ALL-UNNAMED",
        "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
        "-XX:+UseZGC", "-XX:+ZGenerational",
        "-Xms1g", "-Xmx1g" 
    )
}

tasks.register<JavaExec>("runFixReplayer") {
    group = "verification"
    description = "Replays a FIX log file to the gateway"
    mainClass = "com.chronos.bench.FixLogReplayer"
    classpath = sourceSets["main"].runtimeClasspath
    args = listOf("localhost", "9876", "../sample_fix.log") // Default args for testing
}

// ─── Zero-GC Proof Tasks ───────────────────────────────────────────────────

/**
 * Epsilon GC Proof: runs 1M orders with a no-op GC and a tiny 64MB heap.
 *
 * Pass criteria: exits with code 0 (no OutOfMemoryError).
 * Fail criteria: JVM throws OOME → hot path allocates heap objects.
 *
 * Usage: ./gradlew :chronos-benchmarks:verifyZeroGc
 */
tasks.register<JavaExec>("verifyZeroGc") {
    group = "verification"
    description = "Proves zero heap allocation on the hot path using Epsilon GC"
    mainClass = "com.chronos.bench.EpsilonGcProof"
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs = listOf(
        "--add-modules", "jdk.incubator.vector",
        "--add-opens", "java.base/sun.misc=ALL-UNNAMED",
        // Epsilon GC: no-op collector — OOME if anything allocates
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:+UseEpsilonGC",
        // Intentionally small heap: warmup + static data only, no room for hot-path allocs
        "-Xms128m", "-Xmx128m",
        "-XX:+AlwaysPreTouch",
        // GC log for post-run analysis
        "-Xlog:gc*:file=build/epsilon-gc.log:time,uptime,level,tags"
    )
    // Fail the build if the proof exits with a non-zero code (OOME)
    isIgnoreExitValue = false
}

/**
 * JMH Zero-GC Benchmark: runs ZeroGcProofBenchmark with the GC allocation
 * profiler to measure bytes allocated per operation.
 *
 * Pass criteria: gc.alloc.rate.norm = 0.0 B/op
 *
 * Usage: ./gradlew :chronos-benchmarks:jmh -PzeroGcProof
 *
 * Results are written to build/zero-gc-jmh-result.json
 */
tasks.register("zeroGcJmh") {
    group = "verification"
    description = "Alias: runs JMH with GC profiler on ZeroGcProofBenchmark"
    dependsOn("jmh")
    doFirst {
        // Ensure the zeroGcProof property is set so the jmh block picks it up
        if (!project.hasProperty("zeroGcProof")) {
            throw GradleException(
                "Run via: ./gradlew :chronos-benchmarks:jmh -PzeroGcProof"
            )
        }
    }
}
