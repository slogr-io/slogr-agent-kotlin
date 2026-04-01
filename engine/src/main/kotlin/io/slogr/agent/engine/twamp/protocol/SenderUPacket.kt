package io.slogr.agent.engine.twamp.protocol

import io.slogr.agent.engine.twamp.util.PacketPadding
import io.slogr.agent.engine.twamp.util.TwampTimeUtil
import java.nio.ByteBuffer

/**
 * TWAMP-Test sender packet — unauthenticated mode. RFC 5357 §4.1.2
 *
 * Wire layout:
 *   4 bytes  Sequence Number
 *   8 bytes  Timestamp (NTP)
 *   2 bytes  Error Estimate
 *   N bytes  Packet Padding
 *
 * BASE_SIZE = 14 (excludes padding)
 */
class SenderUPacket {
    var seqNumber: Int = 0
    var timestamp: Long = 0L
    var errorEstimate: Short = 0
    var padding: PacketPadding = PacketPadding.empty(0)

    fun writeTo(bb: ByteBuffer) {
        timestamp = TwampTimeUtil.currentNtpTimestamp()
        bb.putInt(seqNumber)
        bb.putLong(timestamp)
        bb.putShort(errorEstimate)
        padding.writeTo(bb)
    }

    companion object {
        const val BASE_SIZE = 14

        fun readFrom(bb: ByteBuffer, paddingLength: Int): SenderUPacket {
            val pkt = SenderUPacket()
            pkt.seqNumber = bb.int
            pkt.timestamp = bb.long
            pkt.errorEstimate = bb.short
            pkt.padding = PacketPadding.empty(paddingLength).also { it.readFrom(bb) }
            return pkt
        }
    }
}
