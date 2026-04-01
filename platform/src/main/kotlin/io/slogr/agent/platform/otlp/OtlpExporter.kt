package io.slogr.agent.platform.otlp

import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader
import io.opentelemetry.sdk.resources.Resource
import io.slogr.agent.contracts.MeasurementBundle
import io.slogr.agent.platform.output.FallbackBundle
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID

/**
 * Wraps the OTel SDK and exports metrics to an OTLP/HTTP endpoint.
 *
 * - In `check` mode (immediate flush): call [record] then [flush].
 * - In `daemon` mode: [record] batches and the SDK exports on its own schedule.
 *
 * Gracefully disabled when [endpoint] is null.
 */
class OtlpExporter(
    private val endpoint: String?,
    private val agentId: UUID,
    /** Flush interval for daemon mode; check mode always flushes immediately. */
    intervalSeconds: Long = 10L
) {
    private val log = LoggerFactory.getLogger(OtlpExporter::class.java)

    private val meterProvider: SdkMeterProvider?
    private val meter: Meter?

    init {
        if (endpoint != null) {
            val exporter = OtlpHttpMetricExporter.builder()
                .setEndpoint(endpoint)
                .build()
            val reader = PeriodicMetricReader.builder(exporter)
                .setInterval(Duration.ofSeconds(intervalSeconds))
                .build()
            meterProvider = SdkMeterProvider.builder()
                .setResource(Resource.getDefault())
                .registerMetricReader(reader)
                .build()
            meter = meterProvider.get("slogr.agent")
            log.info("OTLP exporter initialised → $endpoint")
        } else {
            meterProvider = null
            meter         = null
        }
    }

    /** Record a TWAMP measurement bundle. No-op when OTLP is disabled. */
    fun record(bundle: MeasurementBundle, profileName: String) {
        val m = meter ?: return
        MetricMapper.recordTwamp(m, bundle, agentId, profileName)
    }

    /** Record a fallback (ICMP/TCP) measurement bundle. No-op when OTLP is disabled. */
    fun record(bundle: FallbackBundle) {
        val m = meter ?: return
        MetricMapper.recordFallback(m, bundle, agentId)
    }

    /**
     * Force-flush pending metrics.  Used in `check` mode to ensure single-shot
     * measurements are exported before the process exits.
     */
    fun flush() {
        meterProvider?.forceFlush()
    }

    /** Shutdown the SDK cleanly. */
    fun shutdown() {
        meterProvider?.shutdown()
    }

    val isEnabled: Boolean get() = endpoint != null
}
