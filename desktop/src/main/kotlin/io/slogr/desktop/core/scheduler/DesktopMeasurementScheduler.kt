package io.slogr.desktop.core.scheduler

import io.slogr.agent.contracts.SlaProfile
import io.slogr.agent.contracts.TimingMode
import io.slogr.agent.contracts.interfaces.MeasurementEngine
import io.slogr.desktop.core.history.LocalHistoryStore
import io.slogr.desktop.core.profiles.ProfileManager
import io.slogr.desktop.core.settings.ServerEntry
import io.slogr.desktop.core.viewmodel.DesktopAgentViewModel
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.net.InetAddress

class DesktopMeasurementScheduler(
    private val engine: MeasurementEngine,
    private val viewModel: DesktopAgentViewModel,
    private val profileManager: ProfileManager,
    private val historyStore: LocalHistoryStore? = null,
) {

    private val log = LoggerFactory.getLogger(DesktopMeasurementScheduler::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var schedulerJob: Job? = null

    /** Base profile used for the TWAMP session parameters. */
    private val baseProfile = SlaProfile(
        name = "desktop-base", nPackets = 10, intervalMs = 50, waitTimeMs = 2000,
        dscp = 0, packetSize = 64, timingMode = TimingMode.FIXED,
        rttGreenMs = 100f, rttRedMs = 200f,
        jitterGreenMs = 30f, jitterRedMs = 50f,
        lossGreenPct = 1f, lossRedPct = 5f,
    )

    fun start(servers: List<ServerEntry>, intervalSeconds: Int, tracerouteEnabled: Boolean) {
        stop()
        if (servers.isEmpty()) return
        schedulerJob = scope.launch {
            while (isActive) {
                runCycle(servers, tracerouteEnabled)
                delay(intervalSeconds * 1000L)
            }
        }
    }

    fun stop() {
        schedulerJob?.cancel()
        schedulerJob = null
    }

    suspend fun runOnce(servers: List<ServerEntry>, tracerouteEnabled: Boolean) {
        runCycle(servers, tracerouteEnabled)
    }

    fun shutdown() {
        stop()
        scope.cancel()
        engine.shutdown()
    }

    private suspend fun runCycle(servers: List<ServerEntry>, tracerouteEnabled: Boolean) {
        if (servers.isEmpty()) return
        viewModel.setMeasuring(true)
        try {
            val semaphore = kotlinx.coroutines.sync.Semaphore(3)
            coroutineScope {
                servers.forEach { server ->
                    launch {
                        semaphore.acquire()
                        try {
                            measureServer(server, tracerouteEnabled)
                        } finally {
                            semaphore.release()
                        }
                    }
                }
            }
        } finally {
            viewModel.setMeasuring(false)
        }
    }

    private suspend fun measureServer(server: ServerEntry, tracerouteEnabled: Boolean) {
        try {
            val target = InetAddress.getByName(server.host)
            val bundle = engine.measure(
                target = target,
                targetPort = server.port,
                profile = baseProfile,
                traceroute = tracerouteEnabled,
            )
            viewModel.updateResult(server.id, server.displayLabel, bundle, profileManager)
            try {
                historyStore?.insert(bundle.twamp, makeReflector(server), bundle.grade)
            } catch (e: Exception) {
                log.warn("Failed to write history for {}: {}", server.displayLabel, e.message)
            }
            log.info("Measurement to {}: RTT={}ms loss={}% grade={}",
                server.displayLabel, bundle.twamp.fwdAvgRttMs, bundle.twamp.fwdLossPct, bundle.grade)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Measurement to {} failed: {}", server.displayLabel, e.message)
            viewModel.recordFailure(server.id, server.displayLabel)
        }
    }

    /** Adapter: create a lightweight Reflector-like object for history insertion. */
    private fun makeReflector(server: ServerEntry) =
        io.slogr.desktop.core.reflectors.Reflector(
            id = server.id, region = server.label.ifBlank { "user" },
            cloud = "user", host = server.host, port = server.port,
            latitude = 0.0, longitude = 0.0, tier = "free",
        )
}
