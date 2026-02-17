// Matching engine module: Vector API (SIMD) for price scanning
tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("--add-modules", "jdk.incubator.vector"))
}

tasks.withType<Test> {
    jvmArgs("--add-modules", "jdk.incubator.vector")
}

dependencies {
    implementation(project(":chronos-core"))
    implementation(project(":chronos-schema"))
}
