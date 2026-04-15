plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(21)
}

sourceSets {
    create("integrationTest") {
        kotlin.srcDir("src/integrationTest/kotlin")
        resources.srcDir("src/integrationTest/resources")
        compileClasspath += sourceSets["main"].output + configurations["testRuntimeClasspath"]
        runtimeClasspath += output + compileClasspath
    }
}

configurations {
    getByName("integrationTestImplementation").extendsFrom(configurations["testImplementation"])
    getByName("integrationTestRuntimeOnly").extendsFrom(configurations["testRuntimeOnly"])
}

dependencies {
    implementation(project(":contracts"))
    implementation(project(":engine"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.rabbitmq.client)
    implementation(libs.gcp.pubsub)
    implementation(libs.otel.exporter.otlp)
    implementation(libs.otel.sdk)
    implementation(libs.clikt)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
    "integrationTestImplementation"(libs.testcontainers.rabbitmq)
    "integrationTestImplementation"(libs.testcontainers.junit)
    "integrationTestRuntimeOnly"(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

val integrationTest by tasks.registering(Test::class) {
    description = "Runs integration tests."
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
}

tasks.check {
    dependsOn(integrationTest)
}
