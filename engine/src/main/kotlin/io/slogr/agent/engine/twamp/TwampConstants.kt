package io.slogr.agent.engine.twamp

/**
 * Protocol constants for RFC 5357 TWAMP.
 *
 * FIX-1 applied: no upper bound on PBKDF2 count. The Java reference
 * capped count at DEFAULT_MAX_COUNT (32768), which rejects Cisco IOS
 * ServerGreeting messages that send count=65536. RFC 5357 places no
 * upper bound — any count that is a power of 2 and >= 1024 is valid.
 */
object TwampConstants {
    /** RFC 5357 well-known TWAMP-Control TCP port. */
    const val DEFAULT_PORT: Int = 862

    /** Minimum PBKDF2 iteration count mandated by RFC 5357. */
    const val MIN_COUNT: Int = 1024

    /** Default count used when generating a ServerGreeting (power of 2 >= 1024). */
    const val DEFAULT_COUNT: Int = 1024

    /** Server-wait interval in seconds before closing an idle control session. */
    const val DEFAULT_SERVER_WAIT_SEC: Int = 900

    /** Reflector-wait interval in seconds before expiring a test session. */
    const val DEFAULT_REF_WAIT_SEC: Int = 900

    /** Maximum stop-session wait in ms (how long reflector waits for final packets). */
    const val MAX_STOP_SESSION_TIMEOUT_MS: Long = 10_000L

    /** Max simultaneous test sessions per control connection. */
    const val MAX_TEST_SESSIONS_PER_CONN: Int = 25

    /**
     * Packet truncation offset for unauthenticated mode:
     * ReflectorUPacket.BASE_SIZE - SenderUPacket.BASE_SIZE = 41 - 14 = 27.
     */
    const val PKT_TRUNC_UNAUTH: Int = 27

    /**
     * Packet truncation offset for authenticated/encrypted mode:
     * ReflectorEncryptUPacket.BASE_SIZE - SenderEncryptUPacket.BASE_SIZE = 112 - 48 = 64.
     */
    const val PKT_TRUNC_AUTH_ENC: Int = 64

    /** Slogr fingerprint magic bytes written into ServerGreeting unused bytes. */
    val SLOGR_MAGIC: ByteArray = "SLOGR".toByteArray(Charsets.US_ASCII)

    /** R1 protocol version written into ServerGreeting bytes 5-6. */
    const val SLOGR_PROTOCOL_VERSION: Short = 0x0001
}
