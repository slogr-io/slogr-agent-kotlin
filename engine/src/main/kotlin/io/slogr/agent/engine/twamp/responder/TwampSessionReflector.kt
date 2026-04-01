package io.slogr.agent.engine.twamp.responder

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
import java.nio.ByteBuffer
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Receives TWAMP test packets from a sender and immediately reflects them back.
 *
 * Threading model (mirrors Java reference):
 * - Runs in a dedicated [Thread] started by [TwampResponderSession].
 * - An inactivity timeout is enforced via [ScheduledExecutorService]: if no packet
 *   arrives within [timeoutMs], the reflector stops.
 * - [closeSocket] is synchronized to prevent double-close.
 *
 * @param adapter       Platform UDP socket abstraction.
 * @param localIp       Local IP to bind the reflector socket.
 * @param paddingLength Negotiated padding length (bytes).
 * @param mode          TWAMP mode for this test session.
 * @param timeoutMs     Inactivity timeout in milliseconds.
 * @param scheduler     Shared ScheduledExecutorService for the timeout task.
 */
class TwampSessionReflector(
    private val adapter: NativeProbeAdapter,
    private val localIp: InetAddress,
    private val paddingLength: Int,
    private val mode: TwampMode,
    private val timeoutMs: Long,
    private val scheduler: ScheduledExecutorService
) : Runnable {

    private val log = LoggerFactory.getLogger(TwampSessionReflector::class.java)

    private var fd: Int = -1
    @Volatile private var isAlive = true
    @Volatile private var inSocketTimeout = false

    private var seqNo = 0
    private val inactivityCount = AtomicInteger(0)
    private val inactivityIntervalMs = (timeoutMs / 10).coerceAtLeast(100L)
    private var inactivityTask: Future<*>? = null

    /**
     * UDP port the reflector is bound to.
     *
     * Pre-bound in [init] so the port is known before the thread starts, allowing
     * [TwampResponderSession] to include it in the AcceptTwSession message.
     */
    val boundPort: Int

    init {
        fd = adapter.createSocket(localIp, 0)
        boundPort = if (fd >= 0) adapter.getLocalPort(fd) else 0
        if (fd >= 0) {
            adapter.setTtlAndCapture(fd, 64)
            adapter.setTimeout(fd, inactivityIntervalMs.toInt())
        }
    }

    override fun run() {
        if (fd < 0) {
            log.error("Failed to create reflector UDP socket — fd=$fd")
            return
        }
        startInactivityTimer()
        reflectLoop()
        closeSocket()
    }

    private fun startInactivityTimer() {
        inactivityTask = scheduler.scheduleAtFixedRate({
            if (inactivityCount.incrementAndGet() >= 10) {
                log.debug("Reflector inactivity timeout — stopping")
                isAlive = false
            }
        }, inactivityIntervalMs, inactivityIntervalMs, TimeUnit.MILLISECONDS)
    }

    private fun reflectLoop() {
        val encBufSize = if (mode.isTestEncrypted())
            SenderEncryptUPacket.BASE_SIZE + paddingLength
        else
            SenderUPacket.BASE_SIZE + paddingLength
        val recvBuf = ByteArray(encBufSize)

        while (isAlive) {
            val recv = adapter.recvPacket(fd, recvBuf)
            if (recv.isTimeout) {
                inSocketTimeout = true
                continue
            }
            inSocketTimeout = false
            inactivityCount.set(0)

            val receiveTimeNtp = TwampTimeUtil.currentNtpTimestamp()
            val senderIp = recv.srcIp ?: continue

            try {
                reflect(recvBuf, recv.bytesRead, senderIp, recv.srcPort,
                        recv.ttl.toByte(), receiveTimeNtp)
            } catch (e: Exception) {
                log.debug("Failed to parse/reflect sender packet: ${e.message}")
            }
        }
    }

    private fun reflect(
        data: ByteArray,
        len: Int,
        senderIp: InetAddress,
        senderPort: Int,
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

        adapter.sendPacket(fd, senderIp, senderPort, replyData)
    }

    fun stop(workerThread: Thread) {
        isAlive = false
        if (inSocketTimeout) {
            workerThread.interrupt()
        }
    }

    @Synchronized
    private fun closeSocket() {
        if (fd < 0) return
        inactivityTask?.cancel(false)
        try { adapter.closeSocket(fd) } catch (_: Exception) {}
        fd = -1
    }
}
