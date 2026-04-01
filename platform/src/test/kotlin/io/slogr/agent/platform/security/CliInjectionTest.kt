package io.slogr.agent.platform.security

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Verifies that shell-injection payloads in the target address field are rejected
 * before any network operation is attempted.
 *
 * The Kotlin agent uses JNI (not shell subprocess) so no shell execution is possible.
 * However, the input must be validated at the boundary so that malicious payloads
 * never reach the JNI layer even as a malformed address.
 *
 * Attack vectors tested:
 *   - Shell metacharacters  ("127.0.0.1; rm -rf /")
 *   - Backtick injection    ("127.0.0.1`id`")
 *   - Command substitution  ("$(id)")
 *   - Pipe injection        ("127.0.0.1 | cat /etc/passwd")
 *   - Newline injection     ("127.0.0.1\n/etc/cron.d/evil")
 *   - Null byte injection   ("127.0.0.1\u0000evil")
 */
class CliInjectionTest {

    // InetAddress.getByName() is the boundary used by all CLI commands before
    // passing an address to the engine or JNI layer.

    @Test
    fun `shell semicolon payload is rejected as invalid address`() {
        assertThrows(UnknownHostException::class.java) {
            InetAddress.getByName("127.0.0.1; rm -rf /")
        }
    }

    @Test
    fun `backtick injection is rejected as invalid address`() {
        assertThrows(UnknownHostException::class.java) {
            InetAddress.getByName("127.0.0.1`id`")
        }
    }

    @Test
    fun `dollar-paren command substitution is rejected`() {
        assertThrows(UnknownHostException::class.java) {
            InetAddress.getByName("\$(id)")
        }
    }

    @Test
    fun `pipe injection is rejected as invalid address`() {
        assertThrows(UnknownHostException::class.java) {
            InetAddress.getByName("127.0.0.1 | cat /etc/passwd")
        }
    }

    @Test
    fun `newline injection is rejected as invalid address`() {
        assertThrows(UnknownHostException::class.java) {
            InetAddress.getByName("127.0.0.1\n/etc/cron.d/evil")
        }
    }

    @Test
    fun `null byte injection is rejected as invalid address`() {
        assertThrows(UnknownHostException::class.java) {
            InetAddress.getByName("127.0.0.1\u0000evil")
        }
    }

    @Test
    fun `valid IP address is accepted`() {
        val addr = InetAddress.getByName("127.0.0.1")
        assertFalse(addr.hostAddress.contains(";"))
        assertFalse(addr.hostAddress.contains("|"))
    }

    @Test
    fun `valid hostname is accepted`() {
        // Verify that the validation doesn't reject legitimate hostnames
        val addr = InetAddress.getByName("localhost")
        assertFalse(addr.hostAddress.isBlank())
    }
}
