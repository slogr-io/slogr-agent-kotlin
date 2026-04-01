plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

// C compilation is handled by the Makefile, not Gradle.
// See native/Makefile for libslogr-native.so build instructions.

dependencies {
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
