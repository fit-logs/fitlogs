# Forgotten workouts and retrospective entry

## Forgotten strength sessions

- Strength workouts are checked before the active-session UI is exposed, on
  foreground/resume and about once a minute while the app is open. No persistent
  background timer/service is required. Runs, hikes and other sports are not capped.
- Defaults: two hours without a meaningful saved action, or six hours in the
  current active segment. **Keep running for 6 hours** explicitly pauses these
  checks for that workout. Opening a screen alone is not a saved action.
- Actual set saves, edits, exercise changes and saved notes record activity time.
  Existing sets never receive invented historical timestamps.
- With a reliable saved-action timestamp, an automatic end is estimated from that
  action plus up to 15 minutes, bounded by now/the elapsed safeguard. It never
  precedes a known saved action. The estimate stays labelled and editable.
- Legacy sets without reliable activity timestamps keep an unknown end/duration.
  The workout is finished, but no 163-hour duration or exact end is fabricated.
- An abandoned empty workout becomes a recoverable **past workout draft**. Its
  exercises/notes stay available, while History/progress gain no performed workout.
- The Train notice offers **Edit time / duration**, **Undo / Resume** and Dismiss.
  Resume keeps the same workout/sets and starts a fresh segment; the unlogged gap
  is excluded. An unknown earlier duration stays unknown instead of becoming zero.
  A confirmed timing correction persists and is not replaced on another app open.
- Wall clocks can change across device settings/reboots. Future recorded actions
  or a future start hold automatic repair for review. Automatic timing is always
  estimated/unknown; it is not a measured training duration. Timezone changes do
  not rewrite stored instants. Original per-set timestamps survive timing edits.

## Log yesterday's workout

1. Open **Train → Start workout** and choose the workout date. For a completed
   workout from earlier today, choose **Done**.
2. Start empty, copy a recent performed workout, or copy a saved routine directly.
   Copying retains exercise order and editable references, never completed sets.
3. Choose **Continue**, then use the exercise picker and numeric set logger. Save only the sets
   you actually performed, then tap **Save past workout**.

From a workout overview, browse to the desired previous date and tap **Log
workout for this day**. The setup form uses that date instead of yesterday.
Empty days stay open, and days with an existing session allow another entry.
Opening or cancelling setup does not create a draft. The selected setup date
survives activity recreation.

Train has one **Start workout** action. With today's date and **Now** selected,
**Start now** starts a live session and opens exercise selection. **Done** for
today or an earlier date switches the same form to completed-workout entry.
Future dates are disabled. **Resume workout**
opens the current live session directly. When a live session is already running,
past entry leaves it alone; starting another live session still offers the
existing Resume/Finish/Discard choices. History has no duplicate entry button.

A retrospective draft does not start a live timer or replace another active
workout. Navigating away or restarting retains it in **Past workout drafts**.
Draft sets stay outside History, training context, analysis JSON and progress
until **Save past workout**; complete backups retain unsaved drafts for recovery.
Discard is explicit. Empty drafts cannot become completed performed sessions.

Optional timing accepts start, end or duration independently. Unknown values stay
unknown; an end-only time does not create a midnight start. Known start plus
reported duration can determine an end. Overnight sessions use an explicit end
date. Conflicting fields or ambiguous/nonexistent daylight-saving local times
need correction; an explicit offset such as `02:30+13:00` resolves a repeated time.

History retains **Edit time / duration**. Local corrections take precedence over
source presentation; attached/imported provider timing remains separate. Actual
sets, workout IDs, notes and completed source records are preserved.

## Persistence and verification

Room 11 → 12 adds lifecycle/draft metadata without replacing records. Backup v10
retains this metadata and drafts; older backups stay readable. Analysis JSON v5
excludes uncommitted drafts and their child results/source attachments. Explicit
legacy-format encoding cannot silently drop new lifecycle metadata.

Synthetic unit checks cover week-old sessions, reliable versus unknown timestamps,
idle/hard limits, empty drafts, long-workout override, gap-aware resume, manual
corrections, backwards clocks, timezones/DST, copied workouts/routines/original
history, backup/analysis and all migration paths. Emulator fixtures use only
`com.example.fitnotes.preview.photos`; no personal application data is cleared.

Validation: 195 unit tests, no failures/errors and one optional skipped check;
normal and preview debug APKs built; lint reported zero errors and 50 existing
warnings. Four lifecycle/past-entry emulator flows passed in light mode and dark
mode with 150% text, followed in each mode by a force-stop/relaunch persistence
check. Rendered captures were inspected for timing labels, copied suggestions,
draft results and completed history. Physical-device use has not been tested
for this change.

### Past-workout header repair — 28 October 2026

The exercise logger places **Save past workout** and **Suggested weights** below
the title in a wrapping action row. The overview also gives its save action a
separate row. Notebook titles use at most two lines (one with the keyboard open);
tap an ellipsized title to read it in full. Long titles and large text can no
longer push set entry off the screen.

`PastWorkoutLayoutInteractionTest` reproduces the original 854 dp tall title and
passes with the repair at normal text/light mode and 360 × 640 dp/200% text/dark
mode. It checks visible navigation, save and weight actions, reading the full
title, adding a performed set, activity recreation and saving the draft while
preserving the concurrent active workout and the draft's unknown start time.
Screenshots were inspected under the ignored
`android/captures/past-workout-layout-20261028/` directory. The validation run had
356 passing unit tests, five skipped tests and lint with zero errors/55 warnings.
The existing exercise-reordering/live-logging interaction test also passed.
These checks use the isolated preview package; no phone installation was changed.

### Unified workout entry — 28 October 2026

`WorkoutEntryInteractionTest` covers normal/light mode and
360 × 640 dp with 200% text/dark mode. It checks cancelling without creating a
session, repeated start taps, live-session recreation/resume, earlier-date entry
beside a live session, copying exercises without performed results, disabled
future dates, and **Done** entry for a completed workout earlier today. The
original past-workout header/add-set/save regression also passes after this change.
The unit run had 359 passes and five skips; lint reported zero errors/55 warnings.
Settled previews remain in the ignored `android/captures/workout-entry-20261028/`
directory. Verification used the isolated emulator package.
