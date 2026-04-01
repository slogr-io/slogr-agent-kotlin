package io.slogr.agent.engine.twamp.protocol

import io.slogr.agent.engine.twamp.SessionId
import io.slogr.agent.engine.twamp.TwampCommand
import io.slogr.agent.engine.twamp.TwampMode
import io.slogr.agent.engine.twamp.auth.TwampCrypto
import java.nio.ByteBuffer

/**
 * TWAMP Start-N-Sessions (variable length). RFC 5938 §3.3
 *
 * Wire layout (sent by controller → reflector):
 *    1 byte  type = 7
 *   11 bytes MBZ
 *    4 bytes noSession (count of SIDs that follow)
 *   [noSession × 16 bytes] SIDs
 *   16 bytes HMAC
 *
 * When reading: the ByteBuffer is positioned AFTER the command byte.
 */
class StartNSession {
    var sids: List<SessionId> = emptyList()

    fun writeTo(bb: ByteBuffer, mode: TwampMode) {
        val startPos = bb.position()
        bb.put(TwampCommand.START_N_SESSION)
        bb.put(ByteArray(11))  // MBZ
        bb.putInt(sids.size)
        for (sid in sids) bb.put(sid.toByteArray())
        val hmac = if (mode.isControlEncrypted()) {
            val msgBytes = ByteArray(bb.position() - startPos).also {
                bb.duplicate().also { d -> d.position(startPos) }.get(it)
            }
            TwampCrypto.hmacSha1Truncated(requireNotNull(mode.hmacKey), msgBytes)
        } else {
            ByteArray(16)
        }
        bb.put(hmac)
    }

    companion object {
        fun readFrom(bb: ByteBuffer, mode: TwampMode): StartNSession {
            val hmacStart = bb.position() - 1
            repeat(11) { bb.get() }  // MBZ
            val count = bb.int
            val sids = (0 until count).map { SessionId.fromByteArray(ByteArray(16).also { bb.get(it) }) }
            val hmac = ByteArray(16).also { bb.get(it) }
            if (mode.isControlEncrypted()) {
                val msgBytes = ByteArray(bb.position() - 16 - hmacStart).also {
                    bb.duplicate().also { d -> d.position(hmacStart) }.get(it)
                }
                val expected = TwampCrypto.hmacSha1Truncated(requireNotNull(mode.hmacKey), msgBytes)
                if (!hmac.contentEquals(expected)) {
                    throw SecurityException("HMAC validation failed for Start-N-Sessions")
                }
            }
            return StartNSession().also { it.sids = sids }
        }
    }
}
