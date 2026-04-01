package io.slogr.agent.platform.output

import io.slogr.agent.contracts.MeasurementBundle
import java.net.InetAddress

/**
 * Formats measurement results for human or machine consumption.
 *
 * Implementations: [TextResultFormatter], [JsonResultFormatter].
 */
interface ResultFormatter {

    /**
     * Format a successful TWAMP measurement.
     *
     * @param target      The measured target (for display).
     * @param bundle      Full measurement bundle with TWAMP, optional traceroute, and grade.
     * @param profileName SLA profile name used for this measurement.
     */
    fun format(target: InetAddress, bundle: MeasurementBundle, profileName: String): String

    /**
     * Format a fallback measurement (ICMP ping + TCP connect + traceroute).
     * Used when no TWAMP responder is found on the target port.
     */
    fun formatFallback(bundle: FallbackBundle): String
}
