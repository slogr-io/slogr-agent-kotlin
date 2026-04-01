package io.slogr.agent.platform.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/**
 * Root Clikt command group for `slogr-agent`.
 */
class SlogrCli(ctx: CliContext) : CliktCommand(name = "slogr-agent") {
    init {
        subcommands(
            VersionCommand(ctx),
            StatusCommand(ctx),
            CheckCommand(ctx),
            DaemonCommand(ctx),
            ConnectCommand(ctx),
            DisconnectCommand(ctx),
            SetupAsnCommand(ctx)
        )
    }

    override fun help(context: Context): String = "Slogr network measurement agent"

    override fun run() {
        if (currentContext.invokedSubcommand == null) {
            echo(getFormattedHelp())
        }
    }
}
