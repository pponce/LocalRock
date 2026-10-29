package com.kodraliu.localrock.shared.webrtc

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class RtcIceServer(
    val url: String,
    val username: String = "",
    val password: String = "",
)

data class RtcIceCandidate(
    val sdpMid: String?,
    val sdpMLineIndex: Int,
    val candidate: String,
)

enum class RtcConnectionState { NEW, CONNECTING, CONNECTED, DISCONNECTED, FAILED, CLOSED }

/** Advertised capabilities only. No option creates a local microphone or camera capture track. */
data class RtcOfferOptions(val audioFirst: Boolean = false, val sendReceiveVideo: Boolean = false)

interface RtcPeer {
    val localCandidates: SharedFlow<RtcIceCandidate>
    val connectionState: StateFlow<RtcConnectionState>

    suspend fun createOffer(options: RtcOfferOptions = RtcOfferOptions()): String
    suspend fun setRemoteAnswer(sdp: String)
    suspend fun addRemoteCandidate(candidate: RtcIceCandidate)
    suspend fun diagnostics(): RtcDiagnostics
    fun close()
}

expect val liveViewSupported: Boolean

expect fun createRtcPeer(iceServers: List<RtcIceServer>): RtcPeer

