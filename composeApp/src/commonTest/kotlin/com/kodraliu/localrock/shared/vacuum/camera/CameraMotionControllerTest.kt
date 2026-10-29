package com.kodraliu.localrock.shared.vacuum.camera

import com.kodraliu.localrock.shared.protocol.V1Response
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CameraMotionControllerTest {
    private class Rpc : CameraRpc {
        val calls = mutableListOf<Pair<String,JsonElement>>()
        var state = 3
        var cleaning = 0
        var startDelay = 0L
        var enterManualMode = true
        var statusDelay = 0L
        var refuse: String? = null
        override suspend fun call(method: String, params: JsonElement): V1Response {
            calls += method to params
            if (method == refuse) return V1Response(null,null,buildJsonObject { put("code",-1) })
            if (method == "get_status") {
                delay(statusDelay)
                return V1Response(null,buildJsonArray { add(buildJsonObject {
                    put("state",state);put("in_cleaning",cleaning);put("error_code",0);put("map_status",0)
                }) },null)
            }
            if (method == "app_rc_start") { delay(startDelay); if (enterManualMode) state=7 }
            if (method == "app_rc_end") state=3
            if (method == "app_goto_target") state=16
            if (method == "app_stop") state=3
            return V1Response(null,buildJsonArray {add("ok")},null)
        }
        fun methods() = calls.map {it.first}
    }
    private fun TestScope.controller(rpc: Rpc, ready: () -> Boolean = {true}) =
        CameraMotionController(rpc,ready,CoroutineScope(SupervisorJob()+StandardTestDispatcher(testScheduler)))

    @Test fun release_sends_zero_then_end_and_stops_repeating() = runTest {
        val rpc=Rpc();val c=controller(rpc)
        c.hold(CameraMotionController.Direction.FORWARD);runCurrent();advanceTimeBy(450);runCurrent()
        c.release();advanceUntilIdle()
        assertEquals(listOf("app_rc_move","app_rc_end"),rpc.methods().takeLast(2))
        val last=rpc.calls[rpc.calls.lastIndex-1].second.jsonArray.single().jsonObject
        assertEquals(0.0,last["velocity"]!!.jsonPrimitive.double)
        assertEquals(300,last["duration"]!!.jsonPrimitive.int)
        val sequences=rpc.calls.filter { it.first=="app_rc_move" }.map { it.second.jsonArray.single().jsonObject["seqnum"]!!.jsonPrimitive.int }
        assertEquals((1..sequences.size).toList(),sequences)
        val count=rpc.calls.size;advanceTimeBy(5000);runCurrent();assertEquals(count,rpc.calls.size)
        c.dispose();advanceUntilIdle()
    }
    @Test fun release_during_status_read_never_starts_driving() = runTest {
        val rpc=Rpc().apply {statusDelay=1000};val c=controller(rpc)
        c.hold(CameraMotionController.Direction.FORWARD);runCurrent();c.release();advanceUntilIdle()
        assertEquals(listOf("get_status"),rpc.methods());c.dispose();advanceUntilIdle()
    }
    @Test fun release_before_dispatch_never_starts_driving() = runTest {
        val rpc=Rpc();val c=controller(rpc)
        c.hold(CameraMotionController.Direction.LEFT);c.release();advanceUntilIdle()
        assertTrue(rpc.calls.isEmpty());assertFalse(c.busy.value);c.dispose();advanceUntilIdle()
    }
    @Test fun late_start_ack_is_cleaned_without_motion() = runTest {
        val rpc=Rpc().apply {startDelay=800};val c=controller(rpc)
        c.hold(CameraMotionController.Direction.FORWARD);runCurrent();c.release();advanceUntilIdle()
        assertEquals(listOf("get_status","app_rc_start","app_rc_move","app_rc_end"),rpc.methods())
        assertEquals(0.0,rpc.calls[2].second.jsonArray.single().jsonObject["velocity"]!!.jsonPrimitive.double)
        c.dispose();advanceUntilIdle()
    }
    @Test fun connection_loss_stops_drive_and_reports_failure() = runTest {
        val rpc=Rpc();var connected=true;val c=controller(rpc){connected}
        c.hold(CameraMotionController.Direction.RIGHT);runCurrent();connected=false;advanceUntilIdle()
        assertEquals("app_rc_end",rpc.methods().last());assertNotNull(c.error.value)
        c.dispose();advanceUntilIdle()
    }
    @Test fun busy_robot_and_missing_video_cannot_start_motion() = runTest {
        for (connected in listOf(false,true)) {
            val rpc=Rpc().apply {state=5;cleaning=1};val c=controller(rpc){connected}
            c.hold(CameraMotionController.Direction.BACKWARD);advanceUntilIdle()
            assertFalse("app_rc_start" in rpc.methods());assertNotNull(c.error.value)
            c.dispose();advanceUntilIdle()
        }
    }
    @Test fun missing_manual_mode_never_sends_nonzero_motion() = runTest {
        val rpc=Rpc().apply {enterManualMode=false};val c=controller(rpc)
        c.hold(CameraMotionController.Direction.FORWARD);advanceUntilIdle()
        val moves=rpc.calls.filter {it.first=="app_rc_move"}
        assertTrue(moves.all {it.second.jsonArray.single().jsonObject["velocity"]!!.jsonPrimitive.double==0.0})
        assertEquals("app_rc_end",rpc.methods().last());assertNotNull(c.error.value)
        c.dispose();advanceUntilIdle()
    }
    @Test fun refused_start_does_not_send_move_or_end() = runTest {
        val rpc=Rpc().apply {refuse="app_rc_start"};val c=controller(rpc)
        c.hold(CameraMotionController.Direction.FORWARD);advanceUntilIdle()
        assertEquals(listOf("get_status","app_rc_start"),rpc.methods());assertNotNull(c.error.value)
        c.dispose();advanceUntilIdle()
    }
    @Test fun refused_move_stops_and_does_not_repeat() = runTest {
        val rpc=Rpc().apply {refuse="app_rc_move"};val c=controller(rpc)
        c.hold(CameraMotionController.Direction.FORWARD);advanceUntilIdle()
        assertEquals("app_rc_end",rpc.methods().last());assertNotNull(c.error.value)
        c.dispose();advanceUntilIdle()
    }
    @Test fun goto_uses_bare_coordinates_and_stop_releases_owned_navigation() = runTest {
        val rpc=Rpc();val c=controller(rpc)
        c.goTo(24500,26000,0);advanceUntilIdle()
        assertEquals("[24500,26000]",rpc.calls.last().second.toString())
        c.stop();advanceUntilIdle();assertEquals("app_stop",rpc.methods().last())
        c.dispose();advanceUntilIdle()
    }
    @Test fun changed_floor_prevents_goto() = runTest {
        val rpc=Rpc();val c=controller(rpc)
        c.goTo(24500,26000,1);advanceUntilIdle()
        assertFalse("app_goto_target" in rpc.methods());c.dispose();advanceUntilIdle()
    }
    @Test fun completed_goto_does_not_stop_a_later_cleaning_task() = runTest {
        val rpc=Rpc();val c=controller(rpc)
        c.goTo(24500,26000,0);advanceUntilIdle();rpc.state=5;rpc.cleaning=1
        c.stop();advanceUntilIdle();assertFalse("app_stop" in rpc.methods())
        c.dispose();advanceUntilIdle()
    }
    @Test fun explicit_stop_can_stop_returning_to_dock() = runTest {
        val rpc=Rpc().apply {state=6};val c=controller(rpc)
        c.stop();advanceUntilIdle();assertEquals("app_stop",rpc.methods().last())
        c.dispose();advanceUntilIdle()
    }
    @Test fun background_cleanup_does_not_interrupt_returning_to_dock() = runTest {
        val rpc=Rpc().apply {state=6};val c=controller(rpc)
        c.stop(includeDock=false);advanceUntilIdle();assertTrue(rpc.calls.isEmpty())
        c.dispose();advanceUntilIdle();assertTrue(rpc.calls.isEmpty())
    }
    @Test fun dispose_releases_held_drive() = runTest {
        val rpc=Rpc();val c=controller(rpc)
        c.hold(CameraMotionController.Direction.LEFT);runCurrent();c.dispose();advanceUntilIdle()
        assertEquals("app_rc_end",rpc.methods().last())
        val count=rpc.calls.size;c.hold(CameraMotionController.Direction.RIGHT);runCurrent();assertEquals(count,rpc.calls.size)
    }
}
