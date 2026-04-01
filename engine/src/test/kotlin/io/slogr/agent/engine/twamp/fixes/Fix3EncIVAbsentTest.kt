package io.slogr.agent.engine.twamp.fixes

import io.slogr.agent.engine.twamp.protocol.SetUpResponse
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

/**
 * FIX-3: Client-IV in SetUpResponse is ALWAYS sent unencrypted (plaintext).
 *
 * RFC 4656 §3.1 is explicit: the IV is not protected. The Java reference
 * had an `encIV` toggle that could encrypt the IV — this Kotlin port removes
 * the toggle entirely. There is one code path: IV is always written and read
 * as raw bytes, regardless of mode.
 */
class Fix3EncIVAbsentTest {

    @Test fun `SetUpResponse writes clientIv as plaintext bytes`() {
        val iv = ByteArray(16) { (it + 1).toByte() }  // 0x01..0x10
        val resp = SetUpResponse().apply {
            mode = 1
            clientIv = iv
        }
        val buf = ByteBuffer.allocate(SetUpResponse.SIZE)
        resp.writeTo(buf)

        // The last 16 bytes of the SetUpResponse wire format are the Client-IV.
        // mode(4) + keyId(80) + token(64) + iv(16) = 164 bytes total.
        val wire = buf.array()
        val extractedIv = wire.sliceArray(148 until 164)
        assertArrayEquals(iv, extractedIv, "Client-IV must be written as-is (plaintext)")
    }

    @Test fun `SetUpResponse readFrom recovers plaintext clientIv`() {
        val iv = ByteArray(16) { (it * 7).toByte() }
        val resp = SetUpResponse().apply {
            mode = 2
            clientIv = iv
        }
        val buf = ByteBuffer.allocate(SetUpResponse.SIZE)
        resp.writeTo(buf)
        buf.flip()

        val decoded = SetUpResponse.readFrom(buf)
        assertArrayEquals(iv, decoded.clientIv, "Client-IV must be read back unchanged")
    }

    @Test fun `SetUpResponse SIZE is 164 bytes`() {
        assertEquals(164, SetUpResponse.SIZE)
    }

    @Test fun `There is no encIV field or toggle in SetUpResponse`() {
        // Verify the encIV toggle does not exist as a class member.
        val fields = SetUpResponse::class.java.declaredFields.map { it.name }
        assertFalse(fields.any { it.contains("encIV", ignoreCase = true) },
            "encIV toggle must not exist — FIX-3 removes it entirely")
    }
}
