package io.slogr.agent.platform.scheduler

import io.slogr.agent.contracts.SlaProfile

/**
 * Built-in SLA profiles. Used by command handlers that receive profile names
 * from Layer 2.5 commands (e.g. `set_schedule`, `run_test`).
 *
 * All profiles use FIXED timing by default. Thresholds are conservative — any
 * tightening happens via a `push_config` command or a custom schedule JSON.
 */
object BuiltinProfiles {

    private val all: Map<String, SlaProfile> = mapOf(
        "internet" to SlaProfile(
            name           = "internet",
            nPackets       = 100,
            intervalMs     = 100L,
            waitTimeMs     = 2_000L,
            dscp           = 0,
            packetSize     = 172,
            rttGreenMs     = 80f,
            rttRedMs       = 250f,
            jitterGreenMs  = 20f,
            jitterRedMs    = 80f,
            lossGreenPct   = 1f,
            lossRedPct     = 5f
        ),
        "voip" to SlaProfile(
            name           = "voip",
            nPackets       = 100,
            intervalMs     = 20L,
            waitTimeMs     = 2_000L,
            dscp           = 46,
            packetSize     = 172,
            rttGreenMs     = 150f,
            rttRedMs       = 400f,
            jitterGreenMs  = 30f,
            jitterRedMs    = 100f,
            lossGreenPct   = 1f,
            lossRedPct     = 5f
        ),
        "mesh" to SlaProfile(
            name           = "mesh",
            nPackets       = 50,
            intervalMs     = 200L,
            waitTimeMs     = 2_000L,
            dscp           = 0,
            packetSize     = 172,
            rttGreenMs     = 100f,
            rttRedMs       = 300f,
            jitterGreenMs  = 20f,
            jitterRedMs    = 80f,
            lossGreenPct   = 1f,
            lossRedPct     = 5f
        )
    )

    fun byName(name: String): SlaProfile? = all[name]

    fun names(): Set<String> = all.keys
}
