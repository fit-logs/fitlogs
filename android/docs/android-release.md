# Android beta release

The first downloadable Android release is `v1.0.0-beta.1`, version code 2,
package `com.example.fitnotes`, for Android 7.0 (API 24) or later.

## Install and update

Download `fitnotes-1.0.0-beta.1.apk` from
[GitHub Releases](https://github.com/avivajpeyi/fitnotes/releases/tag/v1.0.0-beta.1).
Open it on the phone and allow installation from that source. Android should
offer an update for earlier builds signed with the same key. Never uninstall or
clear app data to work around a signature mismatch. Export a training backup
before updating; select the optional photo archive to retain progress photos.

The APK uses the release build type with debugging disabled and optimization
disabled. The earlier partial-library optimizer configuration caused a Kotlin
class-access failure at startup in the release smoke test. It keeps the
existing development signing certificate for compatibility with earlier local
installations. This beta is distributed directly, outside Google Play. The
signing certificate SHA-256 is:

```text
7ab64af6c9ab261ba03a9471b6a3adc59fa33d684d5d1c4ef1f06a6bc693ddce
```

Signing keys and their passwords remain local and are never published. APKs are
attached to the GitHub release, rather than committed to the source repository.
`SHA256SUMS` identifies the exact downloadable APK bytes.

## Included

- Workout Library: eight original programs, three bundled offline starters,
  in-app download/cache, and review before installation. These are original
  generic routines, not copies of Muscle & Fitness or Muscle & Strength plans.
- Whole-program text/JSON import, exercise matching, programs and templates,
  and explicit next-workout progression.
- History-based weight suggestions with explicit setup and target/actual
  separation, plus session lifecycle and retrospective logging improvements.
- Health Connect automatic import and clearer source summaries; owner-export
  Strava CSV import. Live Strava API synchronization is not included.
- Existing local workout history, body measurements, progress photos, manual
  backup/restore, and manual ChatGPT sharing/import workflows.

Current formats remain Room 15, training backup 13, and analysis JSON 6. Release
validation uses synthetic test data; it does not establish physical-phone
installation or real Garmin/Health Connect synchronization.

The release gate passed 249 unit tests with one optional external fixture
skipped, debug lint with zero errors (50 existing warnings), and release vital
lint. The signed release APK was installed as an update on the API 37 emulator
and opened Train and the bundled Workout Library successfully. The date-based
load-advice persistence fixture now pins its new session to the same date as
its fixed advice clock, so later test runs do not mistake it for future history.

## Packaging

Build from the tagged source with Android Studio's JBR and the local SDK:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease --offline
```

Run this from `android/`. The release build produces an unsigned APK. Align it
with Android SDK `zipalign`, then use `apksigner` and the existing local signing
key to sign the aligned file. Verify `apksigner verify --verbose --print-certs`,
the expected certificate above, package/version/minimum SDK, and that the
manifest is not debuggable before uploading. Keep the same key for subsequent
updates and increment the version code for each release.
