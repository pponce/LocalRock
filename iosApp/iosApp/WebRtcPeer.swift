import Foundation
import UIKit
import WebRTC
import ComposeApp

// iOS WebRTC bridge for LocalRock's camera live view. Implements the Kotlin `RtcNativePeer*`
// contracts (see `RtcPeer.ios.kt`) on top of Apple's WebRTC.framework. The shared Kotlin code
// (`CameraLiveSession`) drives signaling over MQTT; this class is only the peer-connection engine
// plus the render view.
//
// The target's synchronized source folder includes this file. WebRTC is linked via SPM;
// ContentView injects WebRtcNativeFactory. This viewer never creates a local capture track.

final class WebRtcNativeFactory: NSObject, RtcNativePeerFactory {

    private let factory: RTCPeerConnectionFactory

    override init() {
        // Native diagnostics may contain SDP/ICE; the app exposes allowlisted stats instead.
        RTCSetMinDebugLogLevel(.none)
        RTCInitializeSSL()
        let encoder = RTCDefaultVideoEncoderFactory()
        let decoder = RTCDefaultVideoDecoderFactory()
        self.factory = RTCPeerConnectionFactory(encoderFactory: encoder, decoderFactory: decoder)
        super.init()
    }

    func create(iceServers: [RtcNativeIceServer], listener: RtcNativePeerListener) -> RtcNativePeer? {
        return WebRtcPeer(factory: factory, iceServers: iceServers, listener: listener)
    }
}

final class WebRtcPeer: NSObject, RtcNativePeer, RTCPeerConnectionDelegate {

    private let listener: RtcNativePeerListener
    private let peerConnection: RTCPeerConnection
    private let videoView: RTCMTLVideoView
    private var remoteVideoTrack: RTCVideoTrack?
    // Access renderer state only on the main queue.
    private var rendererClosed = false

    init?(factory: RTCPeerConnectionFactory, iceServers: [RtcNativeIceServer], listener: RtcNativePeerListener) {
        self.listener = listener

        let config = RTCConfiguration()
        config.sdpSemantics = .unifiedPlan
        config.iceServers = iceServers.map { server in
            RTCIceServer(
                urlStrings: [server.url],
                username: server.username.isEmpty ? nil : server.username,
                credential: server.password.isEmpty ? nil : server.password
            )
        }

        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        guard let pc = factory.peerConnection(with: config, constraints: constraints, delegate: nil) else {
            return nil
        }
        self.peerConnection = pc

        // RTCMTLVideoView is a UIView and must be created/configured on the main thread.
        self.videoView = WebRtcPeer.onMain { RTCMTLVideoView(frame: .zero) }
        super.init()
        self.peerConnection.delegate = self
        WebRtcPeer.onMainVoid { self.videoView.videoContentMode = .scaleAspectFit }
    }

    // MARK: RtcNativePeer

    func createOffer(onSuccess: @escaping (String) -> Void, onError: @escaping (String) -> Void) {
        let recvVideo = RTCRtpTransceiverInit()
        recvVideo.direction = .recvOnly
        peerConnection.addTransceiver(of: .video, init: recvVideo)

        let recvAudio = RTCRtpTransceiverInit()
        recvAudio.direction = .recvOnly
        peerConnection.addTransceiver(of: .audio, init: recvAudio)

        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        peerConnection.offer(for: constraints) { [weak self] sdp, error in
            guard let self = self else { return }
            if error != nil { onError("Video offer creation failed"); return }
            guard let sdp = sdp else { onError("createOffer returned a nil description"); return }
            self.peerConnection.setLocalDescription(sdp) { error in
                if error != nil { onError("Video offer creation failed"); return }
                onSuccess(sdp.sdp)
            }
        }
    }

    func setRemoteAnswer(sdp: String, onSuccess: @escaping () -> Void, onError: @escaping () -> Void) {
        peerConnection.setRemoteDescription(RTCSessionDescription(type: .answer, sdp: sdp)) { error in
            if error != nil { onError() } else { onSuccess() }
        }
    }

    func addRemoteCandidate(sdpMid: String?, sdpMLineIndex: Int32, candidate: String,
                            onSuccess: @escaping () -> Void, onError: @escaping () -> Void) {
        peerConnection.add(RTCIceCandidate(sdp: candidate, sdpMLineIndex: sdpMLineIndex, sdpMid: sdpMid)) { error in
            if error != nil { onError() } else { onSuccess() }
        }
    }

    func diagnostics(onResult: @escaping (RtcDiagnostics) -> Void) {
        peerConnection.statistics { report in
            let stats = report.statistics
            // Use the selected pair on the video RTP transport. A successful/nominated or
            // gathered candidate is not proof it carries this stream.
            let video = stats.values.first {
                $0.type == "inbound-rtp" &&
                    (($0.values["kind"] as? String ?? $0.values["mediaType"] as? String) == "video")
            }
            let transportId = video?.values["transportId"] as? String
            let transport = transportId.flatMap { stats[$0] }
            let pairId = transport?.values["selectedCandidatePairId"] as? String
            let pair = pairId.flatMap { stats[$0] }
            let localId = pair?.values["localCandidateId"] as? String
            let remoteId = pair?.values["remoteCandidateId"] as? String
            let local = localId.flatMap { stats[$0] }
            let remote = remoteId.flatMap { stats[$0] }
            let frames = (video?.values["framesDecoded"] as? NSNumber)?.int64Value ?? 0
            onResult(RtcDiagnosticsKt.selectedPairDiagnostics(
                localType: local?.values["candidateType"] as? String,
                remoteType: remote?.values["candidateType"] as? String,
                protocol: local?.values["protocol"] as? String,
                videoReceived: frames > 0
            ))
        }
    }

    func close() {
        WebRtcPeer.onMainVoid {
            self.rendererClosed = true
            self.remoteVideoTrack?.remove(self.videoView)
            self.remoteVideoTrack = nil
        }
        peerConnection.close()
    }

    func renderView() -> UIView? {
        return videoView
    }

    // MARK: RTCPeerConnectionDelegate

    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        listener.onLocalCandidate(
            sdpMid: candidate.sdpMid,
            sdpMLineIndex: candidate.sdpMLineIndex,
            candidate: candidate.sdp
        )
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        listener.onConnectionState(state: WebRtcPeer.stateString(newState))
    }

    // Unified-plan track arrival: attach the remote video track to the render view.
    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd rtpReceiver: RTCRtpReceiver, streams: [RTCMediaStream]) {
        if let track = rtpReceiver.track as? RTCVideoTrack {
            WebRtcPeer.onMainVoid {
                guard !self.rendererClosed else { return }
                self.remoteVideoTrack?.remove(self.videoView)
                self.remoteVideoTrack = track
                track.add(self.videoView)
            }
        }
    }

    // Required-but-unused delegate methods.
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}

    // MARK: Helpers

    private static func stateString(_ state: RTCIceConnectionState) -> String {
        switch state {
        case .new: return "new"
        case .checking: return "connecting"
        case .connected, .completed: return "connected"
        case .disconnected: return "disconnected"
        case .failed: return "failed"
        case .closed: return "closed"
        default: return "new"
        }
    }

    private static func onMain<T>(_ block: () -> T) -> T {
        if Thread.isMainThread { return block() }
        return DispatchQueue.main.sync { block() }
    }

    private static func onMainVoid(_ block: @escaping () -> Void) {
        if Thread.isMainThread { block() } else { DispatchQueue.main.async { block() } }
    }
}
