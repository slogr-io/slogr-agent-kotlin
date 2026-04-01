package io.slogr.agent.engine.twamp.protocol

import io.slogr.agent.engine.twamp.TwampMode
import io.slogr.agent.engine.twamp.auth.TwampCrypto
import io.slogr.agent.engine.twamp.util.PacketPadding
import io.slogr.agent.engine.twamp.util.TwampTimeUtil
import java.nio.ByteBuffer

/**
 * TWAMP-Test sender packet — authenticated / encrypted mode. RFC 5357 §4.1.2
 *
 * Wire layout:
 *   4 bytes  Sequence Number     ┐
 *  12 bytes  MBZ1                ┤ first 16 bytes → AES-ECB in auth mode
 *   8 bytes  Timestamp (NTP)     ┤ first 32 bytes → AES-CBC in encrypted mode
 *   2 bytes  Error Estimate      ┤
 *   6 bytes  MBZ2                ┘
 *  16 bytes  HMAC
 *   N bytes  Packet Padding
 *
 * BASE_SIZE = 48 (excludes padding)
 *
 * BUG-E fix: Java comments in the `else` branch (encrypted-mode path) incorrectly say
 * "in Authenticated mode". Corrected to "Encrypted mode".
 */
class SenderEncryptUPacket {
    var seqNumber: Int = 0
    var timestamp: Long = 0L
    var errorEstimate: Short = 0
    var padding: PacketPadding = PacketPadding.empty(0)

    fun writeTo(bb: ByteBuffer, mode: TwampMode) {
        val startPos = bb.position()
        timestamp = TwampTimeUtil.currentNtpTimestamp()

        bb.putInt(seqNumber)
        bb.put(ByteArray(12))     // MBZ1

        var hmac = ByteArray(16)

        if (mode.isTestAuthenticated()) {
            // Authenticated mode: first 16-byte block (seqNumber + MBZ1) encrypted with AES-ECB.
            // HMAC covers the plaintext of that block.
            val plain = ByteArray(16).also {
                bb.duplicate().also { d -> d.position(startPos) }.get(it)
            }
            hmac = TwampCrypto.hmacSha1Truncated(requireNotNull(mode.hmacKey), plain)
            val encrypted = TwampCrypto.encryptAesEcb(requireNotNull(mode.aesKey), plain)
            System.arraycopy(encrypted, 0, bb.array(), startPos, encrypted.size)
        }

        bb.putLong(timestamp)
        bb.putShort(errorEstimate)
        bb.put(ByteArray(6))      // MBZ2

        if (!mode.isTestAuthenticated()) {
            // Encrypted mode: first 32-byte block (seqNumber + MBZ1 + timestamp + errorEstimate + MBZ2)
            // encrypted with AES-CBC. HMAC covers the plaintext of that block.
            val plain = ByteArray(32).also {
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
        const val BASE_SIZE = 48

        fun readFrom(bb: ByteBuffer, mode: TwampMode, paddingLength: Int): SenderEncryptUPacket {
            val startPos = bb.position()
            val pkt = SenderEncryptUPacket()

            val msgSize: Int
            if (mode.isTestAuthenticated()) {
                // Authenticated mode: decrypt first 16-byte block with AES-ECB
                msgSize = 16
                val encrypted = ByteArray(msgSize).also {
                    bb.duplicate().also { d -> d.position(startPos) }.get(it)
                }
                val decrypted = TwampCrypto.decryptAesEcb(requireNotNull(mode.aesKey), encrypted)
                System.arraycopy(decrypted, 0, bb.array(), startPos, decrypted.size)
            } else {
                // Encrypted mode: decrypt first 32-byte block with AES-CBC
                msgSize = 32
                val encrypted = ByteArray(msgSize).also {
                    bb.duplicate().also { d -> d.position(startPos) }.get(it)
                }
                val decrypted = TwampCrypto.decryptAesCbc(
                    requireNotNull(mode.aesKey), encrypted, mode.receiveIv, updateIv = false
                )
                System.arraycopy(decrypted, 0, bb.array(), startPos, decrypted.size)
            }

            pkt.seqNumber = bb.int
            bb.get(ByteArray(12))    // MBZ1
            pkt.timestamp = bb.long
            pkt.errorEstimate = bb.short
            bb.get(ByteArray(6))     // MBZ2
            val hmac = ByteArray(16).also { bb.get(it) }

            // HMAC validation: covers the (now-decrypted) first msgSize bytes
            val digestData = ByteArray(msgSize).also {
                bb.duplicate().also { d -> d.position(startPos) }.get(it)
            }
            val expected = TwampCrypto.hmacSha1Truncated(requireNotNull(mode.hmacKey), digestData)
            if (!hmac.contentEquals(expected)) {
                throw SecurityException("HMAC validation failed for sender test packet")
            }

            pkt.padding = PacketPadding.empty(paddingLength).also { it.readFrom(bb) }
            return pkt
        }
    }
}
