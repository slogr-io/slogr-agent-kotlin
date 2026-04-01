package io.slogr.agent.engine.twamp.auth

import java.net.InetAddress

/**
 * Identifies a TWAMP control session by its four-tuple: local address+port
 * and remote address+port. Used as a map key for session state lookups.
 *
 * Port values use [Int] (unsigned-16 range 0..65535) to avoid sign-extension
 * issues when reading from [java.nio.ByteBuffer.getShort].
 */
data class TwampSessionKeys(
    val localIp: InetAddress,
    val localPort: Int,
    val remoteIp: InetAddress,
    val remotePort: Int
)
