package io.slogr.agent.engine.twamp

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SessionIdTest {

    @Test fun `toByteArray produces 16 bytes`() {
        val sid = SessionId(ipv4 = 0x01020304, timestamp = 0x1ABBCCDDEFF0011L, randNumber = -559038737)
        assertEquals(16, sid.toByteArray().size)
    }

    @Test fun `round-trip fromByteArray restores original`() {
        val original = SessionId(ipv4 = 0x7F000001, timestamp = 1_234_567_890_123L, randNumber = 42)
        val restored = SessionId.fromByteArray(original.toByteArray())
        assertEquals(original, restored)
    }

    @Test fun `wire layout is big-endian IPv4 then timestamp then random`() {
        val sid = SessionId(ipv4 = 0x01020304, timestamp = 0x0102030405060708L, randNumber = 0x090A0B0C)
        val bytes = sid.toByteArray()
        // IPv4: bytes 0-3
        assertEquals(0x01.toByte(), bytes[0])
        assertEquals(0x02.toByte(), bytes[1])
        assertEquals(0x03.toByte(), bytes[2])
        assertEquals(0x04.toByte(), bytes[3])
        // timestamp: bytes 4-11
        assertEquals(0x01.toByte(), bytes[4])
        assertEquals(0x08.toByte(), bytes[11])
        // randNumber: bytes 12-15
        assertEquals(0x09.toByte(), bytes[12])
        assertEquals(0x0C.toByte(), bytes[15])
    }

    @Test fun `fromByteArray rejects buffer shorter than 16 bytes`() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionId.fromByteArray(ByteArray(15))
        }
    }

    @Test fun `toString has expected format`() {
        val sid = SessionId(ipv4 = 0x7F000001, timestamp = 0L, randNumber = 1)
        val str = sid.toString()
        assertTrue(str.contains("0x7f000001"), "should contain IPv4 hex: $str")
        assertTrue(str.contains("0x"), "should use hex formatting: $str")
    }
}
