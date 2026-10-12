# Handoff verification — 13 October 2026

## Build and local checks

- Production debug APK built with the existing `com.example.fitnotes` ID and
  debug signing key. No uninstall, storage clearing, API key or Internet permission.
- 78 local tests: 77 passed, one optional private-history fixture skipped.
- Android lint: zero errors, 40 nonblocking warnings.
- Parser checks: fenced/prose/CRLF input, decimals/units/bodyweight, optional
  weights/date, rep ranges, malformed/multiple/unsupported blocks, conflicting
  duplicates, field limits and exact/explicit-alias/ambiguous/unknown matching.
- Room checks: cancellation/unresolved previews write nothing; reopen retains
  plans; planned rows produce no performed sets; starting is idempotent; actual
  45.5 kg × 7 leaves a 100.25 lb target unchanged; backup v5 retains plans and
  preferences; old versions remain readable; migration preserves old rows;
  duplicate/concurrent saves and injected-write rollback are covered.
- Full prompt assembly checks completed/unfinished/non-gym history, missing
  values, explicit body/photo/sample selections, raw-sample date filtering, and
  oversized complete-file sharing with FileProvider read grants.

## Actual Android interactions

All synthetic fixtures ran only in `com.example.fitnotes.preview.activities` on
an isolated API 37 emulator. They never entered the personal installation.

Three handoff interaction tests passed with 200% font size and dark theme:

1. Preparation, optional measurement/photo/raw-sample selections, complete
   preview, exact clipboard Copy, and the native Android Share sheet with a JPEG.
   The chooser opened; the test returned without sending content to another app.
2. An unknown `DB press` required manual selection of the intended catalogue
   movement. Preview/rotation wrote no plan. Save retained targets across
   recreation; Start created empty actual entries; logging recorded different
   actual kg/reps while keeping lb targets unchanged.
3. Cancel retained the database; malformed input showed a field error; corrected
   input saved; repeated blocks offered Open existing / Save another copy.

The same handoff screens were also exercised at normal text size in light theme.
The error visibility check waits for Android's keyboard/layout transition. All
five existing activity-flow checks passed across the regression runs: timed
activity/rotation, date-only activity/history sharing, import/attachment/refresh,
permission/source display and sync-toggle persistence. The sync check explicitly
resets its initial preference so it is independent of the handoff fixtures.

## Physical update and receiving app

The authorised USB-connected Pixel 6 accepted `adb install -r`. The retained
signing certificate was verified, Room opened schema 7, and before/after totals
matched for catalogue entries, local/imported workouts, sets, measurements,
source links and heart-rate samples. No personal exports/screenshots were saved
in the repository. The app was launched after the update.

The installed ChatGPT app resolved and received a short `ACTION_SEND` /
`text/plain` compatibility message. Its editable composer contained the exact
complete test text, with the Send control available. The test draft was cleared
and FitNotes brought back to the foreground; Send was never tapped. This tested
the same receiving intent contract as text Share, directly addressed to the
installed ChatGPT app. The native chooser itself was verified on the emulator.

ChatGPT receiving photo/multiple-file/large-text attachments was not tested on
the phone. Copy remains the manual fallback when a receiver rejects content.

## Actual screenshots

These captures contain only named synthetic exercises, records and a solid-colour
photo fixture. They are emulator screenshots, not design mockups.

- [Ask ChatGPT preparation](screenshots/chatgpt-handoff/ask-preparation.png)
- [Complete prompt preview](screenshots/chatgpt-handoff/ask-preview.png)
- [Unknown exercise requiring resolution, large text](screenshots/chatgpt-handoff/plan-match-unresolved-large-dark.png)
- [Manual match resolved in editable preview](screenshots/chatgpt-handoff/plan-match-resolved.png)
- [Saved plan and suggested targets](screenshots/chatgpt-handoff/saved-plan-targets.png)
- [Saved targets at 200% text size](screenshots/chatgpt-handoff/saved-plan-targets-large-dark.png)
- [Actual results logged separately from targets](screenshots/chatgpt-handoff/logger-actual-results.png)

## Remaining limits

V1 imports strength plans only. There is no catalogue alias/merge manager;
resolution reuses the existing picker. Raw heart rate covers cached imported
exercise-session samples, not all-day monitoring. Copy contains text only;
photos require Share or manual attachment. The complete prompt is never silently
truncated; large exports use an attachment or a user-selected shorter range.
