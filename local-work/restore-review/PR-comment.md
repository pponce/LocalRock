Thanks @virtitnerd. I reviewed the new restore flow at `f964cadd` and added automated checks against the unchanged production code.

The existing **387 tests passed**, the Android debug build passed, and shared Kotlin code compiled for both iPhone and iOS Simulator. Of **22 additional restore checks, 10 passed and 12 failed**. Those failures cover three groups below, rather than 12 separate bugs. I also ran **14 server store/reconciliation checks**, all passing, against server #179 at `1927b430`.

Normal enabled/disabled whole-home restore worked in the simulation, including the native timer ID, known cleaning/map settings, and dated one-time cron. Existing native timers were not overwritten, and ordinary room schedules and server-only jobs were correctly refused.

There are a few things I think we should address:

1. **A partial restore can enable a schedule that was disabled.** Restore creates the native timer enabled, then sends a separate disable. If creation succeeds but disable fails, the timer stays on. Lost responses also exit without reading back the actual state. The details UI can still label it “Missing,” and another restore attempt refuses the now-existing timer. The server reconciliation checks confirm that observing this native timer on can also enable the saved recurring server job.

   I suggest reusing the partial-save safeguards: read back uncertain writes, report what succeeded and what remains unconfirmed, provide a recovery path without blindly recreating the timer, and check the server job's final state too. There is also a temporary synchronization window between create and disable; a later confirmed off poll corrects that case.

2. **Restore uses the previously loaded server record without checking for changes.** In the tests, deleting or editing the server job after loading the screen still allowed restoration from the old record. Two server jobs sharing the same native timer reference were also not rejected.

   I suggest using the fresh snapshot and unique-reference checks already used by normal schedule edits/toggles, including rechecking after network waits.

3. **Unsupported input needs stricter validation.** These were defensive tests with synthetic payloads: a malformed native response such as `["ok"]` was treated as “Missing”; array-valued room selections bypassed the room guard and became whole-home settings; and an unknown extra setting was silently dropped.

   I suggest refusing malformed or unsupported formats before writing, and preserving extra settings only where they can be restored faithfully. These tests do not establish that a particular robot normally emits those payloads.

I would address the first two before merging and add the input-validation guards alongside them. This remains automated validation; real-device restore, scheduled execution, and OEM/Homebridge interaction still need confirmation. The agreed dependency on server #179 being merged and released remains unchanged.

[Validation run](https://github.com/pponce/LocalRock/actions/runs/43625485455) · [Detailed findings, reproductions and tests](https://github.com/pponce/LocalRock/blob/review/schedule-restore-validation-20261114/local-work/restore-review/REVIEW.md)

The validation run is red because the additional restore checks expose these gaps; the existing test suite and Android build passed.
