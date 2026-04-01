package io.slogr.agent.engine.twamp.protocol

import io.slogr.agent.engine.twamp.TwampCommand
import io.slogr.agent.engine.twamp.TwampMode
import io.slogr.agent.engine.twamp.auth.TwampCrypto
import java.nio.ByteBuffer

/**
 * TWAMP Request-TW-Session (112 bytes). RFC 5357 §3.5
 *
 * Wire layout (sent by controller → reflector):
 *    1 byte  type = 5
 *    1 byte  ipvn (IP version: 4 or 6)
 *    1 byte  confSender = 0
 *    1 byte  confReceiver = 0
 *    4 bytes scheduleSlots = 0 (TWAMP: always 0)
 *    4 bytes numPackets = 0    (TWAMP: always 0)
 *    2 bytes senderPort
 *    2 bytes receiverPort
 *   16 bytes senderAddress (IPv4 in first 4 bytes, rest = 0)
 *   16 bytes receiverAddress
 *   16 bytes SID (set by reflector; zero when sent by controller)
 *    4 bytes paddingLength
 *    8 bytes startTime (NTP)
 *    8 bytes timeout (NTP duration)
 *    4 bytes typeDescriptor (DSCP / Type-P)
 *    2 bytes reflectOctets (RFC 5938; 0 if not supported)
 *    2 bytes reflectLength
 *    4 bytes MBZ
 *   16 bytes HMAC (all-zero in unauthenticated mode)
 *
 * When reading: the ByteBuffer is positioned AFTER the command byte (which was consumed
 * by the message dispatcher). HMAC covers from the command byte (position - 1) through
 * the byte before the HMAC field.
 */
class RequestTwSession {
    var ipvn: Byte = 4
    var senderPort: Short = 0
    var receiverPort: Short = 0
    var senderAddress: ByteArray = ByteArray(16)
    var receiverAddress: ByteArray = ByteArray(16)
    var sid: ByteArray = ByteArray(16)
    var paddingLength: Int = 0
    var startTime: Long = 0L
    var timeout: Long = 0L
    var typeDescriptor: Int = 0
    var reflectOctets: Short = 0
    var reflectLength: Short = 0

    /**
     * Serialize to [bb] (includes the leading command byte).
     * HMAC is computed over all bytes before the HMAC field; all-zero in unauth mode.
     */
    fun writeTo(bb: ByteBuffer, mode: TwampMode) {
        val startPos = bb.position()
        bb.put(TwampCommand.REQUEST_TW_SESSION)
        bb.put(ipvn)
        bb.put(0)  // confSender = 0
        bb.put(0)  // confReceiver = 0
        bb.putInt(0)   // scheduleSlots
        bb.putInt(0)   // numPackets
        bb.putShort(senderPort)
        bb.putShort(receiverPort)
        bb.put(senderAddress)
        bb.put(receiverAddress)
        bb.put(sid)
        bb.putInt(paddingLength)
        bb.putLong(startTime)
        bb.putLong(timeout)
        bb.putInt(typeDescriptor)
        bb.putShort(reflectOctets)
        bb.putShort(reflectLength)
        bb.put(ByteArray(4))  // MBZ
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
        const val SIZE = 112

        /**
         * Deserialize from [bb] (positioned AFTER the command byte).
         * Throws [SecurityException] on HMAC mismatch in authenticated mode.
         */
        fun readFrom(bb: ByteBuffer, mode: TwampMode): RequestTwSession {
            val hmacStart = bb.position() - 1  // include already-consumed command byte
            val msg = RequestTwSession()
            msg.ipvn = bb.get()
            bb.get()  // confSender
            bb.get()  // confReceiver
            bb.getInt()  // scheduleSlots
            bb.getInt()  // numPackets
            msg.senderPort = bb.short
            msg.receiverPort = bb.short
            bb.get(msg.senderAddress)
            bb.get(msg.receiverAddress)
            bb.get(msg.sid)
            msg.paddingLength = bb.int
            msg.startTime = bb.long
            msg.timeout = bb.long
            msg.typeDescriptor = bb.int
            msg.reflectOctets = bb.short
            msg.reflectLength = bb.short
            repeat(4) { bb.get() }  // MBZ
            val hmac = ByteArray(16).also { bb.get(it) }
            if (mode.isControlEncrypted()) {
                val msgBytes = ByteArray(bb.position() - 16 - hmacStart).also {
                    bb.duplicate().also { d -> d.position(hmacStart) }.get(it)
                }
                val expected = TwampCrypto.hmacSha1Truncated(requireNotNull(mode.hmacKey), msgBytes)
                if (!hmac.contentEquals(expected)) {
                    throw SecurityException("HMAC validation failed for Request-TW-Session")
                }
            }
            return msg
        }
    }
}
