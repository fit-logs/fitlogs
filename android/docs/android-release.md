# Android beta release

## Next release: v1.0.0-beta.2

Version code 3 adds **Log workout for this day** to previous-day workout
overviews and keeps empty dates open when browsing from Train. See the
[release notes](releases/v1.0.0-beta.2.md).

On the Mac used to build beta.1, the existing development signing key is
usually `~/.android/debug.keystore`. GitHub distribution still requires this
same key for Android update installation. The packaging script checks its
certificate against beta.1 before building, then runs tests/lint, builds the
release, aligns/signs/verifies the APK and generates `SHA256SUMS`:

```sh
cd android
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./scripts/package-release.sh
```

Set `FITNOTES_KEYSTORE`, `FITNOTES_KEY_ALIAS`, `FITNOTES_STORE_PASSWORD`, and
`FITNOTES_KEY_PASSWORD` in the local environment for a nondefault key. Set
`ANDROID_HOME` if the SDK is outside `~/Library/Android/sdk`. Do not create a
replacement key for an update. Outputs are in the ignored `android/build/release/`
directory. The script does not publish a release.

After validating the signed APK, publish the tagged source with
`build/release/fitnotes-1.0.0-beta.2.apk` and `build/release/SHA256SUMS` attached,
using `docs/releases/v1.0.0-beta.2.md` as the release notes.

Cloud verification on 26 October 2026 passed 251 tests with one optional
external fixture skipped. The two new Compose/Robolectric checks use the real
MainActivity and Room database and cover empty-day navigation, the selected
date across recreation, additional same-day entry, cancellation, and preservation
of the live workout. Debug lint reports zero errors and 50 existing warnings;
release vital lint also passed. The unsigned release APK has package
`com.example.fitnotes`, version code 3, minimum API 24, target API 37, debugging
disabled, and verified 16 KB alignment. Same-certificate signing and device
update installation remain pending because the original local key is unavailable
in the cloud workspace.

## Previous release: v1.0.0-beta.1

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
