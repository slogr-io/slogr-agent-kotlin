package io.slogr.agent.engine.twamp.controller

import io.slogr.agent.engine.twamp.FillMode
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
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sends TWAMP test packets to a reflector and accumulates per-packet statistics.
 *
 * Threading model (mirrors Java reference):
 * - Runs in a dedicated [Thread] started by [TwampControllerSession].
 * - Packet sends are scheduled via [ScheduledExecutorService] (millisecond-precision,
 *   NOT coroutine delays). FIXED_INTERVAL uses scheduleAtFixedRate; POISSON uses
 *   recursive schedule() calls.
 * - [closeSocket] is synchronized to prevent double-close races.
 *
 * @param adapter      Platform UDP socket abstraction.
 * @param reflectorIp  Target reflector address.
 * @param reflectorPort Target reflector UDP port.
 * @param localIp      Source IP to bind the sender socket (null = wildcard).
 * @param config       Measurement parameters (count, interval, padding, etc.).
 * @param onComplete   Callback invoked on the sender thread once the session closes.
 */
class TwampSessionSender(
    private val adapter: NativeProbeAdapter,
    private val reflectorIp: InetAddress,
    private val reflectorPort: Int,
    private val localIp: InetAddress,
    private val config: SenderConfig,
    private val scheduler: ScheduledExecutorService,
    private val onComplete: (SenderResult) -> Unit
) : Runnable {

    private val log = LoggerFactory.getLogger(TwampSessionSender::class.java)

    companion object {
        /** Number of DSCP-marked packets to send before checking for filtering. */
        const val DSCP_PROBE_COUNT = 5
    }

    // Set by TwampControllerSession after test session is accepted
    @Volatile private lateinit var sid: SessionId
    @Volatile private lateinit var testMode: TwampMode

    private var fd: Int = -1
    @Volatile private var isAlive = true

    // Sequence numbers
    @Volatile private var seqNo = 0
    private val recvCount = AtomicInteger(0)

    // DSCP filtering detection
    @Volatile private var dscpFiltered = false

    // Statistics accumulators (access only from receiver loop)
    private val metrics = mutableListOf<PacketRecord>()

    @Volatile private var senderTask: Future<*>? = null
    @Volatile private var stopTask: Future<*>? = null

    fun setSid(sid: SessionId, testMode: TwampMode) {
        this.sid = sid
        this.testMode = testMode
    }

    override fun run() {
        fd = adapter.createSocket(localIp, config.senderPort)
        if (fd < 0) {
            log.error("Failed to create sender UDP socket")
            onComplete(SenderResult(packets = emptyList(), error = "socket creation failed"))
            return
        }
        adapter.setTtlAndCapture(fd, 64)
        adapter.setTos(fd, (config.dscp shl 2).toShort())
        adapter.setTimeout(fd, config.recvTimeoutMs.toInt())
        adapter.connectSocket(fd, reflectorIp, reflectorPort)
        val localPort = adapter.getLocalPort(fd)
        log.info("Sender ready (fd=$fd, localPort=$localPort, target=$reflectorIp:$reflectorPort, packets=${config.count})")

        scheduleFirstSend()

        // Stop task: fires after all packets + wait window
        val totalMs = config.count * config.intervalMs + config.waitTimeMs
        stopTask = scheduler.schedule({ isAlive = false }, totalMs, TimeUnit.MILLISECONDS)

        // Receive loop
        receiveLoop()
        closeSocket()
    }

    private fun scheduleFirstSend() {
        if (config.timingMode == TimingMode.FIXED_INTERVAL) {
            senderTask = scheduler.scheduleAtFixedRate(
                ::sendPacket, 0L, config.intervalMs, TimeUnit.MILLISECONDS
            )
        } else {
            scheduleNextPoisson()
        }
    }

    private fun scheduleNextPoisson() {
        if (!isAlive) return
        val delayMs = TwampTimeUtil.poissonIntervalMs(
            config.intervalMs,
            config.poissonMaxIntervalMs
        )
        senderTask = scheduler.schedule({
            sendPacket()
            scheduleNextPoisson()
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun sendPacket() {
        val currentSeq = seqNo
        if (currentSeq >= config.count) {
            senderTask?.cancel(false)
            return
        }
        seqNo = currentSeq + 1

        // DSCP probe: after 5 marked packets with 0 responses, fall back to best-effort
        if (currentSeq == DSCP_PROBE_COUNT && recvCount.get() == 0 && config.dscp > 0) {
            adapter.setTos(fd, 0)
            dscpFiltered = true
            log.warn("DSCP {} dropped (0/{} responses) — falling back to best-effort",
                config.dscp, DSCP_PROBE_COUNT)
        }

        try {
            val data: ByteArray
            if (testMode.isTestEncrypted()) {
                val pkt = SenderEncryptUPacket().apply {
                    seqNumber = currentSeq
                    errorEstimate = 0
                    if (config.paddingLength > 0) {
                        padding = PacketPadding.forSender(testMode, config.paddingLength, config.fillMode)
                    }
                }
                val buf = ByteBuffer.allocate(SenderEncryptUPacket.BASE_SIZE + config.paddingLength)
                pkt.writeTo(buf, testMode)
                data = buf.array()
            } else {
                val pkt = SenderUPacket().apply {
                    seqNumber = currentSeq
                    errorEstimate = 0
                    if (config.paddingLength > 0) {
                        padding = PacketPadding.forSender(testMode, config.paddingLength, config.fillMode)
                    }
                }
                val buf = ByteBuffer.allocate(SenderUPacket.BASE_SIZE + config.paddingLength)
                pkt.writeTo(buf)
                data = buf.array()
            }
            val sent = adapter.sendPacket(fd, reflectorIp, reflectorPort, data)
            if (currentSeq < 3) log.info("Sent packet #$currentSeq to $reflectorIp:$reflectorPort ($sent bytes, fd=$fd)")
        } catch (e: Exception) {
            log.warn("sendPacket seq=$currentSeq failed: ${e.message}")
        }
    }

    private fun receiveLoop() {
        val recvBufSize = if (testMode.isTestEncrypted())
            ReflectorEncryptUPacket.BASE_SIZE + config.paddingLength
        else
            ReflectorUPacket.BASE_SIZE + config.paddingLength
        val recvBuf = ByteArray(recvBufSize)

        while (isAlive) {
            val result = adapter.recvPacket(fd, recvBuf)
            if (result.isTimeout) continue

            val rxNtp = TwampTimeUtil.currentNtpTimestamp()
            try {
                val bb = ByteBuffer.wrap(recvBuf, 0, result.bytesRead)
                val record = if (testMode.isTestEncrypted()) {
                    val pkt = ReflectorEncryptUPacket.readFrom(bb, testMode, config.paddingLength)
                    buildRecord(
                        senderSeq    = pkt.senderSeqNumber,
                        senderTxNtp  = pkt.senderTime,
                        reflectorRxNtp = pkt.receiverTime,
                        reflectorTxNtp = pkt.timestamp,
                        rxNtp        = rxNtp,
                        txTtl        = result.ttl.toInt(),
                        rxTtl        = result.ttl.toInt(),
                        rxTos        = result.tos
                    )
                } else {
                    val pkt = ReflectorUPacket.readFrom(bb, config.paddingLength)
                    buildRecord(
                        senderSeq    = pkt.senderSeqNumber,
                        senderTxNtp  = pkt.senderTime,
                        reflectorRxNtp = pkt.receiverTime,
                        reflectorTxNtp = pkt.timestamp,
                        rxNtp        = rxNtp,
                        txTtl        = result.ttl.toInt(),
                        rxTtl        = result.ttl.toInt(),
                        rxTos        = result.tos
                    )
                }
                metrics.add(record)
                recvCount.incrementAndGet()
            } catch (e: Exception) {
                log.debug("Failed to parse reflector packet: ${e.message}")
            }
        }
    }

    private fun buildRecord(
        senderSeq: Int,
        senderTxNtp: Long,
        reflectorRxNtp: Long,
        reflectorTxNtp: Long,
        rxNtp: Long,
        txTtl: Int,
        rxTtl: Int,
        rxTos: Short = 0
    ): PacketRecord {
        val fwdDelayMs = TwampTimeUtil.ntpDiffMs(reflectorRxNtp, senderTxNtp)
        val revDelayMs = TwampTimeUtil.ntpDiffMs(rxNtp, reflectorTxNtp)
        val procTimeNs = TwampTimeUtil.ntpDiffNs(reflectorTxNtp, reflectorRxNtp)
        return PacketRecord(
            seq              = senderSeq,
            txNtp            = senderTxNtp,
            rxNtp            = rxNtp,
            fwdDelayMs       = fwdDelayMs.toFloat(),
            revDelayMs       = revDelayMs.toFloat(),
            reflectorProcNs  = procTimeNs,
            txTtl            = txTtl,
            rxTtl            = rxTtl,
            rxTos            = rxTos
        )
    }

    @Synchronized
    private fun closeSocket() {
        if (fd < 0) return
        senderTask?.cancel(false)
        stopTask?.cancel(false)
        try { adapter.closeSocket(fd) } catch (_: Exception) {}
        fd = -1
        onComplete(buildResult())
    }

    private fun buildResult(): SenderResult {
        val sent = seqNo
        val recv = recvCount.get()
        return SenderResult(
            packets      = metrics.toList(),
            packetsSent  = sent,
            packetsRecv  = recv,
            dscpFiltered = dscpFiltered
        )
    }
}

/** Timing mode for test packet scheduling. */
enum class TimingMode { FIXED_INTERVAL, POISSON }

/** Configuration for a single TWAMP measurement session (sender side). */
data class SenderConfig(
    val count: Int,
    val intervalMs: Long,
    val waitTimeMs: Long,
    val paddingLength: Int,
    val dscp: Int,
    val senderPort: Int = 0,
    val recvTimeoutMs: Long = 2000L,
    val fillMode: FillMode = FillMode.ZERO,
    val timingMode: TimingMode = TimingMode.FIXED_INTERVAL,
    val poissonMaxIntervalMs: Long = 0L
)

/** Per-packet raw measurement record, populated by the receive loop. */
data class PacketRecord(
    val seq: Int,
    val txNtp: Long,
    val rxNtp: Long,
    val fwdDelayMs: Float,
    val revDelayMs: Float,
    val reflectorProcNs: Long,
    val txTtl: Int,
    val rxTtl: Int,
    val outOfOrder: Boolean = false,
    val rxTos: Short = 0
)

/** Result produced when a sender session closes. */
data class SenderResult(
    val packets: List<PacketRecord>,
    val packetsSent: Int = packets.size,
    val packetsRecv: Int = packets.size,
    val error: String? = null,
    val dscpFiltered: Boolean = false
)
