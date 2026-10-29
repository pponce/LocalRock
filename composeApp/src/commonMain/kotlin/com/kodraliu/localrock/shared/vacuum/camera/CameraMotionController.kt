package com.kodraliu.localrock.shared.vacuum.camera

import com.kodraliu.localrock.shared.vacuum.VacuumSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*

private class MotionRefused : RuntimeException()

/** One foreground viewer owns the manual session and serializes robot controls. */
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
        FORWARD(.2, 0.0), BACKWARD(-.2, 0.0), LEFT(0.0, 1.05), RIGHT(0.0, -1.05),
    }
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _manualMode = MutableStateFlow(false)
    val manualMode: StateFlow<Boolean> = _manualMode
    private val _viewerStartedActivity = MutableStateFlow(false)
    val viewerStartedActivity: StateFlow<Boolean> = _viewerStartedActivity
    private var job: Job? = null
    private var holding = false
    private var haltJob: Job? = null
    private var sequence = 0
    private var navigationRequested = false
    private var disposed = false

    fun hold(direction: Direction) {
        if (disposed || job != null || haltJob != null) return
        holding = true
        launchOperation {
            var entered = false
            var failed = false
            try {
                requireReady()
                val status = requireIdle()
                if (!_manualMode.value || status.state() !in setOf(4, 7)) {
                    // A late acknowledgement must still be cleaned up after finger release.
                    withContext(NonCancellable) {
                        _manualMode.value = true
                        sequence = 0
                        try { ack("app_rc_start") }
                        catch (e: MotionRefused) { _manualMode.value = false; throw e }
                    }
                }
                currentCoroutineContext().ensureActive()
                withTimeout(1_500) {
                    while (true) {
                        requireReady()
                        val current = readStatus()
                        requireNoTask(current)
                        if (current.state() in setOf(4, 7)) break
                        delay(100)
                    }
                }
                entered = true
                while (currentCoroutineContext().isActive) {
                    requireReady()
                    _viewerStartedActivity.value = true
                    ack("app_rc_move", movement(direction.velocity, direction.omega, ++sequence))
                    delay(200)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                throw e
            } finally {
                holding = false
                if (_manualMode.value) withContext(NonCancellable) {
                    val stopped = runCatching { ack("app_rc_stop") }.isSuccess
                    if (!stopped) _error.value = "Drive release could not be confirmed. Check the robot before continuing."
                    // Normal release retains the OEM manual session and its sequence numbers.
                    if (!entered || failed || !stopped || !ready()) endManualMode()
                }
            }
        }
    }

    /** Pointer-up stops motion; leaving the viewer separately ends its manual session. */
    fun release() { if (holding) job?.cancel() }

    fun cleaning(action: CameraCleaningAction) = launchOperation {
        if (_manualMode.value) {
            check(endManualMode())
            withTimeout(1_500) {
                while (readStatus().state() in setOf(4, 7)) delay(100)
            }
        }
        val status = readStatus()
        val method = cameraCleaningCommand(action, status.state(),
            status["in_cleaning"]?.jsonPrimitive?.intOrNull,
            status["error_code"]?.jsonPrimitive?.intOrNull)
            ?: error("The robot's cleaning state changed")
        if (action == CameraCleaningAction.START) _viewerStartedActivity.value = true
        ack(method)
    }

    /** Pin-and-Go can undock the robot before camera frames are available. */
    fun goTo(x: Int, y: Int, floor: Int) = launchOperation {
        require(x in 0..51_200 && y in 0..51_200)
        requireIdle(floor)
        navigationRequested = true
        _viewerStartedActivity.value = true
        ack("app_goto_target", JsonArray(listOf(JsonPrimitive(x), JsonPrimitive(y))))
    }

    fun dock(onDocked: () -> Unit = {}) = endViewing(returnToDock = true, onFinished = onDocked)

    /** Finish robot commands before the caller releases the camera preview. */
    fun endViewing(returnToDock: Boolean = false, onFinished: () -> Unit = {}) = transition {
        if (returnToDock) {
            val status = readStatus()
            check(status["error_code"]?.jsonPrimitive?.intOrNull == 0)
            check(status.state() in setOf(2, 3, 4, 5, 6, 7, 8, 10, 11, 15, 16, 17, 18, 24, 100))
            if (status.state() !in setOf(6, 8, 15, 100)) ack("app_charge")
            navigationRequested = false
        } else check(stopNavigation())
        check(endManualMode())
        _viewerStartedActivity.value = false
        onFinished()
    }

    /** Explicit Stop may halt docking; background cleanup lets a return to dock continue. */
    fun stop(includeDock: Boolean = true) = transition {
        stopNavigation(includeDock)
        endManualMode()
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
            try { stopNavigation(); endManualMode() } finally { scope.cancel() }
        }
    }

    private fun transition(action: suspend () -> Unit) {
        if (disposed || haltJob != null) return
        val previous = job
        previous?.cancel()
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try { previous?.join(); action() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { _error.value = "Robot control was not confirmed. Check the robot before continuing." }
            finally { haltJob = null; _busy.value = false }
        }
        haltJob = next
        _error.value = null
        _busy.value = true
        next.start()
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

    private suspend fun endManualMode(): Boolean {
        if (!_manualMode.value) return true
        return try {
            ack("app_rc_end")
            _manualMode.value = false
            true
        } catch (_: Exception) {
            _error.value = "Ending manual control could not be confirmed. Check the robot."
            false
        }
    }

    private suspend fun stopNavigation(includeDock: Boolean = false): Boolean {
        if (!navigationRequested && !includeDock) return true
        return try {
            val status = readStatus()
            // A completed Go must never cause a later cleaning task to be stopped.
            val state = status.state()
            if (status["in_cleaning"]?.jsonPrimitive?.intOrNull == 0) {
                if (navigationRequested && state == 16) ack("stop_goto_target")
                else if (includeDock && state in setOf(6, 15)) ack("app_stop")
            }
            navigationRequested = false
            true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { _error.value = "Movement stop could not be confirmed. Check the robot."; false }
    }

    private fun requireReady() { check(ready()) { "Video is not connected" } }
    private fun JsonObject.state() = this["state"]?.jsonPrimitive?.intOrNull
    private fun requireNoTask(status: JsonObject) {
        check(status["in_cleaning"]?.jsonPrimitive?.intOrNull == 0)
        check(status["error_code"]?.jsonPrimitive?.intOrNull == 0)
    }

    private suspend fun readStatus(): JsonObject {
        val response = withTimeout(1_500) { rpc.call("get_status", JsonArray(emptyList())) }
        check(response.error == null)
        val result = response.result
        return (if (result is JsonArray) result.singleOrNull() else result) as? JsonObject
            ?: error("Status unavailable")
    }

    private suspend fun requireIdle(floor: Int? = null): JsonObject {
        val status = readStatus()
        requireNoTask(status)
        check(status.state() in setOf(2, 3, 8, 24, 100) ||
            (_manualMode.value && status.state() in setOf(4, 7)))
        if (floor != null) check(status["map_status"]?.jsonPrimitive?.intOrNull?.shr(2) == floor)
        return status
    }

    private suspend fun ack(method: String, params: JsonElement = JsonArray(emptyList())) {
        val response = withTimeout(1_500) { rpc.call(method, params) }
        if (response.error != null) throw MotionRefused()
        val result = response.result
        if ((if (result is JsonArray) result.singleOrNull() else result) != JsonPrimitive("ok")) throw MotionRefused()
    }

    private fun movement(velocity: Double, omega: Double, sequence: Int) = buildJsonArray {
        add(buildJsonObject {
            put("velocity", velocity); put("omega", omega); put("duration", 1500); put("seqnum", sequence)
        })
    }
}
