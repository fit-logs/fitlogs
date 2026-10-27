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
  compilation remain release gates. Physical-device smoothness still needs a
  check with the user's actual history; synthetic tests do not establish that.
