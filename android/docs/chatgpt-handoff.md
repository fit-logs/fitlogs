# Manual ChatGPT handoff

Ask ChatGPT prepares readable training context. Copy or Android Share transfers
it at your request. Use your existing ChatGPT account, copy its reply, and return
to Paste plan. No API, backend, automatic message submission or answer retrieval.

## Preparation and data

- Default range: 30 inclusive local dates ending today; 7 days and custom dates
  are available. The prompt states both dates and device timezone.
- Default target: tomorrow in the device timezone. Time is optional, 1–720 minutes.
- Goals, equipment, constraints and activity preference are editable and remembered
  locally; no personal defaults are bundled. Each preference allows 4,000 characters.
- Next workout requests a strength plan. Review progress / Custom question request
  ordinary advice, including other activities. V1 cannot import running/climbing plans.
- Existing range/session export supplies completed training, exercises, performed
  sets, non-gym activity, recorded notes/effort and imported summaries. Saved actual
  results from unfinished sessions are labelled separately. Planned workouts are
  excluded. Missing values remain explicit; preparation waits for full history.
- Body measurement name/unit types require explicit selection. Photos require
  individual selection (maximum 8 within the range). Raw heart rate is opt-in and
  includes available cached imported-session samples by actual local sample date,
  with timestamp, epoch milliseconds, bpm and source. It is not full-day coverage.
- Photo sharing makes orientation-correct JPEG copies up to 2,048 pixels on the
  longest edge. Originals remain local. Copy exports only text/photo descriptions;
  attach the photos manually when using Copy.
- Preview shows all text. Inline Copy is limited to 200,000 UTF-8 bytes; above that,
  choose a shorter range or Share/Export the complete text attachment. History is
  never silently cut off. Receiver attachment support depends on the installed app.
- Prompt text remains in memory until dismissed. Explicit Share creates private
  cache files with read-only URI grants; files older than 24 hours are removed on
  the next Share. Explicit Export writes the full text to your selected destination.
  Payloads and clipboard text are not stored in the database or logged.

## Exact V1 format

Explanation belongs outside exactly one fenced code block:

````text
```text
WORKOUT_PLAN_V1
Title: Upper body
Date: 2026-10-14
Duration: 45 min
Note: Session-level guidance.

Exercise: Flat Barbell Bench Press
Sets: 3
Reps: 8
Weight: 80 kg
Note: Exercise-level guidance.

Exercise: Lat Pulldown
Sets: 3
Reps: 8-10
Weight: 70.50 lb

Exercise: Bodyweight Squat
Sets: 3
Reps: 12
Weight: bodyweight
END_WORKOUT_PLAN
```
````

Title and Duration precede the exercises and are required. Date is optional;
omitting it uses the date selected in the app. Weight is optional; omission means
unspecified, never zero. Notes are optional single-line `Note: ...` fields; put
rest, tempo or alternatives in notes. Suggested values remain targets.

## Deterministic parser

The on-device parser accepts surrounding prose/Markdown fences, LF/CRLF/CR,
whitespace around fields, and case-insensitive field names. Start/end markers
are case-sensitive standalone lines. It never calls a service.

Limits: 64,000 input characters; title 200 characters; exercise name 100
characters; 1–50 exercises; 1–100 sets; reps 1–1,000 as an integer or strictly
ascending range (hyphen or en dash); duration 1–720 whole `min`; each note 2,000
characters. Weights accept decimal dot notation up to six decimal places,
0–10,000, maximum 16 numeric characters, with `kg` or `lb`, or `bodyweight`.
Units and decimal text are retained without conversion/rounding. Dates must be
valid `YYYY-MM-DD` calendar dates. Blank explicit Weight is invalid: omit it.

Missing markers/required fields, multiple blocks, unsupported numeric versions,
unknown/misplaced fields, invalid values and conflicting duplicate fields fail.
Identical duplicate values are accepted. Errors identify the line/exercise/field
where available. Correct the editable reply and retry. Parse/cancel writes nothing.

## Matching, review and saving

Only case and whitespace are normalised. Exact catalogue names are reused;
multiple matches require selection and unknown names require selection or
explicit creation. No abbreviation or movement variant is guessed. The catalogue
has no alias/merge manager; the matcher supports explicit alias maps for callers,
while this UI uses existing catalogue matching and manual resolution.

Known cardio-only entries cannot receive strength prescriptions. The catalogue
has no universal exercise-type metadata, so manual selection confirms a strength
exercise; uncategorised catalogue entries are usable. Custom creation is queued
until final Save. Cancellation does not create catalogue rows.

Edit title, scheduled date, duration, order, sets, rep range, weight/unit and notes,
or remove exercises. Resolve every exercise before Save. A repeated exercise ID
in one plan is rejected; combine its guidance explicitly rather than silently merge.
Repeated pasted blocks show Open existing / Save another copy. Concurrent writes
are guarded, and saving opens the saved plan before starting is available.

Room 6 → 7 adds plan, target and preference tables without replacing training,
measurements, photos or imports. Plans never appear as completed training or
contribute to recorded volume/records. Starting a plan creates an ordinary
strength session with empty set rows and a link to immutable saved targets.
The existing logger records actual kg/reps; these may differ from targets in kg,
lb, bodyweight or unspecified weight. No target is converted into an actual set.
Deleting a plan leaves its performed workout intact.

Backup JSON v5 and the optional photo ZIP retain plans, targets, start links and
saved preferences. Versions 1–4 remain readable (they contain no plans). Old JSON
restore preserves current handoff preferences when that field is absent. Restore
continues to replace training/plans only after explicit confirmation. Analysis
JSON keeps its existing completed-data schema and excludes plans/preferences.

## Verification

Synthetic unit/Room tests cover parsing, matching, cancellation, migration,
restart, targets versus actual sets, backup versions/round trips and duplicates.
Android interaction tests must run only with application ID
`com.example.fitnotes.preview.activities`; a package guard precedes fixture writes.
The personal `com.example.fitnotes` package must never receive synthetic fixtures.
See [verification and actual screenshots](chatgpt-handoff-verification.md). Extra
screenshots and transient test output remain in the ignored local captures folder.
