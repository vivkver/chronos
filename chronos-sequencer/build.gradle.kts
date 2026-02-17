plugins {
    application
}

dependencies {
    implementation(project(":chronos-core"))
    implementation(project(":chronos-matching"))
    implementation(project(":chronos-schema"))
    implementation("io.aeron:aeron-cluster:1.44.1")
    implementation("io.aeron:aeron-driver:1.44.1")
    implementation("io.aeron:aeron-archive:1.44.1")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("--add-modules", "jdk.incubator.vector"))
}

application {
    mainClass.set("com.chronos.sequencer.SequencerMain")
    applicationDefaultJvmArgs = listOf(
        "--add-modules", "jdk.incubator.vector",
        "--add-opens", "java.base/sun.misc=ALL-UNNAMED",
        "-XX:+UseZGC",
        "-XX:+ZGenerational",
        "-Xms4g", "-Xmx4g",
        "-XX:+AlwaysPreTouch"
    )
}
