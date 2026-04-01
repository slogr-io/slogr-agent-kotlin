package io.slogr.agent.engine.twamp.auth

import io.slogr.agent.engine.twamp.TwampMode

/**
 * Ordered list of preferred TWAMP mode bits for a controller.
 *
 * The controller populates this chain with the mode bits it supports, in
 * decreasing order of preference. When the server's capability bitmask is
 * known, [selectMode] returns the highest-preference mode bit that the
 * server also supports, or null if no overlap exists.
 *
 * Usage:
 * ```
 * val chain = ModePreferenceChain()
 *     .prefer(TwampMode.AUTHENTICATED)
 *     .prefer(TwampMode.UNAUTHENTICATED)
 * val modeBit = chain.selectMode(serverCapabilities)
 * ```
 */
class ModePreferenceChain {

    private val preferences: MutableList<Int> = mutableListOf()

    /**
     * Add [modeBit] (e.g. [TwampMode.AUTHENTICATED]) as the next (lower) preference.
     * Duplicate entries are ignored.
     */
    fun prefer(modeBit: Int): ModePreferenceChain {
        if (modeBit !in preferences) preferences.add(modeBit)
        return this
    }

    /**
     * Return the first mode bit in the preference list that is also set in
     * [serverModeBits], or null if no supported mode is found.
     */
    fun selectMode(serverModeBits: Int): Int? =
        preferences.firstOrNull { it and serverModeBits != 0 }
}
