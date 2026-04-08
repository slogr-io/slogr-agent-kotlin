package io.slogr.desktop.core.export

import io.slogr.desktop.core.diagnostics.DiagnosticsRunner
import io.slogr.desktop.core.history.LocalHistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ResultsExporter {

    private val json = Json { prettyPrint = true }

    /**
     * Export last 20 test results + diagnostics + system info as a .zip file.
     * Returns the path to the created zip, or null on failure.
     */
    suspend fun export(historyStore: LocalHistoryStore, outputFile: File): Boolean =
        withContext(Dispatchers.IO) {
            try {
                ZipOutputStream(FileOutputStream(outputFile)).use { zip ->
                    // 1. Test results (last 20)
                    val results = historyStore.getRecentResults(limit = 20)
                    val resultsJson = buildJsonArray {
                        results.forEach { r ->
                            add(buildJsonObject {
                                put("timestamp", r.measuredAt.toString())
                                put("reflector_id", r.reflectorId)
                                put("reflector_host", r.reflectorHost)
                                put("reflector_region", r.reflectorRegion)
                                put("profile", r.profile)
                                put("avg_rtt_ms", r.avgRttMs.toDouble())
                                put("min_rtt_ms", r.minRttMs.toDouble())
                                put("max_rtt_ms", r.maxRttMs.toDouble())
                                put("jitter_ms", r.jitterMs.toDouble())
                                put("loss_pct", r.lossPct.toDouble())
                                put("packets_sent", r.packetsSent)
                                put("packets_recv", r.packetsRecv)
                                put("grade", r.grade.name)
                            })
                        }
                    }
                    zip.putNextEntry(ZipEntry("test_results.json"))
                    zip.write(json.encodeToString(kotlinx.serialization.json.JsonArray.serializer(), resultsJson).toByteArray())
                    zip.closeEntry()

                    // 2. Diagnostics
                    val diag = DiagnosticsRunner.runAll()
                    val diagJson = buildJsonArray {
                        diag.forEach { d ->
                            add(buildJsonObject {
                                put("name", d.name)
                                put("passed", d.passed)
                                put("detail", d.detail)
                            })
                        }
                    }
                    zip.putNextEntry(ZipEntry("diagnostics.json"))
                    zip.write(json.encodeToString(kotlinx.serialization.json.JsonArray.serializer(), diagJson).toByteArray())
                    zip.closeEntry()

                    // 3. System info
                    val sysInfo = buildJsonObject {
                        put("app_version", "1.1.0")
                        put("os_name", System.getProperty("os.name") ?: "unknown")
                        put("os_version", System.getProperty("os.version") ?: "unknown")
                        put("os_arch", System.getProperty("os.arch") ?: "unknown")
                        put("java_version", System.getProperty("java.version") ?: "unknown")
                        put("java_vendor", System.getProperty("java.vendor") ?: "unknown")
                        put("user_timezone", java.util.TimeZone.getDefault().id)
                        put("export_timestamp", kotlinx.datetime.Clock.System.now().toString())
                        put("test_count", results.size)
                    }
                    zip.putNextEntry(ZipEntry("system_info.json"))
                    zip.write(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), sysInfo).toByteArray())
                    zip.closeEntry()

                    // 4. Summary (human-readable)
                    val summary = buildString {
                        appendLine("Slogr Desktop — Test Results Export")
                        appendLine("Generated: ${kotlinx.datetime.Clock.System.now()}")
                        appendLine("App Version: 1.1.0")
                        appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
                        appendLine()
                        appendLine("=== Last ${results.size} Tests ===")
                        appendLine()
                        results.forEach { r ->
                            appendLine("${r.measuredAt}  ${r.reflectorHost}  ${r.profile}")
                            appendLine("  RTT: ${r.avgRttMs}ms (min=${r.minRttMs}, max=${r.maxRttMs})  Jitter: ${r.jitterMs}ms  Loss: ${r.lossPct}%  Grade: ${r.grade}")
                        }
                        appendLine()
                        appendLine("=== Diagnostics ===")
                        appendLine()
                        diag.forEach { d ->
                            appendLine("${if (d.passed) "[OK]" else "[FAIL]"} ${d.name}: ${d.detail}")
                        }
                    }
                    zip.putNextEntry(ZipEntry("summary.txt"))
                    zip.write(summary.toByteArray())
                    zip.closeEntry()
                }
                true
            } catch (e: Exception) {
                false
            }
        }
}
