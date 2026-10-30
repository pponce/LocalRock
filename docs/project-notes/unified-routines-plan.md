# Unified routine editor and device compatibility

Status: Future work. Logged 2026-10-29; updated 2026-10-30. This is a design and implementation plan; the routine editor and older-device support have not been implemented or hardware-validated by this planning task.

## Objective

Provide one routine experience in LocalRock for vacuums with and without OEM routine support. Users create an ordered cleaning plan, run it on demand, or attach an optional schedule. The interface should not require users to choose between native and emulated routines.

For supported vacuums, create OEM-compatible scenes through the local server's existing scene APIs. A scene created in LocalRock should be readable, editable, and executable in the OEM app when both apps use the same local server. For older vacuums, the server should execute equivalent steps using commands that the device supports.

Priority update (2026-10-30): The first milestone is routine creation and editing for Roborocks that already support OEM routines. Establish and validate that experience before adding routine support for models that lack it.

## Agreed requirements

- Preserve OEM scene formats, identifiers, supported settings, and unrelated or unknown fields when creating and editing supported scenes.
- Keep one routine catalog and editor across device models. Select execution behavior automatically from actual device capabilities.
- Preserve an unfinished editor draft when scheduled cleaning starts. Handle busy-state restrictions and temporary map unavailability without losing work or interrupting the scheduled task.
- Preserve existing OEM schedule behavior, including schedule IDs, enablement, time changes, and native timer links.
- Homebridge schedule pause/delay implementation is outside this work. Do not redesign or modify it; compatibility is maintained through the existing OEM scheduling behavior.
- Keep routine definitions, schedules, and execution progress on the local server. The phone must not need to remain open for later steps or scheduled runs.
- Reflect real hardware capabilities in the available actions. Do not offer mopping or other actions a robot cannot perform, and do not silently omit a requested action.
- Preserve the robot's actual model identity. Routine support should not depend on advertising a different model.
- OEM compatibility is with the local server. Automatic synchronization to Roborock's cloud is outside scope.
- The OEM app may continue to hide its routine editor for older models. The consistent experience is provided by LocalRock.

## Architecture

| Component | Responsibilities |
| --- | --- |
| LocalRock | List, create, edit, and delete routines; add and reorder cleaning steps; configure optional schedules; start, pause/resume where supported, cancel, and show execution status |
| Local server routine service | Persist definitions and revisions; maintain OEM scene compatibility; validate device and map capabilities; expose consistent operations to clients |
| Local server scheduler and runner | Trigger runs, coordinate one active operation per robot, execute ordered steps, observe completion, and persist run progress |
| Device command adapters | Translate routine steps into commands appropriate for the model and firmware, including scene-management operations only where supported |
| Vacuum | Execute supported cleaning and dock commands and report status |

Use a shared execution engine with capability-specific command handling. A separate application or independent routine system for older models is unnecessary.

The internal representation must preserve the OEM scene payload and support a common view of the routine's target device, map, ordered actions, and settings. Keep execution metadata separate from OEM fields. Finalize the representation after checking the create/edit/read-back contract; avoid a lossy conversion into a reduced proprietary format.

Schedules should reference the saved routine rather than duplicate its steps. A manual run must not change the next scheduled occurrence. Use the same execution service for manual and scheduled starts, while preserving the intended rules for each trigger.

## Current implementation baseline

The inspected code already provides much of the transport and execution foundation:

- LocalRock lists scenes through `GET /user/scene/device/{duid}` and requests execution through `POST /user/scene/{id}/execute`.
- The server stores scene definitions in its inventory and supports creation through `POST /v2/user/scene`, updates to scene names and parameters, and deletion.
- The server's routine runner parses OEM scene actions for whole-home, room, and zone cleaning, translates them into ordinary cleaning commands, and can wait between steps.
- The schedule integration branch also uses the routine runner for device jobs.
- Active routine execution is currently tracked with in-memory tasks. Durable step progress and restart reconciliation require additional work.
- The existing scene execute operation can cancel an already-running scene. New explicit start/cancel operations should avoid accidental cancellation on repeated requests while retaining required OEM endpoint compatibility.

An OEM-compatible saved scene is not proof that local execution exactly matches Roborock's cloud. The present runner already translates scene actions on supported models. Preserve and validate that behavior before adding older-device adaptations; do not describe an unverified execution path as native parity.

Reference snapshots inspected during planning:

| Repository | Branch | Commit |
| --- | --- | --- |
| [LocalRock fork](https://github.com/pponce/LocalRock) | `testflight/camera-schedules-map-furniture` | `19baf8292c2cb99f976e5c37e58d41ce1e99c1ef` |
| [Server fork](https://github.com/pponce/local_roborock_server) | `integrate/schedule-hardening` | `99b64d71febddd2984dd7eeeafbdbfa4e0da0ffa` |
| [Server upstream](https://github.com/Python-roborock/local_roborock_server) | `main` | `206d560f622836fc0a58f221e042858c475564cc` |

These are reference snapshots, not instructions to reset another branch or claims about the currently deployed server.

Source references:

- [LocalRock scene API](https://github.com/pponce/LocalRock/blob/19baf8292c2cb99f976e5c37e58d41ce1e99c1ef/composeApp/src/commonMain/kotlin/com/kodraliu/localrock/shared/device/DeviceApi.kt)
- [Server scene storage and API service](https://github.com/pponce/local_roborock_server/blob/99b64d71febddd2984dd7eeeafbdbfa4e0da0ffa/src/roborock_local_server/bundled_backend/https_server/routes/user/scene/service.py)
- [Server routine runner](https://github.com/pponce/local_roborock_server/blob/99b64d71febddd2984dd7eeeafbdbfa4e0da0ffa/src/roborock_local_server/bundled_backend/shared/routine_runner.py)
- [Schedule integration contract](https://github.com/pponce/local_roborock_server/blob/99b64d71febddd2984dd7eeeafbdbfa4e0da0ffa/docs/schedules.md)

## Observed edge case: scheduled cleaning starts during routine creation

Reported by the user on 2026-10-30 while exploring and trying to record the OEM routine-creation workflow with the local server. This is a user observation, not a reproduced or diagnosed server defect. The exact affected device, app build, firmware, server revision, and duration were not captured in this report.

Observed sequence:

1. Two saved routines were visible on the OEM app's vacuum list page.
2. While the routine-creation workflow was open, a scheduled cleaning task started.
3. The map became unavailable in the routine workflow.
4. After leaving the workflow, the two existing routines were temporarily absent from the vacuum list page.
5. The routines later reappeared without reported recreation. The disappearance occurred around the robot's transition from preparing to cleaning; its exact timing is unknown. There is no evidence from this observation that the saved definitions were deleted.
6. While the robot was cleaning, tapping **Add favorite routine** was blocked with a message reported approximately as: "Task not completed. End the task before editing."

It is not yet known whether the same behavior occurs with Roborock's cloud, whether the map/list disappearance is an OEM UI restriction or a local-server response issue, or which transition causes it. The editing restriction during active cleaning and the transient disappearance of saved routines are separate observations; do not assume they share a cause.

Required LocalRock behavior for the supported-device milestone:

- Preserve the draft, including selected map/rooms and step settings, when the robot becomes busy, the map becomes temporarily unavailable, or the user leaves and resumes the workflow.
- Clearly explain any editing or saving restriction. If saving during cleaning is unsupported, retain the draft for an explicit retry when permitted; do not silently discard it or automatically submit it later.
- Keep saved routines visible during preparing/cleaning transitions, with availability shown separately. A failed or unavailable refresh must not be treated as deletion or overwrite the saved catalog with an empty list.
- Use a previously loaded map for drafting only when its identity and references remain valid. Otherwise retain the draft and explain the map's temporary unavailability; refresh and revalidate map/room references before saving or running.
- Recheck any required busy-state and map conditions when saving, not just when opening the editor. A schedule may start between the initial check and the save request.
- Let the scheduled cleaning continue normally. Opening or recovering an editor must not cancel, delay, pause, or replace the active cleaning task.

Follow-up and acceptance checks:

- Reproduce with the editor already open when a schedule fires, when a schedule starts during a save, and when opening the editor after cleaning has begun. Check preparing, active cleaning, and return-to-idle transitions.
- Record app/server versions, device and firmware, timestamps, robot status, and sanitized scene-list/map/save responses. Distinguish an empty server result from an OEM rendering/loading issue.
- Compare the same workflow against Roborock's cloud when feasible; keep the cause unclassified until evidence supports attribution.
- Verify draft recovery, stable scene IDs/count, no unintended deletes or duplicate saves, map recovery, and explicit retry after the task finishes. Confirm schedule behavior remains unchanged.

## Execution and compatibility work

1. Validate the complete plan before starting: device capability, supported settings, target map, and room or zone references. Revalidate affected references when maps change.
2. Preserve scene-specific behavior on supported firmware. Older models must not receive unsupported scene-management commands such as `reunion_scenes` merely because the routine is stored in scene format.
3. Distinguish an unset OEM value from an explicitly requested setting. Unknown or unsupported settings require a deliberate compatibility decision, not blanket skipping.
4. Track runs with stable run IDs, a snapshot of the routine revision, current step, command outcomes, and terminal status. Duplicate start requests should not create duplicate runs.
5. Observe actual cleaning completion before advancing. Pausing, charging during an unfinished clean, dock activity, or losing the connection must not be mistaken for completing a step.
6. Reconcile robot state after a server restart or lost acknowledgement. Do not blindly resend a start command whose outcome is unknown.
7. Define consistent busy, cancellation, error, and manual-intervention behavior. Coordinate routines with scheduled and manually started cleaning so one operation does not unexpectedly replace another.
8. Keep one owner for each scheduled trigger. Preserve native timer checks and avoid duplicate robot/server starts.

The existing schedule documentation distinguishes device jobs from scene/routine scheduling. Verify the OEM routine scheduling contract before choosing how to connect routine triggers to the scheduler. Do not assume every scene schedule is already represented by the ordinary device jobs API, or introduce an unrelated admin-only scheduling system as a substitute for OEM compatibility.

## Related PRs

Status observed on 2026-10-29; refresh before implementation.

| PR | Status | Relevance |
| --- | --- | --- |
| [Server #205](https://github.com/Python-roborock/local_roborock_server/pull/205) | Open | Omits unset scene settings and skips unsupported preset commands. Partial compatibility work; no editor or complete older-device routine support. Review its skipping policy against explicit requested settings. |
| [Server #208](https://github.com/Python-roborock/local_roborock_server/pull/208) | Open draft | Restores OEM scene tiles through product metadata. Still requires the device's scene capability flag; does not enable unsupported devices. |
| [Server #211](https://github.com/Python-roborock/local_roborock_server/pull/211) | Open | Fixes OEM scene ordering response compatibility. Relevant to preserving scene order across clients. |
| [LocalRock #65](https://github.com/DonSidro/LocalRock/pull/65) | Merged | Includes listing and starting saved routines, without a routine creation editor. |
| [Server #133](https://github.com/Python-roborock/local_roborock_server/pull/133) | Closed, unmerged | Earlier proposal for scheduling saved routines through an admin API. Explicitly excludes a routine editor and OEM scheduling-screen compatibility. |

The PR review did not identify an existing proposal implementing the unified LocalRock editor or full routine support for models without OEM scene support. Recheck for new or expanded work before opening implementation PRs.

## Implementation sequence and acceptance checks

1. Refresh both forks and upstream repositories, inspect applicable repository instructions, and recheck related PRs. Select current bases that preserve schedule, video, furniture, and map work.
2. Establish the supported-device contract: create, edit, list, reorder, schedule, run, and delete a scene using the OEM app against the local server. Use sanitized captures or fixtures to resolve gaps in formats and scene-management commands.
3. Implement the supported-device milestone first: close any scene-service contract gaps and build the LocalRock routine creation/editing UI for models with OEM routine support. Retain settings the editor does not understand and reject edits that cannot preserve their meaning.
4. Verify supported-scene round trips in both apps. A LocalRock-created scene on a supported robot must appear and remain editable/runnable in the OEM app connected to the same server, subject to verified busy-state restrictions. Include the scheduled-start-during-editing cases above before considering this milestone complete.
5. Then extend the shared service and runner for models without OEM routine support, using capability validation and older-device command handling. Add durable execution tracking and explicit operations while retaining OEM API behavior.
6. Test equivalent room/full/zone routines on an S7 (`roborock.vacuum.a15`) and S7 MaxV (`roborock.vacuum.a27`). Confirm step order, settings, on-demand starts, scheduling, and phone-closed execution. Hardware support remains to be established by these tests.
7. Exercise pauses, cancellation, recharging, busy robots, stale map references, disconnects, duplicate requests, unknown command outcomes, and server restart. Verify no unintended replay or advancement.
8. Run existing schedule regression checks for each milestone and validate OEM schedule IDs, enablement, edits, and native links. No Homebridge implementation changes are part of this work.
9. Prepare separate server and LocalRock implementation PRs with dependencies and hardware evidence stated precisely. The documentation-only planning branch does not serve as an integration base for those changes.

## Resume instructions

Continue from this plan in `docs/project-notes/unified-routines-plan.md` on the `docs/unified-routines-plan` branch of `pponce/LocalRock`. Begin by refreshing the repository and PR state rather than treating the recorded commits as current heads.

The next concrete task is to establish the OEM scene creation/editing/scheduling contract for supported devices, including behavior when scheduled cleaning begins during editing. Deliver and validate supported-device routine creation first; older-device routine support follows. Implementation, deployment, and hardware testing remain future work.
