# Progress and exercise usability — 14 October 2026

Work began on the existing dirty `android-mvp` tree at `bda08f0`. Existing
notebook, logger, photos, plans, import repair and health work was preserved.

- Progress selection is a nearly full-height modal sheet with pinned search,
  Recent and alphabetical sections. Only completed strength exercises qualify.
  Exact, prefix and token matching searches explicit aliases once per canonical
  exercise. Adding exercises still uses the complete catalogue.
- Progress preferences survive navigation/restart. Calendar-spaced points retain
  separate sessions, and unavailable metrics break the line. Rounded axes cover
  empty, singleton, constant and genuinely recorded zero-load cases.
- Heaviest sets and rep summaries include actual loads/reps. Latest summaries
  retain same-day date-only sessions rather than inventing their within-day order.
  Recent results group consecutive equivalent loads only and expand on overflow.
  Point details offer every session on that date and View workout; Back restores
  selection and list state. Assistance has no heaviest-load/1RM metric; mixed
  added-load and total-load history cannot produce a misleading weight comparison.
- Exercise merge selects source then destination, previews affected records and
  blocks incompatible semantics/settings. It saves a recovery backup first.
  Destination IDs remain stable; aliases, source mappings and redirects preserve
  import identity. Every occurrence, target, result, note and grouping stays ordered.
- Room 9 → 10 changes two indexes only, allowing separate ordered occurrences
  of one canonical exercise in a workout/plan. No destructive fallback is used.
- Deleted sets offer Undo; values/position/result identity and any still-valid
  target association are restored without restarting rest. Completed workouts
  become editable plans with actual results and only known prescribed rest.
- Import outcomes report actual added, updated and already-imported workout counts.
- Current FitNotes plan files open through Android content/file URIs into a
  validated preview. Export and pasted/opened import use `WORKOUT_PLAN_V2`.
  Repeat intent delivery and save fingerprints prevent accidental duplicate saves.
  No file opening/saving starts training. Empty target plans require targets before
  export. Current backup JSON v7 restores; older file adapters have been removed.
  Stored guidance and installed-data migrations remain to preserve current records.

## Upstream review

Reviewed public LiftLog main at **9fa31d4bc256fbdc5ad90a6df1259d9d31b4e773**.
Confirmed and read:

- `docs/PlanFileFormat.md`
- `docs/schemas/program-blueprint/ProgramBlueprint.json`
- `app/src/models/plan-file.ts`
- `docs/WorkoutWorker.md`
- `app/modules/workout-worker/android/src/main/java/expo/modules/workoutworker/WorkoutWorkerService.kt`
- `.../utils/RepeatingTimerAction.kt`

Adopted the validated preview/file-opening pattern and one-format source/example
pattern. The existing Room/elapsed-clock rest controller already covers the native
lifecycle responsibilities; no Redux/React Native store, backend, foreign plan
format or upstream migration framework was added. This is a patterns review;
no new upstream code was copied.

## Evidence and limits

The earlier private investigation demonstrated duplicate native imports sharing
source identities. The genuine 38 BPM source sample remains; this task does not
reclassify it or claim a new heart-rate diagnosis. See
[the notebook investigation](notebook-cleanup-verification.md).

The submitted new specification ended at “Explicit” in the round-trip fixture
requirements. Complete requirements above were implemented; the fixture includes
varying targets, rep ranges, unspecified values, explicit bodyweight/assistance,
rest and warm-up flags. No unseen requirements are claimed complete.

Validation:

- Normal `com.example.fitnotes` debug APK assembled successfully. Existing signing
  certificate SHA-256 is unchanged:
  `7ab64af6c9ab261ba03a9471b6a3adc59fa33d684d5d1c4ef1f06a6bc693ddce`.
- 151 unit tests: 150 passed, one optional external native-backup fixture skipped.
  The authorized private Room 9 snapshot was exercised separately in this run;
  every stored row survived the 9 → 10 migration. Recovery/restore and import
  identity tests also passed using that copy.
- Lint: 0 errors, 47 existing warnings. `git diff --check` passed.
- Three emulator tests passed in light mode at normal text. Five passed at 150%
  system text in dark mode. Pixel 6 dimensions were 1080 × 2400 at 420 dpi.
  Scenarios cover alias search, keyboard layouts, overlapping same-day records,
  result/workout navigation and recreation, content-URI validation/repeated
  delivery, opened-plan save without training, all chart edge cases, freeform
  repeated-set logging/editing, plans, pending/completed targets, rest, imported
  date-only history and sparse/gapped heart rate including 38 BPM.
- Android resolved `ACTION_VIEW` for a current plan content URI to MainActivity
  and a cold launch successfully opened it. The in-process content-URI test
  verifies parser/preview/save without opening extra test Activities.

Screenshots use synthetic data only and stay ignored outside Git:

- [Before this Progress change](../captures/progress-20261014/before/progress-previous-cleanup.png)
  — preserved from the preceding cleanup capture, 13 October.
- [After: Progress](../captures/progress-20261014/after/progress-light-final-screen.png)
- [After: searchable picker](../captures/progress-20261014/after/progress-light-final-picker.png)
- [After: large-text dark picker](../captures/progress-20261014/after/progress-dark-large-picker.png)
- [After: large-text keyboard](../captures/progress-20261014/after/progress-dark-large-picker-keyboard.png)
- [After: same-day sessions](../captures/progress-20261014/after/progress-light-final-same-day-details.png)

The final two small refinements (independent plan-row focus and export limits)
were verified by compilation and data tests after the UI capture runs; no new
layout was introduced.

## Physical phone update

Updated the connected Pixel 6 over Wi-Fi with `adb install -r` on 14 October,
01:49 NZDT. Application ID/version/signing identity stayed unchanged. APK SHA-256:
`0dde15ab28da8a391edd4c3c9634da74f81060fee9f992cb40abf5eb41c360d3`.

The installed APK and a verified Room 9 recovery snapshot were saved privately
before installation. After launch, Room reported schema 10 and SQLite integrity
passed. Full row-by-row comparison found **no changed application tables**:
workouts/results, exercise identities, imported source records, saved plans/targets,
measurements, photo metadata and health associations/samples were identical.
The phone was never cleared, uninstalled or populated with synthetic fixtures.

Private recovery evidence is under the previously authorized ignored
`captures/notebook-cleanup-20261013/private/progress-pre-update-final-20261014/`
and `.../progress-after-update-20261014/preservation.json`. No private records are
included in this report or Git. No commit, push or merge was performed.

## Logger History graph — 15 October 2026

The exercise logger’s History tab now shows a progress graph above its existing
session tables. Range/metric controls use the same completed-result calculations
as Progress. Both screens share the session plotting and point-detail component;
points retain actual reps and open their recorded workout. The active workout and
pending targets do not contribute to this previous-session graph. Cardio-only
history retains its existing tables without an irrelevant strength chart.

Normal application build succeeded; 148 unit tests passed and three optional
external/private fixtures were skipped. Lint reported zero errors and 47 existing
warnings. Focused logger-history and Progress navigation UI checks passed on the
1080 × 2400 dark-mode emulator. The logger check also verified actual results and
pending targets were unchanged. An initial emulator System UI crash required a
restart; a fixture assertion was corrected to account for its already-recorded
active set. Neither required an app data change.

[Logger History graph preview](../captures/logger-history-20261015/history-graph.png)
uses synthetic data. The normal `com.example.fitnotes` APK is built. The physical
phone was unavailable through ADB/Wi-Fi, so this follow-up is not installed there.
