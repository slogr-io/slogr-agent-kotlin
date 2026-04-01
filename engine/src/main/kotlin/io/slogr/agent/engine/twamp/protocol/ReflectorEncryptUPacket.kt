package io.slogr.agent.engine.twamp.protocol

import io.slogr.agent.engine.twamp.TwampMode
import io.slogr.agent.engine.twamp.auth.TwampCrypto
import io.slogr.agent.engine.twamp.util.PacketPadding
import io.slogr.agent.engine.twamp.util.TwampTimeUtil
import java.nio.ByteBuffer

/**
 * TWAMP-Test reflector packet — authenticated / encrypted mode. RFC 5357 §4.2.1
 *
 * Wire layout:
 *   4 bytes  Sequence Number      ┐
 *  12 bytes  MBZ1                 ┤ first 16 bytes → AES-ECB in auth mode
 *   8 bytes  Timestamp (NTP)      ┤
 *   2 bytes  Error Estimate       ┤
 *   6 bytes  MBZ2                 ┤
 *   8 bytes  Receive Timestamp    ┤ first 96 bytes → AES-CBC in encrypted mode
 *   8 bytes  MBZ3                 ┤
 *   4 bytes  Sender Sequence Num  ┤
 *  12 bytes  MBZ4                 ┤
 *   8 bytes  Sender Timestamp     ┤
 *   2 bytes  Sender Error Est.    ┤
 *   6 bytes  MBZ5                 ┤
 *   1 byte   Sender TTL           ┤
 *  15 bytes  MBZ6                 ┘
 *  16 bytes  HMAC
 *   N bytes  Packet Padding
 *
 * BASE_SIZE = 112 (excludes padding)
 *
 * BUG-B fix: Java setMbz2 and setMbz6 both use parameter name 'mbz1', causing
 * `this.mbz2 = mbz2` / `this.mbz6 = mbz6` to be no-op self-assignments (the identifier
 * resolves to the field rather than the parameter). In this Kotlin port all setters use
 * the correct parameter name so the value is actually stored.
 */
class ReflectorEncryptUPacket {
    var seqNumber: Int = 0
    var timestamp: Long = 0L
    var errorEstimate: Short = 0
    var receiverTime: Long = 0L
    var senderSeqNumber: Int = 0
    var senderTime: Long = 0L
    var senderErrorEstimate: Short = 0
    var senderTtl: Byte = 0
    var padding: PacketPadding = PacketPadding.empty(0)

    fun writeTo(bb: ByteBuffer, mode: TwampMode) {
        val startPos = bb.position()
        timestamp = TwampTimeUtil.currentNtpTimestamp()

        bb.putInt(seqNumber)
        bb.put(ByteArray(12))          // MBZ1

        var hmac = ByteArray(16)

        if (mode.isTestAuthenticated()) {
            // Authenticated mode: first 16-byte block encrypted with AES-ECB
            val plain = ByteArray(16).also {
                bb.duplicate().also { d -> d.position(startPos) }.get(it)
            }
            hmac = TwampCrypto.hmacSha1Truncated(requireNotNull(mode.hmacKey), plain)
            val encrypted = TwampCrypto.encryptAesEcb(requireNotNull(mode.aesKey), plain)
            System.arraycopy(encrypted, 0, bb.array(), startPos, encrypted.size)
        }

        bb.putLong(timestamp)
        bb.putShort(errorEstimate)
        bb.put(ByteArray(6))           // MBZ2
        bb.putLong(receiverTime)
        bb.put(ByteArray(8))           // MBZ3
        bb.putInt(senderSeqNumber)
        bb.put(ByteArray(12))          // MBZ4
        bb.putLong(senderTime)
        bb.putShort(senderErrorEstimate)
        bb.put(ByteArray(6))           // MBZ5
        bb.put(senderTtl)
        bb.put(ByteArray(15))          // MBZ6

        if (!mode.isTestAuthenticated()) {
            // Encrypted mode: first 96-byte block encrypted with AES-CBC
            val plain = ByteArray(96).also {
                bb.duplicate().also { d -> d.position(startPos) }.get(it)
            }
            hmac = TwampCrypto.hmacSha1Truncated(requireNotNull(mode.hmacKey), plain)
            val encrypted = TwampCrypto.encryptAesCbc(
                requireNotNull(mode.aesKey), plain, mode.sendIv, updateIv = false
            )
            System.arraycopy(encrypted, 0, bb.array(), startPos, encrypted.size)
        }

        bb.put(hmac)
        padding.writeTo(bb)
    }

    companion object {
        const val BASE_SIZE = 112

        fun readFrom(bb: ByteBuffer, mode: TwampMode, paddingLength: Int): ReflectorEncryptUPacket {
            val startPos = bb.position()
            val pkt = ReflectorEncryptUPacket()

            val msgSize: Int
            if (mode.isTestAuthenticated()) {
                msgSize = 16
                val encrypted = ByteArray(msgSize).also {
                    bb.duplicate().also { d -> d.position(startPos) }.get(it)
                }
                val decrypted = TwampCrypto.decryptAesEcb(requireNotNull(mode.aesKey), encrypted)
                System.arraycopy(decrypted, 0, bb.array(), startPos, decrypted.size)
            } else {
                msgSize = 96
                val encrypted = ByteArray(msgSize).also {
                    bb.duplicate().also { d -> d.position(startPos) }.get(it)
                }
                val decrypted = TwampCrypto.decryptAesCbc(
                    requireNotNull(mode.aesKey), encrypted, mode.receiveIv, updateIv = false
                )
                System.arraycopy(decrypted, 0, bb.array(), startPos, decrypted.size)
            }

            pkt.seqNumber = bb.int
            bb.get(ByteArray(12))          // MBZ1
            pkt.timestamp = bb.long
            pkt.errorEstimate = bb.short
            bb.get(ByteArray(6))           // MBZ2 — BUG-B fix: not a self-assignment
            pkt.receiverTime = bb.long
            bb.get(ByteArray(8))           // MBZ3
            pkt.senderSeqNumber = bb.int
            bb.get(ByteArray(12))          // MBZ4
            pkt.senderTime = bb.long
            pkt.senderErrorEstimate = bb.short
            bb.get(ByteArray(6))           // MBZ5
            pkt.senderTtl = bb.get()
            bb.get(ByteArray(15))          // MBZ6 — BUG-B fix: not a self-assignment
            val hmac = ByteArray(16).also { bb.get(it) }

            val digestData = ByteArray(msgSize).also {
                bb.duplicate().also { d -> d.position(startPos) }.get(it)
            }
            val expected = TwampCrypto.hmacSha1Truncated(requireNotNull(mode.hmacKey), digestData)
            if (!hmac.contentEquals(expected)) {
                throw SecurityException("HMAC validation failed for reflector test packet")
            }

            pkt.padding = PacketPadding.empty(paddingLength).also { it.readFrom(bb) }
            return pkt
        }
    }
}
