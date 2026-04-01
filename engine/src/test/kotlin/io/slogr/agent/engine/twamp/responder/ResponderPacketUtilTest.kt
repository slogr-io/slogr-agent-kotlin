package io.slogr.agent.engine.twamp.responder

import io.slogr.agent.engine.twamp.SessionId
import io.slogr.agent.engine.twamp.TwampMode
import io.slogr.agent.engine.twamp.protocol.SenderUPacket
import io.slogr.agent.engine.twamp.util.TwampTimeUtil
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetAddress

class ResponderPacketUtilTest {

    @Test fun `genServerGreeting sets modes and allocates random fields`() {
        val greeting = ResponderPacketUtil.genServerGreeting(
            modeBits = TwampMode.UNAUTHENTICATED or TwampMode.AUTHENTICATED
        )
        assertEquals(TwampMode.UNAUTHENTICATED or TwampMode.AUTHENTICATED, greeting.modes)
        assertEquals(16, greeting.challenge.size)
        assertEquals(16, greeting.salt.size)
    }

    @Test fun `genServerGreeting produces unique challenges each call`() {
        val g1 = ResponderPacketUtil.genServerGreeting()
        val g2 = ResponderPacketUtil.genServerGreeting()
        // With overwhelming probability, two SecureRandom 16-byte values differ
        assertFalse(g1.challenge.contentEquals(g2.challenge))
    }

    @Test fun `genServerStart unauthenticated mode has zero IV`() {
        val start = ResponderPacketUtil.genServerStart(TwampMode.UNAUTHENTICATED)
        assertEquals(0.toByte(), start.accept)
        assertArrayEquals(ByteArray(16), start.serverIv)
    }

    @Test fun `genServerStart authenticated mode has random IV - BUG-C`() {
        val start = ResponderPacketUtil.genServerStart(TwampMode.AUTHENTICATED)
        assertFalse(start.serverIv.all { it == 0.toByte() },
            "BUG-C: authenticated mode must have non-zero random server IV")
    }

    @Test fun `genServerStart mixed mode has random IV - BUG-C`() {
        val start = ResponderPacketUtil.genServerStart(TwampMode.MIXED_MODE)
        assertFalse(start.serverIv.all { it == 0.toByte() },
            "BUG-C: mixed mode must have non-zero random server IV")
    }

    @Test fun `genSessionId encodes last 4 bytes of IP in ipv4`() {
        val ip = InetAddress.getByName("10.20.30.40")
        val sid = ResponderPacketUtil.genSessionId(ip)
        val expected = (10 shl 24) or (20 shl 16) or (30 shl 8) or 40
        assertEquals(expected, sid.ipv4)
    }

    @Test fun `genSessionId toByteArray round-trips via SessionId`() {
        val ip = InetAddress.getByName("192.168.1.1")
        val sid = ResponderPacketUtil.genSessionId(ip)
        val bytes = sid.toByteArray()
        assertEquals(16, bytes.size)
        assertEquals(sid, SessionId.fromByteArray(bytes))
    }

    @Test fun `genAcceptSession sets accept and port`() {
        val sid = SessionId(0, 0, 0)
        val accept = ResponderPacketUtil.genAcceptSession(sid, port = 862, accept = 0)
        assertEquals(0.toByte(), accept.accept)
        assertEquals(862.toShort(), accept.port)
        assertArrayEquals(sid.toByteArray(), accept.sid)
    }

    @Test fun `genStartAck returns accept 0 by default`() {
        assertEquals(0.toByte(), ResponderPacketUtil.genStartAck().accept)
    }

    @Test fun `genReflectorPacket copies sender fields`() {
        val senderPkt = SenderUPacket().apply {
            seqNumber = 7
            errorEstimate = 0x0100
        }
        senderPkt.writeTo(java.nio.ByteBuffer.allocate(SenderUPacket.BASE_SIZE))
        val recvTime = TwampTimeUtil.currentNtpTimestamp()
        val r = ResponderPacketUtil.genReflectorPacket(
            senderPacket = senderPkt,
            receiveTimeNtp = recvTime,
            senderTtl = 64,
            reflectorSeq = 1
        )
        assertEquals(1, r.seqNumber)
        assertEquals(7, r.senderSeqNumber)
        assertEquals(recvTime, r.receiverTime)
        assertEquals(senderPkt.timestamp, r.senderTime)
        assertEquals(senderPkt.errorEstimate, r.senderErrorEstimate)
        assertEquals(64.toByte(), r.senderTtl)
    }

    @Test fun `genStartNAck sets sids`() {
        val sids = listOf(SessionId(1, 2, 3), SessionId(4, 5, 6))
        val ack = ResponderPacketUtil.genStartNAck(sids)
        assertEquals(sids, ack.sids)
    }

    @Test fun `genStopNAck sets sids`() {
        val sids = listOf(SessionId(9, 8, 7))
        val ack = ResponderPacketUtil.genStopNAck(sids)
        assertEquals(sids, ack.sids)
    }
}
