package io.slogr.agent.engine.twamp.util

import io.slogr.agent.engine.twamp.FillMode
import io.slogr.agent.engine.twamp.TwampMode
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * Padding for TWAMP test packets (sender and reflector sides).
 *
 * **Sender wire layout:**
 * ```
 * [mbz: pktTruncLen bytes, all-zero]  ← only present in symmetrical-size mode
 * [payload: length bytes]             ← zero-filled or random; serverOctet in first 2 bytes
 * ```
 *
 * **Reflector wire layout (no symmetrical-size):**
 * ```
 * [payload: senderPayloadLen - pktTruncLen bytes]
 * ```
 *
 * **Reflector wire layout (symmetrical-size):**
 * ```
 * [payload: same as sender payload]
 * ```
 */
class PacketPadding private constructor(
    private val mbz: ByteArray?,
    private var payload: ByteArray?,
) {
    /** Total wire-byte count for this padding section. */
    val length: Int get() = (mbz?.size ?: 0) + (payload?.size ?: 0)

    /** Deserialize: consume mbz bytes first (if any), then all remaining bytes as payload. */
    fun readFrom(bb: ByteBuffer) {
        mbz?.let { bb.get(it) }
        val rem = bb.remaining()
        if (rem > 0) {
            payload = ByteArray(rem).also { bb.get(it) }
        }
    }

    /** Serialize into [bb]. */
    fun writeTo(bb: ByteBuffer) {
        mbz?.let { bb.put(it) }
        payload?.let { bb.put(it) }
    }

    companion object {
        /**
         * Create sender-side padding.
         *
         * @param mode     Current TWAMP mode (determines symmetricalSize and serverOctet).
         * @param length   Requested payload length in bytes (0 = no payload).
         * @param fillMode Whether payload bytes are all-zero or random.
         */
        fun forSender(mode: TwampMode, length: Int, fillMode: FillMode): PacketPadding {
            val payload: ByteArray? = if (length > 0) {
                val bytes = if (fillMode == FillMode.RANDOM) Random.nextBytes(length) else ByteArray(length)
                // serverOctet goes in the first two bytes of the padding (RFC 5357 §4.1.2)
                if (mode.serverOctet.toInt() != 0 && bytes.size >= 2) {
                    ByteBuffer.wrap(bytes).putShort(mode.serverOctet)
                }
                bytes
            } else null

            // In symmetrical-size mode, a zero-filled MBZ block (pktTruncLen bytes) precedes
            // the payload on the sender side so that the total sender packet size matches the
            // reflector packet size after the reflector drops those bytes (RFC 6038 §4.2).
            val mbz: ByteArray? = if (mode.isSymmetricalSize()) ByteArray(mode.pktTruncLength()) else null

            return PacketPadding(mbz, payload)
        }

        /**
         * Create reflector-side padding derived from [senderPadding].
         *
         * - symmetrical-size: echo the sender's payload bytes unchanged.
         * - normal: truncate sender payload by [TwampMode.pktTruncLength] bytes.
         */
        fun forReflector(mode: TwampMode, senderPadding: PacketPadding): PacketPadding {
            val senderBytes = senderPadding.payload
            val payload: ByteArray? = when {
                senderBytes == null -> null
                mode.isSymmetricalSize() -> senderBytes.copyOf()
                else -> {
                    val truncLen = mode.pktTruncLength()
                    val newLen = senderBytes.size - truncLen
                    if (newLen > 0) senderBytes.copyOf(newLen) else null
                }
            }
            return PacketPadding(null, payload)
        }

        /**
         * Create a padding placeholder with [byteCount] zero bytes.
         * Used when deserializing packets where the padding length is inferred
         * from the total datagram size.
         */
        fun empty(byteCount: Int): PacketPadding =
            PacketPadding(null, if (byteCount > 0) ByteArray(byteCount) else null)
    }
}
