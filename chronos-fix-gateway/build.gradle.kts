plugins {
    application
}

dependencies {
    implementation(project(":chronos-core"))
    implementation(project(":chronos-schema"))
    implementation("io.aeron:aeron-client:1.44.1")
    implementation("io.aeron:aeron-driver:1.44.1")
    
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.11.0")
}

application {
    mainClass.set("com.chronos.gateway.fix.FixGatewayMain")
    applicationDefaultJvmArgs = listOf(
        "--add-opens", "java.base/sun.misc=ALL-UNNAMED",
        "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
        "-XX:+UseZGC", "-XX:+ZGenerational",
        "-Xms2g", "-Xmx2g", "-XX:+AlwaysPreTouch"
    )
}
