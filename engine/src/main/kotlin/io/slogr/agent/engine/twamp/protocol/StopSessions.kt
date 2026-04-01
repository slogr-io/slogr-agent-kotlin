package io.slogr.agent.engine.twamp.protocol

import io.slogr.agent.engine.twamp.TwampCommand
import io.slogr.agent.engine.twamp.TwampMode
import io.slogr.agent.engine.twamp.auth.TwampCrypto
import java.nio.ByteBuffer

/**
 * TWAMP Stop-Sessions (32 bytes). RFC 5357 §3.8
 *
 * Wire layout (sent by controller or reflector):
 *    1 byte  type = 3
 *    1 byte  accept
 *    2 bytes MBZ
 *    4 bytes sessionCount (informational — see FIX-2)
 *    8 bytes MBZ2
 *   16 bytes HMAC
 *
 * **FIX-2**: [sessionCount] is informational per RFC 5357 §3.8:
 *   "The Number of Sessions field is informational; the receiver MUST accept
 *    the Stop-Sessions command regardless of this value."
 * The Java reference incorrectly closed the connection when sessionCount did not
 * match the active session count. The Kotlin port ignores this field on receipt.
 *
 * When reading: the ByteBuffer is positioned AFTER the command byte.
 */
class StopSessions {
    var accept: Byte = 0
    /** Informational count of sessions being stopped. Ignored by receiver (FIX-2). */
    var sessionCount: Int = 0

    fun writeTo(bb: ByteBuffer, mode: TwampMode) {
        val startPos = bb.position()
        bb.put(TwampCommand.STOP_SESSIONS)
        bb.put(accept)
        bb.put(ByteArray(2))  // MBZ
        bb.putInt(sessionCount)
        bb.put(ByteArray(8))  // MBZ2
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

        /**
         * Deserialize from [bb] (positioned AFTER the command byte).
         * FIX-2: accepts any value in the sessionCount field.
         */
        fun readFrom(bb: ByteBuffer, mode: TwampMode): StopSessions {
            val hmacStart = bb.position() - 1
            val msg = StopSessions()
            msg.accept = bb.get()
            repeat(2) { bb.get() }  // MBZ
            msg.sessionCount = bb.int  // FIX-2: read but never validated against active count
            repeat(8) { bb.get() }  // MBZ2
            val hmac = ByteArray(16).also { bb.get(it) }
            if (mode.isControlEncrypted()) {
                val msgBytes = ByteArray(bb.position() - 16 - hmacStart).also {
                    bb.duplicate().also { d -> d.position(hmacStart) }.get(it)
                }
                val expected = TwampCrypto.hmacSha1Truncated(requireNotNull(mode.hmacKey), msgBytes)
                if (!hmac.contentEquals(expected)) {
                    throw SecurityException("HMAC validation failed for Stop-Sessions")
                }
            }
            return msg
        }
    }
}
