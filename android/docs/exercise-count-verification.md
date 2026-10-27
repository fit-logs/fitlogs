# Added-exercise set counts

An exercise added during a planned workout appears in the run relation with zero
set targets. The overview treated that empty relation as a prescription and
counted only target-linked results, displaying `0 of 0 sets` despite saved actuals.

The overview now shows **3 sets logged** for three actual sets with no targets,
**No sets logged** before the first result, and **1 set logged** for one result.
A prescribed exercise retains its target progress and exposes additional results,
for example **2 of 3 sets · 1 extra**. Extras and skipped targets do not satisfy
unfinished planned targets. A completion check requires all actual targets.

This is a read-only display calculation. Results, targets, timestamps, rest state,
saved plans and backup formats are unchanged; no data migration is needed.

- Five unit regressions cover empty/missing prescriptions, three manual results,
  singular/empty labels, linked results, extras, completion and skipped targets.
- Full local suite: 158 tests, **155 passed**, three optional private fixtures skipped.
- Android regression passed: add an exercise through the picker in a planned
  workout, log three 20 kg × 8 sets, return/recreate, delete one result and see two,
  and verify a planned exercise exposes its extra result. Source targets stay intact.
- The exercise-reordering Android regression still passes. Lint: zero errors.
- Debug APK includes both fixes. No physical phone was connected for installation.

Synthetic captures: [three logged sets](../captures/exercise-order-20261015/added-exercise-three-logged-sets.png)
and [correct overview](../captures/exercise-order-20261015/added-exercise-correct-overview.png).

APK: `app/build/outputs/apk/debug/app-debug.apk`.
SHA-256: `821215506e47da8333cf0ad9823607ae72845687350b11d8e085bc74efc71a57`.
