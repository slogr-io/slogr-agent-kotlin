package io.slogr.agent.platform.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import io.slogr.agent.platform.config.AirGapDetector
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Diagnostic command — verifies the agent environment is correctly configured.
 *
 * Checks:
 * 1. JNI native library (`libslogr-native.so`) — required for TWAMP and traceroute on Linux
 * 2. `CAP_NET_RAW` capability — Linux only; required for ICMP ping and traceroute
 * 3. TLS handshake to `slogr.io:443` — skipped when air-gapped
 *
 * Exit codes:
 * - 0: all checks passed (or skipped)
 * - 1: one or more checks failed
 *
 * All check functions are injectable so unit tests can simulate failure/success
 * without modifying the host environment.
 */
class DoctorCommand(
    @Suppress("unused") private val ctx: CliContext,
    internal val jniChecker:    () -> Boolean = { defaultCheckJni() },
    internal val capChecker:    () -> Boolean = { defaultCheckCapNetRaw() },
    internal val tlsChecker:    () -> Boolean = { defaultCheckTls("slogr.io", 443) },
    internal val airGapChecker: () -> Boolean = { AirGapDetector.isAirGapped() },
    internal val isLinux:       Boolean = System.getProperty("os.name", "")
                                              .lowercase().contains("linux")
) : CliktCommand(name = "doctor") {

    override fun help(context: Context) =
        "Run diagnostics: verify JNI, CAP_NET_RAW, and TLS connectivity"

    override fun run() {
        data class Check(val name: String, val pass: Boolean, val remediation: String?)

        val airGapped = airGapChecker()
        val checks    = mutableListOf<Check>()

        // 1. JNI
        val jniOk = jniChecker()
        checks += Check(
            name        = "JNI native library",
            pass        = jniOk,
            remediation = if (!jniOk)
                "JNI library not found. Copy libslogr-native.so to /usr/lib/slogr/ and restart:\n" +
                "  java -Djava.library.path=/usr/lib/slogr -jar slogr-agent.jar"
            else null
        )

        // 2. CAP_NET_RAW (Linux only)
        if (isLinux) {
            val capOk = capChecker()
            checks += Check(
                name        = "CAP_NET_RAW",
                pass        = capOk,
                remediation = if (!capOk)
                    "CAP_NET_RAW required for ICMP ping and traceroute.\n" +
                    "  Run: sudo setcap cap_net_raw+ep \$(which java)"
                else null
            )
        }

        // 3. TLS (skip when air-gapped)
        if (airGapped) {
            echo("Air-gapped environment detected — skipping TLS/API checks")
        } else {
            val tlsOk = tlsChecker()
            checks += Check(
                name        = "TLS to slogr.io",
                pass        = tlsOk,
                remediation = if (!tlsOk)
                    "Cannot reach slogr.io:443. Check firewall / proxy settings."
                else null
            )
        }

        // ── Print summary ─────────────────────────────────────────────────────
        echo("")
        for (c in checks) {
            val icon = if (c.pass) "✓" else "✗"
            echo("  $icon ${c.name}: ${if (c.pass) "OK" else "FAIL"}")
        }
        echo("")

        val failed = checks.filter { !it.pass }
        if (failed.isEmpty()) {
            echo("All checks passed.")
        } else {
            for (c in failed) {
                c.remediation?.let { echo("Remediation — ${c.name}:\n  $it\n") }
            }
            throw ProgramResult(1)
        }
    }
}

// ── Default check implementations ─────────────────────────────────────────────

internal fun defaultCheckJni(): Boolean = runCatching {
    Class.forName("io.slogr.agent.native.SlogrNative")
    true
}.getOrDefault(false)

internal fun defaultCheckCapNetRaw(): Boolean = runCatching {
    // Read /proc/self/status; CapEff bit 13 = CAP_NET_RAW
    val status = java.io.File("/proc/self/status").readText()
    val capEff = status.lines()
        .firstOrNull { it.startsWith("CapEff:") }
        ?.substringAfter("CapEff:")
        ?.trim()
        ?.toLong(16) ?: 0L
    (capEff and (1L shl 13)) != 0L
}.getOrDefault(false)

internal fun defaultCheckTls(host: String, port: Int): Boolean = runCatching {
    (SSLSocketFactory.getDefault().createSocket(host, port) as SSLSocket)
        .use { it.startHandshake() }
    true
}.getOrDefault(false)
