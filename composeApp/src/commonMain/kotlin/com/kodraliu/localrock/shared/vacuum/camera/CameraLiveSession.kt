package com.kodraliu.localrock.shared.vacuum.camera

import com.kodraliu.localrock.shared.protocol.V1Response
import com.kodraliu.localrock.shared.vacuum.VacuumSession
import com.kodraliu.localrock.shared.webrtc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.kotlincrypto.hash.md.MD5
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

/** Deliberately narrow while model/firmware compatibility is being verified. */
fun experimentalCameraSupported(model: String?): Boolean = model == "roborock.vacuum.a27"

internal fun interface CameraRpc {
    suspend fun call(method: String, params: JsonElement): V1Response
}

internal class CameraFailure(message: String) : RuntimeException(message)

/** UI methods run on the main thread. One attempt owns its peer and a unique preview client id. */
@OptIn(ExperimentalEncodingApi::class)
class CameraLiveSession internal constructor(
    private val rpc: CameraRpc,
    private val createPeer: (List<RtcIceServer>) -> RtcPeer,
    private val scope: CoroutineScope,
    private val clientId: () -> String = {
        Random.nextBytes(8).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    },
    private val connectTimeoutMs: Long = 60_000,
    private val cleanupTimeoutMs: Long = 12_000,
) {
    constructor(session: VacuumSession) : this(
        CameraRpc { method, params -> session.sendCommandRaw(method, params) },
        ::createRtcPeer, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    sealed interface State {
        data object Idle : State
        data class Connecting(val stage: String) : State
        data object Streaming : State
        data object Stopping : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private val _peer = MutableStateFlow<RtcPeer?>(null)
    val peer: StateFlow<RtcPeer?> = _peer
    private val _diagnostics = MutableStateFlow(RtcDiagnostics())
    val diagnostics: StateFlow<RtcDiagnostics> = _diagnostics
    private val _cleanupWarning = MutableStateFlow<String?>(null)
    val cleanupWarning: StateFlow<String?> = _cleanupWarning
    private var attemptJob: Job? = null
    private var disposed = false

    private class Attempt(val id: String) {
        var previewRequested = false
        var startAcknowledged = false
        var peer: RtcPeer? = null
        var stage = "Preparing"
    }

    fun start(pin: String) {
        if (disposed || attemptJob != null) return
        if (pin.isBlank()) {
            _state.value = State.Failed("Enter your existing camera PIN or pattern.")
            return
        }
        val attempt = Attempt(clientId())
        _diagnostics.value = RtcDiagnostics()
        _cleanupWarning.value = null
        _state.value = State.Connecting("Preparing…")
        // Assign before starting, including when a fake RPC completes synchronously in tests.
        attemptJob = scope.launch(start = CoroutineStart.LAZY) {
            var failure: String? = null
            try {
                connect(attempt, pin)
            } catch (e: TimeoutCancellationException) {
                failure = "${attempt.stage} timed out. Check the robot and local server, then retry."
            } catch (e: CancellationException) {
                throw e
            } catch (e: CameraFailure) {
                failure = e.message
            } catch (_: Exception) {
                // Native/protocol exceptions may contain SDP, ICE or credentials.
                failure = "${attempt.stage} failed. Check the robot and local server, then retry."
            } finally {
                _state.value = State.Stopping
                _peer.value = null
                try { attempt.peer?.close() } catch (_: Exception) { /* Continue preview cleanup. */ }
                withContext(NonCancellable) { releaseOwnedPreview(attempt) }
                _state.value = failure?.let { State.Failed(it) } ?: State.Idle
                attemptJob = null
                if (disposed) scope.cancel()
            }
        }
        val job = requireNotNull(attemptJob)
        job.invokeOnCompletion {
            // A cancellation before dispatch never enters the coroutine's finally block.
            if (attemptJob === job) {
                attemptJob = null
                if (_state.value is State.Stopping) _state.value = State.Idle
                if (disposed) scope.cancel()
            }
        }
        job.start()
    }

    fun stop() {
        if (attemptJob != null) {
            _state.value = State.Stopping
            attemptJob?.cancel()
        } else {
            _state.value = State.Idle
        }
    }

    fun dispose() {
        disposed = true
        stop()
        if (attemptJob == null) scope.cancel()
    }

    private suspend fun connect(attempt: Attempt, pin: String) = coroutineScope {
        fun stage(value: String) {
            attempt.stage = value
            _state.value = State.Connecting("$value…")
        }
        val signalingScope = this
        var localIce: Job? = null
        var remoteIce: Job? = null
        val pc = withTimeout(connectTimeoutMs) {
            stage("Checking PIN")
            val hash = md5Hex(pin)
            val pinResult = command("check_homesec_password", obj("password", hash)).result
            if (!cameraPinAccepted(pinResult)) {
                throw CameraFailure(if (cameraPinRejected(pinResult))
                    "The robot rejected this camera PIN or pattern. Enter the existing credential again."
                else "The robot returned an unrecognized PIN-check response. Camera access was not started.")
            }
            stage("Checking camera availability")
            if (owner(command("get_homesec_connect_status").result) != "none") {
                throw CameraFailure("Camera in use. Close live view in the other app, then retry.")
            }
            stage("Starting camera")
            // Await the start acknowledgement even if the screen closes, then clean up in finally.
            // A timed-out request still gets an ownership check; it is never blindly stopped.
            withContext(NonCancellable) {
                withTimeout(10_000) {
                    attempt.previewRequested = true
                    val response = try {
                        command("start_camera_preview", buildJsonObject {
                            put("client_id", attempt.id); put("quality", "HD"); put("password", hash)
                        })
                    } catch (e: CameraFailure) {
                        // An explicit robot refusal differs from a lost/late acknowledgement.
                        attempt.previewRequested = false
                        throw e
                    }
                    requireAck(response.result, "Camera start")
                    attempt.startAcknowledged = true
                }
            }
            ensureActive()
            if (owner(command("get_homesec_connect_status").result) != attempt.id) {
                throw CameraFailure("Camera ownership could not be confirmed. Close other viewers and retry.")
            }
            stage("Obtaining TURN configuration")
            val turn = objectResult(command("get_turn_server").result)
                ?: throw CameraFailure("The robot returned no TURN configuration. Check the server's camera support.")
            val url = (turn["url"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.startsWith("turn:") || it.startsWith("turns:") }
                ?: throw CameraFailure("The robot returned an unsupported TURN configuration.")
            val user = (turn["user"] as? JsonPrimitive)?.contentOrNull
            val password = (turn["pwd"] as? JsonPrimitive)?.contentOrNull
            if (user.isNullOrBlank() || password.isNullOrBlank()) {
                throw CameraFailure("The robot returned incomplete TURN configuration.")
            }
            stage("Creating video connection")
            val peer = createPeer(listOf(RtcIceServer(url, user, password)))
            attempt.peer = peer
            _peer.value = peer
            val offerSent = CompletableDeferred<Unit>()
            localIce = signalingScope.launch {
                offerSent.await()
                peer.localCandidates.collect { candidate ->
                    command("send_ice_to_robot", obj("app_ice", encode(buildJsonObject {
                        put("candidate", candidate.candidate)
                        put("sdpMLineIndex", candidate.sdpMLineIndex)
                        put("sdpMid", candidate.sdpMid ?: "0")
                    })))
                }
            }
            stage("Sending video offer")
            val offer = peer.createOffer()
            command("send_sdp_to_robot", obj("app_sdp", encode(buildJsonObject {
                put("type", "offer"); put("sdp", offer)
            })))
            offerSent.complete(Unit)
            stage("Waiting for robot answer")
            var answer: String? = null
            while (answer == null) {
                val raw = objectResult(command("get_device_sdp").result)?.get("dev_sdp") as? JsonPrimitive
                    ?: throw CameraFailure("The robot returned an invalid video answer.")
                if (raw.content == "retry") { delay(1_000); continue }
                val decoded = decodeObject(raw.content, "video answer")
                answer = (decoded["sdp"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                if (decoded["type"] != JsonPrimitive("answer") || answer == null) {
                    throw CameraFailure("The robot returned an invalid video answer.")
                }
            }
            stage("Applying robot answer")
            peer.setRemoteAnswer(answer)
            remoteIce = signalingScope.launch {
                val seen = mutableSetOf<String>()
                while (isActive) {
                    val entries = objectResult(command("get_device_ice").result)?.get("dev_ice")
                    when {
                        entries == JsonPrimitive("retry") -> Unit
                        entries is JsonArray -> entries.forEach { entry ->
                            val raw = (entry as? JsonPrimitive)?.contentOrNull
                                ?: throw CameraFailure("The robot returned invalid video candidates.")
                            if (seen.add(raw)) {
                                val obj = decodeObject(raw, "video candidate")
                                val candidate = (obj["candidate"] as? JsonPrimitive)?.contentOrNull
                                    ?: throw CameraFailure("The robot returned an invalid video candidate.")
                                peer.addRemoteCandidate(RtcIceCandidate(
                                    (obj["sdpMid"] as? JsonPrimitive)?.contentOrNull,
                                    (obj["sdpMLineIndex"] as? JsonPrimitive)?.intOrNull ?: 0,
                                    candidate,
                                ))
                            }
                        }
                        else -> throw CameraFailure("The robot returned invalid video candidates.")
                    }
                    delay(1_000)
                }
            }
            stage("Connecting video")
            peer.connectionState.first { connection ->
                when (connection) {
                    RtcConnectionState.FAILED, RtcConnectionState.CLOSED, RtcConnectionState.DISCONNECTED ->
                        throw CameraFailure("Video connection failed. Check connectivity and retry.")
                    else -> connection == RtcConnectionState.CONNECTED
                }
            }
            stage("Waiting for video frames")
            do {
                if (peer.connectionState.value != RtcConnectionState.CONNECTED) {
                    throw CameraFailure("Video connection lost before receiving frames.")
                }
                _diagnostics.value = withTimeoutOrNull(2_000) { peer.diagnostics() } ?: RtcDiagnostics()
                if (!_diagnostics.value.videoReceived) delay(1_000)
            } while (!_diagnostics.value.videoReceived)
            peer
        }
        _state.value = State.Streaming
        // Keep trickle ICE alive until the session ends; a first CONNECTED event need not be final.
        launch {
            pc.connectionState.first { it != RtcConnectionState.CONNECTED }
            throw CameraFailure("Video connection lost. Retry to start a new viewing session.")
        }
        try {
            while (isActive) {
                _diagnostics.value = withTimeoutOrNull(2_000) { pc.diagnostics() } ?: RtcDiagnostics()
                delay(2_000)
            }
        } finally {
            localIce?.cancel()
            remoteIce?.cancel()
        }
    }

    private suspend fun releaseOwnedPreview(attempt: Attempt) {
        if (!attempt.previewRequested) return
        val released = try {
            withTimeout(cleanupTimeoutMs) {
                // A different app may already own the camera. Never send stop for its client id.
                val currentOwner = owner(command("get_homesec_connect_status").result)
                if (currentOwner == attempt.id) {
                    requireAck(command("stop_camera_preview", obj("client_id", attempt.id)).result, "Camera stop")
                    val currentOwner = owner(command("get_homesec_connect_status").result)
                if (currentOwner == attempt.id) {
                        throw CameraFailure("Preview release not confirmed")
                    }
                } else if (!attempt.startAcknowledged && currentOwner == "none") {
                    // An unanswered start can still arrive late. Do not report a confirmed release.
                    throw CameraFailure("Preview start was not acknowledged")
                }
                true
            }
        } catch (_: Exception) { false }
        if (!released) _cleanupWarning.value =
            "The local video connection is closed, but robot preview release could not be confirmed. " +
                "Wait for the robot to release it before retrying."
    }

    private suspend fun command(method: String, params: JsonElement = JsonArray(emptyList())): V1Response {
        val response = rpc.call(method, params)
        response.error?.let { error ->
            val code = (error["code"] as? JsonPrimitive)?.intOrNull
            val hint = when (code) {
                -1007 -> "This robot reports its camera PIN feature is disabled. Check its existing remote-view settings."
                -10012 -> "This robot denied camera preview. Check its existing camera activation and permissions."
                -10014 -> "The robot could not obtain TURN configuration. Keep TURN enabled and check the server's camera support."
                else -> "$method was refused by the robot (code ${code ?: "unavailable"})."
            }
            throw CameraFailure(hint)
        }
        return response
    }

    private fun owner(result: JsonElement?): String =
        (objectResult(result)?.get("client_id") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw CameraFailure("Camera availability could not be verified. No preview was taken over.")

    private fun requireAck(result: JsonElement?, operation: String) {
        val value = if (result is JsonArray) result.singleOrNull() else result
        if (value != JsonPrimitive("ok")) throw CameraFailure("$operation was not acknowledged by the robot.")
    }

    private fun encode(value: JsonElement): String = Base64.encode(value.toString().encodeToByteArray())
    private fun decodeObject(value: String, what: String): JsonObject = try {
        Json.parseToJsonElement(Base64.decode(value).decodeToString()) as? JsonObject
            ?: throw CameraFailure("The robot returned an invalid $what.")
    } catch (_: Exception) { throw CameraFailure("The robot returned an invalid $what.") }
}

internal fun cameraPinAccepted(result: JsonElement?): Boolean {
    val value = (if (result is JsonArray) result.singleOrNull() else result) as? JsonPrimitive ?: return false
    return !value.isString && (value.booleanOrNull == true || value.intOrNull == 1)
}

internal fun cameraPinRejected(result: JsonElement?): Boolean {
    val value = (if (result is JsonArray) result.singleOrNull() else result) as? JsonPrimitive ?: return false
    return !value.isString && (value.booleanOrNull == false || value.intOrNull == 0)
}

private fun objectResult(result: JsonElement?): JsonObject? = when (result) {
    is JsonObject -> result
    is JsonArray -> result.singleOrNull() as? JsonObject
    else -> null
}

private fun obj(key: String, value: String): JsonObject = buildJsonObject { put(key, value) }
private fun md5Hex(input: String): String = MD5().digest(input.encodeToByteArray())
    .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
