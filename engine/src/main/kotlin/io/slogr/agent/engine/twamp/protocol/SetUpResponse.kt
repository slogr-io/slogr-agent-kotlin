package io.slogr.agent.engine.twamp.protocol

import io.slogr.agent.engine.twamp.TwampMode
import java.nio.ByteBuffer

/**
 * TWAMP SetUpResponse (164 bytes). RFC 5357 §3.1
 *
 * Wire layout:
 *    4 bytes mode
 *   80 bytes KeyID (UTF-8, zero-padded; unused in unauthenticated mode)
 *   64 bytes token (encrypted challenge+keys; unused in unauthenticated mode)
 *   16 bytes Client-IV
 *
 * **Token** (in authenticated/encrypted mode):
 *   token = AES-CBC( [16-byte challenge | 16-byte AES key | 32-byte HMAC key],
 *                    kdk, iv=zeros )
 *   where kdk = PBKDF2(sharedSecret, salt, count)
 *
 * **FIX-3**: Client-IV is ALWAYS sent unencrypted (plaintext), regardless of mode.
 * RFC 4656 §3.1 is explicit: the IV is not protected. The Java reference incorrectly
 * added an `encIV` toggle — this port removes it entirely.
 */
class SetUpResponse {
    var mode: Int = 0
    var keyId: ByteArray = ByteArray(80)
    var token: ByteArray = ByteArray(64)
    var clientIv: ByteArray = ByteArray(16)

    fun writeTo(bb: ByteBuffer) {
        bb.putInt(mode)
        bb.put(keyId)
        bb.put(token)
        // FIX-3: Client-IV is always sent unencrypted
        bb.put(clientIv)
    }

    companion object {
        const val SIZE = 164

        fun readFrom(bb: ByteBuffer): SetUpResponse {
            val msg = SetUpResponse()
            msg.mode = bb.int
            bb.get(msg.keyId)
            bb.get(msg.token)
            // FIX-3: Client-IV is always read as-is (plaintext)
            bb.get(msg.clientIv)
            return msg
        }
    }
}
