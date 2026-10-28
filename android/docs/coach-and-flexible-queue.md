# Coach and the flexible training queue

## Audit and minimal design

The existing Android implementation already separated reusable `PlannedWorkout`
recipes, program-owned `ProgramDay` definitions, `ProgramSession` workout links,
and completed/skipped `ProgramStep` records. Per-set targets, name/alias review,
the strength plan editor, actual workout snapshots, local load advice, Health
Connect/Strava activity imports and atomic backup/restore were reusable.

The missing pieces were occurrence-level order/dates/edits, reversible skips,
out-of-order starts, completion on actual Finish, a unified copy/paste screen,
and running prescriptions. The old program cursor and global not-before date
could not represent those operations independently.

Room 17 extends the existing steps table instead of adding another scheduling
system. `dayIndex` identifies an occurrence permanently, including its repeated
week; `queuePosition` is independently editable. Each step has its own status,
optional date, prescription reference and explicit actual workout link. Program
days remain reusable definitions. Occurrences share a definition until edited,
started, skipped or linked to a run, then retain their own prescription copy.
Actual workout target snapshots and performed sets retain their existing roles.

## Use

- **Coach:** select next workout, training week, progress review or custom question;
  Gym, Running or Both; 7/30/90 days or an inclusive custom range. Goals, equipment,
  constraints, activity preference and time reuse local handoff preferences.
- **Copy coach prompt** prepares the complete compact context off the main thread
  and copies it in one action. **View shared data** shows exactly the same text.
  All completed sets in the range are grouped by exercise/load/reps per session;
  actual activities use the existing summary and running projections. No history
  is silently truncated. Oversized prompts are rejected with range guidance.
  Active/past drafts, planned sets, notes, body measurements, photos and raw heart
  rate are excluded. Nothing is automatically sent or fetched by Coach.
- **Paste AI response** reads the clipboard only on that button press. Manual text
  entry works too. Advice stays as readable advice and creates no program. Plan
  JSON opens the existing editable program preview, including exercise resolution,
  individual set targets, dates, duration and running guidance. Save validates all
  sessions and mappings in one Room transaction; a bad final session rolls back
  earlier writes. Exact names and aliases are reused; uncertain matches require
  selection or explicit creation.
- AI numeric lifting weights are omitted by default. The preview allows explicitly
  keeping reviewed AI weights. **Suggested weights · Review / Change** on Train
  opens the existing deterministic local history engine for that occurrence.
- New programs imported through the UI, including single sessions, use the flexible
  queue. Imported Coach programs become active immediately. Existing saved
  programs retain manual completion until **Enable flexible queue** is selected;
  that operation retains old completion/skip records and workout associations.
- **Train** shows one Up Next session. **Do later** moves it to the end and keeps
  it pending. **Choose another** and **View / Edit Program** open the ordered queue.
  An active workout takes priority. Empty starts and past logging remain available.
- **My Program:** session overflow offers Start now, Move earlier/later/to end,
  Edit session, Change scheduled date and Skip session. Skipped occurrences have
  Restore. Dates are explicit guidance; moving retains them and explains that
  queue order chooses Up Next. No missed-date automatic completion or deletion.
- Strength edits reuse the existing editor/picker. Repeated programs offer this
  occurrence or reusable day/future occurrences. Active and completed workout
  snapshots never change when upcoming prescriptions change.
- Starting a pending strength occurrence copies its targets into the existing
  logger and associates the actual workout by ID. Retried starts reuse that active
  workout. Finish with actual saved sets completes only its occurrence. Discard
  resets it to pending. Editing actual results retains the association; deleting
  a completed actual clears its link while retaining the confirmed completion,
  matching existing history semantics.
- Inactivity alone never completes an occurrence. An abandoned empty workout is
  retained as a draft and its occurrence returns to pending. An auto-finished
  session with actual sets remains associated and requires explicit confirmation
  or Resume/Finish. Unrelated same-day workouts cannot complete it.
- Running prescriptions hold distance/duration targets and optional effort, pace,
  warm-up, interval/recovery and cool-down text. **View / Link recorded run** opens
  My Program; **Link recorded run** selects a completed local activity, including
  Health Connect/Strava imports. Linking never copies targets into actual metrics.
  Deleted/merged hidden runs are excluded. Actual running import/logging remains
  in the existing activity workflow.

## Clipboard contract

No markers or identifiers are needed. One session object or a program with a
`sessions` array is accepted, inside Markdown fences and surrounding explanation:

```json
{
  "title": "Strength + Running",
  "sessions": [
    {"title": "Easy Run", "type": "running", "distanceKm": 5,
     "intensity": "easy", "notes": "Conversational effort"},
    {"title": "Upper Body", "type": "strength", "durationMinutes": 45,
     "exercises": [{"name": "Bench Press", "sets": 3, "reps": "8-10",
                    "restSeconds": 90}]}
  ]
}
```

One to fourteen sessions; optional `weeks` uses the existing 1–52-week repetition.
`date` is optional YYYY-MM-DD on each session. Strength `sets` also accepts the
existing individual target array (`repsMin`, `repsMax`, optional `weight`/`unit`,
`restSeconds`, `loadMode`, `isWarmUp`, `rir`, `rpe`). Omitted loads remain unknown;
zero is an explicit target. Decimal strings retain precision and kg/lb units.
Running supports optional numeric `distanceKm`, whole `durationMinutes`, and text
`intensity`, `pace`, `intervals`, `warmUp`, `coolDown`. Notes remain guidance.

Malformed/duplicate JSON, multiple objects, unknown common-contract fields,
conflicting rep formats, invalid values and unresolved exercises fail with review
errors. Existing portable program definitions and legacy saved workout files
remain supported. Single clean strength JSON also works through the existing
plan-file importer. Saved database content and old backups remain readable.

## Deliberate scope

- Accessible move controls; no drag-and-drop or recovery/readiness scoring.
- Intervals are editable text guidance, not a structured interval recorder.
- Run linking currently selects completed local activity records (including
  imported Health Connect/Strava runs), not individual cardio sets inside older
  mixed FitNotes CSV workouts. Those runs still appear in Coach history.
- There is no GPS recorder, automatic matching by title/date, embedded chatbot,
  cloud inference or new network dependency. Advice and pasted response drafts
  are transient; accepted prescriptions persist in Room and backups.
- Room 16 → 17 is non-destructive. Backup v15 retains definitions, occurrence
  copies, order, dates, statuses, running targets and actual links. Versions 1–14
  plus the existing local development adapter remain readable. Analysis exports
  continue to include actual training and omit all program prescriptions.

## Verification

See [test results, changed code, screenshots and reproduction commands](program-workflow-verification.md).
