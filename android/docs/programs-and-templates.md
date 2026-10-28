# Train, programs and templates

Train shows one active program and a compact **Up Next** session, followed by
quick empty/past logging. **Do later** moves a pending occurrence to the end.
**Choose another** and **View / Edit Program** open the full ordered queue.
Existing active workouts take priority through Resume/Finish/Discard.

- **My templates:** reusable single-workout recipes, with existing search, pin,
  rename, duplicate, archive and confirmed delete. Program definitions and
  occurrence copies do not appear as separate template cards.
- **My programs:** one primary active program, with pause/switch retaining its
  queue. Build from templates still supports 1–14 days and 1–52 repeated weeks.
  Coach can import strength, running and mixed programs, including one session.
- **My Program:** each occurrence has a stable ID, independently persisted order,
  optional date, pending/in-progress/completed/skipped status and explicit actual
  workout link. Move earlier/later/to end, edit, schedule, skip and restore are
  available. Dates are guidance; moving explains that their values are retained.
- **Start now:** any pending strength session can use the existing logger without
  dropping earlier pending sessions. Repeated starts reuse the active workout.
  Finish with saved actual sets completes only the linked occurrence. Discard
  returns it to pending; unrelated and retrospective logs do not advance it.
- **Skip:** reversible, with no actual workout or statistics. Restore retains
  contents and queue position. Restoring after the program ended pauses it until
  explicitly resumed.
- **Edit session:** uses the existing strength editor/picker, or running target
  fields. An occurrence copy isolates its edits. Repeating programs distinguish
  this occurrence from reusable-day/future edits; active/completed actual
  snapshots remain intact.
- **Running:** prescriptions never fabricate activities or metrics. Link a
  completed recorded activity, including existing Health Connect/Strava imports,
  explicitly. Intervals and recovery are editable text guidance.

Existing programs retain their original explicit completion workflow until
**Enable flexible queue** is selected. Existing completed/skipped steps and
workout links are preserved. An auto-finished strength workout requires explicit
confirmation or Resume/Finish; inactivity alone does not complete its occurrence.
Deleting completed actuals clears the link and keeps the confirmed occurrence
record, consistent with the previous workflow.

Room 17 extends the existing program steps and plans. Backup v15 includes the
queue, dates, statuses, occurrence copies and running prescriptions. Older
backups remain readable; migrations do not clear any data. Analysis exports
continue to exclude plans and include only actual training.

See [Coach and the flexible queue](coach-and-flexible-queue.md) and
[verification](program-workflow-verification.md).
