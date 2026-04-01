package io.slogr.agent.engine.twamp

import io.slogr.agent.engine.twamp.protocol.StopSessions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetAddress

class PacketUtilTest {

    @Test fun `genSetUpResponse defaults to zero fields`() {
        val r = PacketUtil.genSetUpResponse(TwampMode.UNAUTHENTICATED)
        assertEquals(TwampMode.UNAUTHENTICATED, r.mode)
        assertEquals(80, r.keyId.size)
        assertEquals(64, r.token.size)
        assertEquals(16, r.clientIv.size)
        assertTrue(r.keyId.all { it == 0.toByte() })
    }

    @Test fun `genSetUpResponse copies supplied arrays defensively`() {
        val iv = ByteArray(16) { 1 }
        val r = PacketUtil.genSetUpResponse(TwampMode.UNAUTHENTICATED, clientIv = iv)
        iv[0] = 99.toByte()
        assertEquals(1.toByte(), r.clientIv[0], "mutation of original array must not affect response")
    }

    @Test fun `genRequestTwSession sets ipvn 4 for IPv4 addresses`() {
        val req = PacketUtil.genRequestTwSession(
            senderIp = InetAddress.getByName("10.0.0.1"),
            receiverIp = InetAddress.getByName("10.0.0.2")
        )
        assertEquals(4.toByte(), req.ipvn)
    }

    @Test fun `genRequestTwSession sets ipvn 6 for IPv6 addresses`() {
        val req = PacketUtil.genRequestTwSession(
            receiverIp = InetAddress.getByName("::1")
        )
        assertEquals(6.toByte(), req.ipvn)
    }

    @Test fun `genRequestTwSession encodes dscp in typeDescriptor high byte`() {
        val req = PacketUtil.genRequestTwSession(dscp = 0x28)
        assertEquals(0x28 shl 24, req.typeDescriptor)
    }

    @Test fun `genRequestTwSession startTime is in the future`() {
        val now = io.slogr.agent.engine.twamp.util.TwampTimeUtil.currentNtpTimestamp()
        val req = PacketUtil.genRequestTwSession()
        assertTrue(req.startTime > now, "startTime must be after current NTP time")
    }

    @Test fun `genRequestTwSession timeout encodes seconds`() {
        val req = PacketUtil.genRequestTwSession(timeoutMs = 5000L)
        val seconds = (req.timeout ushr 32) and 0xFFFFFFFFL
        assertEquals(5L, seconds)
    }

    @Test fun `genStartSessions returns non-null instance`() {
        assertNotNull(PacketUtil.genStartSessions())
    }

    @Test fun `genStopSessions returns accept 0`() {
        val stop = PacketUtil.genStopSessions()
        assertEquals(0.toByte(), stop.accept)
    }

    @Test fun `genTestPacket sets seqNumber`() {
        val pkt = PacketUtil.genTestPacket(99)
        assertEquals(99, pkt.seqNumber)
        assertEquals(0.toShort(), pkt.errorEstimate)
    }

    @Test fun `genEncryptTestPacket sets seqNumber`() {
        val pkt = PacketUtil.genEncryptTestPacket(42)
        assertEquals(42, pkt.seqNumber)
        assertEquals(0.toShort(), pkt.errorEstimate)
    }
}
