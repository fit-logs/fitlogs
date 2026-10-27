# Workout notebook cleanup — 13 October 2026

The existing Kotlin/Compose/Room app was updated in place. The checkout was clean
on `android-mvp`; newer planning, aliases, photos, backup and timer work was retained.
No application ID, Room schema, launcher identity or signing configuration changed.

## What changed

- Train / History / Progress / Body navigation; Settings contains occasional tools.
- Compact plans and active workouts; one editable set table; collapsed notes.
- Explicit completion, out-of-order sets, extra sets and editing completed results.
- A single persistent rest strip; elapsed-clock recovery and cancellation on Finish.
- Keyboard insets and row scrolling keep the selected field and completion visible.
  Long titles remain readable; while the keyboard is open, tap the shortened title
  to read it in full. Text sizes are not reduced.
- Heart rate uses actual timestamps, visible points and median-interval gap breaks.
  Progress uses per-session points, meaningful metrics, axes and selected-period summaries.
- Unknown metadata is omitted. Missing weight/duration is retained as missing.
- Technical notices screen removed. Licensing files remain in the source tree.
- ChatGPT exports omit the full catalogue; existing strict plan validation remains.

## Duplicate-session cause and correction

- A permitted, private, read-only copy of the Pixel 6 database passed `quick_check`.
- Two native FitNotes backups had different whole-file hashes but shared **10,561
  identical source records across 562 workout dates**. Storage contained both copies;
  the UI was rendering those distinct stored IDs once each.
- The cause was file-level import identity. The correction compares original source
  primary keys and canonical source payloads, retaining local edits and provenance.
  Later native snapshots reuse existing records. Exact repeat files remain a no-op.
- Automatic repair runs only for complete, source-identical results with compatible
  timing and exercise mappings. It saves a recovery backup and writes transactionally.
  Ambiguous pairs remain available under Settings → Review repeated imports.
- **Verified against a private copy:** 559 safe merges changed 1,125 → 566 imported
  workouts and 21,146 → 10,628 imported sets. Local workouts/sets, health samples,
  plans and import provenance were retained. Export/restore round trips passed;
  restoring the recovery backup reproduced the original records. A second repair
  was a no-op.
- **Three pairs remain for review** because their complete results differ. They are
  not silently combined. Title/date alone is never used to remove a session.
- Reassociating an imported Garmin placeholder with a local workout keeps one
  displayed workout, retains local sets and requires a recovery backup. A parent
  containing its own user data cannot be removed through this association action.
- CSV files lack stable archive identities; overlapping different CSV exports remain
  separate. This limitation is stated during import.

## Heart-rate finding

- A read-only Health Connect reread found the **same 38 BPM value, source record ID,
  Garmin origin and timestamp** as the stored sample, inside the associated workout.
- Nearby 37 and 36 BPM values also exist in the source. Their source record covers
  about 22 seconds; this was not a daily/resting record selected by date alone.
- Epoch boundaries and source timezone offsets agree. There were no conflicting
  timestamps or mixed sample origins in the audited session. The SDK supplies
  integer beats per minute directly; no unit conversion or numeric parsing occurred.
- These values are retained. Source/device measurement accuracy is outside what
  the app can establish. No smoothing, low-value clamping or physiological inference
  was added.
- The latent fallback to an arbitrary other app's samples was removed. That fallback
  did **not** cause this particular reading. Source diagnostics stay in development
  logs/reports, outside the workout screen.

## Verification

- Standard debug build: successful. `com.example.fitnotes`, API 24 minimum unchanged.
- Signing SHA-256 matches the installed Pixel 6 application:
  `7ab64af6c9ab261ba03a9471b6a3adc59fa33d684d5d1c4ef1f06a6bc693ddce`.
- Unit tests: **137 total, 136 passed, 0 failures, 1 skipped**. The skipped test requires an
  optional original `.fitnotes` fixture absent here. The actual private database-copy
  repair test ran and passed independently.
- Lint: successful, **0 errors / 47 warnings**. `git diff --check`: passed.
- Emulator: Pixel 6 dimensions, **1080 × 2400 at 420 dpi**, light at 100% text and
  dark at 150% text. Three interaction tests passed in each configuration.
- Interaction coverage: varying plan targets and explicit bodyweight; active pending
  and completed sets; out-of-order completion; blank load; editing; restart; rest
  token preservation and Finish cancellation; freeform repeated sets; plan saves
  creating no training; date-only history; sparse heart rate and a long gap; empty,
  single-point, constant, zero and ordinary chart series; point inspection.
- Repository coverage: repeat imports, later native snapshots, user-edit retention,
  merge races and rollback, recoverable repair, Garmin/local association, genuine
  same-day sessions, supported metrics and unavailable timings.
- Physical Pixel 6: the read-only health source audit passed. Its training database
  and installed application were not replaced or cleared. The verified APK is ready
  for an update; automatic repair has been validated on the copy, not applied to the
  phone. No commit, push or merge was performed.

## Screenshots

All captures below are actual emulator screenshots with synthetic data. Private
phone records and source diagnostics are under ignored `captures/.../private`.

| Screen | Before | After |
|---|---|---|
| Active workout | [Before](../captures/notebook-cleanup-20261013/before/before-active.png) | [After](../captures/notebook-cleanup-20261013/after/after-active.png) |
| Logger | [Before](../captures/notebook-cleanup-20261013/before/before-logger.png) | [After](../captures/notebook-cleanup-20261013/after/after-logger.png) |
| Completed / heart rate | [Before](../captures/notebook-cleanup-20261013/before/before-completed.png) | [After](../captures/notebook-cleanup-20261013/after/after-completed.png) |
| Progress | [Before](../captures/notebook-cleanup-20261013/before/before-progress.png) | [After](../captures/notebook-cleanup-20261013/after/after-progress.png) |

- [Saved plan](../captures/notebook-cleanup-20261013/after/after-saved-plan.png) · [Target editor](../captures/notebook-cleanup-20261013/after/after-plan-editor.png)
- [Date-only history](../captures/notebook-cleanup-20261013/after/after-date-only.png) · [Freeform](../captures/notebook-cleanup-20261013/after/after-freeform.png)
- [Rest strip](../captures/notebook-cleanup-20261013/after/after-logger-completed-rest.png)
- [150% text, dark, keyboard open](../captures/notebook-cleanup-20261013/large-dark/after-large-dark-logger-keyboard.png)
- [150% text, dark, saved plan](../captures/notebook-cleanup-20261013/large-dark/after-large-dark-saved-plan.png) · [Editor](../captures/notebook-cleanup-20261013/large-dark/after-large-dark-plan-editor.png)
- Charts: [Empty](../captures/notebook-cleanup-20261013/after/after-chart-empty.png) · [Single](../captures/notebook-cleanup-20261013/after/after-chart-single.png) · [Constant](../captures/notebook-cleanup-20261013/after/after-chart-constant.png) · [Zero](../captures/notebook-cleanup-20261013/after/after-chart-zero.png) · [Ordinary](../captures/notebook-cleanup-20261013/after/after-chart-ordinary.png)

## Wi-Fi installation — 14 October 2026

- Installed and opened on the Pixel 6 with `adb install -r`; application ID and
  signing certificate matched. First-install identity was retained; no data clearing.
- A fresh private recovery copy was validated before installation. The app also
  saved its own recovery JSON before the first-launch repair.
- Actual phone repair: **559 safe merges**, 1,125 → 566 imported workouts,
  21,146 → 10,628 imported sets. Three differing pairs remain for review in Settings.
- Local exercises, workouts, all 25 logged sets, plans and body measurements matched
  the pre-install snapshot exactly. Every distinct imported result and original
  source payload was preserved. Saved health samples were unchanged.
