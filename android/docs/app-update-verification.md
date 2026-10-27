# GitHub updater verification

- Adds **Settings → Update app**, explicit check/download/install controls,
  release notes, bounded progress and retry/cancel states.
- Uses the existing package and signing key. Version **1.1 (2)** introduces
  monotonic version codes in `version.properties`.
- 174 local tests: **171 passed**, three optional private-backup tests skipped,
  including the subsequent [measurement time fix](measurement-time-verification.md)
  [automatic backup](automatic-backup-verification.md) and
  [measurement charts](measurement-chart-verification.md).
- Six updater policy tests cover stable assets, versions, foreign packages,
  signing keys, checksum/size disagreements, unsupported manifest formats,
  duplicate/incomplete assets and unsafe URLs.
- Two Android tests pass at normal and 150% text in dark theme. A real signed
  preview APK version 3 passed archive/checksum/certificate validation against
  installed preview version 2. Tampering and mismatched versions were rejected.
- Android displayed **Update this app?** for FitNotes. The test cancelled it and
  confirmed the installed preview stayed at version 2. Permission and install
  controls require separate explicit taps. No physical phone was changed.
- The normal APK, SHA-256 manifest, checksums and matching source archive were
  prepared locally. Source/build files are explicitly allowlisted; a file-list
  audit found no databases, captures, exports, photos or signing keys.
- GitHub publication was rejected by automatic approval review pending explicit
  approval of the public release payload and destination. Nothing was uploaded.

Actual captures (synthetic isolated preview):
[available](../captures/app-updates-20261015/update-available-normal.png),
[permission](../captures/app-updates-20261015/update-permission-normal.png),
[large text](../captures/app-updates-20261015/update-permission-large.png),
[offline](../captures/app-updates-20261015/update-offline-normal.png),
[Android confirmation](../captures/app-updates-20261015/update-android-installer.png).

See [update behaviour and publishing](app-updates.md). Public GitHub check/download
and a physical-phone installation will be reported separately once available;
local fixture tests do not establish successful public publication. The normal app
was installed separately on the isolated emulator, opened from Settings, and
reached the live GitHub API. It correctly displayed **No Android update has been
published yet**. No download was claimed while the release is unpublished.
