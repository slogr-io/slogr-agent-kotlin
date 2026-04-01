package io.slogr.agent.engine.twamp.responder

import io.slogr.agent.engine.twamp.SessionId
import io.slogr.agent.engine.twamp.TwampCommand
import io.slogr.agent.engine.twamp.TwampConstants
import io.slogr.agent.engine.twamp.TwampMode
import io.slogr.agent.engine.twamp.auth.TwampCrypto
import io.slogr.agent.engine.twamp.protocol.RequestTwSession
import io.slogr.agent.engine.twamp.protocol.SetUpResponse
import io.slogr.agent.engine.twamp.protocol.StartNSession
import io.slogr.agent.engine.twamp.protocol.StartSessions
import io.slogr.agent.engine.twamp.protocol.StopNSession
import io.slogr.agent.engine.twamp.protocol.StopSessions
import io.slogr.agent.native.NativeProbeAdapter
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * TWAMP responder (server-side) control session state machine.
 *
 * Implements RFC 5357 from the responder's perspective. Runs on the NIO
 * Selector thread of [TwampReflector]; no blocking calls permitted.
 *
 * **State machine:**
 * ```
 * START
 *   ↓ smStart() sends ServerGreeting
 * AWAITING_SETUP_RESPONSE
 *   ↓ receives SetUpResponse, validates auth, sends ServerStart
 * AWAITING_COMMAND
 *   ↓ reads REQUEST_TW_SESSION / START_SESSIONS / STOP_SESSIONS / …
 * CLOSED
 * ```
 *
 * **BUG-C fix**: ServerStart.serverIv is always random for non-unauthenticated modes.
 * **FIX-2 fix**: StopSessions sessionCount field is informational — ignored.
 *
 * @param key            SelectionKey for the TCP control channel.
 * @param clientIp       Remote controller IP address.
 * @param localIp        Local responder IP address.
 * @param allowlist      IP allowlist for unauthenticated connections.
 * @param sharedSecret   Shared secret for authenticated mode (null = unauth only).
 * @param adapter        UDP socket adapter for test reflectors.
 * @param scheduler      Shared ScheduledExecutorService for timeout and reflector tasks.
 * @param agentIdBytes   Optional first 6 bytes of this agent's UUID (Slogr fingerprint).
 */
class TwampResponderSession(
    private val key: SelectionKey,
    private val clientIp: InetAddress,
    private val localIp: InetAddress,
    private val allowlist: IpAllowlist,
    private val sharedSecret: ByteArray? = null,
    private val adapter: NativeProbeAdapter,
    private val scheduler: ScheduledExecutorService,
    private val agentIdBytes: ByteArray? = null
) {
    private val log = LoggerFactory.getLogger(TwampResponderSession::class.java)

    private enum class State { START, AWAITING_SETUP_RESPONSE, AWAITING_COMMAND, CLOSED }

    private var state = State.START

    private val twampMode = TwampMode()

    // Crypto state from ServerGreeting
    private var serverChallenge = ByteArray(16)
    private var serverSalt = ByteArray(16)
    private val serverCount = TwampConstants.DEFAULT_COUNT

    // Active test sessions keyed by SID
    private val testSessions = mutableMapOf<SessionId, ReflectorSession>()

    // Server-wait timeout task
    private var serverWaitTask: Future<*>? = null
    private val serverWaitCount = AtomicInteger(0)
    private val serverWaitIntervalMs =
        (TwampConstants.DEFAULT_SERVER_WAIT_SEC * 1000L / 10).coerceAtLeast(1000L)

    fun getSelectionKey(): SelectionKey = key

    /** Called by [TwampReflector] immediately after accept() to send ServerGreeting. */
    fun smStart() {
        if (!allowlist.isAllowed(clientIp)) {
            log.warn("Rejected connection from $clientIp (not in allowlist)")
            close(); return
        }
        val greeting = ResponderPacketUtil.genServerGreeting(
            modeBits     = advertisedModes(),
            count        = serverCount,
            agentIdBytes = agentIdBytes
        )
        serverChallenge = greeting.challenge
        serverSalt = greeting.salt

        val bb = ByteBuffer.allocate(/* ServerGreeting */ 64)
        greeting.writeTo(bb, agentIdBytes)
        writeRaw(bb.array())
        state = State.AWAITING_SETUP_RESPONSE
        startServerWaitTimer()
    }

    private fun advertisedModes(): Int =
        if (sharedSecret != null)
            TwampMode.UNAUTHENTICATED or TwampMode.AUTHENTICATED
        else
            TwampMode.UNAUTHENTICATED

    private fun startServerWaitTimer() {
        serverWaitTask = scheduler.scheduleAtFixedRate({
            if (serverWaitCount.incrementAndGet() >= 10) {
                log.debug("Server-wait timeout — closing idle control session")
                close()
            }
        }, serverWaitIntervalMs, serverWaitIntervalMs, TimeUnit.MILLISECONDS)
    }

    // ── Read dispatch ────────────────────────────────────────────────────────

    @Synchronized
    fun read(buf: ByteBuffer) {
        serverWaitCount.set(0)
        try {
            when (state) {
                State.AWAITING_SETUP_RESPONSE -> handleSetupResponse(buf)
                State.AWAITING_COMMAND        -> readCommand(buf)
                else -> {}
            }
        } catch (e: SecurityException) {
            log.warn("Auth failure from $clientIp: ${e.message}")
            close()
        } catch (e: Exception) {
            log.error("Responder session error: ${e.message}", e)
            close()
        }
    }

    // ── State: AWAITING_SETUP_RESPONSE ───────────────────────────────────────

    private fun handleSetupResponse(buf: ByteBuffer) {
        if (buf.remaining() < SetUpResponse.SIZE) return
        val response = SetUpResponse.readFrom(buf)

        val modeOk = (response.mode and advertisedModes()) != 0
        if (!modeOk) {
            log.warn("Client requested unsupported mode 0x${response.mode.toString(16)}")
            close(); return
        }
        twampMode.mode = response.mode

        if (twampMode.isControlEncrypted()) {
            val secret = sharedSecret
            if (secret == null) {
                log.warn("Auth mode requested but no shared secret configured")
                close(); return
            }
            // Derive key-derivation-key from shared secret + salt + count
            val kdk = TwampCrypto.pbkdf2(secret, serverSalt, serverCount)
            // Decrypt token: [challenge | aesKey | hmacKey]
            val tokenPlain = TwampCrypto.decryptAesCbc(kdk, response.token, ByteArray(16), updateIv = false)
            val recvChallenge = tokenPlain.copyOf(16)
            if (!recvChallenge.contentEquals(serverChallenge)) {
                throw SecurityException("Challenge mismatch — shared secret wrong?")
            }
            twampMode.aesKey  = tokenPlain.copyOfRange(16, 32)
            twampMode.hmacKey = tokenPlain.copyOfRange(32, 48)
            twampMode.receiveIv = response.clientIv.copyOf()
            twampMode.sendIv    = response.clientIv.copyOf()
        }

        sendServerStart()
        state = State.AWAITING_COMMAND
    }

    private fun sendServerStart() {
        val start = ResponderPacketUtil.genServerStart(twampMode.mode)
        twampMode.serverStartMsg = start  // stash for AcceptTwSession HMAC chaining
        if (twampMode.isControlEncrypted()) {
            twampMode.sendIv = start.serverIv.copyOf()
        }
        val bb = ByteBuffer.allocate(/* ServerStart */ 48)
        start.writeTo(bb, twampMode)
        writeRaw(bb.array())
    }

    // ── State: AWAITING_COMMAND ──────────────────────────────────────────────

    private fun readCommand(buf: ByteBuffer) {
        if (!buf.hasRemaining()) return
        when (val cmd = buf.get()) {
            TwampCommand.REQUEST_TW_SESSION -> handleRequestTwSession(buf)
            TwampCommand.START_SESSIONS     -> handleStartSessions(buf)
            TwampCommand.STOP_SESSIONS      -> handleStopSessions(buf)
            TwampCommand.START_N_SESSION    -> handleStartNSession(buf)
            TwampCommand.STOP_N_SESSION     -> handleStopNSession(buf)
            else -> log.warn("Unknown command byte 0x${cmd.toInt().and(0xFF).toString(16)}")
        }
    }

    private fun handleRequestTwSession(buf: ByteBuffer) {
        val req = RequestTwSession.readFrom(buf, twampMode)
        val sid = ResponderPacketUtil.genSessionId(localIp)
        val paddingLen = req.paddingLength

        val reflector = TwampSessionReflector(
            adapter      = adapter,
            localIp      = localIp,
            paddingLength = paddingLen,
            mode         = twampMode.getTestSessionMode(sid),
            timeoutMs    = TwampConstants.DEFAULT_REF_WAIT_SEC * 1000L,
            scheduler    = scheduler
        )
        testSessions[sid] = ReflectorSession(reflector, Thread(reflector, "twamp-reflector-${sid}"))

        val accept = ResponderPacketUtil.genAcceptSession(sid, port = reflector.boundPort.toShort(), accept = 0)
        val bb = ByteBuffer.allocate(/* AcceptTwSession */ 48)
        accept.writeTo(bb, twampMode)
        writeRaw(bb.array())
    }

    private fun handleStartSessions(buf: ByteBuffer) {
        StartSessions.readFrom(buf, twampMode)
        testSessions.values.forEach { it.thread.also { t -> t.isDaemon = true; t.start() } }
        val ack = ResponderPacketUtil.genStartAck()
        val bb = ByteBuffer.allocate(/* StartSessionsAck */ 32)
        ack.writeTo(bb, twampMode)
        writeRaw(bb.array())
    }

    private fun handleStopSessions(buf: ByteBuffer) {
        // FIX-2: read but ignore sessionCount field
        StopSessions.readFrom(buf, twampMode)
        testSessions.values.forEach { it.reflector.stop(it.thread) }
        testSessions.clear()
        close()
    }

    private fun handleStartNSession(buf: ByteBuffer) {
        val req = StartNSession.readFrom(buf, twampMode)
        req.sids.forEach { sid ->
            testSessions[sid]?.thread?.also { it.isDaemon = true; it.start() }
        }
        val ack = ResponderPacketUtil.genStartNAck(req.sids.toList())
        val sidList = req.sids.toList()
        val ackSz = 32 + sidList.size * 16  // header + SID list
        val bb = ByteBuffer.allocate(ackSz)
        ack.writeTo(bb, twampMode)
        writeRaw(bb.array().copyOf(bb.position()))
    }

    private fun handleStopNSession(buf: ByteBuffer) {
        val req = StopNSession.readFrom(buf, twampMode)
        req.sids.forEach { sid ->
            testSessions.remove(sid)?.also { s -> s.reflector.stop(s.thread) }
        }
        val ack = ResponderPacketUtil.genStopNAck(req.sids.toList())
        val sidList = req.sids.toList()
        val ackSz = 32 + sidList.size * 16
        val bb = ByteBuffer.allocate(ackSz)
        ack.writeTo(bb, twampMode)
        writeRaw(bb.array().copyOf(bb.position()))
    }

    // ── Write / close ────────────────────────────────────────────────────────

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
            log.error("Write failed: ${e.message}")
            close()
        }
    }

    fun close() {
        if (state == State.CLOSED) return
        state = State.CLOSED
        serverWaitTask?.cancel(false)
        testSessions.values.forEach { it.reflector.stop(it.thread) }
        testSessions.clear()
        try { key.channel().close() } catch (_: Exception) {}
        key.cancel()
    }

    val isClosed: Boolean get() = state == State.CLOSED

    private data class ReflectorSession(
        val reflector: TwampSessionReflector,
        val thread: Thread
    )
}
