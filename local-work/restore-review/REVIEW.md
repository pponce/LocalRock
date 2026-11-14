# Schedule restore review

Source: DonSidro/LocalRock PR #37, commit `f964cadd0beb0dd2ab39be9d21a59468e99c10b6`.
Server reference: Python-roborock/local_roborock_server PR #179, commit `1927b4307d2ee6ea6771773ecb80cddd549eee64`.
Validation branch: `pponce/LocalRock:review/schedule-restore-validation-20261114`.
Validation commit: `865c6b9b40b73d920c52712d62fab93a7bdafb23`.

## Scope

Review and automated validation only. The validation commit adds tests and a fork-only workflow. It does not change production source. MQTT messages use a synthetic key and in-memory transport; no real robot or service is contacted. The server checks use temporary synthetic inventory.

The existing schedule behavior establishes the requirements: keep HTTP job IDs distinct from native timer names, preserve saved map and cleaning fields, read back uncertain writes without blindly repeating them, reject stale or ambiguous server jobs, preserve disabled state, and retain the protection for consumed one-time jobs.

## Findings reproduced in the added tests

### 1. A disabled restore can leave an enabled native timer after a partial failure

`VacuumRepository.restoreNativeTimer` creates a native timer, which starts enabled, and only then sends `upd_server_timer(..., false)` for a disabled job. Errors or lost responses abort immediately without a fresh native read. A subsequent restore attempt refuses the now-existing timer. The details UI labels ordinary exceptions as `Missing`, even when the native timer was created.

The server's exact `reconcile_native_enabled` function confirms the consequence: an unchanged disabled recurring server job becomes enabled when a later fresh native read reports on. A later off read corrects it only if the robot was actually disabled. Consumed one-time jobs retain their separate protection.

Recommended feedback: use verified partial-save handling, read back after uncertain create/disable results, clearly report the actual or unconfirmed state, and provide a deliberate recovery path without recreating an existing timer. Confirm the server job as well as the robot before reporting combined success.

Priority: address before merge. The direct failure reproduction is a saved disabled whole-home job, a confirmed missing native timer, a successful create, and a refused disable. The native timer remains on. The error only reports `The robot refused upd_server_timer: Param error (code -10005)`; retry refuses the existing timer. The independent server test confirms that a fresh on observation can then enable the recurring server job. This is a combined app/server simulation, not a live robot observation.

The polling-window probe is a separate variant: a native poll between successful create and disable can temporarily enable the server job even when restore returns Off. A later confirmed off poll corrects that window. The test does not establish that this transient mismatch is permanent or that a cleaning run occurred.

Source: [restore workflow](https://github.com/virtitnerd/LocalRock/blob/f964cadd0beb0dd2ab39be9d21a59468e99c10b6/composeApp/src/commonMain/kotlin/com/kodraliu/localrock/shared/vacuum/VacuumRepository.kt#L974), [details error display](https://github.com/virtitnerd/LocalRock/blob/f964cadd0beb0dd2ab39be9d21a59468e99c10b6/composeApp/src/commonMain/kotlin/com/kodraliu/localrock/ui/vacuum/ScheduleScreen.kt#L443).

### 2. Restoration uses a stale server record and does not check shared native references

The restore method uses `timer.serverJob` from the loaded card and never reads `ScheduleMirror`. A job deleted or edited by another client can still recreate a native timer using the old cron/settings/enabled value. Two server records referencing the same missing native timer are not detected.

Recommended feedback: use the same fresh-job snapshot and unique-native-reference checks as normal schedule edits/toggles, both before and after network waits.

Priority: address before merge. The tests load a valid card, then delete/change its job or add a second job referencing the same native timer. Restore still sends a create in each case. No HTTP job read occurs inside restore.

### 3. Unsupported input can be treated as whole-home or missing

`nativeTimerState` filters out non-array entries and reports Missing when no matching row remains; a malformed success array such as `["ok"]` therefore permits a create. `restoreBlockedReason` uses `settingsText("segments")`; an unsupported array value becomes empty, passes the room guard, and later defaults to whole-home `"0"`. Unknown model-specific settings are dropped by conversion to `TimerSettings` and `toActionObject`.

Recommended feedback: validate native-list and stored-job shapes before writing. Only restore formats that can be represented without changing their meaning; preserve additional supported fields or refuse with an explanatory message.

Priority: defensive hardening. The malformed list, array-valued segments and unknown-field probes use synthetic inputs; they are not claims about the normal payloads emitted by a particular firmware. Ordinary string room selections are correctly refused. The unknown-field fixture is deliberately named `future_model_setting`; its failure establishes that extra fields are dropped, not that this invented field is a supported firmware command.

Source: [restore eligibility](https://github.com/virtitnerd/LocalRock/blob/f964cadd0beb0dd2ab39be9d21a59468e99c10b6/composeApp/src/commonMain/kotlin/com/kodraliu/localrock/shared/schedule/NativeTimerRestore.kt), [native state parser](https://github.com/virtitnerd/LocalRock/blob/f964cadd0beb0dd2ab39be9d21a59468e99c10b6/composeApp/src/commonMain/kotlin/com/kodraliu/localrock/shared/schedule/ServerSchedule.kt#L108). The parser is an existing helper used by the new restore path; the finding does not imply it was introduced in this update.

## Validation

| Check | Result |
| --- | --- |
| Existing Android unit suite | 387 passed, 0 failed, 0 skipped |
| Android debug application build | Passed |
| Added restore workflow probes | 22 executed: 10 passed, 12 failed, 0 errors |
| iPhone ARM64 shared Kotlin compilation | Passed |
| iOS Simulator ARM64 shared Kotlin compilation | Passed |
| Server store/reconciliation checks | 14 passed |

The 12 failing probes are variants of the three findings above, not 12 independent defects. The workflow is red because these new assertions expose the gaps; the baseline tests and Android build are green. The first two Android attempts were blocked by a syntax error in the added test fixture; the final run corrected it without changing production source.

App test/build evidence: [run 43625485455](https://github.com/pponce/LocalRock/actions/runs/43625485455), including the Restore review diagnostics check and downloadable XML/log artifacts. iOS evidence: [run 43624553448](https://github.com/pponce/LocalRock/actions/runs/43624553448). The iOS targets were not repeated for the final test-fixture-only revision; production source was identical.

Server reconciliation: 14 tests passed locally, comprising 11 existing NativeStoreTests plus 3 restore-interaction checks. These execute the downloaded production store, inventory and reconciliation modules; only the package-path bootstrap import is replaced.

The app probes instantiate the unmodified `VacuumRepository`, `VacuumSession`, MQTT client and codec, with a fake MQTT transport and server-job mirror. These are workflow tests, not a reimplementation of the restore algorithm.

Hardware and full signed iPhone application validation are outside this review. Successful compilation or simulation does not establish firmware compatibility.

Passing restore probes cover enabled and disabled whole-home restoration, preservation of dated one-time cron and known map/cleaning settings, refusal to overwrite an existing timer, refusal of ordinary room schedules and server-only jobs, failed native reads, duplicate matching native rows, refusal without legacy fallback/retry, and detection of a disable acknowledgement that had no effect.

Machine-readable results: [app-test-result.json](app-test-result.json), [server-test-result.json](server-test-result.json), and [ios-result.json](ios-result.json).

## Reproducing the checks

Use a complete checkout of `pponce/LocalRock` at validation commit `865c6b9b40b73d920c52712d62fab93a7bdafb23`, with its documented Java/Android/Gradle prerequisites.

```bash
./gradlew --init-script local-work/restore-review/baseline.init.gradle :composeApp:testDebugUnitTest :composeApp:assembleDebug
./gradlew :composeApp:testDebugUnitTest --tests '*NativeRestoreFlowTest'
```

The second command is expected to fail on the reviewed source. The tests live in [NativeRestoreFlowTest.kt](../../composeApp/src/commonTest/kotlin/com/kodraliu/localrock/shared/validation/NativeRestoreFlowTest.kt). No credentials or robot access are required.

On macOS with the project prerequisites:

```bash
./gradlew :composeApp:compileKotlinIosArm64 :composeApp:compileKotlinIosSimulatorArm64
```

For the server checks, obtain the server source at `1927b4307d2ee6ea6771773ecb80cddd549eee64` and pass that checkout directory to `check_server_reconciliation.py --source`. The runner needs the original `tests/test_native_schedule_sync.py` and bundled backend shared modules. The runner and result files are included in the report commit following the validation commit. It runs 11 existing store tests and 3 additional interaction checks; it does not claim to run the complete server test suite.

## Copy-and-paste handoff

```text
Objective: review LocalRock #37's new native timer restore without real hardware.
Reviewed LocalRock source: DonSidro/LocalRock#37, virtitnerd:feat/schedule-rework,
f964cadd0beb0dd2ab39be9d21a59468e99c10b6 (head rechecked after testing).
Server reference: Python-roborock/local_roborock_server#179,
1927b4307d2ee6ea6771773ecb80cddd549eee64.
Validation repo/branch: pponce/LocalRock,
review/schedule-restore-validation-20261114.
Test commit: 865c6b9b40b73d920c52712d62fab93a7bdafb23.
Completed: code review, 387 existing app tests, Android debug build,
iPhone/simulator shared compilation, 22 restore probes, 14 server checks.
Results: baseline/build/compilation/server checks passed; restore probes
10 passed and 12 failed across 3 groups: partial restore/state recovery,
stale/deleted/shared-reference jobs, and malformed/unsupported inputs.
No production source changes, real-device writes, deployment, or PR comment.
Next: share findings in #37; agree fixes, add regression coverage, and rerun.
Keep the server #179 merge/release prerequisite before LocalRock #37.
Server #214 remains a separate dashboard integration, not a client prerequisite.
Hardware validation remains outstanding: native firmware acceptance, actual due
execution, OEM/Homebridge interoperability, and persistence across reconnects.
Detailed evidence and reproduction steps: local-work/restore-review/REVIEW.md.
```
