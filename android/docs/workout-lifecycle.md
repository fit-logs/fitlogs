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

1. Open **Log past workout** on Train or History. Yesterday is the default; use
   the date picker for another date.
2. Start empty, copy a recent performed workout, or copy a saved routine directly.
   Copying retains exercise order and editable references, never completed sets.
3. Use the existing exercise picker and numeric set logger. Save only the sets
   you actually performed, then tap **Save completed**.

A retrospective draft does not start a live timer or replace another active
workout. Navigating away or restarting retains it in **Past workout drafts**.
Draft sets stay outside History, training context, analysis JSON and progress
until Save completed; complete backups retain unsaved drafts for recovery.
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
