# Verification and screenshot reproduction

All instrumented fixtures require `com.example.fitnotes.preview.photos` and run
on a separate API 37 ARM emulator. The production package and personal data are
not modified. The audit's Coach snapshot is captured independently and is not
included in this branch's implementation.

## Reproduce

Use JDK 25 and the Android SDK specified by the project. Create an API 37 ARM
phone AVD at 1080 × 2400 / 420 dpi. Run from the repository root:

```sh
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
android/scripts/capture_ui_audit.sh emulator-5556 /tmp/ui-candidate
python3 -m pip install Pillow
python3 android/scripts/compare_ui_screenshots.py android/docs/ui-audit/after /tmp/ui-candidate
```

The capture command installs only the preview, replaces its data with synthetic
fixtures, runs five journeys and writes screenshots plus the JUnit result. It
restores the emulator's font/night preferences afterward. Never substitute the
production application ID or run synthetic fixtures on a phone. The screenshot
comparison requires the same rendering environment as the reviewed images.
It masks Android's status/navigation bars, checks six stable empty/populated
scenes at native resolution, and fails on missing images, resolution mismatches
or more than 0.2% changed pixels (per-channel tolerance 8). It never approves or
updates its own baseline. Review intentional changes visually before replacing
reference images. Keyboard/cursor, elapsed-time and date-dependent chart scenes
are retained as audit evidence and excluded from automated comparison.

Additional regression journeys:

```sh
adb -s emulator-5556 shell am instrument -w -r \
  -e class com.example.fitnotes.WorkoutEntryInteractionTest,com.example.fitnotes.ProgramInteractionTest,com.example.fitnotes.NotebookCleanupCaptureTest#freeformCompletesRepeatedSetsWithoutPlanAndKeepsEdits \
  com.example.fitnotes.preview.photos.test/androidx.test.runner.AndroidJUnitRunner
```

`am instrument` can return shell success for a failed JUnit run: require the
printed `OK (...)` result. The full older instrumentation suite contains tests
for earlier logger layouts; this focused run exercises the changed journeys with
current selectors. JVM tests and lint still run across the complete app.

## Scope

- New journeys: invalid reps, deliberate repeated saves, pending-write duplicate
  protection, edit without append, per-exercise draft restoration after Activity
  recreation, finish, advanced notes/cardio, bodyweight/reps-only separation, immediate saved-template start,
  cancelled deletion, named target controls and target/actual separation.
- Existing journeys: today/past entry and cancellation, keeping a live workout
  while entering a past workout, virtualised History, template search/rename/
  pin/archive/duplicate/delete, manual program advancement/pause/skip and
  freeform logging with a long exercise name.
- Visual checks cover the main screens in light mode at 1.0 and dark mode at
  1.5 font scale, including the numeric keyboard. A full TalkBack/Switch Access
  pass and physical-device gym use remain human follow-ups.

## Accessibility inspection

New Complete/Start actions and planned completion controls have explicit 48 dp
minimum bounds checked by Compose tests. The planned checkbox previously exposed
24 dp measured bounds; it now declares 48 × 48 dp. Planned inputs/completion and
Progress information have descriptive accessible names. Completed rows retain a
check mark as well as colour.

Calculated contrast for the existing theme's body, secondary, action and filled
button text pairs ranges from 4.93:1 to 17.09:1 in light mode and 6.82:1 to 13.69:1
in dark mode. These are checks of those theme pairs, not an exhaustive contrast
or assistive-technology audit.

## Results — 28 October 2026

| Check | Result |
| --- | --- |
| JVM suite | 360 passed, 5 skipped; 0 failures/errors (365 discovered) |
| New UI journeys | 5 passed, including 48 dp assertions and bodyweight/reps-only behavior |
| Existing affected journeys | 7 passed: 4 workout entry, 2 program/template, 1 freeform/long-name |
| Fresh screenshot regression | 6/6 passed, 0% content-pixel change in every compared scene |
| Comparator negative checks | A visible content change and a missing image both fail comparison |
| Dark mode / 150% text | Capture journey passed; Complete set remains visible with numeric keyboard open |
| Android lint | 0 errors, 44 warnings |
| Build | Preview/debug, instrumentation APK and production release assembly passed |

The five new and seven existing journeys were validated on the isolated API 37 emulator. Full
historical instrumentation was not run. Screenshots and test fixtures are
synthetic; nothing was installed on a physical device or published as a release.
