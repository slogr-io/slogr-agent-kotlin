package io.slogr.agent.engine.twamp.protocol

import io.slogr.agent.engine.twamp.TwampMode
import io.slogr.agent.engine.twamp.auth.TwampCrypto
import java.nio.ByteBuffer

/**
 * TWAMP Start-Sessions Ack (32 bytes). RFC 5357 §3.7
 *
 * Wire layout (sent by reflector → controller, no leading command byte):
 *    1 byte  accept
 *   15 bytes MBZ
 *   16 bytes HMAC
 */
class StartSessionsAck {
    var accept: Byte = 0

    fun writeTo(bb: ByteBuffer, mode: TwampMode) {
        val startPos = bb.position()
        bb.put(accept)
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

        fun readFrom(bb: ByteBuffer, mode: TwampMode): StartSessionsAck {
            val startPos = bb.position()
            val msg = StartSessionsAck()
            msg.accept = bb.get()
            repeat(15) { bb.get() }  // MBZ
            val hmac = ByteArray(16).also { bb.get(it) }
            if (mode.isControlEncrypted()) {
                val msgBytes = ByteArray(bb.position() - 16 - startPos).also {
                    bb.duplicate().also { d -> d.position(startPos) }.get(it)
                }
                val expected = TwampCrypto.hmacSha1Truncated(requireNotNull(mode.hmacKey), msgBytes)
                if (!hmac.contentEquals(expected)) {
                    throw SecurityException("HMAC validation failed for Start-Sessions-Ack")
                }
            }
            return msg
        }
    }
}
