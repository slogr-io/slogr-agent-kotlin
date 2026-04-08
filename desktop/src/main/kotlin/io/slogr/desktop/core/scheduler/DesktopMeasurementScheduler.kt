package io.slogr.desktop.core.scheduler

import io.slogr.agent.contracts.interfaces.MeasurementEngine
import io.slogr.desktop.core.history.LocalHistoryStore
import io.slogr.desktop.core.profiles.ProfileManager
import io.slogr.desktop.core.profiles.TrafficType
import io.slogr.desktop.core.settings.ServerEntry
import io.slogr.desktop.core.viewmodel.DesktopAgentViewModel
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.net.InetAddress

/**
 * Runs per-traffic-type TWAMP sessions sequentially against the active server.
 *
 * Each traffic type gets its own TWAMP session with real traffic signature
 * (packet count, interval, size, DSCP). Results update the dashboard
 * progressively — cards light up as each session completes.
 */
class DesktopMeasurementScheduler(
    private val engine: MeasurementEngine,
    private val viewModel: DesktopAgentViewModel,
    private val profileManager: ProfileManager,
    private val historyStore: LocalHistoryStore? = null,
) {

    private val log = LoggerFactory.getLogger(DesktopMeasurementScheduler::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var schedulerJob: Job? = null

    fun start(servers: List<ServerEntry>, intervalSeconds: Int, tracerouteEnabled: Boolean) {
        stop()
        if (servers.isEmpty()) return
        schedulerJob = scope.launch {
            while (isActive) {
                runCycle(servers.first(), tracerouteEnabled)
                delay(intervalSeconds * 1000L)
            }
        }
    }

    fun stop() {
        schedulerJob?.cancel()
        schedulerJob = null
    }

    suspend fun runOnce(servers: List<ServerEntry>, tracerouteEnabled: Boolean) {
        if (servers.isEmpty()) return
        runCycle(servers.first(), tracerouteEnabled)
    }

    fun shutdown() {
        stop()
        scope.cancel()
        engine.shutdown()
    }

    /**
     * Run one measurement cycle: 3 sequential TWAMP sessions (one per active traffic type).
     * Each session uses the traffic type's real signature (DSCP, packet size, interval, count).
     * Dashboard updates progressively after each session.
     */
    private suspend fun runCycle(server: ServerEntry, tracerouteEnabled: Boolean) {
        val activeTypes = profileManager.getActiveTypes()
        if (activeTypes.isEmpty()) return

        viewModel.setMeasuring(true)
        // Set all active types to "testing" (null grade)
        viewModel.clearTrafficGrades(activeTypes)

        try {
            val target = InetAddress.getByName(server.host)
            for (tt in activeTypes) {
                try {
                    val profile = profileManager.toSlaProfile(tt)
                    // Only run traceroute on the first session to avoid redundant overhead
                    val doTrace = tracerouteEnabled && tt == activeTypes.first()
                    val bundle = engine.measure(
                        target = target,
                        targetPort = server.port,
                        profile = profile,
                        traceroute = doTrace,
                    )
                    val grade = profileManager.evaluate(bundle.twamp, tt)
                    viewModel.updateTrafficGrade(tt.name, grade)
                    viewModel.updateServerResult(server.id, server.displayLabel, bundle, true)
                    try {
                        historyStore?.insert(bundle.twamp, makeReflector(server), bundle.grade)
                    } catch (e: Exception) {
                        log.warn("History write failed: {}", e.message)
                    }
                    log.info("{}: RTT={}ms loss={}% DSCP={} grade={}",
                        tt.displayName, bundle.twamp.fwdAvgRttMs, bundle.twamp.fwdLossPct, tt.dscp, grade.grade)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("{} test failed: {}", tt.displayName, e.message)
                    viewModel.updateTrafficGrade(tt.name,
                        io.slogr.desktop.core.profiles.TrafficGrade(tt, io.slogr.agent.contracts.SlaGrade.RED, -1f, 100f))
                    viewModel.updateServerResult(server.id, server.displayLabel, null, false)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Measurement cycle to {} failed: {}", server.displayLabel, e.message)
        } finally {
            viewModel.setMeasuring(false)
        }
    }

    private fun makeReflector(server: ServerEntry) =
        io.slogr.desktop.core.reflectors.Reflector(
            id = server.id, region = server.label.ifBlank { "user" },
            cloud = "user", host = server.host, port = server.port,
            latitude = 0.0, longitude = 0.0, tier = "free",
        )
}
