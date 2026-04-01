plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":contracts"))
    implementation(project(":native"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.maxmind.geoip2)
    implementation(libs.logback)  // provides slf4j-api + logback-classic
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
