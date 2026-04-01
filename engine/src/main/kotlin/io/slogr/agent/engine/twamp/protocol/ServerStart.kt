package io.slogr.agent.engine.twamp.protocol

import io.slogr.agent.engine.twamp.TwampMode
import io.slogr.agent.engine.twamp.auth.TwampCrypto
import java.nio.ByteBuffer

/**
 * TWAMP ServerStart (48 bytes). RFC 5357 §3.1
 *
 * Wire layout:
 *   15 bytes MBZ
 *    1 byte  Accept
 *   16 bytes Server-IV
 *    8 bytes Start-Time (NTP timestamp)  ← encrypted in authenticated mode
 *    8 bytes MBZ2                        ← encrypted in authenticated mode
 *
 * **BUG-C context**: Server-IV MUST be randomly generated for modes 2 (authenticated),
 * 4 (encrypted), AND 8 (mixed). The Java reference only generated a random IV for
 * modes 2 and 4, leaving mode 8 with an all-zero IV. The Kotlin port generates a
 * random IV for all non-unauthenticated modes. The caller is responsible for
 * populating [serverIv] with a random value before calling [writeTo].
 */
class ServerStart {
    var accept: Byte = 0
    var serverIv: ByteArray = ByteArray(16)
    var startTime: Long = 0L

    /**
     * Serialize to [bb].
     *
     * In authenticated/encrypted mode, startTime and MBZ2 are encrypted with AES-CBC
     * using the control session's send-IV (IV is updated in place per CBC chaining).
     */
    fun writeTo(bb: ByteBuffer, mode: TwampMode) {
        bb.put(ByteArray(15))  // MBZ
        bb.put(accept)
        bb.put(serverIv)
        mode.sendIv = serverIv.copyOf()  // update send-IV to server-IV

        val plaintext = ByteBuffer.allocate(16).also {
            it.putLong(startTime)
            it.putLong(0L)  // MBZ2
        }.array()

        if (mode.isControlEncrypted()) {
            val encrypted = TwampCrypto.encryptAesCbc(
                requireNotNull(mode.aesKey), plaintext, mode.sendIv, updateIv = true
            )
            bb.put(encrypted)
        } else {
            bb.put(plaintext)
        }
    }

    companion object {
        const val SIZE = 48

        /**
         * Deserialize from [bb].
         *
         * In authenticated/encrypted mode, [mode].receiveIv is updated with the Server-IV
         * for subsequent CBC decryption, then the start-time block is decrypted.
         */
        fun readFrom(bb: ByteBuffer, mode: TwampMode): ServerStart {
            val msg = ServerStart()
            repeat(15) { bb.get() }  // skip MBZ
            msg.accept = bb.get()
            bb.get(msg.serverIv)
            // Update receive-IV with the Server-IV for CBC chaining
            mode.receiveIv = msg.serverIv.copyOf()

            if (mode.isControlEncrypted()) {
                val ciphertext = ByteArray(16).also { bb.get(it) }
                val plain = TwampCrypto.decryptAesCbc(
                    requireNotNull(mode.aesKey), ciphertext, mode.receiveIv, updateIv = true
                )
                val pbb = ByteBuffer.wrap(plain)
                msg.startTime = pbb.long
                // discard MBZ2
            } else {
                msg.startTime = bb.long
                bb.long  // skip MBZ2
            }
            return msg
        }
    }
}
