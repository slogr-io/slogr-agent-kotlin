package io.slogr.agent.engine.twamp.protocol

import io.slogr.agent.engine.twamp.SessionId
import io.slogr.agent.engine.twamp.TwampCommand
import io.slogr.agent.engine.twamp.TwampMode
import io.slogr.agent.engine.twamp.auth.TwampCrypto
import java.nio.ByteBuffer

/**
 * TWAMP Stop-N-Ack (variable length). RFC 5938 §3.4
 *
 * Wire layout (sent by reflector → controller, but the type byte is written by serializer):
 *    1 byte  type = 10
 *    1 byte  accept
 *    2 bytes MBZ1
 *    8 bytes MBZ2
 *    4 bytes noSession
 *   [noSession × 16 bytes] SIDs
 *   16 bytes HMAC
 *
 * When reading: the ByteBuffer is positioned AFTER the command byte.
 */
class StopNAck {
    var accept: Byte = 0
    var sids: List<SessionId> = emptyList()

    fun writeTo(bb: ByteBuffer, mode: TwampMode) {
        val startPos = bb.position()
        bb.put(TwampCommand.STOP_N_ACK)
        bb.put(accept)
        bb.putShort(0)  // MBZ1
        bb.putLong(0L)  // MBZ2
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
        fun readFrom(bb: ByteBuffer, mode: TwampMode): StopNAck {
            val hmacStart = bb.position() - 1
            val msg = StopNAck()
            msg.accept = bb.get()
            bb.short   // MBZ1
            bb.long    // MBZ2
            val count = bb.int
            msg.sids = (0 until count).map { SessionId.fromByteArray(ByteArray(16).also { bb.get(it) }) }
            val hmac = ByteArray(16).also { bb.get(it) }
            if (mode.isControlEncrypted()) {
                val msgBytes = ByteArray(bb.position() - 16 - hmacStart).also {
                    bb.duplicate().also { d -> d.position(hmacStart) }.get(it)
                }
                val expected = TwampCrypto.hmacSha1Truncated(requireNotNull(mode.hmacKey), msgBytes)
                if (!hmac.contentEquals(expected)) {
                    throw SecurityException("HMAC validation failed for Stop-N-Ack")
                }
            }
            return msg
        }
    }
}
