# Editable plans and session lifecycle — 13 October 2026

V1 stored one uniform prescription per exercise and referenced it from one
started workout. V2 stores individual target sets and copies them into a new
workout snapshot only when Start workout is explicitly tapped. Saved plans can
be edited and reused; planning does not create a training session.

## Data and behaviour

- V2 strict JSON: per-set rep minimum/maximum, optional decimal weight/unit,
  optional rest seconds, exercise notes and separate collapsible warm-up guidance.
- Imports use V2 only. Earlier stored targets and human guidance are retained;
  pasted V1 blocks now show an unsupported-version error.
- Separate Save plan / Start workout actions appear in the editor. Start requires
  a saved, unchanged plan; edited targets must be saved first.
- Save plan creates/updates planning rows only. An optional date and time budget
  can remain blank. The planning editor has no timer, heart-rate import prompt,
  Finish control or completed set records.
- Start workout creates the actual current start time and an active session with
  copied targets/guidance. Repeated taps return that active session. The reusable
  plan is retained, including after a workout finishes and another begins.
- The logger pre-fills editable suggestions and can edit this workout's copied
  targets separately. Only Save set creates actual training. Original target units
  remain intact; lb suggestions copied into the existing kg logger have an explicit
  conversion notice and Use in kg action.
- Convert empty import to plan previews and rechecks older empty suggestions.
  Saving removes their accidental workout/start timestamp. Any recorded sets,
  activity duration/distance/effort, completion or linked health data prevents
  conversion, including training logged after preview. Nothing converts automatically.
- Room 7 → 8 is additive and retains existing actual workouts/sets. Backup v6
  retains individual targets, snapshots and guidance; versions 1–5 remain readable.

## Checks

- 90 local tests: 89 passed, one optional private-history fixture skipped.
- Lint: zero errors, 40 nonblocking warnings. Production debug APK built with the
  existing application ID/signing key.
- Parser and persistence cases verify V2 parsing and rejection of older formats; optional schedule/weights;
  decimals/units/rest; unknown/malformed fields; import/cancel/edit/save/reopen
  without training; duplicate Start; actual-set-only volume; independent edited
  snapshots; plan reuse; backup v6/old v5 restore; empty-import conversion; unknown
  recovered targets; rejection of conversion after logging; and migration retaining
  original performed rows.
- Android lifecycle checks verify editable rows, Save/Start double taps,
  close/reopen/edit, suggestions versus actual results, and reviewed conversion.
  They also check that a strength plan opens strength input even when the previous
  session used cardio fields for the same mixed exercise.
- All six handoff/lifecycle Android checks passed again with V2 fixtures after
  removing the V1 parser and old logger card. They cover Copy/Share/Paste, manual
  matching, rotation, invalid input, cancellation and deliberate duplicate-plan saves. Lifecycle
  screens passed at 200% text size in dark mode before this cleanup.
- All fixtures and screenshots use only `com.example.fitnotes.preview.activities`
  on the isolated API 37 emulator. They never enter the personal installation.

## Before and after screenshots

These are actual emulator captures. Before images show the earlier V1 UI;
current images were refreshed after the V2-only cleanup with synthetic examples. Example weights/content differ, so
these comparisons demonstrate presentation and lifecycle rather than conversion
of those particular values.

| Earlier plan | Editable individual targets |
| --- | --- |
| [![Earlier plan](screenshots/per-set-plans/before-plan.png)](screenshots/per-set-plans/before-plan.png) | [![Editable targets](screenshots/per-set-plans/after-import-editable-set-rows.png)](screenshots/per-set-plans/after-import-editable-set-rows.png) |

| Earlier logger reference | Copied editable suggestions, zero recorded sets |
| --- | --- |
| [![Earlier logger](screenshots/per-set-plans/before-logger.png)](screenshots/per-set-plans/before-logger.png) | [![Active logger](screenshots/per-set-plans/after-active-logger-suggestions.png)](screenshots/per-set-plans/after-active-logger-suggestions.png) |

- [Saved unscheduled plan, no training](screenshots/per-set-plans/after-saved-plan-no-training.png)
- [One actual set logged with different weight/reps](screenshots/per-set-plans/after-actual-set-logged.png)
- [Empty older import before conversion](screenshots/per-set-plans/before-empty-legacy-import.png)
- [Conversion preview retaining ambiguous instructions](screenshots/per-set-plans/after-legacy-conversion-preview.png)

## Earlier physical update

Before the V2-only cleanup, the authorised Pixel 6 accepted an update with the existing application ID and
signing key. Room opened version 8. Before/after totals matched for local/original
workouts, sets, exercise catalogue, measurements, history imports, source links
and cached heart-rate samples. No uninstall, data clearing, synthetic fixtures or
automatic conversion of the phone's sessions was performed.

The V2-only cleanup retains database version 8, application ID and signing key.
The Pixel 6 was disconnected during this build, so this latest APK has not been
installed or checked on the physical phone.
