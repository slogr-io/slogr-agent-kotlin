package io.slogr.agent.engine.twamp.protocol

import io.slogr.agent.engine.twamp.SessionId
import io.slogr.agent.engine.twamp.TwampConstants
import io.slogr.agent.engine.twamp.TwampMode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * Encode/decode round-trip tests for all TWAMP control protocol messages.
 * Unauthenticated mode only — authenticated HMAC tests are in TwampCryptoTest (Step 4).
 */
class ControlMessagesTest {

    private fun unauthMode() = TwampMode().also { it.mode = TwampMode.UNAUTHENTICATED }

    // ── ServerGreeting ────────────────────────────────────────────────────────

    @Test fun `ServerGreeting round-trip unauthenticated`() {
        val greeting = ServerGreeting().apply {
            modes = TwampMode.UNAUTHENTICATED
            challenge = Random.nextBytes(16)
            salt = Random.nextBytes(16)
            count = 1024
        }
        val buf = ByteBuffer.allocate(ServerGreeting.SIZE)
        greeting.writeTo(buf)
        buf.flip()
        val parsed = ServerGreeting.readFrom(buf)
        assertEquals(greeting.modes, parsed.modes)
        assertArrayEquals(greeting.challenge, parsed.challenge)
        assertArrayEquals(greeting.salt, parsed.salt)
        assertEquals(greeting.count, parsed.count)
    }

    @Test fun `ServerGreeting SIZE is 64`() {
        assertEquals(64, ServerGreeting.SIZE)
    }

    @Test fun `ServerGreeting writes Slogr fingerprint`() {
        val greeting = ServerGreeting().apply { modes = TwampMode.UNAUTHENTICATED }
        val agentId = "AGNTID".toByteArray(Charsets.US_ASCII)
        val buf = ByteBuffer.allocate(ServerGreeting.SIZE)
        greeting.writeTo(buf, agentIdBytes = agentId)
        buf.flip()
        // First 5 bytes should be "SLOGR"
        val magic = ByteArray(5).also { buf.get(it) }
        assertArrayEquals(TwampConstants.SLOGR_MAGIC, magic)
    }

    @Test fun `ServerGreeting detects Slogr fingerprint on read`() {
        val greeting = ServerGreeting().apply { modes = TwampMode.UNAUTHENTICATED }
        val buf = ByteBuffer.allocate(ServerGreeting.SIZE)
        greeting.writeTo(buf)  // writes fingerprint
        buf.flip()
        val parsed = ServerGreeting.readFrom(buf)
        assertTrue(parsed.isSlogrAgent)
    }

    @Test fun `ServerGreeting isSlogrAgent false when fingerprint absent`() {
        val buf = ByteBuffer.allocate(ServerGreeting.SIZE)
        // write a vanilla greeting without Slogr fingerprint
        buf.put(ByteArray(12))  // no fingerprint in unused bytes
        buf.putInt(TwampMode.UNAUTHENTICATED)
        buf.put(ByteArray(16))  // challenge
        buf.put(ByteArray(16))  // salt
        buf.putInt(1024)
        buf.put(ByteArray(12))  // mbz
        buf.flip()
        val parsed = ServerGreeting.readFrom(buf)
        assertFalse(parsed.isSlogrAgent)
    }

    @Test fun `FIX-1 - ServerGreeting accepts any count value above MIN_COUNT`() {
        // FIX-1: no max ceiling — values like 65536 (Cisco) must be accepted
        for (count in listOf(1024, 4096, 32768, 65536, 1_048_576)) {
            val greeting = ServerGreeting().apply {
                modes = TwampMode.UNAUTHENTICATED
                this.count = count
            }
            val buf = ByteBuffer.allocate(ServerGreeting.SIZE)
            greeting.writeTo(buf)
            buf.flip()
            val parsed = ServerGreeting.readFrom(buf)
            assertEquals(count, parsed.count, "count=$count should survive round-trip")
        }
    }

    // ── SetUpResponse ─────────────────────────────────────────────────────────

    @Test fun `SetUpResponse round-trip unauthenticated`() {
        val resp = SetUpResponse().apply {
            mode = TwampMode.UNAUTHENTICATED
            clientIv = Random.nextBytes(16)
        }
        val buf = ByteBuffer.allocate(SetUpResponse.SIZE)
        resp.writeTo(buf)
        buf.flip()
        val parsed = SetUpResponse.readFrom(buf)
        assertEquals(resp.mode, parsed.mode)
        assertArrayEquals(resp.clientIv, parsed.clientIv)
    }

    @Test fun `SetUpResponse SIZE is 164`() {
        assertEquals(164, SetUpResponse.SIZE)
    }

    @Test fun `FIX-3 - SetUpResponse clientIV always in plaintext`() {
        // FIX-3: Client-IV must never be encrypted, regardless of mode
        val resp = SetUpResponse().apply {
            mode = TwampMode.AUTHENTICATED  // non-unauthenticated mode
            clientIv = Random.nextBytes(16)
        }
        val buf = ByteBuffer.allocate(SetUpResponse.SIZE)
        resp.writeTo(buf)
        buf.flip()
        val parsed = SetUpResponse.readFrom(buf)
        // If clientIV were encrypted, these bytes would be scrambled
        assertArrayEquals(resp.clientIv, parsed.clientIv,
            "FIX-3 regression: clientIV must be plaintext")
    }

    // ── ServerStart ───────────────────────────────────────────────────────────

    @Test fun `ServerStart round-trip unauthenticated`() {
        val mode = unauthMode()
        val msg = ServerStart().apply {
            accept = 0
            serverIv = Random.nextBytes(16)
            startTime = 1_700_000_000L shl 32
        }
        val buf = ByteBuffer.allocate(ServerStart.SIZE)
        msg.writeTo(buf, mode)
        buf.flip()
        val parsed = ServerStart.readFrom(buf, unauthMode())
        assertEquals(msg.accept, parsed.accept)
        assertArrayEquals(msg.serverIv, parsed.serverIv)
        assertEquals(msg.startTime, parsed.startTime)
    }

    @Test fun `ServerStart SIZE is 48`() {
        assertEquals(48, ServerStart.SIZE)
    }

    // ── RequestTwSession ──────────────────────────────────────────────────────

    @Test fun `RequestTwSession round-trip unauthenticated`() {
        val mode = unauthMode()
        val msg = RequestTwSession().apply {
            ipvn = 4
            senderPort = 12345.toShort()
            receiverPort = 862.toShort()
            paddingLength = 128
            startTime = 0L
            timeout = 2L shl 32
            typeDescriptor = 0
        }
        val buf = ByteBuffer.allocate(RequestTwSession.SIZE)
        msg.writeTo(buf, mode)
        // Move past the command byte to simulate dispatcher behavior
        buf.flip()
        buf.get()  // consume command byte
        val parsed = RequestTwSession.readFrom(buf, unauthMode())
        assertEquals(msg.senderPort, parsed.senderPort)
        assertEquals(msg.receiverPort, parsed.receiverPort)
        assertEquals(msg.paddingLength, parsed.paddingLength)
        assertEquals(msg.timeout, parsed.timeout)
    }

    @Test fun `RequestTwSession SIZE is 112`() {
        assertEquals(112, RequestTwSession.SIZE)
    }

    // ── AcceptTwSession ───────────────────────────────────────────────────────

    @Test fun `AcceptTwSession round-trip unauthenticated`() {
        val mode = unauthMode()
        val sid = SessionId(0x7F000001, 12345L, 99)
        val msg = AcceptTwSession().apply {
            accept = 0
            port = 5000.toShort()
            this.sid = sid.toByteArray()
            serverOctets = 7
        }
        val buf = ByteBuffer.allocate(AcceptTwSession.SIZE)
        msg.writeTo(buf, mode)
        buf.flip()
        val parsed = AcceptTwSession.readFrom(buf, unauthMode())
        assertEquals(msg.accept, parsed.accept)
        assertEquals(msg.port, parsed.port)
        assertArrayEquals(msg.sid, parsed.sid)
        assertEquals(msg.serverOctets, parsed.serverOctets)
    }

    @Test fun `AcceptTwSession SIZE is 48`() {
        assertEquals(48, AcceptTwSession.SIZE)
    }

    // ── StartSessions ─────────────────────────────────────────────────────────

    @Test fun `StartSessions round-trip unauthenticated`() {
        val mode = unauthMode()
        val buf = ByteBuffer.allocate(StartSessions.SIZE)
        StartSessions().writeTo(buf, mode)
        buf.flip()
        buf.get()  // consume command byte
        val parsed = StartSessions.readFrom(buf, unauthMode())
        assertNotNull(parsed)
    }

    @Test fun `StartSessions SIZE is 32`() {
        assertEquals(32, StartSessions.SIZE)
    }

    // ── StartSessionsAck ──────────────────────────────────────────────────────

    @Test fun `StartSessionsAck round-trip unauthenticated`() {
        val mode = unauthMode()
        val msg = StartSessionsAck().apply { accept = 0 }
        val buf = ByteBuffer.allocate(StartSessionsAck.SIZE)
        msg.writeTo(buf, mode)
        buf.flip()
        val parsed = StartSessionsAck.readFrom(buf, unauthMode())
        assertEquals(msg.accept, parsed.accept)
    }

    // ── StopSessions ──────────────────────────────────────────────────────────

    @Test fun `StopSessions round-trip unauthenticated`() {
        val mode = unauthMode()
        val msg = StopSessions().apply { accept = 0; sessionCount = 3 }
        val buf = ByteBuffer.allocate(StopSessions.SIZE)
        msg.writeTo(buf, mode)
        buf.flip()
        buf.get()  // consume command byte
        val parsed = StopSessions.readFrom(buf, unauthMode())
        assertEquals(msg.accept, parsed.accept)
        assertEquals(msg.sessionCount, parsed.sessionCount)
    }

    @Test fun `StopSessions SIZE is 32`() {
        assertEquals(32, StopSessions.SIZE)
    }

    @Test fun `FIX-2 - StopSessions sessionCount read without validation`() {
        // FIX-2: any sessionCount must be parseable without throwing
        for (count in listOf(0, 1, 5, 100)) {
            val mode = unauthMode()
            val msg = StopSessions().apply { sessionCount = count }
            val buf = ByteBuffer.allocate(StopSessions.SIZE)
            msg.writeTo(buf, mode)
            buf.flip()
            buf.get()  // command byte
            assertDoesNotThrow { StopSessions.readFrom(buf, unauthMode()) }
        }
    }

    // ── StartNSession / StartNAck ─────────────────────────────────────────────

    @Test fun `StartNSession round-trip 3 SIDs`() {
        val mode = unauthMode()
        val sids = listOf(
            SessionId(0x01010101, 100L, 1),
            SessionId(0x02020202, 200L, 2),
            SessionId(0x03030303, 300L, 3),
        )
        val msg = StartNSession().also { it.sids = sids }
        val msgSize = 1 + 11 + 4 + sids.size * 16 + 16
        val buf = ByteBuffer.allocate(msgSize)
        msg.writeTo(buf, mode)
        buf.flip()
        buf.get()  // consume command byte
        val parsed = StartNSession.readFrom(buf, unauthMode())
        assertEquals(3, parsed.sids.size)
        assertEquals(sids[0], parsed.sids[0])
        assertEquals(sids[2], parsed.sids[2])
    }

    @Test fun `StartNAck round-trip 2 SIDs`() {
        val mode = unauthMode()
        val sids = listOf(SessionId(0x7F000001, 1L, 1), SessionId(0x7F000002, 2L, 2))
        val msg = StartNAck().also { it.accept = 0; it.sids = sids }
        val msgSize = 1 + 1 + 2 + 8 + 4 + sids.size * 16 + 16
        val buf = ByteBuffer.allocate(msgSize)
        msg.writeTo(buf, mode)
        buf.flip()
        buf.get()  // consume command byte
        val parsed = StartNAck.readFrom(buf, unauthMode())
        assertEquals(msg.accept, parsed.accept)
        assertEquals(2, parsed.sids.size)
    }

    // ── StopNSession / StopNAck ───────────────────────────────────────────────

    @Test fun `StopNSession round-trip 1 SID`() {
        val mode = unauthMode()
        val sids = listOf(SessionId(0x7F000001, 999L, 42))
        val msg = StopNSession().also { it.sids = sids }
        val msgSize = 1 + 11 + 4 + sids.size * 16 + 16
        val buf = ByteBuffer.allocate(msgSize)
        msg.writeTo(buf, mode)
        buf.flip()
        buf.get()  // consume command byte
        val parsed = StopNSession.readFrom(buf, unauthMode())
        assertEquals(1, parsed.sids.size)
        assertEquals(sids[0], parsed.sids[0])
    }

    @Test fun `StopNAck round-trip 1 SID`() {
        val mode = unauthMode()
        val sids = listOf(SessionId(0x7F000001, 999L, 42))
        val msg = StopNAck().also { it.accept = 0; it.sids = sids }
        val msgSize = 1 + 1 + 2 + 8 + 4 + sids.size * 16 + 16
        val buf = ByteBuffer.allocate(msgSize)
        msg.writeTo(buf, mode)
        buf.flip()
        buf.get()  // consume command byte
        val parsed = StopNAck.readFrom(buf, unauthMode())
        assertEquals(0.toByte(), parsed.accept)
        assertEquals(1, parsed.sids.size)
    }
}
