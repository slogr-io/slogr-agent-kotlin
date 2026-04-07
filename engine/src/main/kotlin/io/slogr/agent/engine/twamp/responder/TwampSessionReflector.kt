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
    private val threadPool: ReflectorThreadPool,
    private val testPort: Int = 0
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
        val bindPort = if (testPort > 0) testPort else 0
        fd = adapter.createSocket(localIp, bindPort, reusePort = testPort > 0)
        boundPort = if (fd >= 0) adapter.getLocalPort(fd) else 0
        log.info("Reflector socket created (fd=$fd, bindPort=$bindPort, boundPort=$boundPort, testPort=$testPort)")
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
        log.info("Reflector started (sid=$sessionId, udpPort=$boundPort, sender=$senderIp:$senderPort)")
        threadPool.registerSession(ReflectorSession(sessionId, InetSocketAddress(senderIp, senderPort)))
        try {
            startInactivityTimer()
            reflectLoop()
        } finally {
            log.info("Reflector exiting (sid=$sessionId, isAlive=$isAlive, reflected=$seqNo packets)")
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

            // L2: Only reflect to the IP that established the TCP control session
            if (recvSenderIp.hostAddress != senderIp.hostAddress) {
                log.debug("Dropping UDP from ${recvSenderIp} — expected ${senderIp} (TCP source)")
                continue
            }

            // L7: Packet validation before parsing
            if (recv.bytesRead < MIN_SENDER_PACKET_SIZE) {
                log.debug("Dropping undersized packet: ${recv.bytesRead} bytes (min=$MIN_SENDER_PACKET_SIZE)")
                continue
            }
            if (recv.bytesRead > MAX_PACKET_SIZE) {
                log.debug("Dropping oversized packet: ${recv.bytesRead} bytes (max=$MAX_PACKET_SIZE)")
                continue
            }

            try {
                reflect(recvBytes, recv.bytesRead, recvSenderIp, recv.srcPort,
                        recv.ttl.toByte(), receiveTimeNtp)
                if (seqNo <= 3) log.info("Reflected packet #$seqNo from $recvSenderIp:${recv.srcPort}")
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

        // L1: Anti-amplification — log if response exceeds request (inherent in TWAMP
        // protocol: reflector base=41 vs sender base=14). Padding is already negotiated
        // to match. Do NOT truncate the base header — that corrupts the packet.
        if (replyData.size > len) {
            log.debug("Reflector response (${replyData.size}B) > request (${len}B) — protocol inherent, padding matched")
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

    companion object {
        /** Minimum valid TWAMP sender packet (SenderUPacket.BASE_SIZE = 14). */
        private const val MIN_SENDER_PACKET_SIZE = 14
        /** Maximum sane packet size (jumbo frame). */
        private const val MAX_PACKET_SIZE = 9000
    }
}
