package com.kodraliu.localrock.shared.vacuum.camera

import com.kodraliu.localrock.shared.protocol.V1Response
import com.kodraliu.localrock.shared.webrtc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalEncodingApi::class)
class CameraLiveSessionTest {
    private class FakePeer : RtcPeer {
        override val localCandidates = MutableSharedFlow<RtcIceCandidate>(replay = 4)
        override val connectionState = MutableStateFlow(RtcConnectionState.NEW)
        var closed = false
        var receivedVideo = true
        var rejectAnswer = false
        override suspend fun createOffer(): String {
            localCandidates.emit(RtcIceCandidate("0", 0, "candidate:test"))
            return "test-offer"
        }
        override suspend fun setRemoteAnswer(sdp: String) {
            if (rejectAnswer) error("private-native-sdp")
            connectionState.value = RtcConnectionState.CONNECTED
        }
        override suspend fun addRemoteCandidate(candidate: RtcIceCandidate) = Unit
        override suspend fun diagnostics() = selectedPairDiagnostics("host", "srflx", "udp", receivedVideo)
        override fun close() { closed = true; connectionState.value = RtcConnectionState.CLOSED }
    }

    private class FakeRpc : CameraRpc {
        val calls = mutableListOf<String>()
        val stoppedIds = mutableListOf<String>()
        var pinResult: JsonElement? = JsonPrimitive(true)
        var owner: String? = "none"
        var startDelay: Long = 0
        var waitForAnswer = false
        var malformedAnswer = false
        var failMethod: String? = null
        var failCode = -10005
        var cleanupUnavailable = false
        override suspend fun call(method: String, params: JsonElement): V1Response {
            calls += method
            if (method == failMethod) return V1Response(null, null, buildJsonObject {
                put("code", failCode); put("message", "private-robot-payload")
            })
            val result = when (method) {
                "check_homesec_password" -> pinResult
                "get_homesec_connect_status" -> {
                    if (cleanupUnavailable) error("private-network-error")
                    owner?.let { buildJsonObject { put("client_id", it) } } ?: JsonObject(emptyMap())
                }
                "start_camera_preview" -> {
                    delay(startDelay)
                    owner = params.jsonObject["client_id"]!!.jsonPrimitive.content
                    JsonArray(listOf(JsonPrimitive("ok")))
                }
                "stop_camera_preview" -> {
                    stoppedIds += params.jsonObject["client_id"]!!.jsonPrimitive.content
                    owner = "none"
                    JsonArray(listOf(JsonPrimitive("ok")))
                }
                "get_turn_server" -> buildJsonObject {
                    put("url", "turn:example.test:3478"); put("user", "test"); put("pwd", "test")
                }
                "get_device_sdp" -> buildJsonObject {
                    put("dev_sdp", when {
                        waitForAnswer -> "retry"
                        malformedAnswer -> "invalid-base64"
                        else -> Base64.encode("{\"type\":\"answer\",\"sdp\":\"test-answer\"}".encodeToByteArray())
                    })
                }
                "get_device_ice" -> buildJsonObject { put("dev_ice", JsonArray(emptyList())) }
                else -> JsonArray(listOf(JsonPrimitive("ok")))
            }
            return V1Response(null, result, null)
        }
    }

    private fun TestScope.live(rpc: FakeRpc, peer: FakePeer = FakePeer()): CameraLiveSession {
        var id = 0
        return CameraLiveSession(rpc, { peer },
            CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job])),
            clientId = { "test-client-${++id}" }, connectTimeoutMs = 5_000, cleanupTimeoutMs = 500)
    }

    @Test fun onlyExplicitSuccessfulPinRepliesPass() {
        for (value in listOf("true", "1", "[true]", "[1]")) assertTrue(cameraPinAccepted(Json.parseToJsonElement(value)))
        for (value in listOf("false", "0", "[false]", "[0]", "null", "{}", "[]", "[1,1]", "\"true\"", "\"1\"")) {
            assertFalse(cameraPinAccepted(Json.parseToJsonElement(value)), value)
        }
        assertFalse(cameraPinAccepted(null))
    }

    @Test fun invalidPinNeverStartsOrStopsPreview() = runTest(timeout = 10.seconds) {
        for (result in listOf(JsonPrimitive(false), JsonNull, JsonObject(emptyMap()))) {
            val rpc = FakeRpc().apply { pinResult = result }
            val live = live(rpc)
            live.start("1234"); runCurrent()
            assertIs<CameraLiveSession.State.Failed>(live.state.value)
            assertEquals(listOf("check_homesec_password"), rpc.calls)
            live.dispose(); runCurrent()
        }
    }

    @Test fun busyCameraIsNotTakenOverOrStoppedOnDispose() = runTest(timeout = 10.seconds) {
        val rpc = FakeRpc().apply { owner = "oem-client" }
        val live = live(rpc)
        live.start("1234"); runCurrent()
        assertContains(assertIs<CameraLiveSession.State.Failed>(live.state.value).message, "Camera in use")
        live.dispose(); runCurrent()
        assertFalse("start_camera_preview" in rpc.calls)
        assertTrue(rpc.stoppedIds.isEmpty())
        assertEquals("oem-client", rpc.owner)
    }

    @Test fun unknownOwnershipFailsClosed() = runTest(timeout = 10.seconds) {
        val rpc = FakeRpc().apply { owner = null }
        val live = live(rpc)
        live.start("1234"); runCurrent()
        assertIs<CameraLiveSession.State.Failed>(live.state.value)
        assertFalse("start_camera_preview" in rpc.calls)
        live.dispose()
    }

    @Test fun streamingUsesTurnAndStopsOnlyOwnedPreview() = runTest(timeout = 10.seconds) {
        val rpc = FakeRpc(); val peer = FakePeer(); val live = live(rpc, peer)
        live.start("1234"); runCurrent()
        assertIs<CameraLiveSession.State.Streaming>(live.state.value)
        assertEquals(RtcMediaPath.DIRECT, live.diagnostics.value.path)
        assertTrue("get_turn_server" in rpc.calls)
        assertTrue(rpc.calls.indexOf("send_sdp_to_robot") < rpc.calls.indexOf("send_ice_to_robot"))
        live.stop(); runCurrent()
        assertTrue(peer.closed)
        assertEquals(listOf("test-client-1"), rpc.stoppedIds)
        assertIs<CameraLiveSession.State.Idle>(live.state.value)
        assertNull(live.cleanupWarning.value)
        live.dispose()
    }

    @Test fun cleanupDoesNotStopNewOwner() = runTest(timeout = 10.seconds) {
        val rpc = FakeRpc(); val live = live(rpc)
        live.start("1234"); runCurrent(); rpc.owner = "oem-new-client"
        live.dispose(); runCurrent()
        assertTrue(rpc.stoppedIds.isEmpty())
        assertEquals("oem-new-client", rpc.owner)
    }

    @Test fun cancellationDuringStartWaitsThenReleasesAndBlocksRetry() = runTest(timeout = 10.seconds) {
        val rpc = FakeRpc().apply { startDelay = 2_000 }; val live = live(rpc)
        live.start("1234"); runCurrent()
        live.stop(); live.start("1234"); runCurrent()
        assertIs<CameraLiveSession.State.Stopping>(live.state.value)
        advanceTimeBy(2_001); runCurrent()
        assertEquals(1, rpc.calls.count { it == "start_camera_preview" })
        assertEquals(listOf("test-client-1"), rpc.stoppedIds)
        assertFalse("get_turn_server" in rpc.calls)
        assertIs<CameraLiveSession.State.Idle>(live.state.value)
        live.dispose()
    }

    @Test fun connectionTimeoutReleasesPreview() = runTest(timeout = 10.seconds) {
        val rpc = FakeRpc().apply { waitForAnswer = true }; val peer = FakePeer(); val live = live(rpc, peer)
        live.start("1234"); runCurrent(); advanceTimeBy(5_001); runCurrent()
        assertContains(assertIs<CameraLiveSession.State.Failed>(live.state.value).message, "timed out")
        assertTrue(peer.closed)
        assertEquals(listOf("test-client-1"), rpc.stoppedIds)
        live.dispose()
    }

    @Test fun handshakeWithoutFramesDoesNotBecomeLive() = runTest(timeout = 10.seconds) {
        val rpc = FakeRpc(); val peer = FakePeer().apply { receivedVideo = false }; val live = live(rpc, peer)
        live.start("1234"); runCurrent()
        assertIs<CameraLiveSession.State.Connecting>(live.state.value)
        advanceTimeBy(5_001); runCurrent()
        assertIs<CameraLiveSession.State.Failed>(live.state.value)
        assertEquals(listOf("test-client-1"), rpc.stoppedIds)
        live.dispose()
    }

    @Test fun signalingRefusalReleasesAndDoesNotExposePayload() = runTest(timeout = 10.seconds) {
        for (method in listOf("get_turn_server", "send_sdp_to_robot", "send_ice_to_robot", "get_device_ice")) {
            val rpc = FakeRpc().apply { failMethod = method }; val live = live(rpc)
            live.start("1234"); runCurrent()
            val failed = assertIs<CameraLiveSession.State.Failed>(live.state.value)
            assertFalse(failed.message.contains("private-robot-payload"))
            assertContains(failed.message, method)
            assertEquals(listOf("test-client-1"), rpc.stoppedIds)
            live.dispose()
        }
    }

    @Test fun nativeAnswerErrorIsSanitizedAndReleases() = runTest(timeout = 10.seconds) {
        val rpc = FakeRpc(); val peer = FakePeer().apply { rejectAnswer = true }; val live = live(rpc, peer)
        live.start("1234"); runCurrent()
        val failed = assertIs<CameraLiveSession.State.Failed>(live.state.value)
        assertFalse(failed.message.contains("private-native-sdp"))
        assertEquals(listOf("test-client-1"), rpc.stoppedIds)
        live.dispose()
    }

    @Test fun malformedSignalingIsReportedAndReleased() = runTest(timeout = 10.seconds) {
        val rpc = FakeRpc().apply { malformedAnswer = true }; val live = live(rpc)
        live.start("1234"); runCurrent()
        assertContains(assertIs<CameraLiveSession.State.Failed>(live.state.value).message, "invalid video answer")
        assertEquals(listOf("test-client-1"), rpc.stoppedIds)
        live.dispose()
    }

    @Test fun activationDenialNeverChangesSettingsOrPin() = runTest(timeout = 10.seconds) {
        val rpc = FakeRpc().apply { failMethod = "start_camera_preview"; failCode = -10012 }; val live = live(rpc)
        live.start("1234"); runCurrent()
        val text = assertIs<CameraLiveSession.State.Failed>(live.state.value).message
        assertFalse(text.contains("cloud"))
        assertFalse(rpc.calls.any { it.startsWith("set_") || it.startsWith("reset_") })
        assertTrue(rpc.stoppedIds.isEmpty())
        live.dispose()
    }

    @Test fun failedCleanupIsVisibleAndNeverStopsUnknownClient() = runTest(timeout = 10.seconds) {
        val rpc = FakeRpc(); val live = live(rpc)
        live.start("1234"); runCurrent(); rpc.cleanupUnavailable = true
        live.stop(); runCurrent()
        assertNotNull(live.cleanupWarning.value)
        assertTrue(rpc.stoppedIds.isEmpty())
        assertIs<CameraLiveSession.State.Idle>(live.state.value)
        live.dispose()
    }

    @Test fun retryUsesFreshClientIdAndDoesNotOverlapPreviousAttempt() = runTest(timeout = 10.seconds) {
        val rpc = FakeRpc(); val live = live(rpc)
        live.start("1234"); live.start("1234"); runCurrent()
        live.stop(); runCurrent()
        live.start("1234"); runCurrent(); live.stop(); runCurrent()
        assertEquals(listOf("test-client-1", "test-client-2"), rpc.stoppedIds)
        live.dispose()
    }

    @Test fun connectionLossClosesPeerAndReleases() = runTest(timeout = 10.seconds) {
        val rpc = FakeRpc(); val peer = FakePeer(); val live = live(rpc, peer)
        live.start("1234"); runCurrent(); peer.connectionState.value = RtcConnectionState.DISCONNECTED; runCurrent()
        assertIs<CameraLiveSession.State.Failed>(live.state.value)
        assertTrue(peer.closed)
        assertEquals(listOf("test-client-1"), rpc.stoppedIds)
        live.dispose()
    }

    @Test fun immediateCancellationBeforeDispatchCanBeRetried() = runTest(timeout = 10.seconds) {
        val rpc = FakeRpc(); val live = live(rpc)
        live.start("1234"); live.stop(); runCurrent()
        assertIs<CameraLiveSession.State.Idle>(live.state.value)
        assertTrue(rpc.calls.isEmpty())
        live.start("1234"); runCurrent()
        assertIs<CameraLiveSession.State.Streaming>(live.state.value)
        live.dispose(); runCurrent()
    }

    @Test fun onlyS7MaxVGetsExperimentalEntry() {
        assertTrue(experimentalCameraSupported("roborock.vacuum.a27"))
        for (model in listOf(null, "", "roborock.vacuum.a15", "roborock.vacuum.a70")) assertFalse(experimentalCameraSupported(model))
    }

    @Test fun selectedPairMetadataIsSanitizedAndDoesNotGuess() {
        assertEquals(RtcMediaPath.UNKNOWN, selectedPairDiagnostics(null, null, "udp", false).path)
        assertEquals(RtcMediaPath.UNKNOWN, selectedPairDiagnostics("host", "private-data", "private-data", true).path)
        assertEquals(RtcMediaPath.DIRECT, selectedPairDiagnostics("host", "srflx", "udp", true).path)
        assertEquals(RtcMediaPath.RELAY, selectedPairDiagnostics("relay", "host", "udp", true).path)
        assertEquals(RtcMediaPath.RELAY, selectedPairDiagnostics("host", "relay", "tcp", true).path)
        assertFalse(selectedPairDiagnostics("host", "private-data", "private-data", true).toString().contains("private-data"))
    }
}
