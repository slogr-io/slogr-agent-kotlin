plugins {
    kotlin("jvm")
    id("org.jetbrains.compose") version "1.7.3"
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    // Reuse L1 modules — shared engine, contracts, platform, native (pure-Java transport)
    implementation(project(":contracts"))
    implementation(project(":engine"))
    implementation(project(":platform"))
    implementation(project(":native"))

    // Compose Desktop
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)

    // SQLite (local 24h history)
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")

    // HTTP client (reflector discovery, OAuth callback)
    implementation("io.ktor:ktor-client-core:2.3.8")
    implementation("io.ktor:ktor-client-cio:2.3.8")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.8")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.8")

    // Testing
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "io.slogr.desktop.MainKt"
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg
            )
            packageName = "Slogr"
            packageVersion = "1.1.0"
            description = "Slogr Network Quality Monitor"
            vendor = "Slogr"
            windows {
                menuGroup = "Slogr"
                upgradeUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
                shortcut = true
                dirChooser = true
            }
            macOS {
                bundleID = "io.slogr.desktop"
                appCategory = "public.app-category.utilities"
            }
        }
    }
}
