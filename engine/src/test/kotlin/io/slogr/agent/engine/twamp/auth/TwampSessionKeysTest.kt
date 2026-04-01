package io.slogr.agent.engine.twamp.auth

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetAddress

class TwampSessionKeysTest {

    private val loopback = InetAddress.getByName("127.0.0.1")
    private val other = InetAddress.getByName("10.0.0.1")

    @Test fun `equal when all fields match`() {
        val a = TwampSessionKeys(loopback, 862, other, 12345)
        val b = TwampSessionKeys(loopback, 862, other, 12345)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test fun `not equal when local port differs`() {
        val a = TwampSessionKeys(loopback, 862, other, 12345)
        val b = TwampSessionKeys(loopback, 863, other, 12345)
        assertNotEquals(a, b)
    }

    @Test fun `not equal when remote port differs`() {
        val a = TwampSessionKeys(loopback, 862, other, 12345)
        val b = TwampSessionKeys(loopback, 862, other, 12346)
        assertNotEquals(a, b)
    }

    @Test fun `not equal when remote ip differs`() {
        val remote1 = InetAddress.getByName("192.168.1.1")
        val remote2 = InetAddress.getByName("192.168.1.2")
        val a = TwampSessionKeys(loopback, 862, remote1, 12345)
        val b = TwampSessionKeys(loopback, 862, remote2, 12345)
        assertNotEquals(a, b)
    }

    @Test fun `usable as map key`() {
        val map = HashMap<TwampSessionKeys, String>()
        val key = TwampSessionKeys(loopback, 862, other, 9999)
        map[key] = "session-1"
        assertEquals("session-1", map[TwampSessionKeys(loopback, 862, other, 9999)])
    }
}
