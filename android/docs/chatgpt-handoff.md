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
  ordinary advice, including other activities. V2 imports strength plans only.
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

## Current V2 format

Ask ChatGPT now requests V2. Return a brief explanation outside one fenced block:

````text
```text
WORKOUT_PLAN_V2
{
  "title": "Upper body",
  "date": "2026-10-14",
  "durationMinutes": 45,
  "warmUp": "Five minutes of gentle movement, then warm-up sets.",
  "note": "General session guidance.",
  "exercises": [
    {
      "name": "Flat Barbell Bench Press",
      "note": "Keep shoulders steady and leave two reps in reserve.",
      "sets": [
        {"repsMin": 8, "repsMax": 10, "weight": "80.50", "unit": "kg", "restSeconds": 90},
        {"repsMin": 6, "repsMax": 8, "weight": "180.25", "unit": "lb", "restSeconds": 120},
        {"repsMin": 10, "repsMax": 10, "restSeconds": 90}
      ]
    },
    {
      "name": "Bodyweight Squat",
      "note": "Exercise-specific guidance.",
      "sets": [{"repsMin": 12, "repsMax": 12, "unit": "bodyweight"}]
    }
  ]
}
END_WORKOUT_PLAN
```
````

V2 uses strict JSON inside case-sensitive standalone markers. `title` and
`exercises` are required. Each exercise requires `name` and 1–100 individual
`sets`; `note` is optional. Each set requires whole `repsMin` and `repsMax`,
1–1,000 with maximum >= minimum. Equal values mean a fixed count. Optional rest
is a whole `restSeconds` value from 0–3,600. Omit weight/unit together for an
unspecified load; otherwise use decimal weight plus kg/lb, or bodyweight unit
without numeric weight. Decimal strings preserve formatting; numeric JSON
weights also work. Targets retain their original unit and are never results.

`date` and `durationMinutes` are optional. An omitted date uses the optional date
chosen in Paste plan, initially remembered from the latest Ask ChatGPT request.
Leaving it blank makes an unscheduled plan. Duration is a
1–720 minute budget when supplied. `warmUp` holds general warm-up guidance;
exercise-specific instructions belong in the corresponding exercise's `note`.
Unknown fields, duplicate JSON keys, invalid values, multiple blocks and malformed
JSON fail before database writes. Existing name/field/input size limits below apply.

## Planning, reuse and conversion

Import/preview/Save plan/Edit plan only read or write planning tables. They never
create an actual workout, start timestamp, timer or completed set. The editor
shows individual rep minimum/maximum, optional weight/unit and rest rows under
each exercise; add/remove target sets and exercises explicitly. Exercise guidance
stays beside that exercise. Warm-up instructions are collapsible. Scheduled date
and time budget may remain blank. There are no heart-rate prompts or completed
session controls in this planning screen.

Saved plans offer **Edit plan** and explicit **Start workout**. The editor also
shows separate Save plan / Start workout actions; Start is enabled only for a
saved, unchanged plan. Save edited targets first. Only Start creates
an active workout and its actual current start time. Rapid repeated starts return
the same active workout. Each start copies guidance and targets into a separate
workout snapshot; the original plan remains reusable. Editing the plan later or
editing workout suggestions changes neither other snapshots nor actual results.

The logger copies a target into editable actual input and provides **Use** for
other sets, plus **Edit workout suggestions** for this workout's target rows.
The established actual logger stores kg; lb target copies use the exact factor
0.45359237 kg/lb, explained in the logger and labelled **Use in kg**. Original lb
targets remain intact. Bodyweight suggests zero external kg; unspecified weight
stays blank. Only **Save set** writes performed weight/reps. Actual results may
differ from any suggestion; plans/suggestions never enter recorded volume or PRs.

**Convert empty import to plan** previews older immediate-start suggestions
identified by their saved suggestion marker, or an empty workout started from a
plan. Save plan explicitly moves it into planning and removes the accidental
workout/start timestamp. Cancellation changes nothing. The operation rechecks the
source in one transaction: any saved sets, recorded duration/distance/effort,
completion or linked Health Connect data prevents conversion. A set logged after
preview also prevents conversion. Nothing is converted automatically.

Unstructured old target text is retained in editable **Additional instructions**.
Weights, set counts, rep ranges and the association of ambiguous text with an
exercise are never guessed. Missing targets stay unspecified until the user
adds them. An empty recovered plan can be saved; add explicit strength exercises
before starting it.

Room 7 → 8 adds per-set targets, workout snapshots and exercise guidance without
replacing existing training. Existing V1 plans expand their explicit uniform
prescription into individual rows; any already-started plan gains a separate
snapshot. Existing mistaken imports remain untouched until conversion is chosen.
Backup JSON v6 (also inside the optional photo ZIP) retains all new rows and
notes. Backup versions 1–5 remain readable; old uniform prescriptions expand
without inferring rest or parsing prose. Analysis JSON still exports completed
training in its existing schema, excluding plans and suggestions.

## Deterministic parser

The on-device parser accepts only WORKOUT_PLAN_V2. Older and unknown versions
show an unsupported-version error; request a new V2 reply. Surrounding prose,
Markdown fences, LF/CRLF/CR and JSON whitespace are accepted. JSON keys and
standalone start/end markers are case-sensitive. No service is called.

Limits: 64,000 input characters; title 200 characters; exercise name 100
characters; 1–50 exercises; 1–100 set rows per exercise; whole reps 1–1,000,
maximum >= minimum; duration 1–720 minutes; rest 0–3,600 seconds; each note
2,000 characters. Weights accept decimal dot notation up to six decimal places,
0–10,000, maximum 16 numeric characters, with kg/lb, or explicit bodyweight.
Units and decimal text are retained without conversion/rounding. Dates must be
valid YYYY-MM-DD calendar dates. Omit both weight and unit for unspecified load.

Missing markers/required fields, multiple blocks, unsupported numeric versions,
unknown fields, invalid values and any duplicate JSON key fail. Errors identify
the exercise/set/field where available. Correct the editable reply and retry.
Parse/cancel writes nothing.

There is one per-set prescription model and one import parser. Stored database
columns and migration/backup readers remain to preserve existing phone data;
older text imports no longer have a separate parser or special logger card.

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

Plan targets and workout suggestion snapshots have separate tables from actual
performed sets. Deleting a plan leaves its recorded workouts and snapshots intact.

## Verification

Synthetic unit/Room tests cover parsing, matching, cancellation, migration,
restart, targets versus actual sets, backup versions/round trips and duplicates.
Android interaction tests must run only with application ID
`com.example.fitnotes.preview.activities`; a package guard precedes fixture writes.
The personal `com.example.fitnotes` package must never receive synthetic fixtures.
See [per-set planning verification and before/after screenshots](per-set-plan-verification.md). Extra
screenshots and transient test output remain in the ignored local captures folder.
