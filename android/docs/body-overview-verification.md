# Body overview verification — 15 October 2026

## Delivered behaviour

Body now opens on latest weight, the shared measurement chart (3M by default),
a deterministic four-week comparison when supported, and BMI. The local Body
profile accepts cm or feet/inches, optional birth date and kg/lb preferences.
Other measurement types and photos remain accessible. Existing measurement
editing is reused, including date-only recording and deletion with Undo.

See [calculation definitions, CDC sources and import limits](body-overview.md).
BMI uses kg/metres², with one decimal displayed and unrounded classification.
Four-week comparison averages each day's readings before averaging the measured
days in each 28-day window; each window requires three days spanning seven days.

## Checks

- `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug` succeeded.
- **186 unit tests: 183 passed, 3 optional private-fixture tests skipped.**
- Lint: **0 errors, 47 existing warnings**.
- Light theme, standard text: **9 Android interaction tests passed** (new Body,
  existing measurement editor, measurement chart and shared chart cases).
- Dark theme, 150% system text: **4 Body interaction tests passed**.
- Rendered captures were inspected. Keyboard insets were repaired so Save remains
  reachable at large text sizes. These are actual Android screenshots at
  **1080 × 2400, 420 dpi**, matching Pixel 6 dimensions.
- Unit coverage includes BMI 80 kg / 1.8 m = 24.691358… → 24.7; exact adult
  boundaries and unrounded classification; equivalent imperial inputs; invalid
  inputs; unknown/under-20 age and birthday boundary; same-day readings; sparse
  windows; local-date/DST boundaries; decimal rounding; stale dates; latest
  edit/deletion; repeated native import; backup round-trip and restart.
- Schema 10→11 adds a profile table and creation-order metadata. Migration preserved the complete prior backup contents, other
  measurement types, photo metadata and image bytes in a synthetic on-disk
  database. JSON, photo ZIP and opt-in system backup retained the profile;
  analysis exports omitted it. UI checks confirmed cancel leaves the profile
  unchanged and weight logging creates no workout or completed sets.
- No physical device was connected at the final check, so no phone installation
  was performed. No personal app data was cleared or seeded. The test emulator
  has been stopped.

## Synthetic screenshot gallery

All images use **synthetic test data only**, in the separate
`com.example.fitnotes.preview.photos` package. No personal measurements or photos
were copied into these fixtures. Main fixture: height 180 cm, adult birth date,
latest weight 80 kg, recent daily average 80 kg, previous average 80.6 kg.
The keyboard capture shows an unsaved height edit to 200 cm. Sparse fixture has
one date-only 80 kg reading nine days earlier and no height.

Captures remain in the local ignored `captures/body-overview-20261015/` folder.

| Screen | Light / standard text | Dark / 150% text |
| --- | --- | --- |
| Latest weight and history | [Light](../captures/body-overview-20261015/synthetic-body-overview-light.png) | [Dark, large](../captures/body-overview-20261015/synthetic-body-overview-large-dark.png) |
| History, comparison and BMI | [Light](../captures/body-overview-20261015/synthetic-body-bmi-and-trend-light.png) | [Dark, large](../captures/body-overview-20261015/synthetic-body-bmi-and-trend-large-dark.png) |
| Missing height | [Light](../captures/body-overview-20261015/synthetic-body-missing-height-light.png) | [Dark, large](../captures/body-overview-20261015/synthetic-body-missing-height-large-dark.png) |
| Sparse history / single point | [Light](../captures/body-overview-20261015/synthetic-body-sparse-light.png) | [Dark, large](../captures/body-overview-20261015/synthetic-body-sparse-large-dark.png) |
| Profile editor | [Light](../captures/body-overview-20261015/synthetic-body-profile-light.png) | [Dark, large](../captures/body-overview-20261015/synthetic-body-profile-large-dark.png) |
| Profile with keyboard | [Light](../captures/body-overview-20261015/synthetic-body-profile-keyboard-light.png) | [Dark, large](../captures/body-overview-20261015/synthetic-body-profile-keyboard-large-dark.png) |
| Log weight with keyboard | [Light](../captures/body-overview-20261015/synthetic-body-weight-keyboard-light.png) | [Dark, large](../captures/body-overview-20261015/synthetic-body-weight-keyboard-large-dark.png) |

## APK and limits

- APK: `app/build/outputs/apk/debug/app-debug.apk` (18,004,557 bytes).
- Application ID `com.example.fitnotes`, version 1.1 (2); existing debug signer.
- APK SHA-256: `4991f9ea827964b01227a600592772caba9fbfdb58327ba02d3099dc3fd1de64`.
- Signer SHA-256: `7ab64af6c9ab261ba03a9471b6a3adc59fa33d684d5d1c4ef1f06a6bc693ddce`.
- The named Body Tracker CSV fixture was absent, and that CSV format was already
  unsupported. Native FitNotes body imports remain supported and tested. No CSV
  columns or missing units were guessed.
- Multiple date-only readings are retained. New manual readings use local creation
  order for latest selection; existing imported readings with unknown order retain
  a stable tie-break. No measurement time is invented.
