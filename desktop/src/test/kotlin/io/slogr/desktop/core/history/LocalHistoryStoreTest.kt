package io.slogr.desktop.core.history

import io.slogr.agent.contracts.*
import io.slogr.desktop.core.reflectors.Reflector
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*
import kotlin.time.Duration.Companion.hours

class LocalHistoryStoreTest {

    private lateinit var tempDir: java.nio.file.Path
    private lateinit var store: LocalHistoryStore

    private val profile = SlaProfile(
        name = "internet", nPackets = 10, intervalMs = 50, waitTimeMs = 2000,
        dscp = 0, packetSize = 64,
        rttGreenMs = 100f, rttRedMs = 200f,
        jitterGreenMs = 30f, jitterRedMs = 50f,
        lossGreenPct = 1f, lossRedPct = 5f,
    )

    private val reflector = Reflector(
        id = "r1", region = "us-east", cloud = "aws",
        host = "1.1.1.1", port = 862,
        latitude = 39.04, longitude = -77.49, tier = "free",
    )

    @BeforeTest
    fun setUp() = runBlocking {
        tempDir = Files.createTempDirectory("slogr-history-test")
        store = LocalHistoryStore(tempDir)
        store.initialize()
    }

    @AfterTest
    fun tearDown() {
        store.close()
        tempDir.toFile().deleteRecursively()
    }

    private fun makeResult(rtt: Float = 25f, loss: Float = 0f, jitter: Float = 5f): MeasurementResult =
        MeasurementResult(
            sessionId = UUID.randomUUID(),
            pathId = UUID.randomUUID(),
            sourceAgentId = UUID.randomUUID(),
            destAgentId = UUID.randomUUID(),
            srcCloud = "residential", srcRegion = "local",
            dstCloud = "aws", dstRegion = "us-east",
            windowTs = Clock.System.now(),
            profile = profile,
            fwdMinRttMs = rtt - 5, fwdAvgRttMs = rtt, fwdMaxRttMs = rtt + 5,
            fwdJitterMs = jitter, fwdLossPct = loss,
            packetsSent = 10, packetsRecv = 10,
        )

    @Test
    fun `empty store returns zero count`() = runBlocking {
        assertEquals(0, store.count())
    }

    @Test
    fun `insert and count`() = runBlocking {
        store.insert(makeResult(), reflector, SlaGrade.GREEN)
        assertEquals(1, store.count())
    }

    @Test
    fun `insert and getRecentResults`() = runBlocking {
        store.insert(makeResult(25f), reflector, SlaGrade.GREEN)
        store.insert(makeResult(120f), reflector, SlaGrade.YELLOW)

        val results = store.getRecentResults(limit = 10)
        assertEquals(2, results.size)
        // Most recent first
        assertEquals(SlaGrade.YELLOW, results[0].grade)
        assertEquals(SlaGrade.GREEN, results[1].grade)
    }

    @Test
    fun `getResultsForReflector filters by ID`() = runBlocking {
        val r2 = reflector.copy(id = "r2", region = "eu-west")
        store.insert(makeResult(25f), reflector, SlaGrade.GREEN)
        store.insert(makeResult(50f), r2, SlaGrade.YELLOW)
        store.insert(makeResult(30f), reflector, SlaGrade.GREEN)

        val r1Results = store.getResultsForReflector("r1")
        assertEquals(2, r1Results.size)
        assertTrue(r1Results.all { it.reflectorId == "r1" })

        val r2Results = store.getResultsForReflector("r2")
        assertEquals(1, r2Results.size)
    }

    @Test
    fun `getGradeDistribution counts grades`() = runBlocking {
        store.insert(makeResult(25f), reflector, SlaGrade.GREEN)
        store.insert(makeResult(30f), reflector, SlaGrade.GREEN)
        store.insert(makeResult(120f), reflector, SlaGrade.YELLOW)

        val dist = store.getGradeDistribution()
        assertEquals(2, dist[SlaGrade.GREEN])
        assertEquals(1, dist[SlaGrade.YELLOW])
        assertNull(dist[SlaGrade.RED])
    }

    @Test
    fun `pruneOlderThan removes old entries`() = runBlocking {
        // Insert entries
        store.insert(makeResult(25f), reflector, SlaGrade.GREEN)
        store.insert(makeResult(30f), reflector, SlaGrade.GREEN)
        assertEquals(2, store.count())

        // Prune entries older than "now + 1 hour" (removes everything)
        val cutoff = Clock.System.now() + 1.hours
        val deleted = store.pruneOlderThan(cutoff)
        assertEquals(2, deleted)
        assertEquals(0, store.count())
    }

    @Test
    fun `pruneOlderThan keeps recent entries`() = runBlocking {
        store.insert(makeResult(), reflector, SlaGrade.GREEN)
        assertEquals(1, store.count())

        // Prune entries older than 25 hours ago (keeps everything)
        val cutoff = Clock.System.now() - 25.hours
        val deleted = store.pruneOlderThan(cutoff)
        assertEquals(0, deleted)
        assertEquals(1, store.count())
    }

    @Test
    fun `history entry fields roundtrip correctly`() = runBlocking {
        val result = makeResult(42.5f, 0.5f, 8.3f)
        store.insert(result, reflector, SlaGrade.YELLOW)

        val entries = store.getRecentResults(1)
        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals("r1", entry.reflectorId)
        assertEquals("1.1.1.1", entry.reflectorHost)
        assertEquals("internet", entry.profile)
        assertEquals(42.5f, entry.avgRttMs)
        assertEquals(0.5f, entry.lossPct)
        assertEquals(8.3f, entry.jitterMs)
        assertEquals(SlaGrade.YELLOW, entry.grade)
    }

    @Test
    fun `getRecentResults respects limit`() = runBlocking {
        repeat(10) { store.insert(makeResult(), reflector, SlaGrade.GREEN) }
        assertEquals(10, store.count())
        assertEquals(5, store.getRecentResults(limit = 5).size)
    }
}
