# FitLogs Android

Offline workout, running and body-measurement tracking for Android 7.0 or later.
The active project is [fit-logs/fitlogs](https://github.com/fit-logs/fitlogs).
The former [fitnotes repository](https://github.com/fit-logs/fitnotes) retains the legacy application and web experiment.

## Install and update

Download **fitnotes.apk** from the [latest stable release](https://github.com/fit-logs/fitlogs/releases/latest) and install it over the existing application.
Keep the installed app and its data; do not uninstall or clear storage to update.
The package remains `com.example.fitnotes`, with the original signing certificate.

The old local build showing **1.1 (2)** checks the previous repository and only accepts stable releases with an update manifest. The legacy beta releases do not meet that format. Install the first migrated release manually once. This release switches **Settings → Update app** to this repository for future updates. Android still requires installation permission and confirmation.

## Source layout

- `android/`: application, database schemas, tests, documentation and release tools.
- `catalog/`: public workout library JSON, checksums and attribution; no webapp build is required.
- `data/programs/`: portable program schema and synthetic example.
- `scripts/`: catalog validation.

The bundled starters work offline. Additional public programs are fetched directly from this repository's `catalog/` directory.

## Build and release

Use JDK 25, Android platform 37 and Build Tools 36.0.0:

```sh
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

CI runs those checks plus catalog and release-tool tests. For a new stable release, change both values in `android/version.properties`, increase `VERSION_CODE`, add matching notes under `android/docs/releases/v<version>.md`, then merge into `main`. The signing workflow publishes automatically after validation. The initial repository import is released manually through **Actions → Publish Android release**.

Published assets are `fitnotes.apk`, `fitnotes-update.json`, `fitlogs-source.tar.gz` and `SHA256SUMS`. The updater requires a stable release and validates the manifest, checksum, package, newer version code and signing certificate. Published releases and conflicting tags are never replaced.

See [release setup](android/docs/github-actions-release.md), [migration notes](android/docs/fitlogs-migration.md), [Android documentation](android/README.md) and [third-party notices](android/THIRD_PARTY_NOTICES.md). Existing software and catalog licences are preserved.
