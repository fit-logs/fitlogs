# Logging performance

Issue #1 reported slow weight editing and scrolling. Inspection found history
and run projections executing during root Compose recomposition, repeated time
parsing inside sort comparisons, a growing-list scan for every imported run,
and full target-table reads when changing one exercise's weight.

The notebook now emits a paired database/projection snapshot from a background
worker. Imported session rows survive unrelated local edits; identity changes
invalidate them. Run processing indexes associations and handles each imported
workout's components directly. Target edits use indexed entry-scoped SQL and
preserve completed and skipped sets. There is no database schema change.

History uses `LazyColumn`, so only viewport rows and a small prefetch area are
composed. Its display summaries now come from the existing background notebook
projection and reuse that projection's run records. Opening History no longer
rebuilds all run records and history summaries on the UI thread. Month grouping
is remembered for the current filtered list instead of being rebuilt in the
lazy-list content block.

The shared Room observers still read the full workout/import dataset used by
logging, progress and suggestions. This is lazy rendering, not database paging.
Loading older records from storage in batches would require a separate data-layer
change; adding an in-memory page limit would not reduce those reads.

Verification on 28 October 2026: the unit suite passed (359 passes, five skips),
and lint reported zero errors/55 warnings. Background projection checks preserve
all 1,600 mixed sessions and their run identities, exclude the active workout,
and pick up a newly completed local workout. Five normal-text emulator checks
passed entry, history filtering and older-workout navigation; the four entry/
history checks also passed at 360 × 640 dp with 200% text/dark mode. The 120-session
fixture verifies that offscreen rows are absent initially and become accessible
on scroll. Screenshots remain in the ignored
`android/captures/history-cleanup-20261028/` directory. No phone app was updated.

## Verification

- `HistoryProjectionTest`: 1,600 synthetic mixed sessions, 9,600 sets, timed
  sorting, distinct run identity, copied timestamps and cache invalidation.
- Local JVM median of five warmed full history/run builds: 39.3 ms before,
  19.6 ms after the parsing/index changes. These are development-machine
  timings, not a phone frame-rate measurement. The UI also avoids doing these
  full builds on its main thread.
- `TrainingWorkflowTest`: weight changes leave completed/skipped targets and
  other exercises untouched.
- Existing unit tests, lint, unsigned release assembly and instrumentation
  compilation passed. The isolated preview emulator also passed exercise
  reordering, cancel/save, process recreation, scrolling and completing a
  target while preserving existing results and the rest timer. Its test now
  uses the current completion checkbox and permits the existing last-action
  timestamp update. Physical-device smoothness still needs a
  check with the user's actual history; synthetic tests do not establish that.
