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
}

tasks.register<JavaExec>("runWireToWire") {
    group = "verification"
    description = "Runs the end-to-end wire-to-wire latency benchmark"
    mainClass = "com.chronos.bench.WireToWireBenchmark"
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs = listOf(
        "--add-modules", "jdk.incubator.vector",
        "--add-opens", "java.base/sun.misc=ALL-UNNAMED",
        "-XX:+UseZGC", "-XX:+ZGenerational",
        "-XX:+AlwaysPreTouch"
    )
}

tasks.register<JavaExec>("runFixReplayer") {
    group = "verification"
    description = "Replays a FIX log file to the gateway"
    mainClass = "com.chronos.bench.FixLogReplayer"
    classpath = sourceSets["main"].runtimeClasspath
    args = listOf("localhost", "9876", "../sample_fix.log") // Default args for testing
}
