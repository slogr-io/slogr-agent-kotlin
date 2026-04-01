package io.slogr.agent.contracts

import kotlinx.serialization.Serializable

@Serializable enum class SlaGrade { GREEN, YELLOW, RED }
@Serializable enum class Direction { UPLINK, DOWNLINK }
@Serializable enum class TimingMode { FIXED, POISSON }
@Serializable enum class TwampAuthMode { UNAUTHENTICATED, AUTHENTICATED, ENCRYPTED }
@Serializable enum class TracerouteMode { ICMP, UDP, TCP }
@Serializable enum class TargetDeviceType { SLOGR_AGENT, CISCO, JUNIPER, GENERIC_RFC5357 }
@Serializable enum class PublishStatus { OK, DEGRADED, FAILING }
@Serializable enum class ConnectionMethod { BOOTSTRAP_TOKEN, API_KEY }

/**
 * Probe mode classification — whether ICMP and/or TCP measurements succeeded (R2).
 * Used to distinguish "ICMP filtered (TCP healthy)" from true packet loss.
 */
@Serializable enum class ProbeMode {
    /** Both ICMP and TCP measurements succeeded — full data. */
    ICMP_AND_TCP,
    /** ICMP blocked, TCP succeeded — path is up, ICMP filtered by firewall. */
    TCP_ONLY,
    /** ICMP succeeded, TCP failed — unusual but possible (port blocked). */
    ICMP_ONLY,
    /** Neither ICMP nor TCP succeeded — target may be down. */
    BOTH_FAILED
}

/** Clock synchronization quality reported by the virtual clock estimator (R2). */
@Serializable enum class ClockSyncStatus {
    /** Both sender and reflector are NTP-synced — raw T2-T1 / T4-T3 used. */
    SYNCED,
    /** Clock offset estimated from packet stream — corrected delays used. */
    ESTIMATED,
    /** Clock skew too large to estimate reliably — forward/reverse set to RTT/2. */
    UNSYNCABLE
}
