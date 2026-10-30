package com.kodraliu.localrock.shared.vacuum.camera

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*

/** Explicit experimental requests only. An acknowledgement is not a setting readback. */
class CameraRemoteVoice internal constructor(
    private val rpc: CameraRpc,
    private val scope: CoroutineScope,
    private val currentClientId: () -> String?,
    private val streaming: () -> Boolean,
    private val commandTimeoutMs: Long = 5_000,
) {
    private val _acceptedEnabled = MutableStateFlow<Boolean?>(null)
    val acceptedEnabled: StateFlow<Boolean?> = _acceptedEnabled
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private var job: Job? = null

    fun setEnabled(enabled: Boolean) {
        if (job != null || !streaming()) return
        val id = currentClientId() ?: return
        _busy.value = true
        _error.value = null
        val next = scope.launch(start = CoroutineStart.LAZY) {
            var issued = false
            try {
                withTimeout(commandTimeoutMs) { requireOwner(id) }
                currentCoroutineContext().ensureActive()
                check(streaming() && currentClientId() == id)
                // Observe an already-issued write before releasing the preview. Never replay it.
                withContext(NonCancellable) {
                    withTimeout(commandTimeoutMs) {
                        issued = true
                        val response = rpc.call("enable_homesec_voice", buildJsonObject { put("enable", enabled) })
                        if (response.error != null) {
                            val code = (response.error["code"] as? JsonPrimitive)?.intOrNull
                            throw RequestFailure("The robot refused the voice setting" +
                                (code?.let { " (code $it)" } ?: "") + ". This firmware may not support it.")
                        }
                        val result = response.result.let { if (it is JsonArray) it.singleOrNull() else it }
                        if (result != JsonPrimitive("ok"))
                            throw RequestFailure("The voice setting was not confirmed. Its current value is unknown.")
                    }
                }
                currentCoroutineContext().ensureActive()
                check(streaming() && currentClientId() == id)
                _acceptedEnabled.value = enabled
            } catch (_: TimeoutCancellationException) {
                if (issued) _acceptedEnabled.value = null
                _error.value = if (issued)
                    "The voice request timed out. The robot may have changed the setting; its current value is unknown."
                else "Camera ownership could not be checked in time. No voice request was sent."
            } catch (e: CancellationException) {
                if (issued) _acceptedEnabled.value = null
                throw e
            } catch (e: RequestFailure) {
                if (issued) _acceptedEnabled.value = null
                _error.value = e.message
            } catch (_: Exception) {
                if (issued) _acceptedEnabled.value = null
                _error.value = if (issued)
                    "The voice setting was not confirmed. Its current value is unknown."
                else "Camera ownership could not be confirmed. No voice request was sent."
            } finally {
                job = null
                _busy.value = false
            }
        }
        job = next
        next.invokeOnCompletion { if (job === next) { job = null; _busy.value = false } }
        next.start()
    }

    internal fun stop() { job?.cancel() }

    internal suspend fun release() {
        val pending = job
        stop()
        pending?.join()
        _acceptedEnabled.value = null
        _error.value = null
    }

    private suspend fun requireOwner(id: String) {
        val response = rpc.call("get_homesec_connect_status", JsonArray(emptyList()))
        check(response.error == null)
        val result = response.result.let { if (it is JsonArray) it.singleOrNull() else it }
        val owner = ((result as? JsonObject)?.get("client_id") as? JsonPrimitive)?.contentOrNull
        check(owner == id)
    }

    private class RequestFailure(message: String) : RuntimeException(message)
}
