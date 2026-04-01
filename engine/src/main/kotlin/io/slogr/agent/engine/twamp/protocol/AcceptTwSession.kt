package io.slogr.agent.engine.twamp.protocol

import io.slogr.agent.engine.twamp.TwampMode
import io.slogr.agent.engine.twamp.auth.TwampCrypto
import java.nio.ByteBuffer

/**
 * TWAMP Accept-Session (48 bytes). RFC 5357 §3.5
 *
 * Wire layout (sent by reflector → controller, no leading command byte):
 *    1 byte  accept
 *    1 byte  MBZ
 *    2 bytes port (reflector's test UDP port)
 *   16 bytes SID
 *    2 bytes reflectedOctets
 *    2 bytes serverOctets
 *    8 bytes MBZ2
 *   16 bytes HMAC
 *
 * HMAC in authenticated mode: if [mode].serverStartMsg is set (i.e., this is the first
 * AcceptTwSession after the control handshake), the HMAC is computed over
 * [ServerStart.startTime + serverStart.mbz2 (8+8 bytes)] prepended to this message.
 * After computation, [mode].serverStartMsg is cleared.
 */
class AcceptTwSession {
    var accept: Byte = 0
    var port: Short = 0
    var sid: ByteArray = ByteArray(16)
    var reflectedOctets: Short = 0
    var serverOctets: Short = 0

    fun writeTo(bb: ByteBuffer, mode: TwampMode) {
        val startPos = bb.position()
        bb.put(accept)
        bb.put(0)  // MBZ
        bb.putShort(port)
        bb.put(sid)
        bb.putShort(reflectedOctets)
        bb.putShort(serverOctets)
        bb.put(ByteArray(8))  // MBZ2
        val hmac = if (mode.isControlEncrypted()) {
            val msgBytes = ByteArray(bb.position() - startPos).also {
                bb.duplicate().also { d -> d.position(startPos) }.get(it)
            }
            val digestInput = if (mode.serverStartMsg != null) {
                val serverStart = requireNotNull(mode.serverStartMsg)
                val prefix = ByteBuffer.allocate(16).also { p ->
                    p.putLong(serverStart.startTime)
                    p.putLong(0L)  // MBZ2 from ServerStart
                }.array()
                mode.serverStartMsg = null
                prefix + msgBytes
            } else {
                msgBytes
            }
            TwampCrypto.hmacSha1Truncated(requireNotNull(mode.hmacKey), digestInput)
        } else {
            ByteArray(16)
        }
        bb.put(hmac)
    }

    companion object {
        const val SIZE = 48

        /**
         * Deserialize from [bb] (positioned at start of message — no command byte).
         * Throws [SecurityException] on HMAC mismatch in authenticated mode.
         */
        fun readFrom(bb: ByteBuffer, mode: TwampMode): AcceptTwSession {
            val startPos = bb.position()
            val msg = AcceptTwSession()
            msg.accept = bb.get()
            bb.get()  // MBZ
            msg.port = bb.short
            bb.get(msg.sid)
            msg.reflectedOctets = bb.short
            msg.serverOctets = bb.short
            repeat(8) { bb.get() }  // MBZ2
            val msgSize = bb.position() - startPos
            val hmac = ByteArray(16).also { bb.get(it) }
            if (mode.isControlEncrypted()) {
                val msgBytes = ByteArray(msgSize).also {
                    bb.duplicate().also { d -> d.position(startPos) }.get(it)
                }
                val digestInput = if (mode.serverStartMsg != null) {
                    val serverStart = requireNotNull(mode.serverStartMsg)
                    val prefix = ByteBuffer.allocate(16).also { p ->
                        p.putLong(serverStart.startTime)
                        p.putLong(0L)
                    }.array()
                    mode.serverStartMsg = null
                    prefix + msgBytes
                } else {
                    msgBytes
                }
                val expected = TwampCrypto.hmacSha1Truncated(requireNotNull(mode.hmacKey), digestInput)
                if (!hmac.contentEquals(expected)) {
                    throw SecurityException("HMAC validation failed for Accept-Session")
                }
            }
            return msg
        }
    }
}
