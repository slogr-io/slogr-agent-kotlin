package io.slogr.agent.engine.twamp.responder

import io.slogr.agent.engine.reflector.ReflectorSession
import io.slogr.agent.engine.reflector.ReflectorThreadPool
import io.slogr.agent.engine.twamp.SessionId
import io.slogr.agent.engine.twamp.TwampMode
import io.slogr.agent.engine.twamp.protocol.ReflectorEncryptUPacket
import io.slogr.agent.engine.twamp.protocol.ReflectorUPacket
import io.slogr.agent.engine.twamp.protocol.SenderEncryptUPacket
import io.slogr.agent.engine.twamp.protocol.SenderUPacket
import io.slogr.agent.engine.twamp.util.PacketPadding
import io.slogr.agent.engine.twamp.util.TwampTimeUtil
import io.slogr.agent.native.NativeProbeAdapter
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Receives TWAMP test packets from a sender and immediately reflects them back.
 *
 * **R2 threading model** — pool-dispatch:
 * - Submitted to [ReflectorThreadPool.submit] instead of a dedicated [Thread].
 * - A pool-size of `nCPU × 2` handles many concurrent sessions because TWAMP
 *   test bursts are short-lived and the pool threads time-share across sessions.
 * - T2 is read from [RecvResult.kernelTimestampNtp][io.slogr.agent.native.RecvResult.kernelTimestampNtp]
 *   when available (SO_TIMESTAMPING via JNI). Falls back to [TwampTimeUtil.currentNtpTimestamp]
 *   when the JNI adapter does not provide a kernel timestamp (e.g. [JavaUdpTransport]).
 * - The per-session UDP socket is pre-bound in [init] so the port is known
 *   before the task starts, allowing [TwampResponderSession] to include it in
 *   AcceptTwSession.
 * - [PacketBufferPool] provides pre-allocated receive buffers to reduce GC pressure.
 *
 * @param adapter        Platform UDP socket abstraction.
 * @param localIp        Local IP to bind the reflector socket.
 * @param paddingLength  Negotiated padding length (bytes).
 * @param mode           TWAMP mode for this test session.
 * @param sessionId      Session identifier (used for pool registration).
 * @param senderIp       Remote sender IP (used for pool registration).
 * @param senderPort     Remote sender port (used for pool registration).
 * @param timeoutMs      Inactivity timeout in milliseconds.
 * @param threadPool     Shared [ReflectorThreadPool] — provides scheduler, buffer pool, and session tracking.
 */
class TwampSessionReflector(
    private val adapter: NativeProbeAdapter,
    private val localIp: InetAddress,
    private val paddingLength: Int,
    private val mode: TwampMode,
    private val sessionId: SessionId,
    private val senderIp: InetAddress,
    private val senderPort: Int,
    private val timeoutMs: Long,
    private val threadPool: ReflectorThreadPool
) : Runnable {

    private val log = LoggerFactory.getLogger(TwampSessionReflector::class.java)

    private var fd: Int = -1
    @Volatile private var isAlive = true

    private var seqNo = 0
    private val inactivityCount = AtomicInteger(0)
    private val inactivityIntervalMs = (timeoutMs / 10).coerceAtLeast(100L)
    private var inactivityTask: Future<*>? = null

    // ── Receive buffer (borrowed from pool if available) ──────────────────────

    private val recvBuf: ByteBuffer
    private val recvBytes: ByteArray

    /**
     * UDP port the reflector is bound to.
     *
     * Pre-bound in [init] so the port is known before the task is submitted to
     * the thread pool, allowing [TwampResponderSession] to include it in the
     * AcceptTwSession message.
     */
    val boundPort: Int

    init {
        fd = adapter.createSocket(localIp, 0)
        boundPort = if (fd >= 0) adapter.getLocalPort(fd) else 0
        if (fd >= 0) {
            adapter.setTtlAndCapture(fd, 64)
            adapter.enableTimestamping(fd)
            adapter.setTimeout(fd, inactivityIntervalMs.toInt())
        }

        recvBuf   = threadPool.bufferPool.borrow()
        recvBytes = recvBuf.array()
    }

    override fun run() {
        if (fd < 0) {
            log.error("Failed to create reflector UDP socket — fd=$fd")
            return
        }
        threadPool.registerSession(ReflectorSession(sessionId, InetSocketAddress(senderIp, senderPort)))
        try {
            startInactivityTimer()
            reflectLoop()
        } finally {
            threadPool.unregisterSession(sessionId)
            threadPool.bufferPool.returnBuffer(recvBuf)
            closeSocket()
        }
    }

    private fun startInactivityTimer() {
        inactivityTask = threadPool.scheduler.scheduleAtFixedRate({
            if (inactivityCount.incrementAndGet() >= 10) {
                log.debug("Reflector inactivity timeout — stopping")
                isAlive = false
            }
        }, inactivityIntervalMs, inactivityIntervalMs, TimeUnit.MILLISECONDS)
    }

    private fun reflectLoop() {
        while (isAlive) {
            val recv = adapter.recvPacket(fd, recvBytes)
            if (recv.isTimeout) continue
            inactivityCount.set(0)

            // T2: prefer kernel-captured SO_TIMESTAMPING; fall back to userspace clock.
            val receiveTimeNtp = if (recv.kernelTimestampNtp != 0L)
                recv.kernelTimestampNtp
            else
                TwampTimeUtil.currentNtpTimestamp()

            val recvSenderIp = recv.srcIp ?: continue

            try {
                reflect(recvBytes, recv.bytesRead, recvSenderIp, recv.srcPort,
                        recv.ttl.toByte(), receiveTimeNtp)
            } catch (e: Exception) {
                log.debug("Failed to parse/reflect sender packet: ${e.message}")
            }
        }
    }

    private fun reflect(
        data: ByteArray,
        len: Int,
        recvSenderIp: InetAddress,
        recvSenderPort: Int,
        senderTtl: Byte,
        receiveTimeNtp: Long
    ) {
        val reflectorSeq = seqNo++
        val replyData: ByteArray

        if (mode.isTestEncrypted()) {
            val senderPkt = SenderEncryptUPacket.readFrom(
                ByteBuffer.wrap(data, 0, len), mode, paddingLength
            )
            val reflPkt = ResponderPacketUtil.genReflectorEncryptPacket(
                senderPacket   = senderPkt,
                receiveTimeNtp = receiveTimeNtp,
                senderTtl      = senderTtl,
                reflectorSeq   = reflectorSeq,
                padding        = if (paddingLength > 0) PacketPadding.empty(paddingLength) else PacketPadding.empty(0)
            )
            val buf = ByteBuffer.allocate(ReflectorEncryptUPacket.BASE_SIZE + paddingLength)
            reflPkt.writeTo(buf, mode)
            replyData = buf.array()
        } else {
            val senderPkt = SenderUPacket.readFrom(ByteBuffer.wrap(data, 0, len), paddingLength)
            val reflPkt = ResponderPacketUtil.genReflectorPacket(
                senderPacket   = senderPkt,
                receiveTimeNtp = receiveTimeNtp,
                senderTtl      = senderTtl,
                reflectorSeq   = reflectorSeq,
                padding        = if (paddingLength > 0) PacketPadding.empty(paddingLength) else PacketPadding.empty(0)
            )
            val buf = ByteBuffer.allocate(ReflectorUPacket.BASE_SIZE + paddingLength)
            reflPkt.writeTo(buf)
            replyData = buf.array()
        }

        adapter.sendPacket(fd, recvSenderIp, recvSenderPort, replyData)
    }

    /**
     * Signal the reflector to stop and close the socket.
     *
     * Closing the socket unblocks any in-progress [NativeProbeAdapter.recvPacket] call
     * immediately, regardless of which pool thread is currently executing the loop.
     */
    fun stop() {
        isAlive = false
        closeSocket()
    }

    @Synchronized
    private fun closeSocket() {
        if (fd < 0) return
        inactivityTask?.cancel(false)
        try { adapter.closeSocket(fd) } catch (_: Exception) {}
        fd = -1
    }
}
