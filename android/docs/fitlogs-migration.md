# Migration from the legacy FitNotes repository

The application combines legacy `main` at `fd588e98f9ffddddb89f9c4b76eff31707e4a37b` with the local Android source changes captured from the `android-mvp` checkout. The original working checkout remains untouched. Android and public-catalog history are retained; the webapp and personal CSV/target-processing directories are excluded from the new repository.

## Installation compatibility

The application ID remains `com.example.fitnotes`, and the database remains `fitnotes.db`. The launcher label is FitLogs. The original certificate SHA-256 is:

`7ab64af6c9ab261ba03a9471b6a3adc59fa33d684d5d1c4ef1f06a6bc693ddce`

Version 1.1.1 uses version code 4, above the local version 1.1 (2) and legacy beta.2 (3). No new signing key is generated.

## Database and backup compatibility

Released builds and local development used versions 10–12 for different schema features. Database version 16 inspects the actual schema and adds the missing features from each branch. Direct migrations from versions 9–15 converge both families. Earlier supported databases retain their existing migrations through version 9. Recorded rows are retained; no destructive fallback is enabled.

Tests cover released schemas 9–15 and local schemas 10–12. Local development schema fixtures are stored separately from the released schema history under `app/src/test/resources/local-schemas/`.

Backup version 14 contains the released backup-13 fields plus local body-profile, measurement-creation-time and running-link data. The decoder also accepts the local development backup-9 shape and supported earlier released backups. Analysis exports retain their existing schema and omit body profiles and backup-only state.

## Update delivery

The old installed updater is restricted to `avivajpeyi/fitnotes` URLs, ignores pre-releases, and requires `fitnotes.apk` plus `fitnotes-update.json`. The legacy beta releases therefore did not appear as updates. The repository transfer also prevents the old strict URL validator from accepting the new canonical location.

Install the first migrated APK manually over the existing app. Future checks use `fit-logs/fitlogs/releases/latest`. Publication is stable, with fixed updater asset names, a manifest, a matching source archive and verified asset checksums. Installation still requires the user's Android confirmation.

## Legacy repository

After the first new release is verified, mark `fit-logs/fitnotes` as legacy and disable its Android publishing workflow. Preserve its historic releases. Archiving is a later, separate action.
