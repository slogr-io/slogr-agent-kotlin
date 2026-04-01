plugins {
    kotlin("jvm") version "2.1.0" apply false
    kotlin("plugin.serialization") version "2.1.0" apply false
    id("com.github.johnrengelman.shadow") version "8.1.1" apply false
}

subprojects {
    group = "io.slogr"
    version = "1.0.0-SNAPSHOT"
    repositories {
        mavenCentral()
    }
}
