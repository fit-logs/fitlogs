# Automatic backup verification — 15 October 2026

- **Local suite:** 170 tests, 167 passed and three optional private-fixture skips.
  Lint: zero errors, 47 existing warnings. Debug APK built successfully.
- **Snapshot checks:** workout/results, plan targets, measurements, imported
  heart-rate samples and saved preferences round-trip through the compressed
  backup and Room restore. Photos and photo metadata are absent. Corrupt,
  unsupported and oversized expanded payloads are rejected.
- **Android UI:** opt-in, reopen, recovered-preview cancellation and explicit
  restore pass at normal text/light theme and 150% text/dark theme. Cancel
  preserves the current database; confirmed restore keeps plans and actual
  results separate. The final compact screen was checked again at large text.
- **Android service:** the emulator's encrypted local test transport successfully
  backed up and restored the isolated preview. The restored v7 snapshot contained
  the expected workout, actual set, plan targets and measurement. The app opened
  the standard review. A first attempt was skipped because the preview was
  force-stopped; opening/backgrounding it normally made it eligible. The original
  emulator transport was restored after the test.
- **Pixel deployment:** a private local copy of the prior APK, database and
  settings passed integrity checks. The exact tested normal APK was installed
  over authorized Wi-Fi debugging with the same package and signing certificate.
  Every database table's count and saved-row fingerprint matched before/after;
  schema 10 was retained. No uninstall or data clear.
- **Cloud boundary:** Android's Backup Manager is enabled on the Pixel. FitNotes'
  new automatic-backup switch remains off. No actual Google upload or Google
  cloud recovery has been claimed; the service round-trip used the emulator's
  local transport and synthetic data.

Actual captures, kept locally outside Git:
[final compact settings at large text](../captures/automatic-backup-20261015/automatic-backup-settings-compact.png),
[explicit restore review](../captures/automatic-backup-20261015/automatic-backup-restore-review-compact.png),
[review after Android service restore](../captures/automatic-backup-20261015/android-local-restore-review.png).

APK: `app/build/outputs/apk/debug/app-debug.apk`.
SHA-256: `0c770d72da77bb3fb07523a93937703e8487e6cd6c00f0b62d77407b2d68daba`.
The matching local update bundle is prepared in
`build/update-release/android-v1.1-2/`; nothing was published.
