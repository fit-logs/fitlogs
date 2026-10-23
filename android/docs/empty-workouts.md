# Unplanned workouts

- **Train → Start empty workout** immediately starts a strength session and opens
  exercise selection. No name, plan, notes or effort are required.
- Search switches to **All** exercises. Selecting one exercise and confirming
  opens its logger; multiple selections return to the workout overview.
- Back from selection keeps the active empty workout. **Add exercise** remains
  available. Previous results fill editable drafts; only **Save set** records a set.
- Train shows **Resume**, **Finish** and **Discard** for the active workout.
  Starting another empty workout or a different plan offers Resume, Finish and
  start, Discard and start, or Cancel. Discard requires a separate confirmation.
- Empty strength sessions can be discarded but cannot be finished. Finishing a
  recorded workout permits another session on the same day.
- Completed strength workouts offer **More actions → Use as plan**. This opens a
  reviewable draft; creating the reusable plan requires **Save plan**.
- Other activity entry remains under **Train → Settings → Log another activity**.

The existing Room session store is retained. Resolving an old session and starting
its replacement happens in one transaction. A missing or invalid new plan rolls
back both actions. Confirmation targets a specific session ID, so an old dialog
cannot discard a newer workout. No schema or backup-format change is needed.

## Verification — 23 October 2026

- Normal debug build: passed.
- Unit tests: **131 passed, one optional private-fixture test skipped**.
- Lint: **zero errors, 37 warnings**.
- Eight isolated emulator checks: the full workout flow, explicit force-stop and
  reopen, active-session choices, and exercise reordering, each at standard
  text/light theme and 150% text/dark theme.
- Checks include keyboard-open logging, repeated start/save taps, single and
  multiple exercise selection, result editing, two sessions on one day,
  draft-only previous results, explicit plan preview, Cancel/Resume/Finish/Discard,
  unchanged saved plans, and retained timestamps/order/sets after restart.
- No personal app data or physical phone was changed. Screenshots use synthetic
  records and remain locally in ignored `android/captures/issues-20261023/`.

From `android/`, run the normal build, unit tests and lint:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug \
  --offline --console=plain
```

For emulator checks, build/install the isolated application and test APKs with
`-PphotoVerification=true`. Run the flow method
`EmptyWorkoutInteractionTest#startAddLogEditFinishAndStartAgainWithSuggestionsKeptAsDrafts`
first. Force-stop `com.example.fitnotes.preview.photos`, then run
`EmptyWorkoutRestartTest`. Finally run
`EmptyWorkoutInteractionTest#activeSessionChoicesCancelResumeFinishAndDiscardWithoutChangingSavedPlans`
and `ExerciseOrderInteractionTest`. Test fixture writes require the isolated
package. Change the emulator's font scale/night mode to repeat the two appearances.
