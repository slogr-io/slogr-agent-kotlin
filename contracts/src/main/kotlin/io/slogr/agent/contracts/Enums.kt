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
