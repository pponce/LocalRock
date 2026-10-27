package com.kodraliu.localrock.shared.webrtc

/** Only allowlisted metadata leaves the native stats adapters. No addresses or credentials. */
data class RtcDiagnostics(
    val path: RtcMediaPath = RtcMediaPath.UNKNOWN,
    val localCandidateType: String = "unknown",
    val remoteCandidateType: String = "unknown",
    val protocol: String = "unknown",
    val videoReceived: Boolean = false,
) {
    val summary: String get() = when (path) {
        RtcMediaPath.DIRECT -> "Direct · $localCandidateType / $remoteCandidateType · $protocol"
        RtcMediaPath.RELAY -> "Relay · $localCandidateType / $remoteCandidateType · $protocol"
        RtcMediaPath.UNKNOWN -> "Connection path not yet verified"
    }
}

enum class RtcMediaPath { UNKNOWN, DIRECT, RELAY }

/** Call with types from the transport's selected candidate pair, never a gathered candidate. */
fun selectedPairDiagnostics(
    localType: String?, remoteType: String?, protocol: String?, videoReceived: Boolean,
): RtcDiagnostics {
    val known = setOf("host", "srflx", "prflx", "relay")
    val local = localType?.takeIf { it in known } ?: "unknown"
    val remote = remoteType?.takeIf { it in known } ?: "unknown"
    val path = when {
        local == "relay" || remote == "relay" -> RtcMediaPath.RELAY
        local != "unknown" && remote != "unknown" -> RtcMediaPath.DIRECT
        else -> RtcMediaPath.UNKNOWN
    }
    return RtcDiagnostics(path, local, remote,
        protocol?.takeIf { it in setOf("udp", "tcp") } ?: "unknown", videoReceived)
}
