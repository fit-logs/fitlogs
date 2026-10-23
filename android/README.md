# FitNotes Android

Small offline workout logger. Package: `com.example.fitnotes`. Android 7.0
(API 24) or later. One app module, Compose Material UI, Room database.

Current formats: Room 15, training backup v13, analysis JSON v6.

## Use

**Log past workout** on Train/History starts a retrospective draft, defaulting to
yesterday. Copy a recent workout/routine or start empty, log performed sets, then
**Save completed**. Forgotten strength sessions are reconciled with editable
estimated/unknown timing. [Lifecycle details and verification](docs/workout-lifecycle.md).


**Train → Start empty workout** opens exercise selection immediately. Resume,
Finish and Discard manage the active session; reusable templates stay separate.
See [unplanned workout flow and verification](docs/empty-workouts.md).

- **Train:** the active program shows its next workout. **Start empty workout**,
  **Resume workout** and **Log past workout** keep ad-hoc logging quick.
  **My programs** and **My templates** have separate lists and management controls.
  Other activities are under Settings. In the workout
  overview, date arrows and the date picker browse existing sessions; they never
  create a workout. A selector appears when a day has multiple sessions.
- Tap **Add exercise** to search Recent / Favourites / All, select multiple exercises,
  and confirm **Add N exercises**. Stars save favourites; selections survive filter
  and search changes. **Create custom exercise** adds a named exercise. The overview contains compact saved
  set rows; tap an exercise for its focused **Log / History** screen. Back returns
  to the overview, preserving scroll and draft state where practical.
- The logger shows the latest completed session before the viewed workout.
  Ambiguous same-day date-only records are not treated as earlier sessions.
  Suggested weight/reps or distance/time are drafts, not saved records.
- Enter kg/reps and tap **Save set**. Zero external kg is valid; reps must be
  whole numbers from 1–10,000. Values remain ready for a deliberate repeated save,
  and the keyboard closes after saving. Pending writes reject concurrent taps.
- **Log cardio** in the exercise menu selects distance/unit and seconds controls.
  Existing imported cardio and mixed records retain their fields and units.
- Tap a saved row to select it: a check mark and highlight identify the selection.
  The editor becomes **Update set**, with **Cancel edit**, **Duplicate**, and
  confirmed **Delete** actions. Typing alone never changes a saved record.
  Use the exercise menu to add an optional set note or remove an exercise.
- Switch exercises using **Switch**. Confirmed records remain persisted; drafts
  are kept separately for each exercise while navigating within the app.
- Edit session title/notes through the overview menu. Notes appear only when
  present, as a collapsed preview. **Finish workout** completes an active workout.
- **History:** compact sessions grouped by month. Completed workouts open in
  viewing mode; choose **Edit workout** explicitly to enable logging/editing.
  **Repeat exercises** starts a new workout with no sets or notes copied.
  Finish/delete the active workout before repeating another. Deletion is confirmed.
- History's **Share** offers 14 days, 30 days or a custom date range. Workout
  share buttons and menus share one session. Review readable text before copying or opening Android's
  share sheet. Empty ranges explain that no activity was found; nothing is sent.
- **Ask ChatGPT** in Workout or History prepares a question with 7 days, 30 days
  (default), or a custom history range. Review the complete prompt, then **Copy
  prompt** or **Share** through Android. Choose Next workout, Review progress, or
  Custom question. Goals, equipment, constraints, time and activity preferences
  are remembered locally. Body measurement types, photos and raw heart-rate
  samples are optional and start unselected each time.
- **Paste plan** reads the clipboard only when **Paste** is tapped; manual text
  entry also works. Review and edit the strength plan, resolve exercise names,
  then **Save plan**. This saves targets without recorded sets.
  **My templates** lists reusable single-session recipes. Starting one creates an actual
  session with empty exercise entries and keeps its targets visible separately.
  Targets appear in editable per-set rows, including rest and original units.
  The logger compares Previous / Target / Actual and links completion to a target.
  Skip, undo, extra sets and free exercise switching remain available. The next
  pending set fills after saving. Rest can pause/resume, add 30 seconds or skip;
  alerts are optional in **More actions → Training settings**.
  **Edit plan** changes the reusable plan; **Start workout** copies suggestions
  into the logger. Enter the weights/reps actually performed. Scheduled dates
  are optional. **Convert empty import to plan** repairs empty older suggestions
  after review, while preserving uncertain legacy text and actual training.
- ChatGPT handoff is manual, using your existing app/account. Nothing is submitted
  or answered automatically. There is no OpenAI API, backend or cloud upload.
  Copy is the fallback if a receiving app rejects Android sharing.
  [Exact plan format, limits and matching behaviour](docs/chatgpt-handoff.md).
- **Body:** select an actual available name/unit pair. See the latest value and
  dated list; tap a measurement for Edit/Delete, or use **+** to add one.
  You can enter a new measurement name/unit explicitly. Blank time means date-only;
  otherwise use HH:mm:ss. Editing preserves its ID, type, unit and original fields.
  Different measurement types/units remain separate. There are no charts.
- **Import history** and **Backup and restore** are available in overflow menus.
- **More actions → Exercise library:** save aliases, resolve original imported
  names to canonical exercises, and review a merge before confirming it. Both
  names in one session retain all actual sets and notes. Fuzzy search ranks
  candidates but never makes a match or merge decision.
- **Strength progress:** charts and comparisons use actual sets in finished
  sessions, with separate external, added-bodyweight and unknown load measures.
  Optional double progression suggests an increase only after every prescribed
  working set meets its upper rep target. Applying it to a reusable plan is explicit.
  [Training changes, definitions, verification and screenshots](docs/liftlog-adoption-verification.md).
- **Settings → Appearance:** System / Light / Dark. An explicit choice persists
  across restarts and overrides the system appearance. Theme is a device preference;
  favourites are also retained by current training backups.

## Backup and restore

Use **⋮ → Backup and restore → Export JSON backup** and choose a destination in Android's
file picker. The version 10 file contains the entire exercise library, local session
titles/notes and sets, any unfinished workout, imported history, body measurements,
original fields, import fingerprints, planned workouts/targets and saved handoff
preferences, individual target sets and workout suggestion snapshots, target/result
links, load modes, aliases/source mappings, superset groups, progression settings
and favourites. A running rest countdown is exported as paused remaining time and
restored paused with a fresh action token. Versions 1–6 remain readable. Store it outside this repository
in a private location. Backups are readable JSON and are not encrypted.

Use **Choose backup to restore** to validate a file and see its export date,
session date range, and exercise/session/set/planned-workout counts. Nothing is changed during
preview. **Replace & restore** explicitly replaces all existing training data
in one Room transaction. Cancel to leave it unchanged. Export current data first
if you want to retain it.

Malformed JSON, unsupported versions, duplicate IDs/names, broken references,
invalid values, and multiple unfinished sessions are rejected before replacement.
The current JSON backup size limit is 50 MB. This format is for this Android app;
it does not import the sibling web app's data.

Actual training data lives in the app's private `fitnotes.db`, outside Git.
Android cloud backup and device-transfer backup of the database are disabled.
Keep exported backups, real workouts, credentials, and signing keys outside Git.
The app uses no account, backend, Internet permission, or API credential.

## Import original FitNotes history

Use **⋮ → Import history → Original FitNotes backup** for the original Android SQLite backup
(schema version 22). Or choose **Workout CSV** for the inspected workout format.
The file picker loads a preview; no database changes occur before **Confirm import**.
The preview reports date range, workouts, records, cardio and body counts, detected
units, invalid/skipped rows with reasons, and unsupported configuration records.

- Native `metric_weight` values are kilograms; `duration_seconds` is seconds.
  The installed original app's unit definitions were checked: distance codes
  2 = metres, 3 = kilometres, 4 = feet, 5 = miles; legacy/default 0 = metres.
  Body units come from `Measurement` joined to `MeasurementUnit`, preserving
  the original name, value, unit, date, time, and comment, including custom types.
- Workout CSV columns must be exactly `Date,Exercise,Category,Weight,Reps,Distance,Time,Comment`.
  Dates are strict `YYYY-MM-DD`. Unlabelled weight/distance/time require explicit
  choices. Weight can be kg/lb; distance m/km/ft/mi; time seconds, minutes, h:m:s,
  m:s, or h:m. Pounds convert to kg while original source values are retained.
  Quoted commas, multiline comments, and doubled quotes are supported.
- **Body Tracker CSV is deferred until an actual CSV export is supplied.** The
  shared `.fitnotes` file is a database, not a CSV example. Its body records are
  fully supported through native backup import; no body CSV columns were guessed.
- Imported workouts are grouped by source date. CSV workouts, and native dates
  without a recorded timer, display **date-only**. No midnight/session times are
  invented. Native timer strings retain their recorded timezone offsets.
- Each source record stays distinct, including identical repeated sets. CSV
  row order and native `training_log._id` order are preserved. Comments are linked
  by original set ID and the date portion of their original comment timestamp.
  Original zero-rep records are retained and labelled; local new sets still require
  positive reps. Source completion/pending flags remain in the raw source fields.
- Imports add data in one Room transaction. Existing sessions and exercise names
  are not overwritten. File fingerprints persist in backup/restore, so importing
  the same export again adds nothing, including after renaming it. Different
  overlapping exports remain separate; do not import overlapping copies blindly.
- Imported records and session notes can be edited, duplicated, or deleted.
  Original raw fields remain available for analysis. Deleting an imported session
  requires confirmation and retains its import fingerprint, preventing accidental
  re-addition by the same file. Repeating copies only its exercise list.
- The **Body** tab lists dated measurements. Graphs are deferred.
  Period sharing includes body measurements. **Backup and restore → Export analysis JSON**
  exports recorded training with format `fitnotes-analysis`, version 2 and
  `backupSchemaVersion: 7`. It excludes plans, target snapshots, progression
  settings and private preferences. Analysis JSON is not a restore file.
- Local workouts can open while imported history loads in the background. A loading
  label appears for imported references/history; period sharing stays disabled until
  the full history is ready, so no partial training context is shared.

Original imports are limited to 20 MB. Import/analysis files and actual workouts
must stay outside Git. Ignore rules cover CSV, `.fitnotes`, generated backups,
and analysis exports under this Android project. The Room 1 → 2 migration only
adds tables; Room 2 → 3 adds optional local-set cardio/comment columns. Neither migration changes existing IDs or imported rows; there is no destructive fallback.

The supplied native example was checked against 562 workout dates (2018-10-08
through 2026-10-11), 10,564 source records, 35 cardio records, 25 zero-rep records,
29 body measurements, 16 set comments, and 50 recorded workout timer windows.
The Workout CSV example has 24 rows across four dates; one row has an unquoted
comma producing nine columns and is reported/skipped. Identical valid rows remain.
The native example has zero skipped workout/body records. Unsupported configuration:
5 routines, 25 routine sections, 151 template exercises, 11 template sets, 2 barbells
and 23 plates. Goals and graph favourites are also outside scope (zero in this file).
These configurations are not reconstructed. Existing exercise/measurement definitions,
workout-group metadata, comments and timer metadata are retained for provenance.

Native example check (keep the personal backup outside Git):

```sh
FITNOTES_EXAMPLE_BACKUP=/absolute/private/path/history.fitnotes \
  JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
```

## Build and checks

Open this directory in Android Studio and sync Gradle. Preserve the generated
wrapper, SDK settings, and daemon JVM criteria. The current setup requests JDK
25; Android Studio's bundled JDK on this machine works:

```sh
cd /Users/avi/Documents/personal/GYM/fitnotes/android
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
```

APK:

```text
/Users/avi/Documents/personal/GYM/fitnotes/android/app/build/outputs/apk/debug/app-debug.apk
```

The local tests exercise actual Room persistence with Robolectric: reopening the
database, three saved sets, bodyweight values, set edits/duplicates/deletes,
last-session lookup, repeating exercises, backup round-trip, invalid imports,
foreign-key cascades, and rollback after an insertion failure during restore.
Tests use separate test databases and synthetic fixtures. An optional read-only
original-backup example can be supplied through FITNOTES_EXAMPLE_BACKUP; it is
never bundled in the app or test sources.

Room's versioned schema is in `app/schemas/`. Keep schema files in Git and add
migrations for future schema changes; do not add destructive migration fallback.
Build reports are under `app/build/reports/`.

### Initial app/import verification on 12 October 2026

- Debug APK assembled; all 22 local tests passed (no skips with the supplied native example); lint completed with zero errors
  and 24 nonblocking dependency/template warnings.
- Android Studio inspections reported no errors in the new Kotlin files after
  Gradle sync. Its MCP build tool timed out without usable build messages;
  Gradle provided the build and test results. The `app` run configuration was
  discovered, but its MCP launch call did not return completion; ADB supplied
  the verified launch and restart.
- API 37 emulator: logged three 60 kg × 8 sets, retained entry values between
  saves, force-stopped the app, updated it with `adb install -r`, and verified
  all three saved sets and the title survived. Draft fields resumed blank.
- Finished the session, opened its History entry, and opened Android's share
  sheet with readable context containing all three sets. Nothing was sent.
- Exported through the Android file picker; parsed the saved version 1 JSON and
  verified eight exercises, one session, and three sets.
- Selected that backup in the restore picker and verified its validation and
  replacement-confirmation preview. Cancelled and verified the three-set History
  entry remained unchanged. Replacement/rollback behavior was exercised in the
  Room tests. Also copied the last 14 days of context on the emulator.
- Native import on the API 37 emulator: preview showed the full date range,
  562 workouts, 10,564 records, 35 cardio records, 29 body measurements,
  512 date-only workouts and zero skipped records. Confirmed import, checked
  database integrity and exact counts, updated with `adb install -r`, restarted,
  and verified the same counts plus the existing three saved sets and unfinished
  workout. Imported History, the dated body list and a last-session reference
  displayed correctly.
- Full native preview and history loading took several minutes on this emulator,
  which uses software graphics. The final build allows local workouts to open
  while imported history loads, loading local Room sessions first. The final APK
  restarted successfully and displayed the unfinished workout and imported reference.
  Physical-phone performance has not been verified.
- The Pixel 6 disconnected before app runtime verification; per the user's request,
  verification finished with the emulator. Its original app binary was inspected
  read-only while connected to verify units; its training databases were not changed.
  Source data is not in the APK or Git. Native repeat-import behavior and complete
  v2 backup/restore round-trip ran in the Room tests, not as a second device import.
- No app uninstall or device-data clearing was performed. The sibling web,
  data, and scripts directories were unchanged.

### Notebook redesign verification on 12 October 2026

- 27 local tests pass, including the actual native backup counts/units/order,
  idempotency and round-trip, legacy v1/v2 backups, cardio/comment saves and updates,
  body precision/date-only editing, reference cutoffs and Room 1 → 2 → 3 migrations.
- Android Studio MCP built successfully and reported no Kotlin inspection errors.
  The `app` run configuration remains available. Device interaction and screenshots
  use ADB, not IDE screenshot APIs.
- A separate preview application in a temporary emulator data image contains only
  synthetic comparison data: three exercises, three sets each, prior sessions,
  imported cardio and set comments, plus two distinct measurement types. The
  before/after screenshots use the same portrait device, dark theme and 1.0 font
  scale. Existing app/user data was never cleared or overwritten by this fixture.
- The guarded device interaction test passed: three deliberate identical saves,
  update without append, cancel without mutation, switching/back persistence,
  date navigation without phantom sessions, finish, completed viewing mode,
  repeat without copied sets and backup round-trip. Reopening resumed the active
  workout. It runs only for the preview package and a named synthetic fixture.
- Espresso was updated from 3.5.1 to 3.7.0 after the old test dependency called an
  input API removed from Android 37. This changes test tooling only.
- At font scale 1.5 the exercise name wrapped, numeric controls stayed readable,
  and Save was reachable with the numeric keyboard open. A confirmed save closed
  the keyboard; font scale was restored to 1.0 afterwards.
- The redesigned range preview included workouts, cardio/comments and measurements;
  Android's share sheet opened without sending. A SAF export produced a valid v3
  JSON file with 3 workouts, 22 local sets, 4 imported records and 3 measurements.
  Its restore preview reported matching counts; explicit restore resumed the
  synthetic unfinished session. The actual native file's larger round-trip remains
  covered by the read-only Room tests.
- Pixel 6: installed the production APK using `install -r`. Room 1 → 3 migration
  preserved every original row (8 exercises and 1 unfinished workout), with an
  integrity check. The updated Body UI subsequently displayed imported history.
  No personal records were created or imported by the verification automation.
- Before/after screenshots and additional verification outputs stay outside Git.
  No uninstall, app-data clearing, push or merge was performed.

### Visual polish verification on 12 October 2026

- 28 local tests pass with no skips, including the supplied native backup fixture,
  legacy backups, precision preservation, existing migrations and atomic multi-select.
- Guarded emulator interaction tests pass for three identical saves, update, cancel,
  switching, finish/repeat/date navigation and selection across filters/search.
- Actual rendered screens were inspected at 1080 × 2400, font scales 1.0 and 1.5,
  in light/dark themes. Matched before/after images and additional keyboard, long-name,
  imported-history and measurement-detail captures are in the ignored
  `captures/polish-20261012` directory, with a verification report.
- A decimal weight was entered and saved with a normal docked keyboard. Save remains
  reachable with large text and long names by scrolling. The unfinished synthetic
  workout, saved records and explicit appearance survived a process restart.
- An actual SAF export produced valid v3 JSON with local sessions/sets, imported
  records and measurements; original `kgs` units and 70.125 precision were retained
  while the UI displays `kg`. Import date-only information remains in details.
- No schema/dependency changes, uninstall, storage clearing, push or merge. Runtime
  checks used isolated synthetic preview packages; the physical phone was unchanged.
- Equipment is absent from the library schema, so the picker uses a consistent generic
  vector icon and displays only unambiguous stored categories. No metadata is inferred
  or silently written from names.

## Install

On your phone, enable Developer options and USB debugging, connect it by USB,
and accept the computer authorization prompt. Then:

```sh
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
"$ADB" devices -l
"$ADB" -d install -r /Users/avi/Documents/personal/GYM/fitnotes/android/app/build/outputs/apk/debug/app-debug.apk
"$ADB" -d shell am start -n com.example.fitnotes/.MainActivity
```

`-r` updates the app while preserving its data. Do not uninstall or clear data.
If more than one physical device is connected, replace `-d` with `-s SERIAL`
using the serial printed by `devices -l`. For the running emulator, use `-e`.
Keep the same debug signing key for future updates. A signature mismatch requires
the matching signing key; do not resolve it by uninstalling an app with workouts.

You can also copy the APK to the phone, open it, and authorize installation from
that source. Use the same APK/signing key for later updates.

## Implementation references

- [Room releases and KSP setup](https://developer.android.com/jetpack/androidx/releases/room)
- [AGP built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)
- [KSP releases](https://github.com/google/ksp/releases)

- [AndroidX Test releases and input API fix](https://developer.android.com/jetpack/androidx/releases/test#espresso-3.7.0)


## Strava activity files

**Settings → Strava activity files** imports a user-selected `activities.csv`
from an account export. Confirm owner key, timestamps/units and duplicate matches,
then import the selected batch. [Setup, API blocker and verification](docs/strava-owner-export.md)
explain why live API authorization/scheduling remains open under issue #7.

## Activities and Health Connect

Imported activity presentation uses one [source-aware summary](docs/activity-summaries.md)
for History, details and sharing, including source offsets, sport labels, elapsed
pace and optional elevation gain. Local edits and date-only attachments retain
separate local timing. Room 9 → 10 and backup v8 preserve this metadata.


- **Start workout** opens the activity selector. Choose Strength training for the
  existing exercises/sets flow, or Running, Walking/hiking, Climbing/bouldering,
  Soccer, Martial arts or Other (custom label). The current unfinished session
  must be resumed, finished or deleted before another session is created.
- **Start session** saves a real timestamp. Non-strength sessions show elapsed
  wall time from that persisted timestamp; navigation, rotation and process
  restart preserve it. Finish saves the end and elapsed duration. Notes and effort
  are saved through **Edit activity details**. There is no timer background service.
- **Log past activity** accepts a date, reported duration in minutes, optional
  start time, title, notes and effort. Running/walking can also have distance in
  m/km/ft/mi. An unknown start remains unknown; no midnight timestamp is invented.
  An optional start plus reported duration determines the end. Timer duration is
  elapsed wall time, while imported duration is the source start/end interval;
  neither assumes Garmin's active/moving time.
- **Import history → Import activities (Health Connect)** offers the last 7 or
  30 days, or custom dates. Grant read permissions through Android's Health Connect UI, then load
  a preview. Exercise access is required; heart rate, distance and elevation gain are optional.
  Missing/revoked permissions and unavailable/setup states explain the next step.
  Leaving the foreground stops an in-progress read.
- Preview sources include their real origin package. Garmin Connect is selected
  when records from `com.garmin.android.apps.connectmobile` are present. Other
  sources remain inspectable. Import as new, explicitly attach to any saved
  session, or persistently ignore it. Only matching activity types with known overlapping local
  times are suggested; date-only history can be attached by explicit selection.
- Attachments preserve local IDs, sets, notes, titles and timing. Source timing,
  title, notes, record/client IDs, offsets, device, origin, last modification and
  import time remain separate. Re-import or **Refresh imported data** updates the
  same source link and atomically replaces the sample snapshot. Deleted local
  sessions retain source tombstones and are not recreated by repeated imports.
- Heart-rate samples are filtered to the session interval (end excluded), with
  the exercise source preferred. When unavailable, another source is explicitly
  identified. Only one source's samples are summarized. Sample count, covered
  timestamp range and sample min/max/average are shown; sparse samples do not
  establish continuous coverage or official Garmin workout statistics.
- Session details show a compact heart-rate card with average/minimum/maximum,
  sample count and covered time range. Large fonts use stacked statistics.
  **Import details** expands source identifiers, full timestamps, coverage and
  sync messages. Activity duration and logged times are kept separate from watch
  timing. Missing/revoked access and refresh failures remain visible on the card.
- **Settings → Garmin via Health Connect** has an opt-in automatic import switch,
  source/activity filters, Sync now and last-attempt/completion/error counters.
  New selected-source activities become completed sessions; possible duplicates
  require review. Existing links refresh by stable source/client identity.
- Foreground/resume, manual and optional WorkManager reads share one coordinator
  and SDK lock. SDK reads never hold a Room write transaction or the workout
  logger's busy flag. Logging can continue during slow reads. Leaving the
  foreground pauses foreground reads; Android can run separately permitted
  background imports. See [connection details and verification](docs/health-sync.md).
- Sessions without an attached Health Connect import show **No heart-rate data
  imported for this session** and a direct **Import activities** action. Sync the
  watch with Garmin Connect and return to FitNotes; use the preview for entries
  requiring review. Watch-to-Garmin syncing remains controlled by Garmin.
- Distance is the sum of fully contained records from a preferred source.
  Boundary-crossing distance is not apportioned; overlapping distance records are
  reported as ambiguous. Missing distance remains null. Its source and limitations
  appear in details and sharing.
- A previously imported source record not returned by a later complete scan is
  flagged as potentially changed, moved or unavailable; the local session stays.
  Updated source fields are marked on refresh. No local workout is silently deleted.
- All activities appear in History and the existing date-range text preview.
  Imported summaries, origins and missing-data context are included before copy/share.
  There is no automatic upload.

Record on your watch separately. Garmin data can be attached after sync. The app
never starts or controls the watch, records GPS, receives live heart rate, logs
into Garmin, or connects directly to Garmin. A query result does not establish that
Garmin finished syncing or that no activity occurred.

The integration uses the official stable Health Connect SDK 1.1.0. Offline
logging retains API 24 support; all Health Connect entry points are guarded for
API 28+. API 24 has a synthetic offline persistence check. Default history access
starts 30 days before the first permission grant. Older-history and background
permissions are optional, requested separately only when supported. Custom date
ranges support deliberate history review. Older/batched data outside the
accessible lookback can be incomplete. Garmin must first write data to Health
Connect on the phone; install/update and enable its sharing there as needed.

Official references checked for this implementation:

- [SDK setup, availability and permissions](https://developer.android.com/health-and-fitness/health-connect/get-started)
- [Pagination and history access](https://developer.android.com/health-and-fitness/health-connect/read-data)
- [Stable SDK release](https://developer.android.com/jetpack/androidx/releases/health-connect)
- [Garmin Connect package identity](https://play.google.com/store/apps/details?id=com.garmin.android.apps.connectmobile)

Room 3 → 4 is additive. Existing IDs and date-only imported records are preserved.
Backup v4 includes activity fields, Health Connect source links and samples;
restore validates all references and replaces the complete dataset atomically.
In the legacy local `startedAt` column, zero now explicitly denotes an unknown
start; `sessionDate` and `finished` carry date/completion independently. All timer,
matching and export code uses `knownStart` rather than interpreting zero as time.

Automated checks use synthetic records and private test databases. The SDK reader
is exercised with the official FakeHealthConnectClient, including >500 records,
permissions, source preference and batched samples crossing boundaries. UI tests
must use the isolated package `com.example.fitnotes.preview.photos` on an
emulator. Never run synthetic fixtures against the personal application ID.


### Version-4 database repair

Room 4 → 5 supports both earlier installable version-4 layouts. When the original
layout lacks the imported-history attachment column, the migration adds that
nullable foreign key and its index without replacing tables or changing records.
The later version-4 layout retains its existing links. Synthetic tests exercise
both layouts with saved sets, notes, source IDs and heart-rate samples. Backup
JSON remains version 4; early files without the optional imported-history link
also remain readable. Storage failures now show a clear error instead of an
indefinite loading spinner.

## Exercise order

Local strength workouts support **More actions → Reorder exercises**.
See [usage and verification](docs/exercise-order.md).

## Progress photos

**Body → Measurements / Photos** keeps measurements separate from optional photos.
In Photos, choose Front / Side / Back / Other. The grid lists newest dates first.
**Add photos** opens Android's system photo picker (up to 20 at a time, with a
system document-picker fallback on older devices). No photo-library permission
is requested. Each image gets a preview, editable date, angle and optional note.
**Cancel import** discards remaining unsaved copies; previously saved photos stay.

Only EXIF DateTimeOriginal is used as a capture date. If absent or invalid, the
default is today with a visible explanation. File modification times are ignored.
Multiple photos can share a date. Tap a thumbnail to view/edit details or confirm
deletion of only the app's copy. Missing/damaged copies remain manageable.

Select two checkboxes in one angle and tap **Compare**. The slider has a visible
drag handle and an accessible slider below it; endpoints reveal either photo.
**Side by side** shows both complete images. Both modes preserve aspect ratio and
retain selections. Slider mode supports per-photo pinch zoom and drag reposition,
plus **Reset**. These adjustments never change stored files. Same-day comparisons
use Photo 1 / Photo 2 labels because within-day capture order is unknown.

Original bytes are copied into private `files/progress-photos/`; the Room version
5 → 6 migration only adds the photo table and date index. UUID metadata IDs and
relative file references are independent of measurements. Processing runs on IO,
with thumbnails bounded to 384 pixels and viewing images to 2048 pixels on the
long edge. Each original is limited to 60 MB and 400 million decoded pixels.
Interrupted unsaved imports/orphan copies are removed on the next app start.

### Manual photo backup

**Backup and restore → Include progress photos** starts off each time the menu
opens. With it off, the current v12 JSON format excludes all photo files and photo
metadata and clearly says so. Versions 1–7 restore; JSON restoration leaves
existing photos alone. Analysis exports and ordinary workout text sharing exclude photos. Ask ChatGPT
can explicitly attach selected JPEG copies; its Copy action remains text only.

With the option on, export creates an unencrypted ZIP containing the training
JSON, a version-1 photo manifest and actual original image bytes (including any
original embedded metadata). Store it privately outside this repository. It uses
portable archive references and SHA-256 checksums, never device-specific paths.
Archives support up to 1000 photos / 512 MB total. Missing/corrupt copies stop the
export with an explanation. Choose the ZIP in the existing restore picker to
validate and preview it before the replacement confirmation. Photo IDs are
upserted, so repeated restores do not duplicate records; photos absent from the
archive are retained. Files are prepared before the database transaction, using
new private locations so an interruption cannot replace an existing image early.

Photos stay local, with no upload. The existing manifest disables Android backup,
and both legacy backup rules and Android 12+ cloud/device-transfer rules explicitly
exclude all private files and the database, including photos and their metadata.
No camera, reminder, cloud sync, collage export, alignment or body analysis is added.

### Synthetic photo verification

`ProgressPhotoTest` covers import/cancel/edit/delete, duplicate dates, restart
persistence, original deletion, missing/corrupt files, EXIF capture date and all
eight orientations (including mirrored pixel checks), large-image bounds,
archive round trips, repeated restore, old JSON restore, traversal rejection,
and rollback after a database failure. Tests use synthetic files/private databases.

`ProgressPhotoInteractionTest` requires the isolated verification package. Build
with `-PphotoVerification=true` to use `com.example.fitnotes.preview.photos`; normal
builds keep `com.example.fitnotes` and the existing signing configuration. Never
run synthetic fixtures on the personal application ID. Actual emulator screenshots
and the detailed verification record are kept in ignored
`captures/progress-photos-20261013/`. Those photo checks used an isolated package.

## Source licence

The Android module is distributed under [AGPL-3.0](LICENSE). It contains an
adapted exercise-search scorer from LiftLog; see [source attribution and revision](THIRD_PARTY_NOTICES.md).
The licence and notices are bundled under **More actions → Open-source notices**.

Train now separates [programs and reusable templates](docs/programs-and-templates.md),
with an explicit next-day cursor and completion/skip records.

[Whole-program import](docs/portable-programs.md) supports offline JSON/text preview,
explicit exercise resolution, atomic installation, and clear file/PDF/URL fallbacks.

[History-based weight suggestions](docs/history-load-suggestions.md) use completed
comparable sets, explicit equipment setup and optional actual RIR/RPE. Review
then Use or Change; session locks and performed results protect existing targets.

[Workout Library](docs/workout-library.md) browses original versioned programs,
bundles three offline starters and installs through the shared program preview.
Explicit public catalog GETs use the internet permission; workout data stays local.

[Program workflow verification](docs/program-workflow-verification.md) traces
conversion, exercise confirmation, actual logging and fresh next-session targets.
