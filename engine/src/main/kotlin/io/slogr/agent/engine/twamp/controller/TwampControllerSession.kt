package io.slogr.agent.engine.twamp.controller

import io.slogr.agent.engine.twamp.PacketUtil
import io.slogr.agent.engine.twamp.SessionId
import io.slogr.agent.engine.twamp.TwampCommand
import io.slogr.agent.engine.twamp.TwampConstants
import io.slogr.agent.engine.twamp.TwampMode
import io.slogr.agent.engine.twamp.auth.ModePreferenceChain
import io.slogr.agent.engine.twamp.auth.TwampCrypto
import io.slogr.agent.engine.twamp.protocol.AcceptTwSession
import io.slogr.agent.engine.twamp.protocol.ServerGreeting
import io.slogr.agent.engine.twamp.protocol.ServerStart
import io.slogr.agent.engine.twamp.protocol.SetUpResponse
import io.slogr.agent.engine.twamp.protocol.StartNAck
import io.slogr.agent.engine.twamp.protocol.StartSessionsAck
import io.slogr.agent.engine.twamp.protocol.StopNAck
import io.slogr.agent.native.NativeProbeAdapter
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel
import java.security.SecureRandom
import java.util.concurrent.ScheduledExecutorService

/**
 * TWAMP controller (client-side) control session state machine.
 *
 * Implements RFC 5357 from the controller's perspective. Runs on the NIO
 * Selector thread of [TwampController]; no blocking calls permitted.
 *
 * **State machine:**
 * ```
 * AWAITING_GREETING
 *   ↓ receives ServerGreeting, sends SetUpResponse
 * AWAITING_SERVER_START
 *   ↓ receives ServerStart, sends RequestTwSession
 * AWAITING_ACCEPT_SESSION            ← AcceptTwSession has NO cmd byte
 *   ↓ receives AcceptTwSession, sends StartSessions
 * AWAITING_START_ACK                 ← StartSessionsAck has NO cmd byte
 *   ↓ receives StartSessionsAck, starts TwampSessionSender threads
 * RUNNING                            ← waits for senders to finish
 *   ↓ all senders complete
 * CLOSED
 * ```
 *
 * Individual session control (RFC 5938) uses StartNSession/StopNSession with
 * command bytes; that path transitions through AWAITING_N_ACK instead.
 *
 * **FIX-1**: no maximum count ceiling (counts ≥ 1024 accepted).
 * **FIX-3**: Client-IV always sent unencrypted.
 *
 * @param key             SelectionKey for the TCP control channel.
 * @param reflectorIp     Reflector IP address.
 * @param config          Measurement parameters (packets, interval, padding…).
 * @param modeChain       Preferred TWAMP modes, highest-preference first.
 * @param sharedSecret    Shared secret for authenticated/encrypted mode.
 * @param adapter         UDP socket adapter for test packet senders.
 * @param scheduler       Shared ScheduledExecutorService.
 * @param localIp         Local IP for sender sockets.
 * @param onComplete      Invoked on the selector thread when the session ends.
 */
class TwampControllerSession(
    private val key: SelectionKey,
    private val reflectorIp: InetAddress,
    private val config: SenderConfig,
    private val modeChain: ModePreferenceChain = ModePreferenceChain().prefer(TwampMode.UNAUTHENTICATED),
    private val sharedSecret: ByteArray? = null,
    private val adapter: NativeProbeAdapter,
    private val scheduler: ScheduledExecutorService,
    private val localIp: InetAddress,
    private val onComplete: (SenderResult) -> Unit
) {
    private val log = LoggerFactory.getLogger(TwampControllerSession::class.java)
    private val rand = SecureRandom()

    private enum class State {
        AWAITING_GREETING,
        AWAITING_SERVER_START,
        AWAITING_ACCEPT_SESSION,  // after RequestTwSession sent; AcceptTwSession has no cmd byte
        AWAITING_START_ACK,       // after StartSessions sent; StartSessionsAck has no cmd byte
        AWAITING_N_ACK,           // after StartNSession sent; StartNAck has cmd byte
        RUNNING,
        CLOSED
    }

    private var state = State.AWAITING_GREETING
    private val twampMode = TwampMode()

    // Crypto from server greeting
    private var serverSalt = ByteArray(16)
    private var serverCount = TwampConstants.DEFAULT_COUNT

    // From AcceptTwSession
    private var reflectorUdpPort = 0
    private var acceptedSid: SessionId? = null

    // Sender lifecycle
    private val activeSenders = mutableListOf<Thread>()
    private val senderResults = mutableListOf<SenderResult>()
    @Volatile private var completed = false
    @Volatile private var closeReason: String? = null

    // Accumulation buffer for partial TCP reads — TCP does not guarantee message
    // boundaries, so a 64-byte ServerGreeting may arrive as 40+24 across two reads.
    private val accumBuf = ByteBuffer.allocate(9216)

    fun getSelectionKey(): SelectionKey = key

    /** Set the reason for closing (called by [TwampController] before closeWithCallback). */
    fun setCloseReason(reason: String) { if (closeReason == null) closeReason = reason }

    /** Called by [TwampController] when TCP data is available. NOT called on close. */
    @Synchronized
    fun read(buf: ByteBuffer) {
        // Accumulate incoming bytes across reads
        accumBuf.put(buf)
        accumBuf.flip()
        try {
            dispatchRead(accumBuf)
        } catch (e: SecurityException) {
            log.error("Auth failure in state $state: ${e.message}")
            closeReason = "auth failure in $state: ${e.message}"
            close()
        } catch (e: Exception) {
            log.error("Session error in state $state: ${e.message}", e)
            closeReason = "session error in $state: ${e.message}"
            close()
        }
        // Preserve unconsumed bytes for next read
        accumBuf.compact()
    }

    private fun dispatchRead(buf: ByteBuffer) {
        when (state) {
            State.AWAITING_GREETING       -> handleServerGreeting(buf)
            State.AWAITING_SERVER_START   -> handleServerStart(buf)
            State.AWAITING_ACCEPT_SESSION -> handleAcceptSession(buf)
            State.AWAITING_START_ACK      -> handleStartSessionsAck(buf)
            State.AWAITING_N_ACK          -> handleNAck(buf)
            State.RUNNING, State.CLOSED   -> {}
        }
    }

    // ── State: AWAITING_GREETING ─────────────────────────────────────────────

    private fun handleServerGreeting(buf: ByteBuffer) {
        if (buf.remaining() < ServerGreeting.SIZE) return
        val greeting = ServerGreeting.readFrom(buf)

        val selectedMode = modeChain.selectMode(greeting.modes)
        if (selectedMode == null) {
            log.error("No common TWAMP mode (server 0x${greeting.modes.toString(16)})")
            closeReason = "no common TWAMP mode (server offered 0x${greeting.modes.toString(16)})"
            close(); return
        }
        twampMode.mode = selectedMode
        serverSalt = greeting.salt
        serverCount = greeting.count   // FIX-1: no ceiling

        sendSetUpResponse(greeting.challenge)
        state = State.AWAITING_SERVER_START
    }

    private fun sendSetUpResponse(challenge: ByteArray) {
        val response: SetUpResponse
        if (twampMode.isControlEncrypted()) {
            val secret = requireNotNull(sharedSecret) { "shared secret required for auth mode" }
            val kdk = TwampCrypto.pbkdf2(secret, serverSalt, serverCount)
            val aesKey  = ByteArray(16).also { rand.nextBytes(it) }
            val hmacKey = ByteArray(32).also { rand.nextBytes(it) }
            val clientIv = ByteArray(16).also { rand.nextBytes(it) }
            // Token: encrypt [challenge | aesKey | hmacKey] with CBC(kdk, iv=zeros)
            val tokenPlain = challenge + aesKey + hmacKey
            val encToken = TwampCrypto.encryptAesCbc(kdk, tokenPlain, ByteArray(16), updateIv = false)

            twampMode.aesKey  = aesKey
            twampMode.hmacKey = hmacKey.copyOf(16)
            twampMode.sendIv  = clientIv.copyOf()
            twampMode.receiveIv = clientIv.copyOf()

            response = PacketUtil.genSetUpResponse(
                mode     = twampMode.mode,
                token    = encToken,
                clientIv = clientIv   // FIX-3: always plaintext
            )
        } else {
            response = PacketUtil.genSetUpResponse(mode = TwampMode.UNAUTHENTICATED)
        }
        val bb = ByteBuffer.allocate(SetUpResponse.SIZE)
        response.writeTo(bb)
        writeRaw(bb.array())
    }

    // ── State: AWAITING_SERVER_START ─────────────────────────────────────────

    private fun handleServerStart(buf: ByteBuffer) {
        if (buf.remaining() < ServerStart.SIZE) return
        val start = ServerStart.readFrom(buf, twampMode)
        if (start.accept != 0.toByte()) {
            log.error("Server rejected SetUpResponse: accept=${start.accept}")
            closeReason = "server rejected SetUpResponse (accept=${start.accept})"
            close(); return
        }
        if (twampMode.isControlEncrypted()) {
            twampMode.receiveIv = start.serverIv.copyOf()
        }
        sendRequestTwSession()
        state = State.AWAITING_ACCEPT_SESSION
    }

    private fun sendRequestTwSession() {
        val req = PacketUtil.genRequestTwSession(
            senderPort    = config.senderPort.toShort(),
            receiverPort  = TwampConstants.DEFAULT_PORT.toShort(),
            paddingLength = config.paddingLength,
            timeoutMs     = config.waitTimeMs,
            dscp          = config.dscp,
            receiverIp    = reflectorIp
        )
        val bb = ByteBuffer.allocate(/* RequestTwSession */ 112)
        req.writeTo(bb, twampMode)
        writeRaw(bb.array())
    }

    // ── State: AWAITING_ACCEPT_SESSION ───────────────────────────────────────

    private fun handleAcceptSession(buf: ByteBuffer) {
        if (buf.remaining() < AcceptTwSession.SIZE) return
        val accept = AcceptTwSession.readFrom(buf, twampMode)
        if (accept.accept != 0.toByte()) {
            log.error("Reflector rejected test session: accept=${accept.accept}")
            closeReason = "reflector rejected test session (accept=${accept.accept})"
            close(); return
        }
        reflectorUdpPort = accept.port.toInt() and 0xFFFF
        acceptedSid = SessionId.fromByteArray(accept.sid)
        sendStartSessions()
    }

    private fun sendStartSessions() {
        if (twampMode.isIndividualSessionControl()) {
            val req = PacketUtil.genStartNSessions()
            val bb = ByteBuffer.allocate(/* StartNSession */ 32)
            req.writeTo(bb, twampMode)
            writeRaw(bb.array())
            state = State.AWAITING_N_ACK
        } else {
            val req = PacketUtil.genStartSessions()
            val bb = ByteBuffer.allocate(/* StartSessions */ 32)
            req.writeTo(bb, twampMode)
            writeRaw(bb.array())
            state = State.AWAITING_START_ACK
        }
    }

    // ── State: AWAITING_START_ACK ────────────────────────────────────────────

    private fun handleStartSessionsAck(buf: ByteBuffer) {
        if (buf.remaining() < StartSessionsAck.SIZE) return
        val ack = StartSessionsAck.readFrom(buf, twampMode)
        if (ack.accept != 0.toByte()) {
            log.error("Start-Sessions rejected: accept=${ack.accept}")
            closeReason = "start-sessions rejected (accept=${ack.accept})"
            close(); return
        }
        startSenders()
    }

    // ── State: AWAITING_N_ACK (individual session control) ───────────────────

    private fun handleNAck(buf: ByteBuffer) {
        if (!buf.hasRemaining()) return
        when (val cmd = buf.get()) {
            TwampCommand.START_N_ACK -> {
                val ack = StartNAck.readFrom(buf, twampMode)
                if (ack.accept != 0.toByte()) { closeReason = "start-n rejected (accept=${ack.accept})"; close(); return }
                startSenders()
            }
            TwampCommand.STOP_N_ACK -> {
                StopNAck.readFrom(buf, twampMode)
                close()
            }
            else -> log.warn("Unexpected cmd 0x${cmd.toInt().and(0xFF).toString(16)} in AWAITING_N_ACK")
        }
    }

    // ── Sender lifecycle ─────────────────────────────────────────────────────

    private fun startSenders() {
        val sid = acceptedSid ?: run { log.error("No SID — cannot start senders"); closeReason = "no SID from reflector"; close(); return }
        val testMode = twampMode.getTestSessionMode(sid)
        val port = reflectorUdpPort.takeIf { it > 0 } ?: TwampConstants.DEFAULT_PORT

        val sender = TwampSessionSender(
            adapter       = adapter,
            reflectorIp   = reflectorIp,
            reflectorPort = port,
            localIp       = localIp,
            config        = config,
            scheduler     = scheduler,
            onComplete    = { result ->
                synchronized(this) {
                    senderResults.add(result)
                    if (senderResults.size >= activeSenders.size) {
                        completed = true
                        onComplete(mergeSenderResults())
                        close()
                    }
                }
            }
        )
        sender.setSid(sid, testMode)
        val t = Thread(sender, "twamp-sender-${sid}").also { it.isDaemon = true }
        activeSenders.add(t)
        state = State.RUNNING
        t.start()
    }

    private fun mergeSenderResults(): SenderResult =
        senderResults.fold(SenderResult(emptyList(), 0, 0)) { acc, r ->
            SenderResult(
                packets     = acc.packets + r.packets,
                packetsSent = acc.packetsSent + r.packetsSent,
                packetsRecv = acc.packetsRecv + r.packetsRecv,
                error       = acc.error ?: r.error
            )
        }

    // ── Stop / close ─────────────────────────────────────────────────────────

    fun triggerStop() {
        val stop = PacketUtil.genStopSessions()
        val bb = ByteBuffer.allocate(/* StopSessions */ 32)
        stop.writeTo(bb, twampMode)
        writeRaw(bb.array())
    }

    @Synchronized
    fun writeRaw(data: ByteArray) {
        try {
            val chan = key.channel() as SocketChannel
            var buf = ByteBuffer.wrap(data)
            if (twampMode.isControlEncrypted()) {
                val enc = TwampCrypto.encryptAesCbc(
                    requireNotNull(twampMode.aesKey),
                    data,
                    twampMode.sendIv,
                    updateIv = true
                )
                buf = ByteBuffer.wrap(enc)
            }
            while (buf.hasRemaining()) chan.write(buf)
        } catch (e: Exception) {
            log.error("Write failed in $state: ${e.message}")
            closeReason = "write failed in $state: ${e.message}"
            close()
        }
    }

    fun close() {
        if (state == State.CLOSED) return
        state = State.CLOSED
        try { key.channel().close() } catch (_: Exception) {}
        key.cancel()
    }

    /** Close and fire onComplete with an error result if not already completed. */
    fun closeWithCallback() {
        val needsCallback = !completed
        val reason = closeReason ?: "closed in state $state"
        close()  // sets state=CLOSED — capture reason above
        if (needsCallback) {
            completed = true
            onComplete(SenderResult(
                packets = emptyList(), packetsSent = 0, packetsRecv = 0,
                error = reason
            ))
        }
    }

    val isClosed: Boolean get() = state == State.CLOSED
}
