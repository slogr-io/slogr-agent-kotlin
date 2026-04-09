import java.time.Instant

plugins {
    kotlin("jvm")
    id("com.github.johnrengelman.shadow")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":contracts"))
    implementation(project(":native"))
    implementation(project(":engine"))
    implementation(project(":platform"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.clikt)
    implementation(libs.logback)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.processResources {
    filesMatching("version.properties") {
        val gitHash = runCatching {
            val proc = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                .directory(rootProject.projectDir)
                .start()
            proc.inputStream.bufferedReader().readLine()?.trim() ?: "unknown"
        }.getOrDefault("unknown")

        expand(
            "version" to project.version,
            "gitCommit" to gitHash,
            "buildTime" to Instant.now().toString()
        )
    }
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "io.slogr.agent.MainKt"
    }
}

tasks.shadowJar {
    archiveBaseName.set("slogr-agent")
    archiveClassifier.set("all")
    archiveVersion.set("")   // produces slogr-agent-all.jar (versioned CI artifacts use the GitHub tag)
    manifest {
        attributes["Main-Class"] = "io.slogr.agent.MainKt"
    }
    mergeServiceFiles()
}

tasks.test {
    useJUnitPlatform()
}

// Alias: `./gradlew :app:run` for quick local invocation
tasks.register<JavaExec>("runAgent") {
    group = "application"
    description = "Run slogr-agent from source"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.slogr.agent.MainKt")
    args = project.findProperty("agentArgs")?.toString()?.split(" ") ?: listOf("version")
}
