# Training workflow adoption

Implemented the four stages of the [review](liftlog-adoption-review.md) in the
existing Room/Compose Android app. The reference revision is LiftLog
`e7004215562994be7e1640172b4d42145ed76a07`. Only its fuzzy search scorer was
adapted directly. Other domain and Android implementations are independent;
[source mapping and licence](../THIRD_PARTY_NOTICES.md) distinguish these uses.
No upstream exercise dataset/assets, API, backend or cloud synchronisation were added.

## Delivered behaviour

- **Plans and actual training:** importing, editing and saving touch planning
  tables only. Start workout alone creates an active session and copies targets
  into a reusable run snapshot. Actual sets have nullable runtime target links,
  a unique result per target, optional completion time, explicit load mode and
  warm-up status. Extras remain unlinked. No historic target correspondence or
  completion timestamp is invented.
- **Logger:** Previous / Target / Actual rows, current actual input above the
  table, next pending target, manual switching, skipped targets, undo completion,
  edit actual results, target reordering, extra sets and apply load to pending
  targets. Applying a load never rewrites completed or skipped targets. Deliberate
  edits survive history loading. The freeform logger retains repeat-save and
  edit/cancel behaviour. Choosing another pending checkbox selects its draft;
  the current checkbox completes the entered result.
- **Rest:** one persisted countdown per active session using elapsed time. Pause,
  resume, +30 seconds and Skip work across recreation. Automatic rest follows a
  new target completion, excluding edits, skips and the last required working
  target. Undo removes the actual result and cancels rest from that target.
  Finishing/deleting the workout cancels rest. Training can continue during rest.
  Optional notifications expose controls and a completion alert. Tokens reject
  stale actions. Clock identity changes/reboot pause at last-known remaining time;
  wall-clock changes do not alter a running countdown.
- **Identity:** persisted canonical IDs, aliases and original source name/category
  mappings drive plan matching, previous history and statistics. Exact/alias
  collisions and imported category collisions remain unresolved until selected.
  Merge preview reports affected workouts/plans/results, incompatible modes and
  categories, and both-name collisions. Explicit confirmation retains every
  actual set, target link, note, source identifier and favourite. Surviving rows
  come first, then the removed name's rows; notes retain both names. Raw imported
  names/fields and deduplication fingerprints are not rewritten. Changed preview
  data requires another review. Fuzzy scoring ranks suggestions only.
- **Carryover and load:** explicit prescriptions take precedence. Per-set previous
  results use known runtime links where available; older records are labelled as
  comparison by set order. Missing load stays blank unless a known compatible
  previous load can fill an editable draft. Actual modes are external, bodyweight
  plus added load, assistance, reps-only or unknown. Unspecified load is retained
  separately from zero. Historic unknown modes remain unknown.
- **Progression:** opt-in per exercise, positive configured kg/lb increment.
  Every prescribed working target must be logged at its upper rep target and
  prescribed compatible load. Skips, partial prescriptions, incompatible modes,
  mixed units and input limits produce a Hold explanation. Warm-ups/extras do not
  satisfy working targets. Apply explicitly changes the reusable plan only when
  its prescription still matches the finished run; snapshots and actuals stay
  unchanged. Repeat apply is rejected. There is no automatic deload.
- **Supersets:** local plan group labels produce round-robin pending navigation
  and rest after the last member of a round. V2 does not accept external superset
  fields; exercise order and local groups remain editable.
- **Statistics:** one pure calculation service supplies progress charts,
  completion summaries and optional compact AI context. Only actual sets in
  finished sessions enter progress statistics. Positive strength reps count as
  sets; planned/skipped targets and unlogged suggestions never do. Multiple
  sessions on a day remain distinct. Charts show recorded dates, without zero
  points for missing dates. External kg × reps, added-bodyweight kg × reps
  (excluding body mass) and unknown-mode recorded kg × reps stay separate.
  Reps-only/assistance comparisons use reps, with assistance explicitly labelled.
  Heaviest external load, reps at a recorded load and estimated 1RM are available.
  Epley estimates apply only to positive external working loads at 1–10 reps;
  one rep uses its recorded load. Estimates display one decimal at most. First
  observations and ambiguous same-day date-only sessions are not new records.
- **AI context:** full selected history remains the default. The catalogue dump
  is removed. Compact strength context is an explicit option using the same
  statistics; activities/notes and explicit body/photo/raw-heart-rate selections
  remain. The strict V2 parser and export share field definitions/example text.

## Storage and preservation

Room **8 → 9** adds training tables/fields and rebuilds `workout_sets` while
copying all old values. Existing results receive unknown modes, null target links
and null completion timestamps. Existing explicit planned units retain their
meaning. There is no destructive migration fallback. Retired merge IDs remain reserved after restoring into a fresh database, so new
exercises cannot reuse them. Earlier migration chains,
including both supported version-4 layouts, remain tested.

Backup JSON **v7** includes actual links, target skips/modes, groups, aliases,
source mappings, redirects, progression, handoff/training preferences and
favourites. Versions 1–6 remain readable. A portable rest backup contains paused
remaining time, no reusable elapsed-clock anchor, and a fresh token on restore.
Photo ZIPs include the same training JSON; existing health imports, body records,
raw provenance and photo handling remain intact. Analysis JSON **v2** includes
actual load semantics/identity and excludes plans, targets and private settings.

## Verification

All fixtures are synthetic and isolated from the personal application package.
Android fixtures require `com.example.fitnotes.preview.activities` before writes.
The production application ID and existing debug signing setup are unchanged.
No uninstall or data clearing is used on a physical phone.

- Local suite: **123 tests, 122 passed, one optional private-backup example
  skipped**. Tests exercise strict V2 fields, aliases/collisions, target linking,
  repeated completion, skips/undo/extras/reordering, full prescriptions, unit
  limits, sparse statistics/records, timer recovery and migration preservation.
- Room tests cover merge when both names exist, every set/target/note surviving,
  stale/conflicting merge refusal, raw-source/category mappings, mapped reimport
  after backup restore, source-plan deletion, portable rest backup, favourites,
  explicit progression application and immutable actual/run data.
- Debug APK builds. Lint: **zero errors, 48 nonblocking warnings**.

- API 37 emulator, **360 dp width / 640 dp height**: nine Android UI workflows
  pass across training, handoff and planning tests. Copy checks the clipboard;
  Share opens Android's system sheet; Paste reads only after a tap. Import,
  cancellation, matching, duplicate warning, save/reopen/start and actual logging
  retain their separation. Freeform repeat-save, edit/cancel and Finish also pass.
- The three training workflows also pass with **font scale 2.0 / dark theme**.
  Table columns scroll horizontally at large text sizes. Narrow numeric controls
  place ± buttons below the field to display decimal drafts fully. Keyboard,
  rest pause/resume/+30/recreation, skips/unskips, out-of-order selection, undo,
  direct checkbox completion and extra results are exercised with real UI taps.
- Background timer: seed a synthetic 20-second timer, end instrumentation and
  confirm no preview process remains, then verify persisted expiry. Android
  delivered **Rest finished / Ready for the next set**. The active session retained
  zero actual sets. A stale-token action changed nothing. Both process-test
  stages passed. Alarm sound was not assessed audibly. Reboot/clock recovery is
  covered by fake-clock unit tests, not a physical reboot.
- Pixel 6 USB update on 13 October: `adb install -r` succeeded using the existing
  package/signing setup. FitNotes opened its database and migrated **8 → 9**.
  A read-only audit compared all original columns in **17 tables**: row counts
  and SHA-256 content checksums matched exactly, including workouts, actual sets,
  imported history/health samples, measurements, photo metadata, plans, targets
  and saved handoff preferences. SQLite integrity was clean. No training was
  created and no uninstall/data clearing occurred. Audit outputs stay private
  outside Git; no personal records or phone screenshots are included here.
- The first physical screen check exposed a startup crash when timed sessions
  and date-only imports shared a date. A mixed Integer/Long comparison caused it.
  The main screen and shared history now use one comparator with a Long fallback;
  completion summaries and plan progression use the same numeric type. Two
  regressions cover the mixed histories and summary comparisons in either order.
  The corrected APK update launched successfully and remained running. A second
  audit confirmed all 17 original-table checksums were unchanged.
- The installed ChatGPT app declares a plain-text share receiver. The user chose
  to skip the physical share test; no test draft was passed to ChatGPT. Emulator
  Share opened the system sheet without sending. After the repair, launch and
  process stability were checked while the phone was locked; a new unlocked UI
  inspection was not performed. The temporary charging-screen setting was
  restored to its original value.

## Actual screenshots

All captures contain synthetic data. Before images come from the preceding
planning verification at the implementation baseline; prescriptions differ, so
these compare presentation rather than claim a matched data migration.

| View | Before | Current |
| --- | --- | --- |
| Editable import | [Prior planning editor](screenshots/training-workflow/before-import.png) | [Per-set targets and modes](screenshots/training-workflow/adoption-editable-plan.png) |
| Active logger | [Separate target suggestions](screenshots/training-workflow/before-logger.png) | [Linked Target / Actual / Done rows](screenshots/training-workflow/adoption-set-table-completed.png) |

- [Ask preparation](screenshots/training-workflow/ask-preparation.png) ·
  [Full prompt preview](screenshots/training-workflow/ask-preview.png) ·
  [Resolved exercise matches](screenshots/training-workflow/plan-match-resolved.png) ·
  [Saved plan targets](screenshots/training-workflow/saved-plan-targets.png).
- [Current actual input](screenshots/training-workflow/adoption-logger-before-completion.png) ·
  [Keyboard open](screenshots/training-workflow/adoption-logger-keyboard.png) ·
  [Rest controls](screenshots/training-workflow/adoption-completed-target-and-rest.png) ·
  [Extra actual set](screenshots/training-workflow/adoption-extra-actual-set.png).
- [Merge preview](screenshots/training-workflow/adoption-merge-review.png) ·
  [Canonical progress after merge](screenshots/training-workflow/adoption-progress-after-merge.png) ·
  [Background completion notification](screenshots/training-workflow/rest-notification.png).
- Large text/dark: [Editable plan](screenshots/training-workflow/adoption-editable-plan-large-dark.png) ·
  [Scrolled actual-result table](screenshots/training-workflow/adoption-set-table-completed-large-dark.png) ·
  [Keyboard](screenshots/training-workflow/adoption-logger-keyboard-large-dark.png).

See the earlier [planning lifecycle evidence](per-set-plan-verification.md) for
previous captures and migration context.

## Operational limits

- Android may delay inexact background rest alarms. A user force-stop stops
  alarms until the app opens again; normal process recreation retains timer
  state. Reboot recovery pauses rather than inventing elapsed rest while powered off.
- Unknown original load modes cannot support external-load 1RM/progression until
  deliberately classified. Body mass is never added to a bodyweight set.
- External exercise metadata/equipment/muscle filters are deferred; existing
  catalogue names and user aliases are retained.
- Android Share opens the system sheet; receiving app support is device-specific.
  Copy remains the reliable fallback. Nothing is automatically sent to ChatGPT.

## Build and installation

Final debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
Application ID `com.example.fitnotes`; version code/name `1` / `1.0`.
Existing signer SHA-256:
`7ab64af6c9ab261ba03a9471b6a3adc59fa33d684d5d1c4ef1f06a6bc693ddce`.
APK SHA-256:
`c6553615e9f862ba01c459b4e4013f674ae949c1623129ed74bed50707c30216`.
Use an update install with `adb install -r`; never uninstall or clear data.
The APK and personal exports are excluded from Git.
