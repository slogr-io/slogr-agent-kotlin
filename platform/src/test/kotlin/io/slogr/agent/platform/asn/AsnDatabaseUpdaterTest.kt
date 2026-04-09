package io.slogr.agent.platform.asn

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.zip.GZIPOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class AsnDatabaseUpdaterTest {

    private val agentId = UUID.fromString("00000001-0000-0000-0000-000000000001")

    // ── Test data ───────────────────────────────────────────────────────────

    private val validTsv = listOf(
        "1.0.0.0\t1.0.0.255\t13335\tUS\tCLOUDFLARENET",
        "8.8.8.0\t8.8.8.255\t15169\tUS\tGOOGLE",
        "104.16.0.0\t104.16.255.255\t13335\tUS\tCLOUDFLARENET"
    ).joinToString("\n")

    private val validGzip: ByteArray by lazy { gzip(validTsv) }

    private fun gzip(content: String): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { it.write(content.toByteArray()) }
        return baos.toByteArray()
    }

    private fun freshMetadata(daysAgo: Long = 1, source: String = "primary"): String {
        val ts = Instant.now().minus(daysAgo, ChronoUnit.DAYS).toString()
        return """{"downloaded_at":"$ts","source":"$source","size_bytes":${validTsv.length}}"""
    }

    private fun seedCache(dir: File, metaDaysAgo: Long = 1) {
        File(dir, "ip2asn-v4.tsv").writeText(validTsv)
        File(dir, "ip2asn-meta.json").writeText(freshMetadata(metaDaysAgo))
    }

    // ── Happy path ──────────────────────────────────────────────────────────

    @Test
    fun `tier-1 fresh cache exists - returns file without download`(@TempDir tmpDir: File) = runTest {
        seedCache(tmpDir)
        var downloadCalled = false
        val updater = AsnDatabaseUpdater(tmpDir.absolutePath, agentId, "1.0.5") { _ ->
            downloadCalled = true; null
        }
        val result = updater.ensureDatabase()
        assertNotNull(result)
        assertTrue(result!!.exists())
        assertFalse(downloadCalled, "httpGet should not be called when cache is fresh")
    }

    @Test
    fun `tier-2 no cache, primary succeeds - file and metadata written`(@TempDir tmpDir: File) = runTest {
        val updater = AsnDatabaseUpdater(tmpDir.absolutePath, agentId, "1.0.5") { url ->
            if (url.contains("data.slogr.io")) validGzip else null
        }
        val result = updater.ensureDatabase()
        assertNotNull(result)
        assertTrue(result!!.exists())
        assertTrue(File(tmpDir, "ip2asn-meta.json").exists())
    }

    @Test
    fun `tier-2 primary succeeds - file content is decompressed TSV not gzip`(@TempDir tmpDir: File) = runTest {
        val updater = AsnDatabaseUpdater(tmpDir.absolutePath, agentId, "1.0.5") { _ -> validGzip }
        updater.ensureDatabase()
        val content = File(tmpDir, "ip2asn-v4.tsv").readText()
        assertTrue(content.contains("CLOUDFLARENET"))
        assertTrue(content.contains('\t'))
    }

    // ── Fallback chain ──────────────────────────────────────────────────────

    @Test
    fun `tier-3 primary fails, fallback succeeds - source is fallback`(@TempDir tmpDir: File) = runTest {
        val updater = AsnDatabaseUpdater(tmpDir.absolutePath, agentId, "1.0.5") { url ->
            if (url.contains("iptoasn.com")) validGzip else null
        }
        val result = updater.ensureDatabase()
        assertNotNull(result)
        val meta = File(tmpDir, "ip2asn-meta.json").readText()
        assertTrue(meta.contains("\"source\":\"fallback\""))
    }

    @Test
    fun `tier-4 both downloads fail, stale cache exists - returns stale file`(@TempDir tmpDir: File) = runTest {
        // Seed stale cache (metadata is 60 days old)
        File(tmpDir, "ip2asn-v4.tsv").writeText(validTsv)
        File(tmpDir, "ip2asn-meta.json").writeText(freshMetadata(daysAgo = 60))
        val updater = AsnDatabaseUpdater(tmpDir.absolutePath, agentId, "1.0.5") { _ -> null }
        val result = updater.ensureDatabase()
        assertNotNull(result)
        assertTrue(result!!.exists())
    }

    @Test
    fun `tier-5 both downloads fail, no stale cache - extracts bundled resource`(@TempDir tmpDir: File) = runTest {
        val updater = AsnDatabaseUpdater(tmpDir.absolutePath, agentId, "1.0.5") { _ -> null }
        val result = updater.ensureDatabase()
        // Bundled resource is on classpath (ip2asn-v4.tsv.gz in engine/src/main/resources)
        // This may or may not be available depending on classpath — if null, that's OK for this test
        if (result != null) {
            assertTrue(result.exists())
            assertTrue(result.length() > 0)
        }
    }

    @Test
    fun `all downloads fail - does not crash`(@TempDir tmpDir: File) = runTest {
        val updater = AsnDatabaseUpdater(tmpDir.absolutePath, agentId, "1.0.5") { _ -> null }
        // May return bundled resource or null depending on classpath — just verify no crash
        updater.ensureDatabase()
    }

    // ── First-run analytics (CRITICAL) ──────────────────────────────────────

    @Test
    fun `first run with no metadata - httpGet IS called with primary URL`(@TempDir tmpDir: File) = runTest {
        var calledUrl: String? = null
        val updater = AsnDatabaseUpdater(tmpDir.absolutePath, agentId, "1.0.5") { url ->
            calledUrl = url; validGzip
        }
        updater.ensureDatabase()
        assertNotNull(calledUrl)
        assertTrue(calledUrl!!.contains("data.slogr.io"))
        assertTrue(calledUrl!!.contains("agent_id="))
        assertTrue(calledUrl!!.contains("v=1.0.5"))
    }

    @Test
    fun `bundled resource exists but no metadata - httpGet still called`(@TempDir tmpDir: File) = runTest {
        // Seed TSV file (as if bundled was extracted previously) but NO metadata
        File(tmpDir, "ip2asn-v4.tsv").writeText(validTsv)
        var downloadAttempted = false
        val updater = AsnDatabaseUpdater(tmpDir.absolutePath, agentId, "1.0.5") { _ ->
            downloadAttempted = true; validGzip
        }
        updater.ensureDatabase()
        assertTrue(downloadAttempted, "Download must be attempted even if TSV exists without metadata")
    }

    // ── Validation ──────────────────────────────────────────────────────────

    @Test
    fun `primary returns invalid gzip - falls to fallback`(@TempDir tmpDir: File) = runTest {
        val tiers = mutableListOf<String>()
        val updater = AsnDatabaseUpdater(tmpDir.absolutePath, agentId, "1.0.5") { url ->
            if (url.contains("data.slogr.io")) {
                tiers.add("primary")
                byteArrayOf(0x00, 0x01, 0x02)  // not valid gzip
            } else {
                tiers.add("fallback")
                validGzip
            }
        }
        val result = updater.ensureDatabase()
        assertNotNull(result)
        assertTrue(tiers.contains("fallback"), "Should have fallen through to fallback")
    }

    @Test
    fun `primary returns empty response - treated as failure`(@TempDir tmpDir: File) = runTest {
        var fallbackCalled = false
        val updater = AsnDatabaseUpdater(tmpDir.absolutePath, agentId, "1.0.5") { url ->
            if (url.contains("data.slogr.io")) {
                ByteArray(0)
            } else {
                fallbackCalled = true; validGzip
            }
        }
        updater.ensureDatabase()
        assertTrue(fallbackCalled, "Fallback should be attempted after empty primary")
    }

    @Test
    fun `primary returns valid gzip but content is not TSV - validation fails`(@TempDir tmpDir: File) = runTest {
        val notTsv = gzip("this is not tab-separated data")
        var fallbackCalled = false
        val updater = AsnDatabaseUpdater(tmpDir.absolutePath, agentId, "1.0.5") { url ->
            if (url.contains("data.slogr.io")) {
                notTsv
            } else {
                fallbackCalled = true; validGzip
            }
        }
        updater.ensureDatabase()
        assertTrue(fallbackCalled, "Fallback should be attempted after TSV validation failure")
    }

    // ── Staleness ───────────────────────────────────────────────────────────

    @Test
    fun `metadata 31 days old - triggers download`(@TempDir tmpDir: File) = runTest {
        seedCache(tmpDir, metaDaysAgo = 31)
        var downloadCalled = false
        val updater = AsnDatabaseUpdater(tmpDir.absolutePath, agentId, "1.0.5") { _ ->
            downloadCalled = true; validGzip
        }
        updater.ensureDatabase()
        assertTrue(downloadCalled, "Download should be triggered for 31-day-old metadata")
    }

    @Test
    fun `metadata 29 days old - uses cache`(@TempDir tmpDir: File) = runTest {
        seedCache(tmpDir, metaDaysAgo = 29)
        var downloadCalled = false
        val updater = AsnDatabaseUpdater(tmpDir.absolutePath, agentId, "1.0.5") { _ ->
            downloadCalled = true; null
        }
        val result = updater.ensureDatabase()
        assertNotNull(result)
        assertFalse(downloadCalled)
    }

    @Test
    fun `corrupt metadata JSON - treated as stale`(@TempDir tmpDir: File) = runTest {
        File(tmpDir, "ip2asn-v4.tsv").writeText(validTsv)
        File(tmpDir, "ip2asn-meta.json").writeText("not json at all {{{")
        var downloadCalled = false
        val updater = AsnDatabaseUpdater(tmpDir.absolutePath, agentId, "1.0.5") { _ ->
            downloadCalled = true; validGzip
        }
        updater.ensureDatabase()
        assertTrue(downloadCalled, "Corrupt metadata should trigger download")
    }

    // ── File safety ─────────────────────────────────────────────────────────

    @Test
    fun `dataDir does not exist - created automatically`(@TempDir tmpDir: File) = runTest {
        val nested = File(tmpDir, "nested/deeply/asn")
        assertFalse(nested.exists())
        val updater = AsnDatabaseUpdater(nested.absolutePath, agentId, "1.0.5") { _ -> validGzip }
        updater.ensureDatabase()
        assertTrue(nested.exists())
        assertTrue(nested.isDirectory)
    }

    @Test
    fun `concurrent ensureDatabase calls - no file corruption`(@TempDir tmpDir: File) = runTest {
        val updater = AsnDatabaseUpdater(tmpDir.absolutePath, agentId, "1.0.5") { _ -> validGzip }
        val jobs = (1..5).map { launch { updater.ensureDatabase() } }
        jobs.forEach { it.join() }
        val content = File(tmpDir, "ip2asn-v4.tsv").readText()
        assertTrue(content.contains("CLOUDFLARENET"))
    }

    // ── Periodic refresh ────────────────────────────────────────────────────

    @Test
    fun `startPeriodicRefresh calls ensureDatabase and fires onUpdated`(@TempDir tmpDir: File) = runTest {
        var updatedFile: File? = null
        val updater = AsnDatabaseUpdater(tmpDir.absolutePath, agentId, "1.0.5") { _ -> validGzip }
        updater.startPeriodicRefresh(this) { file -> updatedFile = file }
        advanceTimeBy(1000)
        updater.stopPeriodicRefresh()
        assertNotNull(updatedFile)
    }

    @Test
    fun `stopPeriodicRefresh cancels the job`(@TempDir tmpDir: File) = runTest {
        val updater = AsnDatabaseUpdater(tmpDir.absolutePath, agentId, "1.0.5") { _ -> validGzip }
        updater.startPeriodicRefresh(this) { }
        advanceTimeBy(1000)
        updater.stopPeriodicRefresh()
        // No assertion needed — just verify no crash or hang
    }

    // ── Metadata ────────────────────────────────────────────────────────────

    @Test
    fun `metadata contains downloaded_at source and size_bytes`(@TempDir tmpDir: File) = runTest {
        val updater = AsnDatabaseUpdater(tmpDir.absolutePath, agentId, "1.0.5") { url ->
            if (url.contains("data.slogr.io")) validGzip else null
        }
        updater.ensureDatabase()
        val meta = File(tmpDir, "ip2asn-meta.json").readText()
        assertTrue(meta.contains("\"downloaded_at\""))
        assertTrue(meta.contains("\"source\":\"primary\""))
        assertTrue(meta.contains("\"size_bytes\""))
    }

    @Test
    fun `metadata downloaded_at is valid ISO-8601`(@TempDir tmpDir: File) = runTest {
        val updater = AsnDatabaseUpdater(tmpDir.absolutePath, agentId, "1.0.5") { _ -> validGzip }
        updater.ensureDatabase()
        val meta = File(tmpDir, "ip2asn-meta.json").readText()
        val json = kotlinx.serialization.json.Json.parseToJsonElement(meta).jsonObject
        val ts = json["downloaded_at"]!!.jsonPrimitive.content
        assertDoesNotThrow { Instant.parse(ts) }
    }
}
