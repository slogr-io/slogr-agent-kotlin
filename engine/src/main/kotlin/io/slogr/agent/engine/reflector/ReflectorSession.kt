package io.slogr.agent.engine.reflector

import io.slogr.agent.engine.twamp.SessionId
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-session state held by [ReflectorThreadPool] for the lifetime of a
 * TWAMP test session.
 *
 * Created by the TWAMP control plane when it accepts a REQUEST-TW-SESSION
 * and removed when the session is stopped or the REFWAIT timer fires.
 */
class ReflectorSession(
    val sessionId: SessionId,
    val senderAddress: InetSocketAddress,
    val createdAt: Instant = Clock.System.now()
) {
    /** Total packets reflected in this session (incremented by the pool worker). */
    val packetCount: AtomicLong = AtomicLong(0)
}
