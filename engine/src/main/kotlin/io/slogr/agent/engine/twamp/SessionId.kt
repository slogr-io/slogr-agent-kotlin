package io.slogr.agent.engine.twamp

import java.nio.ByteBuffer

/**
 * Identifies a TWAMP test session (SID).
 *
 * Per RFC 5357 the SID SHOULD be constructed as:
 *   4 bytes: IPv4 address of the generating side
 *   8 bytes: timestamp
 *   4 bytes: random number
 * = 16 bytes total.
 */
data class SessionId(
    val ipv4: Int,
    val timestamp: Long,
    val randNumber: Int
) {
    /** Encode to 16-byte wire format (big-endian). */
    fun toByteArray(): ByteArray = ByteBuffer.allocate(16).apply {
        putInt(ipv4)
        putLong(timestamp)
        putInt(randNumber)
    }.array()

    override fun toString(): String =
        "0x%08x:0x%016x:0x%08x".format(ipv4, timestamp, randNumber)

    companion object {
        /** Decode from 16-byte wire format. */
        fun fromByteArray(bytes: ByteArray): SessionId {
            require(bytes.size >= 16) { "SessionId requires 16 bytes, got ${bytes.size}" }
            val bb = ByteBuffer.wrap(bytes)
            return SessionId(bb.int, bb.long, bb.int)
        }
    }
}
