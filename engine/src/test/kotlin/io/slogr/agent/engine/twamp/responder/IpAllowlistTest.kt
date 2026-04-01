package io.slogr.agent.engine.twamp.responder

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetAddress

class IpAllowlistTest {

    private val ip1 = InetAddress.getByName("10.0.0.1")
    private val ip2 = InetAddress.getByName("10.0.0.2")

    @Test fun `empty allowlist permits all IPs`() {
        val al = IpAllowlist()
        assertTrue(al.isAllowed(ip1))
        assertTrue(al.isAllowed(ip2))
    }

    @Test fun `non-empty allowlist permits only listed IPs`() {
        val al = IpAllowlist()
        al.add(ip1)
        assertTrue(al.isAllowed(ip1))
        assertFalse(al.isAllowed(ip2))
    }

    @Test fun `clear returns to permit-all`() {
        val al = IpAllowlist()
        al.add(ip1)
        al.clear()
        assertTrue(al.isAllowed(ip2))
    }

    @Test fun `add multiple IPs all permitted`() {
        val al = IpAllowlist()
        al.add(ip1); al.add(ip2)
        assertTrue(al.isAllowed(ip1))
        assertTrue(al.isAllowed(ip2))
        assertFalse(al.isAllowed(InetAddress.getByName("192.168.1.1")))
    }
}
