# Body measurement chart — 15 October 2026

- **Body → Measurements** now shows a trend below Add measurement. Ranges are
  1M / 3M / 1Y / All; All is the default. The full measurement list stays below.
- The chosen name and original stored unit define the series. No conversion,
  averaging, duplicate deletion or database migration. Dates use calendar
  spacing; date-only records retain an empty recording time.
- Tap a point to inspect/edit its original record. Overlapping same-day records
  open a chooser; **View plotted measurements** provides an accessible list.
  Differing readings on one day break the connecting line instead of assigning
  an arbitrary daily value. All original values remain available.
- Negative/unitless, single, constant, zero and small decimal values are handled.
  Tick precision follows the axis step. Values too extreme for normal axes show
  an explanation and remain in the full list.
- **Verification:** 174 local tests, 171 passed and three optional private-fixture
  skips. Lint: zero errors, 47 existing warnings. Five Android checks passed in
  light/normal text, including existing entry/edit and shared-chart regressions;
  both measurement-chart interactions also passed at 150% text/dark theme.
- **Pixel update:** a fresh private APK/database/settings recovery copy passed
  integrity checks. The exact tested APK was installed with the same package and
  signing key. All measurement, workout, set, plan and source-data rows matched.
  Only `health_sessions.importedAt` advanced through the pre-existing foreground
  sync. No uninstall, data clear or training-data replacement.
- **Physical render:** the measurement chart rendered on the Pixel for the
  selected Body Fat series. A later attempt to select Bodyweight could not find
  the selector; Bodyweight interaction coverage comes from the emulator tests.
  The physical screenshot stays local and ignored; no measurement was edited.

Actual local captures (synthetic preview, ignored by Git):
[all-time trend](../captures/measurement-trend-20261015/measurement-trend-all-normal.png),
[repeated reading chooser](../captures/measurement-trend-20261015/measurement-trend-repeated-point-normal.png),
[large text/dark](../captures/measurement-trend-20261015/measurement-trend-all-large.png),
[negative/unitless](../captures/measurement-trend-20261015/measurement-trend-negative-unitless-normal.png).

APK: `app/build/outputs/apk/debug/app-debug.apk`.
SHA-256: `2b619bb7248964fce84715e49ca324f2411106e6064ef15355cec762b49f721e`.
Matching local update assets are in `build/update-release/android-v1.1-2/`;
nothing was published.
