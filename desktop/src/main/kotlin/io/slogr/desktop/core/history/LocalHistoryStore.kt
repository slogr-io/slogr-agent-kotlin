package io.slogr.desktop.core.history

import io.slogr.agent.contracts.MeasurementResult
import io.slogr.agent.contracts.SlaGrade
import io.slogr.agent.contracts.SlaProfile
import io.slogr.agent.engine.sla.SlaEvaluator
import io.slogr.desktop.core.reflectors.Reflector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.createDirectories

class LocalHistoryStore(private val dataDir: Path) {

    private val dbFile = dataDir.resolve("history.db")
    private val mutex = Mutex()
    private var connection: Connection? = null

    suspend fun initialize() = withContext(Dispatchers.IO) {
        dataDir.createDirectories()
        val conn = DriverManager.getConnection("jdbc:sqlite:${dbFile}")
        conn.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
        conn.createStatement().use { it.execute("PRAGMA synchronous=NORMAL") }
        conn.createStatement().use { it.execute(CREATE_TABLE) }
        conn.createStatement().use { it.execute(INDEX_MEASURED_AT) }
        conn.createStatement().use { it.execute(INDEX_REFLECTOR) }
        conn.createStatement().use { it.execute(INDEX_PROFILE) }
        // Migration: add rev columns if missing (upgrade from older schema)
        try { conn.createStatement().use { it.execute("ALTER TABLE measurement_history ADD COLUMN rev_avg_rtt_ms REAL DEFAULT 0") } } catch (_: Exception) {}
        try { conn.createStatement().use { it.execute("ALTER TABLE measurement_history ADD COLUMN rev_jitter_ms REAL DEFAULT 0") } } catch (_: Exception) {}
        connection = conn
    }

    suspend fun insert(result: MeasurementResult, reflector: Reflector, grade: SlaGrade) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val conn = connection ?: return@withContext
                val ps = conn.prepareStatement(INSERT_SQL)
                ps.setString(1, result.sessionId.toString())
                ps.setString(2, reflector.id)
                ps.setString(3, reflector.host)
                ps.setString(4, reflector.displayName)
                ps.setString(5, result.profile.name)
                ps.setString(6, Clock.System.now().toString())
                ps.setFloat(7, result.fwdAvgRttMs)
                ps.setFloat(8, result.fwdMinRttMs)
                ps.setFloat(9, result.fwdMaxRttMs)
                ps.setFloat(10, result.fwdJitterMs)
                ps.setFloat(11, result.fwdLossPct)
                ps.setFloat(12, result.revAvgRttMs ?: 0f)
                ps.setFloat(13, result.revJitterMs ?: 0f)
                ps.setInt(14, result.packetsSent)
                ps.setInt(15, result.packetsRecv)
                ps.setString(16, grade.name)
                ps.executeUpdate()
                ps.close()
            }
        }

    suspend fun getRecentResults(limit: Int = 50): List<HistoryEntry> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val conn = connection ?: return@withContext emptyList()
                conn.prepareStatement("SELECT * FROM measurement_history ORDER BY measured_at DESC LIMIT ?").use { ps ->
                    ps.setInt(1, limit)
                    val rs = ps.executeQuery()
                    val entries = mutableListOf<HistoryEntry>()
                    while (rs.next()) entries.add(rowToEntry(rs))
                    entries
                }
            }
        }

    /** Get history for a specific traffic type profile. */
    suspend fun getResultsForProfile(profile: String, limit: Int = 200): List<HistoryEntry> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val conn = connection ?: return@withContext emptyList()
                conn.prepareStatement("SELECT * FROM measurement_history WHERE profile = ? ORDER BY measured_at DESC LIMIT ?").use { ps ->
                    ps.setString(1, profile)
                    ps.setInt(2, limit)
                    val rs = ps.executeQuery()
                    val entries = mutableListOf<HistoryEntry>()
                    while (rs.next()) entries.add(rowToEntry(rs))
                    entries
                }
            }
        }

    /**
     * Get history for a type that was never tested — uses baseline results
     * re-evaluated against the requested type's SLA thresholds.
     */
    suspend fun getBaselineAsProfile(targetProfile: SlaProfile, limit: Int = 200): List<HistoryEntry> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val conn = connection ?: return@withContext emptyList()
                conn.prepareStatement("SELECT * FROM measurement_history WHERE profile = 'baseline' ORDER BY measured_at DESC LIMIT ?").use { ps ->
                    ps.setInt(1, limit)
                    val rs = ps.executeQuery()
                    val entries = mutableListOf<HistoryEntry>()
                    while (rs.next()) {
                        val base = rowToEntry(rs)
                        // Re-evaluate baseline metrics against the target profile's thresholds
                        val fakeResult = MeasurementResult(
                            sessionId = java.util.UUID.randomUUID(),
                            pathId = java.util.UUID.randomUUID(),
                            sourceAgentId = java.util.UUID.randomUUID(),
                            destAgentId = java.util.UUID.randomUUID(),
                            srcCloud = "local", srcRegion = "local", dstCloud = "local", dstRegion = "local",
                            windowTs = base.measuredAt, profile = targetProfile,
                            fwdMinRttMs = base.minRttMs, fwdAvgRttMs = base.avgRttMs, fwdMaxRttMs = base.maxRttMs,
                            fwdJitterMs = base.jitterMs, fwdLossPct = base.lossPct,
                            packetsSent = base.packetsSent, packetsRecv = base.packetsRecv,
                        )
                        val grade = SlaEvaluator.evaluate(fakeResult, targetProfile)
                        entries.add(base.copy(profile = targetProfile.name, grade = grade))
                    }
                    entries
                }
            }
        }

    suspend fun getResultsForReflector(reflectorId: String, limit: Int = 100): List<HistoryEntry> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val conn = connection ?: return@withContext emptyList()
                conn.prepareStatement("SELECT * FROM measurement_history WHERE reflector_id = ? ORDER BY measured_at DESC LIMIT ?").use { ps ->
                    ps.setString(1, reflectorId)
                    ps.setInt(2, limit)
                    val rs = ps.executeQuery()
                    val entries = mutableListOf<HistoryEntry>()
                    while (rs.next()) entries.add(rowToEntry(rs))
                    entries
                }
            }
        }

    suspend fun getGradeDistribution(): Map<SlaGrade, Int> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val conn = connection ?: return@withContext emptyMap()
                conn.createStatement().use { st ->
                    val rs = st.executeQuery("SELECT grade, COUNT(*) as cnt FROM measurement_history GROUP BY grade")
                    val map = mutableMapOf<SlaGrade, Int>()
                    while (rs.next()) { try { map[SlaGrade.valueOf(rs.getString("grade"))] = rs.getInt("cnt") } catch (_: Exception) {} }
                    map
                }
            }
        }

    suspend fun pruneOlderThan(cutoff: Instant): Int =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val conn = connection ?: return@withContext 0
                conn.prepareStatement("DELETE FROM measurement_history WHERE measured_at < ?").use { ps ->
                    ps.setString(1, cutoff.toString())
                    ps.executeUpdate()
                }
            }
        }

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val conn = connection ?: return@withContext 0
            conn.createStatement().use { st ->
                val rs = st.executeQuery("SELECT COUNT(*) FROM measurement_history")
                if (rs.next()) rs.getInt(1) else 0
            }
        }
    }

    fun close() { connection?.close(); connection = null }

    private fun rowToEntry(rs: java.sql.ResultSet): HistoryEntry = HistoryEntry(
        id = rs.getLong("id"), sessionId = rs.getString("session_id"),
        reflectorId = rs.getString("reflector_id"), reflectorHost = rs.getString("reflector_host"),
        reflectorRegion = rs.getString("reflector_region"), profile = rs.getString("profile"),
        measuredAt = Instant.parse(rs.getString("measured_at")),
        avgRttMs = rs.getFloat("fwd_avg_rtt_ms"), minRttMs = rs.getFloat("fwd_min_rtt_ms"),
        maxRttMs = rs.getFloat("fwd_max_rtt_ms"), jitterMs = rs.getFloat("fwd_jitter_ms"),
        lossPct = rs.getFloat("fwd_loss_pct"),
        revAvgRttMs = try { rs.getFloat("rev_avg_rtt_ms") } catch (_: Exception) { 0f },
        revJitterMs = try { rs.getFloat("rev_jitter_ms") } catch (_: Exception) { 0f },
        packetsSent = rs.getInt("packets_sent"), packetsRecv = rs.getInt("packets_recv"),
        grade = SlaGrade.valueOf(rs.getString("grade")),
    )

    companion object {
        private const val CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS measurement_history (
                id               INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id       TEXT NOT NULL,
                reflector_id     TEXT NOT NULL,
                reflector_host   TEXT NOT NULL,
                reflector_region TEXT NOT NULL,
                profile          TEXT NOT NULL,
                measured_at      TEXT NOT NULL,
                fwd_avg_rtt_ms   REAL,
                fwd_min_rtt_ms   REAL,
                fwd_max_rtt_ms   REAL,
                fwd_jitter_ms    REAL,
                fwd_loss_pct     REAL,
                rev_avg_rtt_ms   REAL DEFAULT 0,
                rev_jitter_ms    REAL DEFAULT 0,
                packets_sent     INTEGER,
                packets_recv     INTEGER,
                grade            TEXT
            )
        """
        private const val INDEX_MEASURED_AT = "CREATE INDEX IF NOT EXISTS idx_history_measured_at ON measurement_history(measured_at)"
        private const val INDEX_REFLECTOR = "CREATE INDEX IF NOT EXISTS idx_history_reflector ON measurement_history(reflector_id, measured_at)"
        private const val INDEX_PROFILE = "CREATE INDEX IF NOT EXISTS idx_history_profile ON measurement_history(profile, measured_at)"
        private const val INSERT_SQL = """
            INSERT INTO measurement_history (
                session_id, reflector_id, reflector_host, reflector_region, profile,
                measured_at, fwd_avg_rtt_ms, fwd_min_rtt_ms, fwd_max_rtt_ms,
                fwd_jitter_ms, fwd_loss_pct, rev_avg_rtt_ms, rev_jitter_ms,
                packets_sent, packets_recv, grade
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
    }
}
