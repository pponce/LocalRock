package com.kodraliu.localrock.shared.vacuum.camera

import com.kodraliu.localrock.shared.vacuum.VacuumSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*

private class MotionRefused : RuntimeException()

/** One foreground video screen owns these short manual-drive leases. */
class CameraMotionController internal constructor(
    private val rpc: CameraRpc,
    private val ready: () -> Boolean,
    private val scope: CoroutineScope,
) {
    constructor(session: VacuumSession, ready: () -> Boolean) : this(
        CameraRpc { method, params -> session.sendCommandRaw(method, params, timeoutMs = 1_500) },
        ready, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    enum class Direction(val velocity: Double, val omega: Double) {
        FORWARD(.15, 0.0), BACKWARD(-.15, 0.0), LEFT(0.0, .6), RIGHT(0.0, -.6),
    }
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private var job: Job? = null
    private var holding = false
    private var haltJob: Job? = null
    private var navigationRequested = false
    private var disposed = false

    fun hold(direction: Direction) {
        if (disposed || job != null || haltJob != null) return
        holding = true
        launchOperation {
            var requested = false
            var sequence = 0
            try {
                requireReady()
                requireIdle()
                // A late start acknowledgement must be followed by rc_end even after finger release.
                withContext(NonCancellable) {
                    requested = true
                    try { ack("app_rc_start") } catch (e: MotionRefused) { requested = false; throw e }
                }
                currentCoroutineContext().ensureActive()
                // app_rc_start may acknowledge before the robot enters manual mode.
                withTimeout(1_500) {
                    while (true) {
                        requireReady()
                        val status = readStatus()
                        check(status["in_cleaning"]?.jsonPrimitive?.intOrNull == 0)
                        if (status["state"]?.jsonPrimitive?.intOrNull in setOf(4, 7)) break
                        delay(100)
                    }
                }
                while (currentCoroutineContext().isActive) {
                    requireReady()
                    ack("app_rc_move", movement(direction.velocity, direction.omega, ++sequence))
                    delay(200)
                }
            } finally {
                holding = false
                if (requested) withContext(NonCancellable) {
                    val zero = runCatching { ack("app_rc_move", movement(0.0, 0.0, ++sequence)) }.isSuccess
                    val ended = runCatching { ack("app_rc_end") }.isSuccess
                    if (!zero || !ended) _error.value = "Drive release could not be confirmed. Check the robot before continuing."
                }
            }
        }
    }

    /** Called on pointer-up, cancellation, mode changes and video disconnection. */
    fun release() { if (holding) job?.cancel() }

    fun goTo(x: Int, y: Int, floor: Int) = launchOperation {
        requireReady()
        require(x in 0..51_200 && y in 0..51_200)
        requireIdle(floor)
        navigationRequested = true
        ack("app_goto_target", JsonArray(listOf(JsonPrimitive(x), JsonPrimitive(y))))
    }

    fun dock() = launchOperation {
        requireReady()
        if (navigationRequested) {
            stopNavigation()
        }
        requireIdle()
        ack("app_charge")
    }

    /** Explicit Stop may also halt docking; lifecycle cleanup lets a return to dock continue. */
    fun stop(includeDock: Boolean = true) {
        if (disposed || haltJob != null) return
        val previous = job
        previous?.cancel()
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try { previous?.join(); stopNavigation(includeDock) }
            finally { haltJob = null; _busy.value = false }
        }
        haltJob = next
        _busy.value = true
        next.start()
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        val previous = job
        val pendingHalt = haltJob
        previous?.cancel()
        scope.launch {
            previous?.join()
            pendingHalt?.join()
            try { stopNavigation() } finally { scope.cancel() }
        }
    }

    private fun launchOperation(action: suspend () -> Unit) {
        if (disposed || job != null || haltJob != null) return
        _busy.value = true
        _error.value = null
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try { action() }
            catch (_: TimeoutCancellationException) { _error.value = "Robot control timed out. Check the robot before trying again." }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { _error.value = "Robot control was not confirmed. Let any task finish and try again." }
            finally { holding = false; _busy.value = haltJob != null; job = null }
        }
        job = next
        next.invokeOnCompletion {
            if (job === next) { holding = false; job = null; _busy.value = haltJob != null }
        }
        next.start()
    }

    private suspend fun stopNavigation(includeDock: Boolean = false) {
        if (!navigationRequested && !includeDock) return
        try {
            val status = readStatus()
            // A completed Go must never cause a later cleaning task to be stopped.
            val state = status["state"]?.jsonPrimitive?.intOrNull
            val stopGo = navigationRequested && state == 16
            val stopDock = includeDock && state in setOf(6, 15)
            if ((stopGo || stopDock) && status["in_cleaning"]?.jsonPrimitive?.intOrNull == 0) ack("app_stop")
            navigationRequested = false
        }
        catch (_: Exception) { _error.value = "Movement stop could not be confirmed. Check the robot." }
    }

    private fun requireReady() { check(ready()) { "Video is not connected" } }

    private suspend fun readStatus(): JsonObject {
        val response = withTimeout(1_500) { rpc.call("get_status", JsonArray(emptyList())) }
        check(response.error == null)
        val result = response.result
        return (if (result is JsonArray) result.singleOrNull() else result) as? JsonObject
            ?: error("Status unavailable")
    }

    private suspend fun requireIdle(floor: Int? = null) {
        val status = readStatus()
        check(status["in_cleaning"]?.jsonPrimitive?.intOrNull == 0)
        check(status["error_code"]?.jsonPrimitive?.intOrNull == 0)
        check(status["state"]?.jsonPrimitive?.intOrNull in setOf(2, 3, 8, 24, 100))
        if (floor != null) check(status["map_status"]?.jsonPrimitive?.intOrNull?.shr(2) == floor)
        requireReady()
    }

    private suspend fun ack(method: String, params: JsonElement = JsonArray(emptyList())) {
        val response = withTimeout(1_500) { rpc.call(method, params) }
        if (response.error != null) throw MotionRefused()
        val result = response.result
        if ((if (result is JsonArray) result.singleOrNull() else result) != JsonPrimitive("ok")) throw MotionRefused()
    }

    private fun movement(velocity: Double, omega: Double, sequence: Int) = buildJsonArray {
        add(buildJsonObject {
            put("velocity", velocity); put("omega", omega); put("duration", 300); put("seqnum", sequence)
        })
    }
}
