package io.slogr.desktop.core.scheduler

import io.slogr.agent.contracts.SlaProfile
import io.slogr.agent.contracts.interfaces.MeasurementEngine
import io.slogr.desktop.core.reflectors.Reflector
import io.slogr.desktop.core.viewmodel.DesktopAgentViewModel
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.net.InetAddress

/**
 * Drives periodic measurement cycles against selected reflectors.
 *
 * All measurement work runs on [Dispatchers.IO] to never block the Compose UI thread.
 */
class DesktopMeasurementScheduler(
    private val engine: MeasurementEngine,
    private val viewModel: DesktopAgentViewModel,
) {

    private val log = LoggerFactory.getLogger(DesktopMeasurementScheduler::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var schedulerJob: Job? = null

    /**
     * Start periodic measurement. Runs one cycle immediately, then repeats
     * every [intervalSeconds].
     */
    fun start(
        reflectors: List<Reflector>,
        profile: SlaProfile,
        intervalSeconds: Int,
        tracerouteEnabled: Boolean,
    ) {
        stop()
        schedulerJob = scope.launch {
            while (isActive) {
                runCycle(reflectors, profile, tracerouteEnabled)
                delay(intervalSeconds * 1000L)
            }
        }
    }

    fun stop() {
        schedulerJob?.cancel()
        schedulerJob = null
    }

    /**
     * Run a single measurement cycle — one test per reflector, bounded concurrency.
     */
    suspend fun runOnce(
        reflectors: List<Reflector>,
        profile: SlaProfile,
        tracerouteEnabled: Boolean,
    ) {
        runCycle(reflectors, profile, tracerouteEnabled)
    }

    fun isRunning(): Boolean = schedulerJob?.isActive == true

    fun shutdown() {
        stop()
        scope.cancel()
        engine.shutdown()
    }

    private suspend fun runCycle(
        reflectors: List<Reflector>,
        profile: SlaProfile,
        tracerouteEnabled: Boolean,
    ) {
        if (reflectors.isEmpty()) return
        viewModel.setMeasuring(true)

        try {
            // Run measurements concurrently with bounded parallelism
            val semaphore = kotlinx.coroutines.sync.Semaphore(3)
            coroutineScope {
                reflectors.forEach { reflector ->
                    launch {
                        semaphore.acquire()
                        try {
                            measureReflector(reflector, profile, tracerouteEnabled)
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

    private suspend fun measureReflector(
        reflector: Reflector,
        profile: SlaProfile,
        tracerouteEnabled: Boolean,
    ) {
        try {
            val target = InetAddress.getByName(reflector.host)
            val bundle = engine.measure(
                target = target,
                targetPort = reflector.port,
                profile = profile,
                traceroute = tracerouteEnabled,
            )
            viewModel.updateResult(reflector.id, reflector.displayName, bundle)
            log.info(
                "Measurement to {} ({}): RTT={}ms loss={}% grade={}",
                reflector.displayName, reflector.host,
                bundle.twamp.fwdAvgRttMs, bundle.twamp.fwdLossPct, bundle.grade,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Measurement to {} ({}) failed: {}", reflector.displayName, reflector.host, e.message)
            viewModel.recordFailure(reflector.id, reflector.displayName)
        }
    }
}
