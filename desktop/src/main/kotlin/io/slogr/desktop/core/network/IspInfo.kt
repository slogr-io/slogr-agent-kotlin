package io.slogr.desktop.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class IspInfo(
    @SerialName("isp_name") val ispName: String,
    val asn: Int,
    @SerialName("public_ip") val publicIp: String,
) {
    val displayText: String get() = "$ispName (AS$asn)"
}
