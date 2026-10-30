package com.kodraliu.localrock.shared.vacuum.camera

import com.kodraliu.localrock.shared.protocol.V1Response
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CameraRemoteVoiceTest {
    private class Rpc : CameraRpc {
        val calls = mutableListOf<Pair<String, JsonElement>>()
        var owner = "our-preview"
        var ownerDelay = 0L
        var writeDelay = 0L
        var result: JsonElement = JsonArray(listOf(JsonPrimitive("ok")))
        var failure: JsonObject? = null
        var throwOnWrite = false
        override suspend fun call(method: String, params: JsonElement): V1Response {
            calls += method to params
            if (method == "get_homesec_connect_status") {
                delay(ownerDelay)
                return V1Response(null, buildJsonObject { put("client_id", owner) }, null)
            }
            assertEquals("enable_homesec_voice", method)
            delay(writeDelay)
            if (throwOnWrite) error("private-network-payload")
            return V1Response(null, result, failure)
        }
        fun writes() = calls.filter { it.first == "enable_homesec_voice" }
    }

    private class Rig(scope: CoroutineScope) {
        val rpc = Rpc()
        var clientId: String? = "our-preview"
        var streaming = true
        val control = CameraRemoteVoice(rpc, scope, { clientId }, { streaming }, commandTimeoutMs=500)
    }

    @Test fun starts_unknown_and_does_nothing_without_an_authenticated_stream() = runTest {
        val r = Rig(backgroundScope)
        runCurrent()
        assertNull(r.control.acceptedEnabled.value)
        assertTrue(r.rpc.calls.isEmpty())
        r.streaming = false
        r.control.setEnabled(false); runCurrent()
        r.streaming = true; r.clientId = null
        r.control.setEnabled(true); runCurrent()
        assertTrue(r.rpc.calls.isEmpty())
    }

    @Test fun off_and_on_are_explicit_boolean_requests_after_an_owner_check() = runTest {
        val r = Rig(backgroundScope)
        r.control.setEnabled(false); runCurrent()
        assertEquals(false, r.control.acceptedEnabled.value)
        assertEquals(listOf("get_homesec_connect_status", "enable_homesec_voice"), r.rpc.calls.map { it.first })
        assertEquals(Json.parseToJsonElement("{\"enable\":false}"), r.rpc.writes().single().second)
        r.rpc.result = JsonPrimitive("ok")
        r.control.setEnabled(true); runCurrent()
        assertEquals(true, r.control.acceptedEnabled.value)
        assertEquals(Json.parseToJsonElement("{\"enable\":true}"), r.rpc.writes().last().second)
        assertFalse(r.control.busy.value)
    }

    @Test fun another_viewer_owns_the_camera_so_no_write_is_sent() = runTest {
        val r = Rig(backgroundScope); r.rpc.owner = "other-viewer"
        r.control.setEnabled(false); runCurrent()
        assertTrue(r.rpc.writes().isEmpty())
        assertNull(r.control.acceptedEnabled.value)
        assertContains(r.control.error.value.orEmpty(), "No voice request was sent")
    }

    @Test fun a_changed_session_during_the_owner_check_prevents_the_write() = runTest {
        val r = Rig(backgroundScope); r.rpc.ownerDelay = 200
        r.control.setEnabled(false); runCurrent()
        r.clientId = "new-preview"
        advanceTimeBy(201); runCurrent()
        assertTrue(r.rpc.writes().isEmpty())
    }

    @Test fun ending_video_during_the_owner_check_prevents_the_write() = runTest {
        val r = Rig(backgroundScope); r.rpc.ownerDelay = 200
        r.control.setEnabled(false); runCurrent()
        r.streaming = false
        r.control.release()
        advanceTimeBy(201); runCurrent()
        assertTrue(r.rpc.writes().isEmpty())
        assertFalse(r.control.busy.value)
    }

    @Test fun repeated_taps_during_a_request_are_not_queued_or_replayed() = runTest {
        val r = Rig(backgroundScope); r.rpc.writeDelay = 200
        r.control.setEnabled(false); runCurrent()
        assertTrue(r.control.busy.value)
        r.control.setEnabled(true); r.control.setEnabled(false)
        advanceTimeBy(201); runCurrent()
        assertEquals(1, r.rpc.writes().size)
        assertEquals(false, r.control.acceptedEnabled.value)
    }

    @Test fun refusal_reports_only_a_safe_code_and_keeps_the_value_unknown() = runTest {
        val r = Rig(backgroundScope)
        r.rpc.failure = buildJsonObject { put("code", -32601); put("message", "private-robot-payload") }
        r.control.setEnabled(false); runCurrent()
        assertNull(r.control.acceptedEnabled.value)
        assertContains(r.control.error.value.orEmpty(), "-32601")
        assertFalse(r.control.error.value.orEmpty().contains("private"))
        assertFalse(r.control.busy.value)
    }

    @Test fun ambiguous_acknowledgement_is_not_reported_as_success() = runTest {
        val r = Rig(backgroundScope)
        r.rpc.result = JsonArray(listOf(JsonPrimitive("ok"), JsonPrimitive("ok")))
        r.control.setEnabled(false); runCurrent()
        assertNull(r.control.acceptedEnabled.value)
        assertContains(r.control.error.value.orEmpty(), "not confirmed")
    }

    @Test fun a_write_timeout_clears_the_previous_request_and_never_retries() = runTest {
        val r = Rig(backgroundScope)
        r.control.setEnabled(true); runCurrent()
        assertEquals(true, r.control.acceptedEnabled.value)
        r.rpc.writeDelay = 1_000
        r.control.setEnabled(false); runCurrent()
        advanceTimeBy(501); runCurrent()
        assertNull(r.control.acceptedEnabled.value)
        assertContains(r.control.error.value.orEmpty(), "may have changed")
        assertFalse(r.control.busy.value)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(2, r.rpc.writes().size)
    }

    @Test fun an_explicit_on_request_can_follow_an_uncertain_off_request() = runTest {
        val r = Rig(backgroundScope); r.rpc.writeDelay = 1_000
        r.control.setEnabled(false); runCurrent()
        advanceTimeBy(501); runCurrent()
        assertNull(r.control.acceptedEnabled.value)
        r.rpc.writeDelay = 0
        r.control.setEnabled(true); runCurrent()
        assertEquals(true, r.control.acceptedEnabled.value)
        assertEquals(Json.parseToJsonElement("{\"enable\":true}"), r.rpc.writes().last().second)
        assertNull(r.control.error.value)
    }

    @Test fun an_owner_check_timeout_sends_no_write() = runTest {
        val r = Rig(backgroundScope); r.rpc.ownerDelay = 1_000
        r.control.setEnabled(false); runCurrent()
        advanceTimeBy(501); runCurrent()
        assertTrue(r.rpc.writes().isEmpty())
        assertContains(r.control.error.value.orEmpty(), "No voice request was sent")
    }

    @Test fun network_errors_do_not_expose_protocol_payloads() = runTest {
        val r = Rig(backgroundScope); r.rpc.throwOnWrite = true
        r.control.setEnabled(false); runCurrent()
        assertNull(r.control.acceptedEnabled.value)
        assertFalse(r.control.error.value.orEmpty().contains("private"))
        assertFalse(r.control.busy.value)
    }

    @Test fun cleanup_waits_for_an_issued_request_and_does_not_restore_or_reapply_it() = runTest {
        val r = Rig(backgroundScope); r.rpc.writeDelay = 200
        r.control.setEnabled(false); runCurrent()
        r.streaming = false
        val released = async { r.control.release() }
        runCurrent()
        assertFalse(released.isCompleted)
        advanceTimeBy(201); runCurrent()
        assertTrue(released.isCompleted)
        assertNull(r.control.acceptedEnabled.value)
        assertFalse(r.control.busy.value)
        r.clientId = "next-preview"; r.streaming = true
        runCurrent()
        assertEquals(1, r.rpc.writes().size)
    }
}
