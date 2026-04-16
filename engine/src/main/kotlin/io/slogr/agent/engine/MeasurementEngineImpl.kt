package io.slogr.agent.engine

import io.slogr.agent.contracts.Direction
import io.slogr.agent.contracts.MeasurementBundle
import io.slogr.agent.contracts.MeasurementResult
import io.slogr.agent.contracts.SlaGrade
import io.slogr.agent.contracts.SlaProfile
import io.slogr.agent.contracts.TracerouteMode
import io.slogr.agent.contracts.TracerouteResult
import io.slogr.agent.contracts.TwampAuthMode
import io.slogr.agent.contracts.interfaces.AsnResolver
import io.slogr.agent.contracts.interfaces.MeasurementEngine
import io.slogr.agent.engine.asn.NullAsnResolver
import io.slogr.agent.engine.pathchange.PathChangeDetector
import io.slogr.agent.engine.sla.SlaEvaluator
import io.slogr.agent.engine.traceroute.TracerouteOrchestrator
import io.slogr.agent.engine.twamp.MeasurementAssembler
import io.slogr.agent.engine.twamp.TwampMode
import io.slogr.agent.engine.twamp.auth.ModePreferenceChain
import io.slogr.agent.engine.twamp.controller.SenderConfig
import io.slogr.agent.engine.twamp.controller.SenderResult
import io.slogr.agent.engine.twamp.controller.TimingMode
import io.slogr.agent.engine.twamp.controller.TwampController
import io.slogr.agent.engine.twamp.responder.TwampReflector
import io.slogr.agent.native.NativeProbeAdapter
import org.slf4j.LoggerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Full [MeasurementEngine] implementation: TWAMP + traceroute + ASN + SLA evaluation.
 *
 * Manages its own [TwampController] and [TwampReflector] lifecycle. Traceroute runs
 * via [TracerouteOrchestrator] with the ICMP → TCP/443 → UDP fallback chain.
 * Path changes are tracked per-path in [PathChangeDetector].
 *
 * @param adapter               UDP/JNI socket adapter.
 * @param asnResolver           ASN lookup; defaults to [NullAsnResolver] (graceful degradation).
 * @param agentId               This agent's UUID.
 * @param localIp               Local IP for sockets; defaults to 0.0.0.0 (any).
 * @param keyStore              Optional map of keyId → shared secret for authenticated sessions.
 * @param reflectorListenPort   Port for the embedded TWAMP responder (default 862; use 0 for tests).
 */
class MeasurementEngineImpl(
    private val adapter: NativeProbeAdapter,
    private val asnResolver: AsnResolver = NullAsnResolver(),
    private val agentId: UUID = UUID.randomUUID(),
    private val localIp: InetAddress = InetAddress.getByName("0.0.0.0"),
    private val keyStore: Map<String, ByteArray> = emptyMap(),
    reflectorListenPort: Int = 862,
    private val startReflector: Boolean = true,
    private val testPort: Int = 0
) : MeasurementEngine {

    private val log = LoggerFactory.getLogger(MeasurementEngineImpl::class.java)
    private val reflector              = TwampReflector(adapter = adapter, listenPort = reflectorListenPort, bindIp = localIp, testPort = testPort)
    // Controller port is wired to the reflector's actual port after binding so that loopback
    // tests can use an ephemeral port (reflectorListenPort=0) without needing privileged ports.
    private val controller: TwampController
    private val tracerouteOrchestrator = TracerouteOrchestrator(adapter, asnResolver)
    private val pathChangeDetector     = PathChangeDetector()

    /** The actual TCP port the embedded TWAMP responder is listening on. */
    val reflectorActualPort: Int get() = reflector.actualPort

    init {
        if (startReflector) {
            reflector.start()
            // Wait up to 3 s for the reflector to bind its port (needed when listenPort=0)
            val deadline = System.currentTimeMillis() + 3_000L
            while (reflector.actualPort == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
            }
            controller = TwampController(adapter = adapter, port = reflector.actualPort, localIp = localIp)
        } else {
            // Client-only mode: no reflector needed.
            // Wire controller directly to reflectorListenPort (default 862).
            // The remote agent's daemon is the reflector.
            controller = TwampController(adapter = adapter, port = reflectorListenPort, localIp = localIp)
        }
        controller.start()
    }

    // ── MeasurementEngine ─────────────────────────────────────────────────────

    override suspend fun measure(
        target: InetAddress,
        targetPort: Int,
        profile: SlaProfile,
        traceroute: Boolean,
        authMode: TwampAuthMode,
        keyId: String?
    ): MeasurementBundle {
        val pathId  = UUID.randomUUID()
        val twampResult = runTwamp(target, targetPort, profile, authMode, keyId, pathId)
        val grade   = SlaEvaluator.evaluate(twampResult, profile)
        val graded  = twampResult.copy(grade = grade)

        if (!traceroute) {
            return MeasurementBundle(twamp = graded, grade = grade)
        }

        val rawTrace = tracerouteOrchestrator.run(
            target    = target,
            sessionId = twampResult.sessionId,
            pathId    = pathId,
            direction = Direction.UPLINK,
            tcpPort   = targetPort
        )
        val detection  = pathChangeDetector.detect(rawTrace)

        return MeasurementBundle(
            twamp      = graded,
            traceroute = detection.traceroute,
            pathChange = detection.pathChange,
            grade      = grade
        )
    }

    override suspend fun twamp(
        target: InetAddress,
        targetPort: Int,
        profile: SlaProfile,
        authMode: TwampAuthMode,
        keyId: String?
    ): MeasurementResult = runTwamp(target, targetPort, profile, authMode, keyId, UUID.randomUUID())

    override suspend fun traceroute(
        target: InetAddress,
        maxHops: Int,
        probesPerHop: Int,
        timeoutMs: Int,
        mode: TracerouteMode?
    ): TracerouteResult = tracerouteOrchestrator.run(
        target       = target,
        sessionId    = UUID.randomUUID(),
        pathId       = UUID.randomUUID(),
        direction    = Direction.UPLINK,
        maxHops      = maxHops,
        probesPerHop = probesPerHop,
        timeoutMs    = timeoutMs,
        mode         = mode
    )

    override fun shutdown() {
        controller.stop()
        reflector.stop()
    }

    // ── Internal TWAMP runner ─────────────────────────────────────────────────

    private suspend fun runTwamp(
        target: InetAddress,
        targetPort: Int,
        profile: SlaProfile,
        authMode: TwampAuthMode,
        keyId: String?,
        pathId: UUID
    ): MeasurementResult = withContext(Dispatchers.IO) {
        val sessionId  = UUID.randomUUID()
        val config     = profile.toSenderConfig()
        val modeChain  = buildModeChain(authMode)
        val secret     = keyId?.let { keyStore[it] }

        val timeoutMs  = config.count * config.intervalMs + config.waitTimeMs + 5000L
        val result: SenderResult = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation {
                    // Coroutine cancelled by withTimeoutOrNull — controller will
                    // purge the dead session on its next selector iteration.
                }
                controller.connect(
                    reflectorIp   = target,
                    reflectorPort = targetPort,
                    config        = config,
                    modeChain     = modeChain,
                    sharedSecret  = secret,
                    onComplete    = { r -> cont.resume(r) }
                )
            }
        } ?: SenderResult(emptyList(), config.count, 0, error = "session timed out")

        if (result.error != null) {
            log.warn("TWAMP to $target:$targetPort failed: ${result.error}")
        } else {
            log.info("TWAMP to $target:$targetPort completed: sent=${result.packetsSent} recv=${result.packetsRecv}")
        }

        MeasurementAssembler.assemble(
            result        = result,
            sessionId     = sessionId,
            pathId        = pathId,
            sourceAgentId = agentId,
            destAgentId   = UUID.fromString("00000000-0000-0000-0000-000000000000"),
            srcCloud      = "unknown",
            srcRegion     = "unknown",
            dstCloud      = "unknown",
            dstRegion     = "unknown",
            profile       = profile
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun SlaProfile.toSenderConfig() = SenderConfig(
        count                = nPackets,
        intervalMs           = intervalMs,
        waitTimeMs           = waitTimeMs,
        paddingLength        = (packetSize - 14).coerceAtLeast(0),
        dscp                 = dscp,
        timingMode           = if (timingMode == io.slogr.agent.contracts.TimingMode.POISSON)
                                   TimingMode.POISSON else TimingMode.FIXED_INTERVAL,
        poissonMaxIntervalMs = poissonMaxInterval ?: 0L
    )

    private fun buildModeChain(authMode: TwampAuthMode): ModePreferenceChain =
        ModePreferenceChain().apply {
            when (authMode) {
                TwampAuthMode.AUTHENTICATED -> {
                    prefer(TwampMode.AUTHENTICATED)
                    prefer(TwampMode.UNAUTHENTICATED)
                }
                TwampAuthMode.ENCRYPTED -> {
                    prefer(TwampMode.ENCRYPTED)
                    prefer(TwampMode.AUTHENTICATED)
                    prefer(TwampMode.UNAUTHENTICATED)
                }
                TwampAuthMode.UNAUTHENTICATED ->
                    prefer(TwampMode.UNAUTHENTICATED)
            }
        }
}
