# Measurement time fix — 15 October 2026

- **Cause:** Add/Edit measurement required `HH:mm:ss`. Entering `20:12` disabled
  Save without explaining the problem.
- **Change:** Manual times accept `HH:mm` and `HH:mm:ss` in 24-hour time. Blank
  stays date-only. Surrounding whitespace is trimmed; minute input is stored
  with `:00` seconds to retain the existing backup format. Invalid times show a
  field error. Existing imported time validation stays strict.
- **Persistence:** A Room regression saves minute input, closes/reopens the
  database, edits and restores a backup. Values, stored units, notes and record
  IDs survive. Invalid creation/editing writes nothing; date-only remains blank.
- **Android UI:** Two tests passed in light theme at normal text and in dark
  theme at 150% text. Save enables for `20:12`; reopening shows `20:12:00`, and
  explicit seconds remain editable. Invalid time disables Save with an error;
  correcting to blank enables it. Cancel leaves the database unchanged.
- **Build:** 167 local tests: 164 passed, three optional private-fixture skips.
  Debug APK and lint succeeded. No schema migration or personal-data rewrite.
- **Device limit:** Only the isolated preview emulator was used. No physical
  Pixel was connected, so the update has not been installed on the phone.

Actual emulator captures:
[Save enabled](../captures/measurement-time-20261015/measurement-minutes-save-large.png),
[invalid time](../captures/measurement-time-20261015/measurement-invalid-time-large.png),
[saved measurement](../captures/measurement-time-20261015/measurement-saved-large.png).
Captures remain local and ignored by Git.

Normal signed APK: `app/build/outputs/apk/debug/app-debug.apk`.
The local update bundle is refreshed in `build/update-release/android-v1.1-2/`;
it has not been uploaded.
