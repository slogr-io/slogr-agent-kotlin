package io.slogr.agent.engine.twamp.util

import io.slogr.agent.engine.twamp.FillMode
import io.slogr.agent.engine.twamp.TwampMode
import io.slogr.agent.engine.twamp.TwampConstants
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class PacketPaddingTest {

    private fun mode(flags: Int, serverOctet: Short = 0) = TwampMode().also {
        it.mode = flags
        it.serverOctet = serverOctet
    }

    // ── Sender padding ────────────────────────────────────────────────────────

    @Test fun `sender zero padding correct length`() {
        val p = PacketPadding.forSender(mode(TwampMode.UNAUTHENTICATED), 100, FillMode.ZERO)
        assertEquals(100, p.length)
    }

    @Test fun `sender random padding correct length`() {
        val p = PacketPadding.forSender(mode(TwampMode.UNAUTHENTICATED), 50, FillMode.RANDOM)
        assertEquals(50, p.length)
    }

    @Test fun `sender zero-length padding has length zero`() {
        val p = PacketPadding.forSender(mode(TwampMode.UNAUTHENTICATED), 0, FillMode.ZERO)
        assertEquals(0, p.length)
    }

    @Test fun `sender symmetrical-size adds MBZ prefix (unauthenticated)`() {
        // symmetricalSize = UNAUTHENTICATED | SYMMETRICAL_SIZE
        val m = mode(TwampMode.UNAUTHENTICATED or TwampMode.SYMMETRICAL_SIZE)
        val p = PacketPadding.forSender(m, 80, FillMode.ZERO)
        // length = mbz (PKT_TRUNC_UNAUTH) + 80 payload
        assertEquals(TwampConstants.PKT_TRUNC_UNAUTH + 80, p.length)
    }

    @Test fun `sender writes serverOctet into first two bytes of padding`() {
        val m = mode(TwampMode.UNAUTHENTICATED, serverOctet = 0x0102.toShort())
        val p = PacketPadding.forSender(m, 10, FillMode.ZERO)
        val buf = ByteBuffer.allocate(p.length)
        p.writeTo(buf)
        buf.flip()
        assertEquals(0x01.toByte(), buf.get(0))
        assertEquals(0x02.toByte(), buf.get(1))
    }

    @Test fun `sender serverOctet not written when length is zero`() {
        // Should not throw even when padding length is 0 and serverOctet != 0
        val m = mode(TwampMode.UNAUTHENTICATED, serverOctet = 0x0001.toShort())
        assertDoesNotThrow { PacketPadding.forSender(m, 0, FillMode.ZERO) }
    }

    // ── Reflector padding ─────────────────────────────────────────────────────

    @Test fun `reflector truncates by pktTruncLength (unauthenticated)`() {
        val m = mode(TwampMode.UNAUTHENTICATED) // PKT_TRUNC_UNAUTH = 27
        val sender = PacketPadding.forSender(m, 100, FillMode.ZERO)
        val reflector = PacketPadding.forReflector(m, sender)
        assertEquals(100 - TwampConstants.PKT_TRUNC_UNAUTH, reflector.length)
    }

    @Test fun `reflector symmetricalSize echoes sender payload unchanged`() {
        val m = mode(TwampMode.UNAUTHENTICATED or TwampMode.SYMMETRICAL_SIZE)
        val sender = PacketPadding.forSender(m, 80, FillMode.RANDOM)
        // Sender length = PKT_TRUNC_UNAUTH + 80; reflector copies only the 80-byte payload
        val reflector = PacketPadding.forReflector(m, sender)
        assertEquals(80, reflector.length)
    }

    @Test fun `reflector with null sender payload produces zero-length padding`() {
        val m = mode(TwampMode.UNAUTHENTICATED)
        val sender = PacketPadding.forSender(m, 0, FillMode.ZERO) // no payload
        val reflector = PacketPadding.forReflector(m, sender)
        assertEquals(0, reflector.length)
    }

    @Test fun `reflector payload too small to truncate produces zero-length padding`() {
        val m = mode(TwampMode.UNAUTHENTICATED) // truncLen = 27
        val sender = PacketPadding.forSender(m, 10, FillMode.ZERO) // payload 10 < 27
        val reflector = PacketPadding.forReflector(m, sender)
        assertEquals(0, reflector.length)
    }

    // ── Round-trip (writeTo / readFrom) ───────────────────────────────────────

    @Test fun `writeTo and readFrom round-trip preserves bytes`() {
        val m = mode(TwampMode.UNAUTHENTICATED)
        val original = PacketPadding.forSender(m, 40, FillMode.RANDOM)
        val buf = ByteBuffer.allocate(original.length)
        original.writeTo(buf)
        buf.flip()

        val parsed = PacketPadding.empty(original.length)
        parsed.readFrom(buf)

        val out1 = ByteBuffer.allocate(original.length)
        val out2 = ByteBuffer.allocate(original.length)
        original.writeTo(out1)
        parsed.writeTo(out2)
        assertArrayEquals(out1.array(), out2.array())
    }

    @Test fun `writeTo and readFrom round-trip with symmetricalSize MBZ`() {
        val m = mode(TwampMode.UNAUTHENTICATED or TwampMode.SYMMETRICAL_SIZE)
        val original = PacketPadding.forSender(m, 60, FillMode.RANDOM)
        val buf = ByteBuffer.allocate(original.length)
        original.writeTo(buf)
        buf.flip()

        val parsed = PacketPadding.empty(original.length)
        parsed.readFrom(buf)

        val out1 = ByteBuffer.allocate(original.length)
        val out2 = ByteBuffer.allocate(original.length)
        original.writeTo(out1)
        parsed.writeTo(out2)
        assertArrayEquals(out1.array(), out2.array())
    }
}
