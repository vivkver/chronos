// Matching engine module: Vector API (SIMD) for price scanning
tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("--add-modules", "jdk.incubator.vector"))
}

tasks.withType<Test> {
    jvmArgs("--add-modules", "jdk.incubator.vector")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

dependencies {
    implementation(project(":chronos-core"))
    implementation(project(":chronos-schema"))

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
