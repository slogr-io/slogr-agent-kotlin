package io.slogr.agent.engine.twamp

import io.slogr.agent.engine.twamp.protocol.RequestTwSession
import io.slogr.agent.engine.twamp.protocol.SenderEncryptUPacket
import io.slogr.agent.engine.twamp.protocol.SenderUPacket
import io.slogr.agent.engine.twamp.protocol.SetUpResponse
import io.slogr.agent.engine.twamp.protocol.StartNSession
import io.slogr.agent.engine.twamp.protocol.StartSessions
import io.slogr.agent.engine.twamp.protocol.StopNSession
import io.slogr.agent.engine.twamp.protocol.StopSessions
import io.slogr.agent.engine.twamp.util.TwampTimeUtil
import java.net.Inet6Address
import java.net.InetAddress


/**
 * Factory for TWAMP controller-side protocol messages.
 *
 * Assembles outgoing control messages and test packets from caller-supplied
 * parameters without any I/O or state.
 */
object PacketUtil {

    /**
     * Build a [SetUpResponse] for the given [mode] and cryptographic fields.
     * In unauthenticated mode ([TwampMode.UNAUTHENTICATED]) the [keyId], [token],
     * and [clientIv] fields are sent as all-zeros.
     */
    fun genSetUpResponse(
        mode: Int,
        keyId: ByteArray = ByteArray(80),
        token: ByteArray = ByteArray(64),
        clientIv: ByteArray = ByteArray(16)
    ): SetUpResponse = SetUpResponse().apply {
        this.mode = mode
        this.keyId = keyId.copyOf()
        this.token = token.copyOf()
        this.clientIv = clientIv.copyOf()
    }

    /**
     * Build a [RequestTwSession] for the given test parameters.
     *
     * @param senderPort      UDP source port for test packets (0 = ephemeral).
     * @param receiverPort    UDP port the reflector will listen on.
     * @param paddingLength   Extra padding bytes per test packet.
     * @param timeoutMs       Session timeout in milliseconds (converted to NTP duration).
     * @param dscp            DSCP value packed into typeDescriptor (bits 31–24).
     * @param senderIp        Sender IP; null lets the reflector use the socket address.
     * @param receiverIp      Reflector IP; null lets the reflector use its bound address.
     */
    fun genRequestTwSession(
        senderPort: Short = 0,
        receiverPort: Short = 862,
        paddingLength: Int = 0,
        timeoutMs: Long = 900_000L,
        dscp: Int = 0,
        senderIp: InetAddress? = null,
        receiverIp: InetAddress? = null
    ): RequestTwSession = RequestTwSession().apply {
        val ipv6 = (senderIp as? Inet6Address) != null || (receiverIp as? Inet6Address) != null
        this.ipvn = if (ipv6) 6 else 4
        val addrLen = if (ipv6) 16 else 4

        this.senderPort = senderPort
        this.receiverPort = receiverPort
        this.paddingLength = paddingLength

        if (senderIp != null) {
            System.arraycopy(senderIp.address, 0, this.senderAddress, 0, addrLen)
        }
        if (receiverIp != null) {
            System.arraycopy(receiverIp.address, 0, this.receiverAddress, 0, addrLen)
        }

        // startTime = now + 10 seconds (10 NTP seconds = 10L shl 32)
        this.startTime = TwampTimeUtil.currentNtpTimestamp() + (10L shl 32)
        // timeout as NTP duration: seconds portion only
        this.timeout = (timeoutMs / 1000L) shl 32

        // typeDescriptor: DSCP occupies the most-significant byte
        this.typeDescriptor = (dscp and 0xFF) shl 24
    }

    /** Build a [StartSessions] message (type is written by [StartSessions.writeTo]). */
    fun genStartSessions(): StartSessions = StartSessions()

    /** Build a [StopSessions] message with accept=0. */
    fun genStopSessions(): StopSessions = StopSessions().apply {
        accept = 0
    }

    /** Build a [StartNSession] with defaults. */
    fun genStartNSessions(): StartNSession = StartNSession()

    /** Build a [StopNSession] with defaults. */
    fun genStopNSessions(): StopNSession = StopNSession()

    /** Build an unauthenticated sender test packet with [seqNo] and zero error estimate. */
    fun genTestPacket(seqNo: Int): SenderUPacket = SenderUPacket().apply {
        seqNumber = seqNo
        errorEstimate = 0
    }

    /**
     * Build an authenticated/encrypted sender test packet with [seqNo] and zero error estimate.
     */
    fun genEncryptTestPacket(seqNo: Int): SenderEncryptUPacket = SenderEncryptUPacket().apply {
        seqNumber = seqNo
        errorEstimate = 0
    }
}
