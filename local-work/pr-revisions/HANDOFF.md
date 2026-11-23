# LocalRock PR review revisions

## Objective
Address review requests for DonSidro/LocalRock PRs #95, #96 and #97, and independently validate #37's restore changes.

## Repository and branches
Upstream: https://github.com/DonSidro/LocalRock
Fork: https://github.com/pponce/LocalRock
Integrated upstream master: 427d2e6bd61226f4b00c583f84afc86ac1a63348

- #95: `video-support`; candidate `7eaed3e7ca48fdbeafbc87e1e1b18102b920d5e9`; 743 Android tests passed; branch published.
- #96: `furniture-display`; candidate `7a2677524cb7d9ec86d2f45711919c7968269871`; 655 Android tests passed; branch published.
- #97: `map-appearance-layers`; candidate `dc7f170039bd8f662efcb79ec14106c4fd9aa639`; 637 Android tests passed; branch published.
- #37: virtitnerd/LocalRock `feat/schedule-rework` at `d4dac0c246dca894832134b59247e4b2dca17faa`; 726 tests, Android APK, both Kotlin iOS targets and unsigned iOS app build passed. Author branch unchanged.

## Completed implementation
- #95: default-off video preference and server/TURN explanation; existing model and audio-testing gates retained; live HD/SD selector hidden; PR workflow and workflow-only helper removed; current upstream session/photo/map APIs integrated; camera inset uses shared viewport transformations.
- #96: shared new_feature_info capability snapshot preserved and reused; failed reads leave furniture unknown; Robolectric moved to version catalog; furniture drawn in floor layer; single-saved-map limitation explained before entry; workflow removed. Generated catalog PNGs retained pending maintainer's response to provenance clarification. Documentation states their image-generation origin.
- #97: shared map palette, outlined room icons, hit testing and room-cell parser; separate selection layer; label measurement outside viewport changes and placement in draw pass; B01 session fallback; B01 floor bounds regression fixed; room selection remains available; layer sheet retained; zoom percentage badge removed; workflow removed.

## Validation
All four candidates passed both Kotlin iOS target compilation and full unsigned iOS app builds.
Android tests: #95 743; #96 655; #97 637; #37 726 including 22 restore-flow tests.
#96's validation source was 6dc68c698e06cbe5d101319596d25d09530910d3; the final commit only clarifies documentation and a code comment.
No robot commands, home deployments, signed archives, TestFlight uploads or interactive device tests were performed.

## Server dependency
Python-roborock/local_roborock_server #179 merged as 4c7bfcd985dbe9879ba530bf4299809365b48846. It includes 1aaf386fe13de461a7771199a94d2da87ba3d09b, integrating dispatch hardening, durable claims and verified native enablement from the #213 work.
Latest published release at review time is v1.2.1 and predates that merge. Clarify #37's release dependency; do not require a duplicate #213 merge.
#60 and server #213/#214 remain open. No PR comments have been posted.

## Remaining actions
- Post the final clarification comments from chat; none were posted automatically.
- Maintainer decides whether to retain the generated furniture PNGs or use vector silhouettes.
- Release a server version containing #179, then complete #37's merge/release coordination.
- Test on physical robots: native timer restoration and enablement synchronization, plus the revised video/furniture/map interfaces.
- #60 remains a reference until #37 merges; server #214 remains a separate admin integration task.

## Validation evidence
- #37: [PR 37 ios diagnostics](https://github.com/pponce/LocalRock/runs/137417549431); [PR 37 android diagnostics](https://github.com/pponce/LocalRock/runs/137414330052)
- #95: [PR 95 ios diagnostics](https://github.com/pponce/LocalRock/runs/137419612874); [PR 95 android diagnostics](https://github.com/pponce/LocalRock/runs/137415803641)
- #96: [PR 96 ios diagnostics](https://github.com/pponce/LocalRock/runs/137418975765); [PR 96 android diagnostics](https://github.com/pponce/LocalRock/runs/137415586327)
- #97: [PR 97 ios diagnostics](https://github.com/pponce/LocalRock/runs/137422643438); [PR 97 android diagnostics](https://github.com/pponce/LocalRock/runs/137417281463)
