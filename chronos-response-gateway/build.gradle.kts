plugins {
    application
}

dependencies {
    implementation(project(":chronos-core"))
    implementation(project(":chronos-schema"))
    implementation("io.aeron:aeron-client:1.44.1")
    implementation("io.aeron:aeron-driver:1.44.1")
    implementation("org.hdrhistogram:HdrHistogram:2.2.2")
}

application {
    mainClass.set("com.chronos.gateway.response.ResponseGatewayMain")
    applicationDefaultJvmArgs = listOf(
        "--add-opens", "java.base/sun.misc=ALL-UNNAMED",
        "-XX:+UseZGC", "-XX:+ZGenerational",
        "-Xms2g", "-Xmx2g", "-XX:+AlwaysPreTouch"
    )
}
