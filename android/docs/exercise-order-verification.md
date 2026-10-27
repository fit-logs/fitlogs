# Exercise order

Open a local workout, choose **⋮ → Reorder exercises**, move rows with the up/down
buttons, then **Save order**. Cancel or Back discards the draft. Completed local
workouts expose the action after **Edit workout**. Saved plans retain their
existing exercise-order editor.

Saving updates only `workout_exercises.position`, in one transaction. Entry IDs,
logged results, target links, guidance, supersets, actual start time and rest state
remain intact. The next pending exercise follows the saved order. The reusable
source plan is unchanged. No schema or backup-format change is needed.

Orders must contain every current entry ID exactly once. This preserves repeated
occurrences of the same exercise and rejects stale lists if exercises were added
or removed while the editor was open.

## Verification

- 158 local tests: **155 passed**, three optional private-backup tests skipped.
- Two new Room tests cover persistence across reopen and backup/restore, unchanged
  results/targets/source plan/timer, repeated exercise identities, supersets,
  pending navigation, and atomic rejection of malformed/stale lists.
- The Android interaction test passes at normal text/light theme and 150% text/dark
  theme. It covers disabled boundary controls, moving rows, Cancel, recreation
  before Save, reopening afterward, and logging against the correct target.
- Lint: zero errors, 47 warnings. Debug APK builds with the existing application
  ID and signing configuration. No physical phone was connected for installation.
- Existing uncommitted work in the checkout was retained.

Synthetic emulator captures remain in the ignored capture directory:

- [Reorder editor](../captures/exercise-order-20261015/exercise-order-editor-light.png)
- [Large text and dark theme](../captures/exercise-order-20261015/exercise-order-editor-large-dark.png)
- [Workout after saving](../captures/exercise-order-20261015/exercise-order-after-light.png)
- [Logger after reordering](../captures/exercise-order-20261015/exercise-order-logger-light.png)

APK: `app/build/outputs/apk/debug/app-debug.apk`.
SHA-256: `821215506e47da8333cf0ad9823607ae72845687350b11d8e085bc74efc71a57`.

The combined build also fixes counts for exercises added during a plan; see
[set-count verification](exercise-count-verification.md).
