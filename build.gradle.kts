plugins {
    java
    checkstyle
    id("net.ltgt.errorprone") version "3.1.0"
}

allprojects {
    group = "com.chronos"
    version = "1.0.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "checkstyle")
    apply(plugin = "net.ltgt.errorprone")

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    checkstyle {
        toolVersion = "10.12.0"
        configFile = rootProject.file("config/checkstyle/checkstyle.xml")
        isIgnoreFailures = false
        isShowViolations = true
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        jvmArgs("--add-opens", "java.base/sun.misc=ALL-UNNAMED")
    }

    dependencies {
        errorprone("com.google.errorprone:error_prone_core:2.26.1")
        implementation("org.agrona:agrona:1.21.2")
        implementation("org.slf4j:slf4j-api:2.0.12")
        runtimeOnly("org.slf4j:slf4j-simple:2.0.12")
        testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    }
}
