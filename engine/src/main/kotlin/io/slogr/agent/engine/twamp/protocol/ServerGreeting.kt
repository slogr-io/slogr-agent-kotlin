package io.slogr.agent.engine.twamp.protocol

import io.slogr.agent.engine.twamp.TwampConstants
import java.nio.ByteBuffer

/**
 * TWAMP ServerGreeting (64 bytes). RFC 5357 §3.1
 *
 * Wire layout:
 *   12 bytes unused   ← Slogr extension: fingerprint when server is a Slogr agent
 *    4 bytes modes    ← supported TWAMP mode bitmask
 *   16 bytes challenge
 *   16 bytes salt
 *    4 bytes count    ← PBKDF2 iteration count; MUST be ≥ 1024; no upper ceiling (FIX-1)
 *   12 bytes MBZ
 *
 * **Slogr fingerprint** (written in unused bytes by Slogr reflectors):
 *   - bytes 0–4:  ASCII "SLOGR"
 *   - bytes 5–6:  [TwampConstants.SLOGR_PROTOCOL_VERSION] as big-endian short
 *   - bytes 7–12: first 6 bytes of the agent's UUID (or zeros if unknown)
 *
 * FIX-1 applied: there is no maximum count ceiling. Any value ≥ MIN_COUNT is accepted.
 */
class ServerGreeting {
    var modes: Int = 0
    var challenge: ByteArray = ByteArray(16)
    var salt: ByteArray = ByteArray(16)
    var count: Int = TwampConstants.DEFAULT_COUNT

    /** True when the remote reflector wrote a Slogr fingerprint into the unused bytes. */
    var isSlogrAgent: Boolean = false

    /**
     * Serialize to [bb].
     *
     * @param agentIdBytes Optional first 6 bytes of the local agent's UUID; written into
     *                     fingerprint bytes 7–12. Pass null to zero-fill those bytes.
     */
    fun writeTo(bb: ByteBuffer, agentIdBytes: ByteArray? = null) {
        val unused = ByteArray(12)
        // Slogr fingerprint
        System.arraycopy(TwampConstants.SLOGR_MAGIC, 0, unused, 0, 5)
        ByteBuffer.wrap(unused, 5, 2).putShort(TwampConstants.SLOGR_PROTOCOL_VERSION)
        agentIdBytes?.let { id ->
            System.arraycopy(id, 0, unused, 7, minOf(id.size, 5))
        }
        bb.put(unused)
        bb.putInt(modes)
        bb.put(challenge)
        bb.put(salt)
        bb.putInt(count)
        bb.put(ByteArray(12)) // MBZ
    }

    companion object {
        const val SIZE = 64

        fun readFrom(bb: ByteBuffer): ServerGreeting {
            val msg = ServerGreeting()
            val unused = ByteArray(12)
            bb.get(unused)
            // Detect Slogr fingerprint
            msg.isSlogrAgent = unused.sliceArray(0 until 5).contentEquals(TwampConstants.SLOGR_MAGIC)
            msg.modes = bb.int
            bb.get(msg.challenge)
            bb.get(msg.salt)
            msg.count = bb.int
            repeat(12) { bb.get() } // skip MBZ
            return msg
        }
    }
}
