dependencies {
    implementation(project(":chronos-core"))
    implementation(project(":chronos-matching"))
    implementation(project(":chronos-schema"))
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("--add-modules", "jdk.incubator.vector"))
}
