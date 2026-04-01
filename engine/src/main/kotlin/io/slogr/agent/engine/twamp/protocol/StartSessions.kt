package io.slogr.agent.engine.twamp.protocol

import io.slogr.agent.engine.twamp.TwampCommand
import io.slogr.agent.engine.twamp.TwampMode
import io.slogr.agent.engine.twamp.auth.TwampCrypto
import java.nio.ByteBuffer

/**
 * TWAMP Start-Sessions (32 bytes). RFC 5357 §3.7
 *
 * Wire layout (sent by controller → reflector):
 *    1 byte  type = 2
 *   15 bytes MBZ
 *   16 bytes HMAC
 *
 * When reading: the ByteBuffer is positioned AFTER the command byte.
 */
class StartSessions {
    // No payload fields — just type + MBZ + HMAC

    fun writeTo(bb: ByteBuffer, mode: TwampMode) {
        val startPos = bb.position()
        bb.put(TwampCommand.START_SESSIONS)
        bb.put(ByteArray(15))  // MBZ
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
        const val SIZE = 32

        fun readFrom(bb: ByteBuffer, mode: TwampMode): StartSessions {
            val hmacStart = bb.position() - 1
            repeat(15) { bb.get() }  // MBZ
            val hmac = ByteArray(16).also { bb.get(it) }
            if (mode.isControlEncrypted()) {
                val msgBytes = ByteArray(bb.position() - 16 - hmacStart).also {
                    bb.duplicate().also { d -> d.position(hmacStart) }.get(it)
                }
                val expected = TwampCrypto.hmacSha1Truncated(requireNotNull(mode.hmacKey), msgBytes)
                if (!hmac.contentEquals(expected)) {
                    throw SecurityException("HMAC validation failed for Start-Sessions")
                }
            }
            return StartSessions()
        }
    }
}
