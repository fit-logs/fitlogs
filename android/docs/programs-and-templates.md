# Train, programs and templates

Train keeps the next program workout above quick empty/past logging. A program
contains ordered days and a week count. It occupies one card in My programs;
its day prescriptions are not additional cards in My templates.

- **My templates:** search, recent/alphabetical sort, pin, rename, duplicate,
  archive/unarchive and confirmed delete. Archive is reversible. Deleting a
  template preserves actual workouts, sets and recorded target snapshots.
- **My programs → Build from templates:** add 1–14 days in order, move/remove
  days, choose 1–52 weeks, then Save program. This copies recipes; it does not
  create performed sets or change the originals. Future day targets remain editable.
- Set one program active. Setting another active pauses the previous program;
  pause/resume/switch retains each cursor, day prescriptions and completed records.
- Start next workout uses the existing set logger. Existing active workouts must
  be resumed, finished or discarded first. Starting is idempotent while that
  same program day is active; target snapshots stay separate from results.
- Finish saves the logged session. **Complete program day** on Train or program
  details explicitly advances its cursor only for a matching, finished session
  with actual saved sets. Reopening/restarting does not advance it. Ad-hoc
  templates, empty starts, backdated logs and unused suggestions cannot count.
- **Skip day** is confirmed and records a skip, never a completion or invented
  workout. **Postpone day** blocks starting until the chosen date, while leaving
  the cursor/results alone; clear its date to train earlier.
- Progress distinguishes completed and skipped days. Completed status means the
  schedule has ended; the separate counts retain which days were skipped.
  Deleting a logged workout clears its step reference without rewinding an
  already-confirmed schedule. No replacement workout is created.

Room 12 → 13 adds programs, owned day references, session associations, schedule
steps and template archive/pin flags. Existing templates remain available and
keep all targets/IDs. Backup v11 restores this state atomically; versions 1–10
remain readable. Analysis JSON v5 still contains actual training and excludes
programs, templates and uncommitted past drafts. No network is needed.

The installed-branch reconciliation was merged before these changes (PR #14).
The existing Train/session logger, saved target editor, rest controls, empty
workout flow and retrospective entry are reused. The original dirty checkout and
personal app package are not used for synthetic fixtures. Explore / Import is an
entry to template import and program building; multi-source whole-program import,
load personalization and a program catalog are separate follow-up issues.

Verification: 206 unit tests, zero failures/errors and one optional skip. Normal
and isolated preview debug APKs build; lint reports zero errors and 50 existing
warnings. Emulator flows cover template search/keyboard focus, rename, pin,
duplicate, archive/unarchive, cancel/confirm deletion, four ordered days, real set
logging, explicit Train completion, pause/resume, skips and force-stop recovery.
Both standard/light and 360×640 dp dark/150% text layouts are checked; emulator
network access is disabled for the latter. Long titles use a rectangular touch
surface so capsule corners cannot clip text. Physical-device verification remains
unperformed.
