# FitNotes / LiftLog source comparison and adoption plan

Reviewed 13 October 2026. This is a source review and implementation plan, not a runtime verification or a completed feature port.

## Revisions and scope

- FitNotes: `avivajpeyi/fitnotes`, `android-mvp`, `c74088cdee0b34d5bfd4b68df4f7aee2099c4f81`.
- FitNotes `main` was `d825e87`; it does not contain the latest planning work. Use the Android branch as the implementation baseline and preserve any newer local work.
- LiftLog: `LiamMorrow/LiftLog`, `e7004215562994be7e1640172b4d42145ed76a07`.
- Reviewed plan parsing/persistence, logger UI, exercise identity/search, previous-session matching, timer/session models, progression, statistics, import/export, and relevant health integration.
- The Mac working tree and the installed Pixel build were not accessible. Neither application was built or launched for this review.

## Decision

Adopt more than the timer. The highest-value unit is the whole planned-set workflow: editable target, actual result, completion state, next-set selection, rest, comparison with last time, and future progression. Keep Room and Compose and port selected domain logic and tests to Kotlin. Do not replace the Android application or its existing history database with LiftLog's React Native application.

LiftLog is AGPL-3.0. Preserve applicable attribution and notices for adapted code, record source revision and file mappings, and handle the licence requirements for distribution. Check separately sourced datasets/assets independently. This review does not change the project's licence or incorporate upstream code.

## Findings in our current code

### Already implemented: retain and extend

- `data/WorkoutPlans.kt`: saves planning rows independently and creates a workout only through `start()`. Targets are copied into a run, preserving the reusable plan. Transactions guard creation and duplicate active starts.
- `data/PlanSetData.kt`: per-set targets already support rep ranges, optional weight/unit and `restSeconds`. Do not create another plan schema to add these same fields.
- `data/StructuredPlanV2.kt` and `WorkoutPlanParser.kt`: strict structured imports, validation and explicit exercise resolution already exist. Current paste imports accept V2 only; stored older data is retained. Compatibility decisions should respect that existing policy rather than invent another legacy parser.
- Existing FitNotes history/body import, health records, progress photos, backup/restore, and activity logging should be preserved.

### Gaps demonstrated by source

1. **No explicit target-to-result relationship.** `WorkoutSetTarget` and `WorkoutSet` have exercise-parent IDs and independent positions, but no relation identifying which target a result fulfils. `RunSuggestionsUi.kt` shows a list of targets with Use buttons. `WorkoutRepository.saveSet()` appends a result independently. This makes skipped, added, deleted and out-of-order sets ambiguous.
2. **Logger seeds the first target rather than tracking completion.** `ExerciseLogger.kt` uses `runExercise.ordered.firstOrNull()`; its save callback clears editing/focus but does not advance to a next uncompleted target. A multi-weight plan therefore still requires manual selection of subsequent targets.
3. **Rest is stored but not executed.** The existing `restSeconds` value appears in prescription text; the inspected logger does not implement a rest timer.
4. **The whole catalogue is explicitly exported.** `ChatGptHandoff.prepare()` appends all compatible names after the plan instructions. Remove this block and the now-unneeded catalogue filtering calculation from export. Continue checking compatibility during import/save/start.
5. **Alias support is only a function parameter.** `matchPlanExercise()` accepts aliases, but its own comment states there is no stored alias table; the inspected ViewModel passes no aliases. Previous-session matching in `NotebookModels.kt` compares names and categories.
6. **Recorded bodyweight semantics are lost.** `suggestedActualWeight()` maps a bodyweight target to numeric zero, and `WorkoutSet` stores `weightKg` without a load-mode field. Zero added weight is useful but cannot by itself identify a bodyweight movement, assistance, or an unknown load.
7. **History is principally a log rather than a strength-analysis screen.** The Android history implementation inspected does not provide LiftLog's exercise statistics and record computation.
8. **Progression already exists elsewhere in this repository.** `scripts/compute_targets.py` implements double progression and plateau suggestions for the older web/data pipeline. It is not wired into the Android logger. Its `all(...)` check examines available logged sets without requiring the planned set count; do not port that behaviour as proof that a full prescription was achieved.

## Upstream source map

Paths below are relative to LiftLog at the reviewed revision.

| Capability | Upstream sources | Adaptation for FitNotes |
|---|---|---|
| Explicit pending/completed sets | `app/src/models/session-models/recorded-weighted-exercise.ts` (`PotentialSet`, `currentSetIndex`, `withNothingCompleted`, `withWeight`) | Link each performed result to a run target; derive pending/completed UI without converting targets into training |
| Compact set interaction | `app/src/components/presentation/workout/weighted/potential-set-display.tsx`, `potential-set-counter.tsx` | Reimplement in Compose with target, previous result, editable actual values, and explicit completion |
| Next exercise and supersets | `app/src/models/session-models/session.ts`, `session.spec.ts` | Port next-step selection and useful edge-case tests; keep free exercise switching |
| Rest timer | `app/src/models/session-models/rest-timer.ts`, `rest-timer.spec.ts` | Adapt elapsed/pause/resume semantics to Android lifecycle and clocks |
| Rest notification | `app/modules/workout-worker/android/` | Adapt relevant native pieces; remove Expo bridge dependencies and choose notification visibility deliberately |
| Previous-performance carryover | `app/src/services/session-service.ts`, `session-service.spec.ts` | Carry values per set with clear precedence and canonical exercise IDs |
| Double progression | `app/src/models/blueprint-models/index.ts`, `progression-behaviour.spec.ts`, `reps-progression.spec.ts`, `docs/Progression.md` | Port bounded, opt-in suggestions; require complete eligible working sets |
| Load semantics | `app/src/models/blueprint-models/index.ts` (`Resistance`), `recorded-weighted-exercise.ts` | Distinguish external load, bodyweight plus added load, assistance, and reps-only work |
| Search ranking | `app/src/components/presentation/workout-editor/exercise-fuzzy-match.ts` and its tests | Rank suggestions; never use fuzzy ranking to automatically merge history |
| Catalogue metadata | `app/src/services/exercise-catalog.ts`, `app/assets/exercises.json`, `app/assets/exercises/en.json`, `app/assets/sources.txt` | Optional enrichment after provenance/licence review; preserve user names and identity |
| Statistics and records | `app/src/store/stats/calculate-stats.ts`, `personal-records.ts`, associated tests | One Kotlin statistics service for charts, session summaries and AI export |
| Session comparison | `app/src/components/presentation/workout/session-comparison-table.tsx` | Compare like-for-like exercise performance and volume with stated definitions |
| Import validation and versioning | `app/src/models/plan-file.ts`, `app/src/store/program/import-export-effects.ts`, `docs/PlanFileFormat.md` | Strengthen the existing V2 importer and build a single documented schema/examples source |

## Implementation sequence

### 1. Complete the planned-set logger, rest and concise AI export

This is one coherent first delivery, not a redesign of the rest of the application.

- Add an optional relation from a recorded set to its run target, enforcing at most one result per target. Keep extra/unplanned sets possible.
- Store explicit skipped state on the run target or an equivalent run-state record. Skipped is not completed and is not zero reps. Use state derived from the result relation for completion where possible to avoid contradictory duplicated flags.
- Preserve stable run-target identity when reordering. Keep source plan targets and run targets separate.
- Add nullable actual set completion timestamps for new training. Do not manufacture timestamps or target links for historical records whose correspondence is unknown.
- Render one compact table: Set, Previous, Target, Actual, completion control. Show current-set input prominently; keep completed sets editable. Preserve the fast freeform logger for sessions with no plan.
- Prefill the current pending target, then advance after a successful save. Unknown weight remains blank. Preserve deliberate user edits across asynchronous state changes.
- Offer Apply weight to remaining sets, add set, skip, undo completion, and exercise switching. Do not overwrite completed results when applying a weight to pending sets.
- Start the target's rest timer only after successfully recording a new completion, subject to a user preference. Editing an old result must not restart rest. Define and test undo/skip behaviour explicitly.
- Use one persisted timer tied to the active workout: pause/resume, +30 seconds, skip, completion alert, and cancellation when the workout ends. Use a monotonic clock while running, persist enough state for process recreation, and define reboot/clock-change recovery. Do not infer actual rest duration from the prescription.
- Do not start rest after the last required set unless explicitly requested. Allow training to continue before rest ends.
- Remove the catalogue dump from ChatGPT exports. Retain actual history, preferences, schema and explicit instructions to use familiar history names where appropriate. New names are resolved on import.
- Update backup/restore for new fields. Retain existing V2 imports and rest values; a new import version is unnecessary unless required fields/meaning change.

### 2. Exercise identity, aliases and safe merging

This is needed before reliable cross-history progression and charts. LiftLog's name-based matching is not the solution to our duplicate-name problem.

- Maintain canonical exercise IDs and persisted aliases. Reuse these everywhere: search, AI import, history lookup and analytics.
- For original imported history, preserve raw source names and provenance; add mappings to canonical exercises rather than rewriting the original source representation.
- Preview merges: surviving exercise, affected workouts/plans, aliases and conflicting categories/load modes.
- Handle both exercises appearing in one workout/plan; current unique parent/exercise constraints mean a simple foreign-key reassignment will fail. Preserve all actual sets and notes, choose an explicit ordering, and reconcile planning targets visibly.
- Preserve import deduplication, favourites, run snapshots, backups, and source identifiers. Never auto-merge bodyweight/external-load or cardio/strength variants simply because names are similar.
- Port LiftLog's search scorer for candidate ranking and consider equipment/muscle filters after metadata is available.

### 3. Previous performance, load types and progression

- Port per-set carryover and adapt it to canonical IDs and our imported/local history view.
- Keep explicit plan prescriptions authoritative. Any use of history to fill missing values should be labelled and editable; do not silently replace a freshly imported AI prescription.
- Define resistance/load mode so bodyweight, added load, assistance and reps-only work display correctly. Preserve unknown legacy mode rather than guessing.
- Add optional simple double progression per exercise: required working sets all meet the top target -> suggest configured load increase and reset reps; otherwise hold. Exclude warm-ups, skipped sets and incomplete prescriptions from successful completion.
- Do not make changing set counts or renaming an exercise unexpectedly sever all performance history. Distinguish movement identity from compatibility of a programmed set scheme.
- Use our existing Python rules as a requirements reference and LiftLog's domain logic/tests as implementation references. Avoid auto-deloading or silently altering plans.
- Record a short explanation with each suggestion; e.g. completed all three prescribed sets at the upper target.
- Add supersets with explicit group/order and rest behaviour after next-set navigation is reliable. Version the import format only if external superset fields are introduced.

### 4. Statistics and a useful completion summary

- Build pure, independently testable Kotlin calculations over canonical completed records.
- Include exercise volume, heaviest set, reps at a given weight, estimated 1RM where appropriate, weekly completed workouts/sets and comparison with the previous comparable session.
- Separate weighted and reps-only/cardio measures. Unknown bodyweight must not silently become zero for inferred total-load statistics. Keep external-load volume and bodyweight-based measures clearly defined.
- Label estimated 1RM as an estimate; decide supported exercise/load modes and rep ranges rather than applying it to every activity.
- Show missing dates as missing, not zero training load unless that is the defined metric. Include multiple sessions on the same day.
- Decide first-observation record semantics explicitly; LiftLog avoids marking every first logged exercise as a new record.
- Reuse the same calculations for chart data and compact AI summaries, with raw results available when desired. Body/photo/heart-rate sharing remains an explicit selection.

## Features to retain rather than replace

- Existing Health Connect reads and Garmin session/sample association: inspected LiftLog Android code writes workouts and bodyweight, not the heart-rate read workflow we need.
- Existing original FitNotes history/body import: LiftLog's CSV adapter skips cardio-only rows and groups by date; it is not a richer replacement.
- Existing photo storage and comparisons.
- Existing Room persistence and source-preserving imports.
- Existing manual ChatGPT handoff: LiftLog's integrated AI backend is a separate system, not a ChatGPT account connection.
- Existing backup approach: LiftLog remote uploads are snapshots, not two-way record/plan sync. Treat future synchronisation as a separate design.

## Porting boundaries and quality checks

- Copy/adapt focused functions and meaningful tests, not the React Native app, Redux stores, Expo runtime, social feed or payment/backend infrastructure.
- Keep a source/target mapping in third-party notices for actual ports; do not claim an upstream algorithm was ported if the implementation was independent.
- Do not adopt known unsuitable details blindly: name-based identity, zero defaults for unknown load, or cross-exercise/bodyweight calculations without declared semantics.
- Verify migrations and full backup round trips against existing databases before installation. No destructive database reset.
- Test differently weighted target sets, skips, added sets, out-of-order completion, editing/deleting a completed set, repeated save taps, no-plan sessions, plan reuse and deleted source plans.
- Test timer pause/resume, background/process recreation, stale notification actions and ending a session.
- Test alias resolution and merging when both names exist in the same workout, imported category collisions, and reimport of previously mapped source data.
- Test progression on partial sessions and charts after a merge; unknown timings and measurements remain unknown.
- Capture before/after plan and logger screens on a small phone and with large fonts/keyboard open. Build and install using the existing application ID and signing setup so real data survives updates.

## Practical delivery order

1. Target/result linking + compact logger + rest + remove catalogue export.
2. Canonical aliases/merges + search improvements.
3. Per-set carryover + load types + optional progression + supersets.
4. Shared statistics + progress charts + completion summary + compact AI context.

This expands the scope of reuse substantially while making each delivery usable and protecting the app's existing health and history work.
