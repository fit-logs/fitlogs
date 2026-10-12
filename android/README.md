# FitNotes Android

Small offline workout logger. Package: `com.example.fitnotes`. Android 7.0
(API 24) or later. One app module, Compose Material UI, Room database.

## Use

- **Workout:** an unfinished session resumes automatically. Otherwise tap
  **Start workout**. Date arrows and the date picker browse existing sessions;
  they never create a workout. A selector appears when a day has multiple sessions.
- Tap **+** to select or create an exercise. The overview contains compact saved
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
  present, as a collapsed preview. **Finish** completes an active workout.
- **History:** compact sessions grouped by month. Completed workouts open in
  viewing mode; choose **Edit workout** explicitly to enable logging/editing.
  **Repeat exercises** starts a new workout with no sets or notes copied.
  Finish/delete the active workout before repeating another. Deletion is confirmed.
- History's **Share** offers 14 days, 30 days or a custom date range. Workout
  menus share one session. Review readable text before copying or opening Android's
  share sheet. Empty ranges explain that no activity was found; nothing is sent.
- **Body:** select an actual available name/unit pair. See the latest value and
  dated list; tap a measurement for Edit/Delete, or use **+** to add one.
  You can enter a new measurement name/unit explicitly. Blank time means date-only;
  otherwise use HH:mm:ss. Editing preserves its ID, type, unit and original fields.
  Different measurement types/units remain separate. There are no charts.
- **Import history** and **Backup and restore** are available in overflow menus.

## Backup and restore

Use **⋮ → Backup and restore → Export JSON backup** and choose a destination in Android's
file picker. The version 3 file contains the entire exercise library, local session
titles/notes and sets, any unfinished workout, imported history, body measurements,
original fields, and import fingerprints. Version 1 and 2 backups remain readable. Store it outside this repository
in a private location. Backups are readable JSON and are not encrypted.

Use **Choose backup to restore** to validate a file and see its export date,
session date range, and exercise/session/set counts. Nothing is changed during
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
  exports the complete dataset with format `fitnotes-analysis`, version 1 and
  `backupSchemaVersion: 3`. Analysis JSON is not a restore file.
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
