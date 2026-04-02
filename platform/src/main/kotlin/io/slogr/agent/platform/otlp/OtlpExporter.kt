package io.slogr.agent.platform.otlp

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader
import io.opentelemetry.sdk.resources.Resource
import io.slogr.agent.contracts.MeasurementBundle
import io.slogr.agent.platform.config.AgentState
import io.slogr.agent.platform.output.FallbackBundle
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID

/**
 * Wraps the OTel SDK and exports metrics to an OTLP/HTTP endpoint.
 *
 * **State gate**: export is skipped when [agentState] is [AgentState.ANONYMOUS].
 * A nudge is logged once to guide users towards getting an API key.
 *
 * - In `check` mode (immediate flush): call [record] then [flush].
 * - In `daemon` mode: [record] batches; the SDK exports on its own schedule.
 *
 * Gracefully disabled when [endpoint] is null.
 */
class OtlpExporter(
    private val endpoint: String?,
    private val agentId: UUID,
    /** Agent state determines whether OTLP export is allowed. */
    private val agentState: AgentState = AgentState.ANONYMOUS,
    /** API key forwarded in the Authorization header (REGISTERED or CONNECTED). */
    private val apiKey: String? = null,
    /** Tenant ID for OTLP resource attributes; comes from registration or key validation. */
    private val tenantId: String? = null,
    /** Cloud provider/region for resource attributes; detected at startup. */
    private val cloudProvider: String = "unknown",
    private val cloudRegion: String = "unknown",
    /** Agent version for resource attributes. */
    private val agentVersion: String = "1.0.0",
    /** Flush interval for daemon mode; check mode always flushes immediately. */
    intervalSeconds: Long = 10L
) {
    private val log = LoggerFactory.getLogger(OtlpExporter::class.java)

    private val meterProvider: SdkMeterProvider?
    private val meter: Meter?

    @Volatile private var nudgeLogged = false

    init {
        if (endpoint != null && agentState != AgentState.ANONYMOUS) {
            val exporterBuilder = OtlpHttpMetricExporter.builder()
                .setEndpoint(endpoint)
            apiKey?.let { exporterBuilder.addHeader("Authorization", "Bearer $it") }
            if (agentState == AgentState.CONNECTED) {
                exporterBuilder.addHeader("X-Slogr-Agent-Id", agentId.toString())
            }

            val resourceAttrs = buildResourceAttributes()
            val reader = PeriodicMetricReader.builder(exporterBuilder.build())
                .setInterval(Duration.ofSeconds(intervalSeconds))
                .build()
            meterProvider = SdkMeterProvider.builder()
                .setResource(Resource.getDefault().merge(Resource.create(resourceAttrs)))
                .registerMetricReader(reader)
                .build()
            meter = meterProvider.get("slogr.agent")
            log.info("OTLP exporter initialised → $endpoint (state=${agentState.name})")
        } else {
            meterProvider = null
            meter         = null
        }
    }

    /** Record a TWAMP measurement bundle. No-op when OTLP is disabled or state is ANONYMOUS. */
    fun record(bundle: MeasurementBundle, profileName: String) {
        if (!checkGate()) return
        val m = meter ?: return
        MetricMapper.recordTwamp(m, bundle, agentId, profileName)
    }

    /** Record a fallback (ICMP/TCP) measurement bundle. No-op when OTLP is disabled or ANONYMOUS. */
    fun record(bundle: FallbackBundle) {
        if (!checkGate()) return
        val m = meter ?: return
        MetricMapper.recordFallback(m, bundle, agentId)
    }

    /**
     * Force-flush pending metrics. Used in `check` mode to ensure single-shot
     * measurements are exported before the process exits.
     */
    fun flush() {
        meterProvider?.forceFlush()
    }

    /** Shutdown the SDK cleanly. */
    fun shutdown() {
        meterProvider?.shutdown()
    }

    val isEnabled: Boolean get() = endpoint != null && agentState != AgentState.ANONYMOUS

    // ── Internal ───────────────────────────────────────────────────────────────

    /** Returns true if export may proceed; logs nudge once if ANONYMOUS. */
    private fun checkGate(): Boolean {
        if (agentState == AgentState.ANONYMOUS) {
            if (!nudgeLogged) {
                log.info("OTLP export requires a Slogr API key. Get one free at https://slogr.io/keys")
                nudgeLogged = true
            }
            return false
        }
        return true
    }

    private fun buildResourceAttributes(): Attributes {
        val hostname = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("unknown")
        val builder = Attributes.builder()
            .put("service.name", "slogr-agent")
            .put("service.version", agentVersion)
            .put("host.name", hostname)
            .put("cloud.provider", cloudProvider)
            .put("cloud.region", cloudRegion)
            .put("slogr.agent_state", agentState.name.lowercase())
            .put("slogr.agent_version", agentVersion)

        val agentIdStr = if (agentState == AgentState.CONNECTED) agentId.toString() else "unregistered"
        builder.put("slogr.agent_id", agentIdStr)

        tenantId?.let { builder.put("slogr.tenant_id", it) }

        return builder.build()
    }
}
