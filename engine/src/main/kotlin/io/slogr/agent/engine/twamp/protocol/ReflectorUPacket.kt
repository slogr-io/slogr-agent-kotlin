package io.slogr.agent.engine.twamp.protocol

import io.slogr.agent.engine.twamp.util.PacketPadding
import io.slogr.agent.engine.twamp.util.TwampTimeUtil
import java.nio.ByteBuffer

/**
 * TWAMP-Test reflector packet — unauthenticated mode. RFC 5357 §4.2.1
 *
 * Wire layout:
 *   4 bytes  Sequence Number
 *   8 bytes  Timestamp (NTP) — when reflector sent this packet
 *   2 bytes  Error Estimate
 *   2 bytes  MBZ1
 *   8 bytes  Receive Timestamp (NTP) — when reflector received the sender packet
 *   4 bytes  Sender Sequence Number
 *   8 bytes  Sender Timestamp (NTP) — copied from sender packet
 *   2 bytes  Sender Error Estimate
 *   2 bytes  MBZ2
 *   1 byte   Sender TTL
 *   N bytes  Packet Padding
 *
 * BASE_SIZE = 41 (excludes padding)
 *
 * BUG-A fix: Java reference reads both MBZ fields into mbz1 (line 90 `bb.get(this.mbz1)`).
 * The second MBZ field must be read into mbz2.
 */
class ReflectorUPacket {
    var seqNumber: Int = 0
    var timestamp: Long = 0L
    var errorEstimate: Short = 0
    var receiverTime: Long = 0L
    var senderSeqNumber: Int = 0
    var senderTime: Long = 0L
    var senderErrorEstimate: Short = 0
    var senderTtl: Byte = 0
    var padding: PacketPadding = PacketPadding.empty(0)

    fun writeTo(bb: ByteBuffer) {
        timestamp = TwampTimeUtil.currentNtpTimestamp()
        bb.putInt(seqNumber)
        bb.putLong(timestamp)
        bb.putShort(errorEstimate)
        bb.putShort(0)            // MBZ1
        bb.putLong(receiverTime)
        bb.putInt(senderSeqNumber)
        bb.putLong(senderTime)
        bb.putShort(senderErrorEstimate)
        bb.putShort(0)            // MBZ2
        bb.put(senderTtl)
        padding.writeTo(bb)
    }

    companion object {
        const val BASE_SIZE = 41

        fun readFrom(bb: ByteBuffer, paddingLength: Int): ReflectorUPacket {
            val pkt = ReflectorUPacket()
            pkt.seqNumber = bb.int
            pkt.timestamp = bb.long
            pkt.errorEstimate = bb.short
            bb.short                         // MBZ1
            pkt.receiverTime = bb.long
            pkt.senderSeqNumber = bb.int
            pkt.senderTime = bb.long
            pkt.senderErrorEstimate = bb.short
            // BUG-A fix: Java reads mbz1 here again; must skip mbz2 (separate 2-byte field)
            bb.short                         // MBZ2
            pkt.senderTtl = bb.get()
            pkt.padding = PacketPadding.empty(paddingLength).also { it.readFrom(bb) }
            return pkt
        }
    }
}
