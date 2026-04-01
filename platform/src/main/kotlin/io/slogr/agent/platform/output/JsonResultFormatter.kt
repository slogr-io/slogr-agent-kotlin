package io.slogr.agent.platform.output

import io.slogr.agent.contracts.MeasurementBundle
import io.slogr.agent.contracts.TracerouteResult
import kotlinx.serialization.json.*
import java.net.InetAddress

/**
 * JSON formatter for TWAMP and fallback results.
 *
 * The `measurement_method` field is `"twamp"` or `"icmp"` to distinguish code paths.
 */
class JsonResultFormatter : ResultFormatter {

    private val json = Json { prettyPrint = true }

    override fun format(target: InetAddress, bundle: MeasurementBundle, profileName: String): String {
        val t   = bundle.twamp
        val obj = buildJsonObject {
            put("measurement_method", "twamp")
            put("target", target.hostAddress)
            put("profile", profileName)
            put("grade", bundle.grade.name)
            put("twamp", buildJsonObject {
                put("packets_sent", t.packetsSent)
                put("packets_recv", t.packetsRecv)
                put("fwd_loss_pct", t.fwdLossPct)
                put("fwd_min_rtt_ms", t.fwdMinRttMs)
                put("fwd_avg_rtt_ms", t.fwdAvgRttMs)
                put("fwd_max_rtt_ms", t.fwdMaxRttMs)
                put("fwd_jitter_ms", t.fwdJitterMs)
                val revAvg = t.revAvgRttMs
                if (revAvg != null) {
                    put("rev_min_rtt_ms", t.revMinRttMs ?: 0f)
                    put("rev_avg_rtt_ms", revAvg)
                    put("rev_max_rtt_ms", t.revMaxRttMs ?: 0f)
                    put("rev_jitter_ms", t.revJitterMs ?: 0f)
                }
                put("session_id", t.sessionId.toString())
                put("path_id", t.pathId.toString())
            })
            bundle.traceroute?.let { put("traceroute", tracerouteJson(it)) }
            bundle.pathChange?.let {
                put("path_change", buildJsonObject {
                    put("prev_asn_path", it.prevAsnPath.joinToString(","))
                    put("new_asn_path", it.newAsnPath.joinToString(","))
                    put("changed_hop_ttl", it.changedHopTtl)
                })
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    override fun formatFallback(bundle: FallbackBundle): String {
        val ping = bundle.ping
        val tcp  = bundle.tcp
        val obj  = buildJsonObject {
            put("measurement_method", "icmp")
            put("target", bundle.target.hostAddress)
            put("profile", bundle.profile.name)
            put("grade", bundle.grade.name)
            put("ping", buildJsonObject {
                put("sent", ping.sent)
                put("received", ping.received)
                put("loss_pct", ping.lossPct)
                ping.minRttMs?.let { put("min_rtt_ms", it) }
                ping.avgRttMs?.let { put("avg_rtt_ms", it) }
                ping.maxRttMs?.let { put("max_rtt_ms", it) }
            })
            val connectMs = tcp.connectMs
            if (!tcp.skipped && connectMs != null) {
                put("tcp", buildJsonObject {
                    put("port", tcp.port ?: 0)
                    put("connect_ms", connectMs)
                })
            }
            bundle.traceroute?.let { put("traceroute", tracerouteJson(it)) }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun tracerouteJson(tr: TracerouteResult) = buildJsonObject {
        put("direction", tr.direction.name.lowercase())
        put("hops", buildJsonArray {
            for (hop in tr.hops) {
                add(buildJsonObject {
                    put("ttl", hop.ttl)
                    hop.ip?.let { put("ip", it) }
                    hop.rttMs?.let { put("rtt_ms", it) }
                    put("loss_pct", hop.lossPct)
                    hop.asn?.let { put("asn", it) }
                    hop.asnName?.let { put("asn_name", it) }
                })
            }
        })
    }
}
