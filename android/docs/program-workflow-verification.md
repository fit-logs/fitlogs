# Program workflow verification

## Coach and flexible queue — Room 17 / backup v15

The implementation reuses existing program definitions, owned day recipes,
per-set target models, exercise identity review, local load advice, the actual
workout logger, activity imports and full backup/restore. Occurrence order/date/
status and prescription references extend `program_steps`; running targets extend
`planned_workouts`. No additional scheduling tables or network services exist.

[Product behavior, initial audit, contract and scope](coach-and-flexible-queue.md).

### Main integration — 2026-10-28

Merged main through `8195d2b` (workout logging simplification and version 1.1.5)
into the Coach branch. The shared date chooser remains the entry for empty and
past workouts; program starts still use the selected occurrence directly. Train
prioritizes the active workout, and reusable-template pickers exclude both program
day recipes and occurrence copies. The compact plan-actions menu and cached
history projection from main are retained.

After resolving the overlapping UI changes, `testDebugUnitTest`, `assembleDebug`
and `lintDebug` pass: **385 tests, zero failures/errors, five optional skips;
zero lint errors and 44 warnings**. The isolated preview and instrumentation
packages also build successfully.

All **13 selected emulator UI tests pass** across `CoachInteractionTest`,
`ProgramInteractionTest`, `WorkoutEntryInteractionTest` and
`PlanLifecycleInteractionTest`, covering the combined queue, logging, date chooser,
template and history behavior. An initial run was interrupted by an emulator
System UI ANR; after dismissing that system dialog, the complete selection passed
without app changes. Both retained APK artifacts were refreshed from the merge.

### Verified results

- Full `testDebugUnitTest`: **385 tests, zero failures/errors, five existing
  optional skips**. New parser/prompt/Room regressions cover real-history privacy,
  single/mixed/running imports, unspecified versus zero load, explicit resolution,
  atomic rollback (including new exercise creation), queue moves, skip/restore,
  occurrence edits, dates, non-first starts, idempotency, actual completion,
  explicit timing completion, discard/inactivity, deletion, database reopening,
  legacy semantics, migration and backup/analysis separation.
- Normal `assembleDebug` and isolated preview APK builds succeed. `lintDebug`
  reports **zero errors, 44 warnings** in existing dependencies/resources/style.
- The combined Android suite runs 16 cases across Coach, program/template
  management, program import, local load advice, request recreation, legacy saved
  plan files and offline catalog installation. **15 execute successfully; the
  opt-in live catalog network case is skipped.**
- A further running UI case passes: invalid `NaN` input is rejected without a
  crash/write, a running occurrence changes from 5 km/30 min to 6.5 km/35 min,
  and explicit linking retains the actual run's 4.9 km/30 min values. Recorded
  duration is labelled separately from moving/elapsed time.
- The three core Coach UI cases also pass with networking disabled at
  **360×640 dp, dark mode, 150% system fonts**. They cover one-tap copy/paste,
  no clipboard reads before Paste, advice without writes, five mixed sessions,
  postponing, skipping/restoring, dates, editing/replacing an exercise, rotation,
  actual set logging, linked completion and backup round-trip. Screenshots were
  inspected; queue settings are compact, controls scroll with the keyboard, and
  navigation labels fit enlarged text.
- An additional explicit **force-stop/restart** case passes. It compares persisted
  program/occurrence IDs, order, prescription copies, actual links, workouts,
  actual sets and target snapshots while Lower Body is active out of order.
  Resume and retried Start return the same workout, preserving the earlier
  pending Upper Body and postponed run. **17 distinct UI cases execute and pass**
  across these selections, excluding the intentional live-network skip.

All emulator fixtures use `com.example.fitnotes.preview.photos`, the repository's
existing separate preview application ID. The personal app/database and other
working checkouts were not modified. Physical-device verification is unperformed.

### Main changed code

| Area | Files / responsibility |
| --- | --- |
| Coach | [CoachScreen](../app/src/main/java/com/example/fitnotes/ui/CoachScreen.kt), [CoachPrompt](../app/src/main/java/com/example/fitnotes/data/CoachPrompt.kt), [CoachResponse](../app/src/main/java/com/example/fitnotes/data/CoachResponse.kt): request, safe completed context, clipboard gestures, advice and common JSON |
| Queue | [TrainingPrograms](../app/src/main/java/com/example/fitnotes/data/TrainingPrograms.kt), [TrainingProgramsUi](../app/src/main/java/com/example/fitnotes/ui/TrainingProgramsUi.kt), [TrainScreen](../app/src/main/java/com/example/fitnotes/ui/TrainScreen.kt): stable occurrences, order/date/status, isolated edits and Up Next |
| Integration | [FitNotesViewModel](../app/src/main/java/com/example/fitnotes/FitNotesViewModel.kt), [FitNotesApp](../app/src/main/java/com/example/fitnotes/ui/FitNotesApp.kt): navigation, background preparation/import, weight confirmation and existing editors |
| Imports / running | [PortableProgram](../app/src/main/java/com/example/fitnotes/data/PortableProgram.kt), [ProgramImportRepository](../app/src/main/java/com/example/fitnotes/data/ProgramImportRepository.kt), [ProgramImportUi](../app/src/main/java/com/example/fitnotes/ui/ProgramImportUi.kt), [RunningPlanUi](../app/src/main/java/com/example/fitnotes/ui/RunningPlanUi.kt): one atomic preview/import path and running prescriptions |
| Actuals | [WorkoutRepository](../app/src/main/java/com/example/fitnotes/data/WorkoutRepository.kt), [WorkoutLifecycle](../app/src/main/java/com/example/fitnotes/data/WorkoutLifecycle.kt), [WorkoutPlans](../app/src/main/java/com/example/fitnotes/data/WorkoutPlans.kt): explicit association, snapshots, finish/discard/timing, template ownership |
| Persistence | [CoachMigration](../app/src/main/java/com/example/fitnotes/data/CoachMigration.kt), schema 17, FitDatabase, BackupCodec, ProgramBackupJson, PlanBackupJson and local backup adapter: non-destructive migration and v15 round-trip |
| Compatibility | Legacy plan-file parsing/export remains supported via WorkoutPlanParser/WorkoutPlanFormat; ChatGptHandoff now provides the clean response instructions |

### Tests and screenshots

- [Queue / migration / backup regressions](../app/src/test/java/com/example/fitnotes/CoachQueueTest.kt)
- [Response contract regressions](../app/src/test/java/com/example/fitnotes/CoachResponseTest.kt)
- [Completed-context and privacy regressions](../app/src/test/java/com/example/fitnotes/CoachPromptTest.kt)
- [Coach UI and force-stop regressions](../app/src/androidTest/java/com/example/fitnotes/CoachInteractionTest.kt)
- [Coach, light](images/coach/request-light.png), [import preview](images/coach/preview-light.png), [Up Next, light](images/coach/up-next-upper-light.png)
- [Coach at 150% fonts](images/coach/request-small-dark-large.png), [Up Next at 150%](images/coach/up-next-upper-small-dark-large.png), [ordered queue at 150%](images/coach/queue-small-dark-large.png)

### Reproduction

Use the Java/SDK environment described in the Android README. From `android/`:

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug
./gradlew -PphotoVerification=true assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.example.fitnotes.CoachInteractionTest com.example.fitnotes.preview.photos.test/androidx.test.runner.AndroidJUnitRunner
```

Coach UI's queue-menu case leaves the synthetic active non-first session and
expected backup for the explicit restart check:

```sh
adb shell am instrument -w -e class com.example.fitnotes.CoachInteractionTest#queueMenusMoveSkipRestoreAndValidateDates com.example.fitnotes.preview.photos.test/androidx.test.runner.AndroidJUnitRunner
adb shell am force-stop com.example.fitnotes.preview.photos
adb shell am instrument -w -e verifyCoachRestart true -e class com.example.fitnotes.CoachRestartTest com.example.fitnotes.preview.photos.test/androidx.test.runner.AndroidJUnitRunner
```

For compact accessibility checks, set emulator size 720×1280, density 320, font
scale 1.5 and night mode before the core UI cases. Restore settings afterwards.
Run offline catalog cases with emulator networking disabled. The preview APK is
also retained in `android/build/coach/fitlogs-coach-preview.apk` (not tracked).

### Remaining scope

Intervals are editable text; reordering uses accessible move controls. Running
completion links completed local/Health Connect/Strava activity records; older
cardio sets within mixed FitNotes CSV workouts are included in context but are
not link candidates. Existing programs explicitly opt into the queue. There is
no drag gesture or recovery/readiness scoring in this slice.

## Earlier program workflow delivery

The following records describe the earlier cursor-based workflow and its file
compatibility foundations; the Coach/queue behavior above supersedes its UI and
completion instructions for newly created flexible programs.

| Step | Delivered behavior | Change |
| --- | --- | --- |
| Train | One active multi-day program, explicit next day, separate reusable templates | [PR #21](https://github.com/avivajpeyi/fitnotes/pull/21) |
| Import | Whole-program JSON/text, editable preview, explicit exercise matching, file/share and PDF/URL conversion fallbacks | [PR #22](https://github.com/avivajpeyi/fitnotes/pull/22) |
| Personal targets | Offline actual-history engine, setup isolation, Use/Change, actual effort and session locks | [PR #23](https://github.com/avivajpeyi/fitnotes/pull/23) |
| Public library | Eight original versioned definitions, three offline starters, cache and safe installation | [PR #24](https://github.com/avivajpeyi/fitnotes/pull/24) |

The earlier Android baseline reconciliation is in
[PR #14](https://github.com/avivajpeyi/fitnotes/pull/14). Work was isolated from the
original dirty checkout and synthetic emulator fixtures use the separate preview
application ID. No personal-app clearing or physical-device result is claimed.

## End-to-end synthetic regression

[`ProgramWorkflowTest`](../app/src/test/java/com/example/fitnotes/ProgramWorkflowTest.kt)
exercises the real Room repositories through the complete workflow:

1. Parse a synthetic private conversion with two ordered days. “Press” has two
   possible aliases, so saving fails until the user explicitly selects Bench Press.
2. Save one three-week program with two owned days and no performed sets added.
3. Review history of 80 kg × 6 × 3 with reported RIR 2 on two distinct dates.
   For 8–10-rep targets at RIR 2, the reviewed proposal is 75 kg.
4. Use the targets, start the next day, lock the session and log actual
   75 kg × 10 × 3 at reported RIR 3. Retried completion adds no duplicate actuals.
   Finishing alone never advances the program; explicit day completion does.
5. Repeat the next prescribed day on another date, then review the following
   session. Two full upper-range sessions with headroom propose one step to
   77.5 kg. Applying it preserves all earlier actuals and target snapshots.
6. Round-trip the full backup and refresh the separate public catalog. Actual
   history, locks, program position and the next suggestion remain intact.

Fixture dates are adjusted through the supported retrospective timing editor;
the test does not wait multiple days or imply a real training outcome. The
conversion fixture contains original synthetic text, not a copied routine.

## Request lifecycle regression

Import request production and consumption now share the ViewModel's lifetime.
Previously, Compose saved a consumed counter while a recreated model restarted
its counter at zero. A new first request could reach the model without opening
its preview. The UI regression reproduced this failure before the fix.

[`ProgramWorkflowInteractionTest`](../app/src/androidTest/java/com/example/fitnotes/ProgramWorkflowInteractionTest.kt)
checks that rotation does not replay a closed import, a fresh model with retained
UI state accepts a new conversion, and another subsequent request also opens.
The fresh-model boundary is simulated by clearing only the isolated test
activity's model store before recreation. Training data must remain unchanged.
Existing actual Android text/URL/file-share regressions run alongside it.

## Public hosting and offline boundaries

The [live library](https://avivajpeyi.github.io/fitnotes/library/) was published by
[Pages run 39928558542](https://github.com/avivajpeyi/fitnotes/actions/runs/39928558542).
Live checks verified HTTP 200 for HTML, the manifest, shared schema and all eight
program files; JSON MIME and all program SHA-256 digests matched source. Pages
returned ETags and `Cache-Control: max-age=600`; the app requests revalidation.

The opt-in emulator network test fetches the actual public manifest and an
unbundled program using Android's real HTTPS transport, then reopens it through
a repository that rejects network access. A separate force-stop test runs with
Wi-Fi/mobile data off and checks the downloaded version plus unchanged training.
Normal automated runs skip this live test unless `allowCatalogNetwork=true`.
The paired offline restart check is explicitly selected with
`verifyDownloadedCatalogRestart=true` after the download fixture exists.

All numeric advice remains local. Public requests contain only fixed manifest
and version-file paths. Installed programs are editable local copies, not live
catalog references. Room 15, backup v13 and analysis v6 remain unchanged by this
verification/lifecycle fix. Physical-device network checks are not substituted
by the isolated emulator results.

Strava API connection/incremental sync remains separate in issue #7; the merged
owner-export route and its API-policy limitations are documented in
[Strava owner export](strava-owner-export.md).

## Complete changed-file manifest

Relative to implementation base `c784a65`:

```text
android/README.md
android/app/schemas/com.example.fitnotes.data.FitDatabase/17.json
android/app/src/androidTest/java/com/example/fitnotes/CatalogInteractionTest.kt
android/app/src/androidTest/java/com/example/fitnotes/CoachInteractionTest.kt
android/app/src/androidTest/java/com/example/fitnotes/LoadAdviceInteractionTest.kt
android/app/src/androidTest/java/com/example/fitnotes/PlanLifecycleInteractionTest.kt
android/app/src/androidTest/java/com/example/fitnotes/ProgramImportInteractionTest.kt
android/app/src/androidTest/java/com/example/fitnotes/ProgramInteractionTest.kt
android/app/src/main/java/com/example/fitnotes/FitNotesViewModel.kt
android/app/src/main/java/com/example/fitnotes/data/BackupCodec.kt
android/app/src/main/java/com/example/fitnotes/data/ChatGptHandoff.kt
android/app/src/main/java/com/example/fitnotes/data/CoachMigration.kt
android/app/src/main/java/com/example/fitnotes/data/CoachPrompt.kt
android/app/src/main/java/com/example/fitnotes/data/CoachResponse.kt
android/app/src/main/java/com/example/fitnotes/data/FitDatabase.kt
android/app/src/main/java/com/example/fitnotes/data/MigratedBackupFields.kt
android/app/src/main/java/com/example/fitnotes/data/PlanBackupJson.kt
android/app/src/main/java/com/example/fitnotes/data/PortableProgram.kt
android/app/src/main/java/com/example/fitnotes/data/ProgramBackupJson.kt
android/app/src/main/java/com/example/fitnotes/data/ProgramImportRepository.kt
android/app/src/main/java/com/example/fitnotes/data/TrainingPrograms.kt
android/app/src/main/java/com/example/fitnotes/data/WorkoutLifecycle.kt
android/app/src/main/java/com/example/fitnotes/data/WorkoutPlanParser.kt
android/app/src/main/java/com/example/fitnotes/data/WorkoutPlans.kt
android/app/src/main/java/com/example/fitnotes/data/WorkoutRepository.kt
android/app/src/main/java/com/example/fitnotes/ui/CoachScreen.kt
android/app/src/main/java/com/example/fitnotes/ui/FitNotesApp.kt
android/app/src/main/java/com/example/fitnotes/ui/ProgramImportUi.kt
android/app/src/main/java/com/example/fitnotes/ui/RunningPlanUi.kt
android/app/src/main/java/com/example/fitnotes/ui/TrainScreen.kt
android/app/src/main/java/com/example/fitnotes/ui/TrainingProgramsUi.kt
android/app/src/main/java/com/example/fitnotes/ui/WorkoutPlanUi.kt
android/app/src/test/java/com/example/fitnotes/ActivityMigrationTest.kt
android/app/src/test/java/com/example/fitnotes/ActivitySummaryTest.kt
android/app/src/test/java/com/example/fitnotes/BodyPersistenceTest.kt
android/app/src/test/java/com/example/fitnotes/ChatGptHandoffTest.kt
android/app/src/test/java/com/example/fitnotes/CoachPromptTest.kt
android/app/src/test/java/com/example/fitnotes/CoachQueueTest.kt
android/app/src/test/java/com/example/fitnotes/CoachResponseTest.kt
android/app/src/test/java/com/example/fitnotes/HistoryImportTest.kt
android/app/src/test/java/com/example/fitnotes/LoadAdvicePersistenceTest.kt
android/app/src/test/java/com/example/fitnotes/NotebookBehaviorTest.kt
android/app/src/test/java/com/example/fitnotes/PerSetPlanTest.kt
android/app/src/test/java/com/example/fitnotes/PortableProgramTest.kt
android/app/src/test/java/com/example/fitnotes/PrivateArchiveRepairTest.kt
android/app/src/test/java/com/example/fitnotes/RunningMigrationTest.kt
android/app/src/test/java/com/example/fitnotes/RunningPrivateFixtureTest.kt
android/app/src/test/java/com/example/fitnotes/StravaOwnerExportTest.kt
android/app/src/test/java/com/example/fitnotes/SystemBackupTest.kt
android/app/src/test/java/com/example/fitnotes/TrainingProgramTest.kt
android/app/src/test/java/com/example/fitnotes/TrainingWorkflowTest.kt
android/app/src/test/java/com/example/fitnotes/UnifiedMigrationTest.kt
android/app/src/test/java/com/example/fitnotes/WorkoutLifecycleTest.kt
android/app/src/test/java/com/example/fitnotes/WorkoutPlanParserTest.kt
android/app/src/test/java/com/example/fitnotes/WorkoutPlanPersistenceTest.kt
android/docs/chatgpt-handoff-verification.md
android/docs/chatgpt-handoff.md
android/docs/coach-and-flexible-queue.md
android/docs/images/coach/preview-light.png
android/docs/images/coach/queue-small-dark-large.png
android/docs/images/coach/request-light.png
android/docs/images/coach/request-small-dark-large.png
android/docs/images/coach/up-next-upper-light.png
android/docs/images/coach/up-next-upper-small-dark-large.png
android/docs/program-workflow-verification.md
android/docs/programs-and-templates.md
```
