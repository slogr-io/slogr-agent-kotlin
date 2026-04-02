package io.slogr.agent.contracts.interfaces

import io.slogr.agent.contracts.MeasurementBundle
import io.slogr.agent.contracts.MeasurementResult
import io.slogr.agent.contracts.SlaProfile
import io.slogr.agent.contracts.TracerouteMode
import io.slogr.agent.contracts.TracerouteResult
import io.slogr.agent.contracts.TwampAuthMode
import java.net.InetAddress

interface MeasurementEngine {

    /**
     * Run a complete measurement: TWAMP + optional traceroute + ASN + SLA eval.
     * Target can be another Slogr agent or any RFC 5357 compliant TWAMP reflector.
     */
    suspend fun measure(
        target: InetAddress,
        targetPort: Int = 862,
        profile: SlaProfile,
        traceroute: Boolean = true,
        authMode: TwampAuthMode = TwampAuthMode.UNAUTHENTICATED,
        keyId: String? = null
    ): MeasurementBundle

    /** Run TWAMP only. */
    suspend fun twamp(
        target: InetAddress,
        targetPort: Int = 862,
        profile: SlaProfile,
        authMode: TwampAuthMode = TwampAuthMode.UNAUTHENTICATED,
        keyId: String? = null
    ): MeasurementResult

    /** Run traceroute only. */
    suspend fun traceroute(
        target: InetAddress,
        maxHops: Int = 30,
        probesPerHop: Int = 2,
        timeoutMs: Int = 2000,
        mode: TracerouteMode? = null  // null = auto (try ICMP, then UDP)
    ): TracerouteResult

    /**
     * Start the engine eagerly: bind the embedded TWAMP reflector to its port.
     * Must be called at daemon startup so the agent can act as a responder even
     * when no outbound schedule is configured. Default is a no-op (for test doubles).
     */
    fun start() {}

    /** Shutdown: cancel in-flight tests, release resources. */
    fun shutdown()
}
