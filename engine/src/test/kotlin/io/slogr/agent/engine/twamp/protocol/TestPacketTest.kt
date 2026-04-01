package io.slogr.agent.engine.twamp.protocol

import io.slogr.agent.engine.twamp.util.PacketPadding
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

/**
 * Encode/decode round-trip tests for TWAMP test (UDP) packet messages.
 *
 * Unauthenticated packets (SenderUPacket, ReflectorUPacket) are fully tested here.
 * Authenticated/encrypted variants (SenderEncryptUPacket, ReflectorEncryptUPacket) have their
 * BASE_SIZE constants verified; crypto paths are covered in TwampCryptoTest (Step 4).
 */
class TestPacketTest {

    // ── SenderUPacket ─────────────────────────────────────────────────────────

    @Test fun `SenderUPacket round-trip no padding`() {
        val pkt = SenderUPacket().apply {
            seqNumber = 42
            errorEstimate = 0x0100
        }
        val buf = ByteBuffer.allocate(SenderUPacket.BASE_SIZE)
        pkt.writeTo(buf)
        buf.flip()
        val parsed = SenderUPacket.readFrom(buf, paddingLength = 0)
        assertEquals(42, parsed.seqNumber)
        assertEquals(0x0100.toShort(), parsed.errorEstimate)
        assertTrue(parsed.timestamp != 0L, "timestamp must be set on write")
    }

    @Test fun `SenderUPacket round-trip with padding`() {
        val paddingLength = 20
        val pkt = SenderUPacket().apply {
            seqNumber = 7
            errorEstimate = 1
            padding = PacketPadding.empty(paddingLength)
        }
        val buf = ByteBuffer.allocate(SenderUPacket.BASE_SIZE + paddingLength)
        pkt.writeTo(buf)
        buf.flip()
        val parsed = SenderUPacket.readFrom(buf, paddingLength)
        assertEquals(7, parsed.seqNumber)
        assertEquals(SenderUPacket.BASE_SIZE + paddingLength, buf.position())
    }

    @Test fun `SenderUPacket BASE_SIZE is 14`() {
        assertEquals(14, SenderUPacket.BASE_SIZE)
    }

    @Test fun `SenderUPacket timestamp is captured at write time`() {
        val pkt = SenderUPacket()
        assertEquals(0L, pkt.timestamp)
        val buf = ByteBuffer.allocate(SenderUPacket.BASE_SIZE)
        pkt.writeTo(buf)
        assertTrue(pkt.timestamp != 0L)
    }

    @Test fun `SenderUPacket seqNumber wraps at max int`() {
        val pkt = SenderUPacket().apply { seqNumber = Int.MAX_VALUE }
        val buf = ByteBuffer.allocate(SenderUPacket.BASE_SIZE)
        pkt.writeTo(buf)
        buf.flip()
        val parsed = SenderUPacket.readFrom(buf, 0)
        assertEquals(Int.MAX_VALUE, parsed.seqNumber)
    }

    // ── ReflectorUPacket ──────────────────────────────────────────────────────

    @Test fun `ReflectorUPacket round-trip unauthenticated`() {
        val pkt = ReflectorUPacket().apply {
            seqNumber = 5
            errorEstimate = 0x0100
            receiverTime = 0x12345678_9ABCDEF0L
            senderSeqNumber = 3
            senderTime = 0x11111111_22222222L
            senderErrorEstimate = 0x0100
            senderTtl = 64
        }
        val buf = ByteBuffer.allocate(ReflectorUPacket.BASE_SIZE)
        pkt.writeTo(buf)
        buf.flip()
        val parsed = ReflectorUPacket.readFrom(buf, 0)
        assertEquals(pkt.seqNumber, parsed.seqNumber)
        assertEquals(pkt.errorEstimate, parsed.errorEstimate)
        assertEquals(pkt.receiverTime, parsed.receiverTime)
        assertEquals(pkt.senderSeqNumber, parsed.senderSeqNumber)
        assertEquals(pkt.senderTime, parsed.senderTime)
        assertEquals(pkt.senderErrorEstimate, parsed.senderErrorEstimate)
        assertEquals(pkt.senderTtl, parsed.senderTtl)
    }

    @Test fun `ReflectorUPacket BASE_SIZE is 41`() {
        assertEquals(41, ReflectorUPacket.BASE_SIZE)
    }

    @Test fun `BUG-A - ReflectorUPacket mbz2 consumed independently of mbz1`() {
        // BUG-A regression: Java reads mbz1 twice instead of reading mbz2.
        // Verify that after a round-trip the buffer position is exactly BASE_SIZE.
        val buf = ByteBuffer.allocate(ReflectorUPacket.BASE_SIZE)
        ReflectorUPacket().writeTo(buf)
        buf.flip()
        ReflectorUPacket.readFrom(buf, 0)
        assertEquals(
            ReflectorUPacket.BASE_SIZE, buf.position(),
            "BUG-A regression: buffer must be fully consumed after readFrom"
        )
    }

    @Test fun `ReflectorUPacket with padding round-trip`() {
        val paddingLength = 16
        val pkt = ReflectorUPacket().apply {
            seqNumber = 10
            senderTtl = 255.toByte()
            padding = PacketPadding.empty(paddingLength)
        }
        val buf = ByteBuffer.allocate(ReflectorUPacket.BASE_SIZE + paddingLength)
        pkt.writeTo(buf)
        buf.flip()
        val parsed = ReflectorUPacket.readFrom(buf, paddingLength)
        assertEquals(10, parsed.seqNumber)
        assertEquals(255.toByte(), parsed.senderTtl)
        assertEquals(ReflectorUPacket.BASE_SIZE + paddingLength, buf.position())
    }

    @Test fun `ReflectorUPacket senderTtl preserved at 255`() {
        val pkt = ReflectorUPacket().apply { senderTtl = 255.toByte() }
        val buf = ByteBuffer.allocate(ReflectorUPacket.BASE_SIZE)
        pkt.writeTo(buf)
        buf.flip()
        val parsed = ReflectorUPacket.readFrom(buf, 0)
        assertEquals(255.toByte(), parsed.senderTtl)
    }

    // ── SenderEncryptUPacket ──────────────────────────────────────────────────

    @Test fun `SenderEncryptUPacket BASE_SIZE is 48`() {
        assertEquals(48, SenderEncryptUPacket.BASE_SIZE)
    }

    // ── ReflectorEncryptUPacket ───────────────────────────────────────────────

    @Test fun `ReflectorEncryptUPacket BASE_SIZE is 112`() {
        assertEquals(112, ReflectorEncryptUPacket.BASE_SIZE)
    }
}
