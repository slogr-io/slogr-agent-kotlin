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

        // JVM args for the packaged application
        jvmArgs(
            "-Xmx256m",                    // Desktop agents need minimal heap
            "-Dslogr.version=1.1.0",
        )

        // Pass CLI args through (e.g. --background)
        args += listOf()

        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
            )
            packageName = "Slogr"
            packageVersion = "1.1.0"
            description = "Slogr Network Quality Monitor"
            copyright = "\u00a9 2026 Slogr"
            vendor = "Slogr"
            // licenseFile.set(rootProject.file("LICENSE"))  // uncomment when LICENSE exists

            // Include SQLite native library + MaxMind MMDB
            includeAllModules = true

            // Linux (future)
            linux {
                debMaintainer = "support@slogr.io"
                appCategory = "Network"
            }

            windows {
                menuGroup = "Slogr"
                upgradeUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // Menu shortcut in Start Menu
                menu = true
                // Console launcher not needed (GUI app)
                console = false
                // MSI custom properties: SLOGR_API_KEY, AUTOSTART
                // Handled by the installer post-install script
            }

            macOS {
                bundleID = "io.slogr.desktop"
                appCategory = "public.app-category.utilities"
                // DMG config
                dmgPackageVersion = "1.1.0"
                // Info.plist: register slogr:// URI scheme (Phase 5/future)
                infoPlist {
                    extraKeysRawXml = """
                        <key>CFBundleURLTypes</key>
                        <array>
                            <dict>
                                <key>CFBundleURLName</key>
                                <string>Slogr OAuth Callback</string>
                                <key>CFBundleURLSchemes</key>
                                <array>
                                    <string>slogr</string>
                                </array>
                            </dict>
                        </array>
                    """.trimIndent()
                }
            }
        }
    }
}
