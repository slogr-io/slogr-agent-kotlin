package io.slogr.agent.engine

import io.slogr.agent.contracts.MeasurementBundle
import io.slogr.agent.contracts.MeasurementResult
import io.slogr.agent.contracts.SlaGrade
import io.slogr.agent.contracts.SlaProfile
import io.slogr.agent.contracts.TracerouteMode
import io.slogr.agent.contracts.TracerouteResult
import io.slogr.agent.contracts.TwampAuthMode
import io.slogr.agent.contracts.interfaces.MeasurementEngine
import io.slogr.agent.engine.twamp.MeasurementAssembler
import io.slogr.agent.engine.twamp.TwampConstants
import io.slogr.agent.engine.twamp.TwampMode
import io.slogr.agent.engine.twamp.auth.ModePreferenceChain
import io.slogr.agent.engine.twamp.controller.SenderConfig
import io.slogr.agent.engine.twamp.controller.SenderResult
import io.slogr.agent.engine.twamp.controller.TimingMode
import io.slogr.agent.engine.twamp.controller.TwampController
import io.slogr.agent.engine.twamp.responder.TwampReflector
import io.slogr.agent.native.NativeProbeAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Partial [MeasurementEngine] implementation: TWAMP only.
 *
 * Traceroute, ASN, and path-change detection are added in Phase 4
 * ([MeasurementEngineImpl]).
 *
 * @param adapter       UDP socket adapter.
 * @param agentId       This agent's UUID (embedded in result + Slogr fingerprint).
 * @param localIp       Local IP for sender and reflector sockets.
 * @param keyStore      Optional map of keyId → shared secret for authenticated sessions.
 */
class TwampOnlyEngine(
    private val adapter: NativeProbeAdapter,
    private val agentId: UUID = UUID.randomUUID(),
    private val localIp: InetAddress = InetAddress.getByName("0.0.0.0"),
    private val keyStore: Map<String, ByteArray> = emptyMap()
) : MeasurementEngine {

    private val controller = TwampController(adapter = adapter, localIp = localIp)
    private val reflector  = TwampReflector(adapter = adapter, bindIp = localIp)

    init {
        controller.start()
        reflector.start()
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
        val twampResult = twamp(target, targetPort, profile, authMode, keyId)
        return MeasurementBundle(
            twamp  = twampResult,
            grade  = twampResult.grade ?: SlaGrade.GREEN
        )
    }

    override suspend fun twamp(
        target: InetAddress,
        targetPort: Int,
        profile: SlaProfile,
        authMode: TwampAuthMode,
        keyId: String?
    ): MeasurementResult = withContext(Dispatchers.IO) {
        val sessionId = UUID.randomUUID()
        val config = profile.toSenderConfig()
        val modeChain = buildModeChain(authMode)
        val secret = keyId?.let { keyStore[it] }

        val timeoutMs = config.count * config.intervalMs + config.waitTimeMs + 5000L
        val result: SenderResult = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                controller.connect(
                    reflectorIp  = target,
                    config       = config,
                    modeChain    = modeChain,
                    sharedSecret = secret,
                    onComplete   = { result -> cont.resume(result) }
                )
            }
        } ?: SenderResult(emptyList(), config.count, 0, error = "session timed out")

        MeasurementAssembler.assemble(
            result        = result,
            sessionId     = sessionId,
            pathId        = UUID.randomUUID(),
            sourceAgentId = agentId,
            destAgentId   = UUID.fromString("00000000-0000-0000-0000-000000000000"),
            srcCloud      = "unknown",
            srcRegion     = "unknown",
            dstCloud      = "unknown",
            dstRegion     = "unknown",
            profile       = profile
        )
    }

    override suspend fun traceroute(
        target: InetAddress,
        maxHops: Int,
        probesPerHop: Int,
        timeoutMs: Int,
        mode: TracerouteMode?
    ): TracerouteResult = TODO("Phase 4: traceroute implementation")

    override fun shutdown() {
        controller.stop()
        reflector.stop()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun SlaProfile.toSenderConfig() = SenderConfig(
        count          = nPackets,
        intervalMs     = intervalMs,
        waitTimeMs     = waitTimeMs,
        paddingLength  = (packetSize - 14).coerceAtLeast(0),
        dscp           = dscp,
        timingMode     = if (timingMode == io.slogr.agent.contracts.TimingMode.POISSON)
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
