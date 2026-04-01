package io.slogr.agent.platform.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import java.util.Properties

class VersionCommand(private val ctx: CliContext) : CliktCommand(name = "version") {
    override fun help(context: Context) = "Print version information"

    override fun run() {
        val props   = loadVersionProps()
        val version = props.getProperty("version", "unknown")
        val commit  = props.getProperty("git.commit", "unknown")
        val built   = props.getProperty("build.time", "unknown")
        echo("slogr-agent $version ($commit) built $built")
    }

    private fun loadVersionProps(): Properties {
        val props  = Properties()
        val stream = javaClass.getResourceAsStream("/version.properties")
        stream?.use { props.load(it) }
        return props
    }
}
