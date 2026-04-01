package io.slogr.agent.platform.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import io.slogr.agent.contracts.Direction
import io.slogr.agent.contracts.SlaGrade
import io.slogr.agent.contracts.TwampAuthMode
import io.slogr.agent.engine.sla.ProfileRegistry
import io.slogr.agent.engine.sla.SlaEvaluator
import io.slogr.agent.platform.output.FallbackBundle
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.util.UUID

/**
 * Runs a one-shot measurement against [target].
 *
 * **TWAMP mode** (default): tries RFC 5357 TWAMP on `--port`.
 *
 * **Fallback mode**: if the TWAMP session returns 0 packets received
 * (no responder / connection refused / timeout), falls back to:
 * 1. ICMP ping  — 5 probes, TTL=64
 * 2. TCP connect — port 443 then 80
 * 3. Traceroute  — ICMP→TCP→UDP fallback chain (always when --traceroute or ping fails)
 *
 * Exit codes: 0 = OK/GREEN, 3 = unreachable, 1 = other error.
 */
class CheckCommand(private val ctx: CliContext) : CliktCommand(name = "check") {
    override fun help(context: Context) = "Run a one-shot measurement against a target"

    private val log = LoggerFactory.getLogger(CheckCommand::class.java)

    private val target: String by argument(help = "Target hostname or IP address")

    private val port: Int by option(
        "--port", "-p",
        help = "TWAMP target port (default: $DEFAULT_TWAMP_PORT)"
    ).int().default(DEFAULT_TWAMP_PORT)

    private val profileName: String by option(
        "--profile",
        help = "SLA profile name (default: internet)"
    ).default("internet")

    private val format: String by option(
        "--format",
        help = "Output format: text or json"
    ).default("text")

    private val withTraceroute: Boolean by option(
        "--traceroute",
        help = "Include traceroute"
    ).flag(default = false)

    override fun run() = runBlocking<Unit> {
        val targetIp  = resolveTarget()
        val profile   = ProfileRegistry.get(profileName) ?: ProfileRegistry.get("internet")!!
        val formatter = if (format == "json") ctx.jsonFormatter else ctx.textFormatter

        // ── 1. Try TWAMP ────────────────────────────────────────────────────────
        val twampBundle = try {
            ctx.engine.measure(
                target     = targetIp,
                targetPort = port,
                profile    = profile,
                traceroute = withTraceroute,
                authMode   = TwampAuthMode.UNAUTHENTICATED
            )
        } catch (e: Exception) {
            log.debug("TWAMP attempt threw exception: ${e.message}")
            null
        }

        if (twampBundle != null && twampBundle.twamp.packetsRecv > 0) {
            echo(formatter.format(targetIp, twampBundle, profileName))
            ctx.otlpExporter.record(twampBundle, profileName)
            ctx.otlpExporter.flush()
            return@runBlocking
        }

        // ── 2. No TWAMP responder — fall back to ICMP/TCP probes ───────────────
        log.debug("No TWAMP response from $targetIp:$port — using fallback probes")

        val ping      = ctx.icmpPingProbe.ping(targetIp, count = 5)
        val tcp       = ctx.tcpConnectProbe.probe(targetIp)
        val needTrace = withTraceroute || ping.received == 0
        val traceroute = if (needTrace) {
            try {
                ctx.tracerouteOrchestrator.run(
                    target    = targetIp,
                    sessionId = UUID.randomUUID(),
                    pathId    = UUID.randomUUID(),
                    direction = Direction.UPLINK
                )
            } catch (e: Exception) {
                log.debug("Traceroute failed: ${e.message}")
                null
            }
        } else null

        if (ping.received == 0 && tcp.skipped) {
            echo("error: target $target is unreachable (TWAMP, ICMP, and TCP all failed)", err = true)
            throw ProgramResult(3)
        }

        val grade = computeGrade(ping.avgRttMs, ping.lossPct, profile)

        val fallback = FallbackBundle(
            target     = targetIp,
            ping       = ping,
            tcp        = tcp,
            traceroute = traceroute,
            grade      = grade,
            profile    = profile
        )

        echo(formatter.formatFallback(fallback))
        ctx.otlpExporter.record(fallback)
        ctx.otlpExporter.flush()
    }

    private fun resolveTarget(): InetAddress = try {
        InetAddress.getByName(target)
    } catch (e: Exception) {
        echo("error: cannot resolve '$target': ${e.message}", err = true)
        throw ProgramResult(3)
    }

    private fun computeGrade(
        avgRttMs: Float?,
        lossPct: Float,
        profile: io.slogr.agent.contracts.SlaProfile
    ): SlaGrade {
        if (avgRttMs == null) return SlaGrade.RED
        return when {
            avgRttMs > profile.rttRedMs    || lossPct > profile.lossRedPct    -> SlaGrade.RED
            avgRttMs > profile.rttGreenMs  || lossPct > profile.lossGreenPct  -> SlaGrade.YELLOW
            else                                                                -> SlaGrade.GREEN
        }
    }

    private companion object {
        const val DEFAULT_TWAMP_PORT = 862
    }
}
