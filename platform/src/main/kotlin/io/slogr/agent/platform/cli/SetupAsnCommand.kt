package io.slogr.agent.platform.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import java.io.File

class SetupAsnCommand(private val ctx: CliContext) : CliktCommand(name = "setup-asn") {
    override fun help(context: Context) = "Configure the MaxMind GeoLite2-ASN database for ASN enrichment"

    private val dbPath: String by option(
        "--db-path",
        help = "Path to the GeoLite2-ASN.mmdb file"
    ).default("")

    override fun run() {
        val path = dbPath.ifBlank { "${ctx.config.dataDir}/GeoLite2-ASN.mmdb" }
        val file = File(path)

        if (file.exists()) {
            val sizeMb = file.length() / (1024 * 1024)
            echo("ASN database found: $path (${sizeMb} MB)")
            echo("MaxMind ASN enrichment is active.")
        } else {
            echo("ASN database not found at: $path")
            echo("")
            echo("To enable ASN enrichment, download the GeoLite2-ASN database:")
            echo("  1. Register for a free MaxMind account at https://www.maxmind.com")
            echo("  2. Download GeoLite2-ASN.mmdb")
            echo("  3. Place it at: $path")
            echo("  4. Or specify a custom path: slogr-agent setup-asn --db-path <path>")
            echo("")
            echo("Running without ASN enrichment. Traceroute hops will show IPs only.")
        }
    }
}
