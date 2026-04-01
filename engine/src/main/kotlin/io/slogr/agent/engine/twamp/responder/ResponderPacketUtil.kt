package io.slogr.agent.engine.twamp.responder

import io.slogr.agent.engine.twamp.SessionId
import io.slogr.agent.engine.twamp.TwampConstants
import io.slogr.agent.engine.twamp.TwampMode
import io.slogr.agent.engine.twamp.protocol.AcceptTwSession
import io.slogr.agent.engine.twamp.protocol.ReflectorEncryptUPacket
import io.slogr.agent.engine.twamp.protocol.ReflectorUPacket
import io.slogr.agent.engine.twamp.protocol.SenderEncryptUPacket
import io.slogr.agent.engine.twamp.protocol.SenderUPacket
import io.slogr.agent.engine.twamp.protocol.ServerGreeting
import io.slogr.agent.engine.twamp.protocol.ServerStart
import io.slogr.agent.engine.twamp.protocol.StartNAck
import io.slogr.agent.engine.twamp.protocol.StartSessionsAck
import io.slogr.agent.engine.twamp.protocol.StopNAck
import io.slogr.agent.engine.twamp.util.PacketPadding
import io.slogr.agent.engine.twamp.util.TwampTimeUtil
import java.net.InetAddress
import java.security.SecureRandom

/**
 * Factory for TWAMP responder-side protocol messages.
 *
 * BUG-C fix: [genServerStart] always generates a random IV for any non-unauthenticated
 * mode. The Java reference only generated a random IV for modes 2 and 4, leaving mode 8
 * (mixed) with an all-zero IV.
 */
object ResponderPacketUtil {

    private val secureRandom = SecureRandom()

    private fun genRandom(length: Int): ByteArray =
        ByteArray(length).also { secureRandom.nextBytes(it) }

    /**
     * Build a [ServerGreeting] advertising [modeBits] with random challenge/salt.
     *
     * @param agentIdBytes Optional first 6 bytes of this agent's UUID for the Slogr fingerprint.
     */
    fun genServerGreeting(
        modeBits: Int = TwampMode.UNAUTHENTICATED,
        count: Int = TwampConstants.DEFAULT_COUNT,
        agentIdBytes: ByteArray? = null
    ): ServerGreeting = ServerGreeting().apply {
        modes = modeBits
        challenge = genRandom(16)
        salt = genRandom(16)
        this.count = count
        this.isSlogrAgent = agentIdBytes != null
        // agentIdBytes is passed to writeTo() by the caller, not stored here
    }

    /**
     * Build a [ServerStart] for [mode] and populate [ServerStart.serverIv].
     *
     * BUG-C fix: random IV is generated for ALL non-unauthenticated modes
     * (including mixed mode = 8), not just modes 2 and 4.
     *
     * The caller must set [ServerStart.startTime] or accept the process-start-time default.
     */
    fun genServerStart(mode: Int, accept: Byte = 0): ServerStart = ServerStart().apply {
        this.accept = accept
        this.startTime = TwampTimeUtil.currentNtpTimestamp()
        // BUG-C fix: generate random IV for any non-unauthenticated mode (including mode 8)
        if (mode != TwampMode.UNAUTHENTICATED) {
            serverIv = genRandom(16)
        }
    }

    /**
     * Build an [AcceptTwSession] accepting the session identified by [sid].
     * The [port] is the UDP port the reflector will use for test packets.
     */
    fun genAcceptSession(sid: SessionId, port: Short = 0, accept: Byte = 0): AcceptTwSession =
        AcceptTwSession().apply {
            this.accept = accept
            this.port = port
            this.sid = sid.toByteArray()
        }

    /**
     * Build a [StartSessionsAck] accepting the Start-Sessions command.
     */
    fun genStartAck(accept: Byte = 0): StartSessionsAck = StartSessionsAck().apply {
        this.accept = accept
    }

    /**
     * Build a [StartNAck] for a list of session IDs.
     */
    fun genStartNAck(sids: List<SessionId>, accept: Byte = 0): StartNAck = StartNAck().apply {
        this.accept = accept
        this.sids = sids
    }

    /**
     * Build a [StopNAck] for a list of session IDs.
     */
    fun genStopNAck(sids: List<SessionId>, accept: Byte = 0): StopNAck = StopNAck().apply {
        this.accept = accept
        this.sids = sids
    }

    /**
     * Generate a [SessionId] from the reflector's IP address. Per RFC 5357:
     * SID = [IPv4 address (4 bytes)] + [NTP timestamp (8 bytes)] + [random (4 bytes)].
     */
    fun genSessionId(reflectorIp: InetAddress): SessionId {
        val ipBytes = reflectorIp.address
        val len = ipBytes.size
        val ipv4 = (ipBytes[len - 4].toInt() and 0xFF shl 24) or
                   (ipBytes[len - 3].toInt() and 0xFF shl 16) or
                   (ipBytes[len - 2].toInt() and 0xFF shl 8)  or
                   (ipBytes[len - 1].toInt() and 0xFF)
        return SessionId(
            ipv4       = ipv4,
            timestamp  = TwampTimeUtil.currentNtpTimestamp(),
            randNumber = secureRandom.nextInt()
        )
    }

    /**
     * Build a [ReflectorUPacket] from a received [SenderUPacket] (unauthenticated mode).
     *
     * @param senderPacket     The decoded sender test packet.
     * @param receiveTimeNtp   NTP timestamp when the reflector received the packet.
     * @param senderTtl        TTL from the IP header of the received packet.
     * @param reflectorSeq     Reflector's own sequence number for this response.
     * @param padding          Optional padding matching the negotiated padding length.
     */
    fun genReflectorPacket(
        senderPacket: SenderUPacket,
        receiveTimeNtp: Long,
        senderTtl: Byte,
        reflectorSeq: Int,
        padding: PacketPadding = PacketPadding.empty(0)
    ): ReflectorUPacket = ReflectorUPacket().apply {
        seqNumber = reflectorSeq
        errorEstimate = 0
        receiverTime = receiveTimeNtp
        senderSeqNumber = senderPacket.seqNumber
        senderTime = senderPacket.timestamp
        senderErrorEstimate = senderPacket.errorEstimate
        this.senderTtl = senderTtl
        this.padding = padding
    }

    /**
     * Build a [ReflectorEncryptUPacket] from a received [SenderEncryptUPacket]
     * (authenticated/encrypted mode).
     */
    fun genReflectorEncryptPacket(
        senderPacket: SenderEncryptUPacket,
        receiveTimeNtp: Long,
        senderTtl: Byte,
        reflectorSeq: Int,
        padding: PacketPadding = PacketPadding.empty(0)
    ): ReflectorEncryptUPacket = ReflectorEncryptUPacket().apply {
        seqNumber = reflectorSeq
        errorEstimate = 0
        receiverTime = receiveTimeNtp
        senderSeqNumber = senderPacket.seqNumber
        senderTime = senderPacket.timestamp
        senderErrorEstimate = senderPacket.errorEstimate
        this.senderTtl = senderTtl
        this.padding = padding
    }
}
