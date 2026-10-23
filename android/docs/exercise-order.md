# Reorder workout exercises

In a local strength workout, open **More actions → Reorder exercises**. Move rows
with the up/down controls and choose **Save order**. Cancel or Back keeps the
original order. For a completed workout, first choose **Edit workout**.

Only the entry positions change, in a single transaction. Logged sets, target
associations, supersets, notes, rest state and the saved source plan are retained.
Saving rejects a stale list if an exercise was added or removed while editing.
No database migration or backup-format change is required.

## Verification — 23 October 2026

- Debug and instrumentation APKs build.
- Unit tests: 124 passed, one optional private-fixture test skipped.
- Lint: zero errors, 37 warnings.
- Room regression tests cover reopen, backup/restore, unchanged results and
  targets, next-pending navigation, and atomic rejection of invalid/stale orders.
- The isolated emulator interaction test passes at standard text/light theme and
  150% text/dark theme. It covers boundary controls, moving rows,
  Cancel, recreation before Save, persistence and logging the correct target.
- Personal device data was not changed.

Run the checks from `android/`:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug \
  :app:assembleDebugAndroidTest -PphotoVerification=true --offline --console=plain
```

Synthetic emulator captures are retained locally under the ignored directory
`android/captures/issues-20261023/`.
