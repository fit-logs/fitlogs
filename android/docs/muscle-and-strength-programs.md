# Muscle & Strength programs

Three factual routine transcriptions join the existing eight original programs.
They install through Workout Library → Preview → Review installation, or by
pasting the versioned JSON into Import program. Each installation is an editable
local program, initially paused, with exercise identities reviewed explicitly.
Coach and the flexible queue reuse the same owned day templates. No performed
sets, personal working weights or effort targets are generated.

Sources checked live on 28 October 2026:

| Program | Author | Weeks / days per week | Versioned JSON |
| --- | --- | --- | --- |
| [Spring Shred: 8 Week Fat Loss Workout Plan](https://www.muscleandstrength.com/workouts/8-week-spring-shred-program) | Roger “Rock” Lockridge | 8 / 5 | [JSON](../../catalog/programs/ms-spring-shred-8week-v1.json) |
| [Lean Mass: 6 Week Workout Program to Build Lean Muscle](https://www.muscleandstrength.com/workouts/6-week-workout-program-to-build-lean-muscle) | Josh England | 6 / 4 | [JSON](../../catalog/programs/ms-lean-mass-6week-v1.json) |
| [8 Week Mass Building Hypertrophy Workout](https://www.muscleandstrength.com/workouts/8-week-hypertrophy-workout) | Roger “Rock” Lockridge | 8 / 4 | [JSON](../../catalog/programs/ms-mass-hypertrophy-8week-v1.json) |

The 90 exercise rows retain table order and every warm-up/working-set count and
rep prescription. Existing canonical identities are reused where the movement
matches; distinct variants have distinct IDs. `sourceName` preserves the printed
label. Linked exercise guides identify barbell incline/seated presses, separate
EZ-bar and barbell preacher curls, dumbbell walking lunges, leg-press calf raises,
and other equipment variants. Importing never forces these IDs onto local rows.

## Source interpretation and app limits

- **Spring Shred:** 31 warm-up targets and 108 working targets; seven superset
  pairs retain group/order through import and logging. A is followed by B without
  rest; rest follows the pair. The author's reply to Adi on 17 September 2025 says
  intensity techniques apply to the final working set only and confirms rest
  between superset rounds. The unnumbered forced reps on lying leg curls stay
  unnumbered. Pull-up and plank failure targets have no invented reps/seconds.
- **Spring Shred rest:** weeks 1–2: 120s; 3–4: 90s; 5–6: 60s; 7–8: 45s.
  `restByWeek`, schedule, day and exercise notes retain all four phases. The
  current engine has one reusable day prescription, so variable rests remain
  unspecified in numeric targets (except 0s between paired movements). Choose
  the current phase's rest manually; automatic weekly timer changes are absent.
- **Lean Mass:** 91 working sets; 45s is the author's recommended default within
  30–60s. Notes retain 2/0/2 tempo and the recommended deload after six weeks.
  Neither this program nor Spring Shred assigns weekdays/rest days, so none are
  fabricated. The source supplies no fixed weekly load increments or RIR/RPE.
- **Hypertrophy:** 81 working targets preserve differing per-set reps and the
  final-set symbols. Table keys say 3–5s negatives; prose describes five seconds.
  The template records that discrepancy and follows the table keys. Rest-pause
  mini-sets and load drops remain execution notes without invented extra set
  counts, percentages or loads. The author’s reply to Clint Morse on 9 June
  2026 confirms repeating the week and seeking more reps or weight.
- **Duration:** Spring Shred supplies 45–60 minutes; Lean Mass supplies 30–45.
  Catalog filters use their upper bounds. Hypertrophy supplies no session time;
  `minutesPerDay` and day duration use 0 for unspecified. It is excluded from a
  maximum-duration filter, rather than presented as a zero-minute session.
- **Cardio:** Spring Shred's 20-minute HIIT format and 3–4 weekly frequency, and
  Hypertrophy's 20-minute moderate cardio 3–4 times/week, remain visible guidance.
  No arbitrary cardio days, modality or extra strength sessions are inserted.
- **Unilateral reps:** counts stay as printed; no per-side convention is inferred.
  Warm-up counts for Lean Mass and Hypertrophy are not supplied and stay absent.

The app preview now supports missing reps/rest/effort and marks warm-ups. Source
credits and an original-program link appear in each detail. New clients use
`catalog/manifest-v2.json` so missing durations and numeric preview fields can be handled correctly. The original `manifest.json` endpoint
and its eight entries stay byte-for-byte unchanged for released clients. The
portable JSON import also works offline. These templates appear in the expanded
Library when running the accompanying app changes.
Only the three existing starters remain bundled; new templates download on
explicit preview and then cache locally.

Descriptions and implementation notes are original concise wording. Source
articles, images and videos are not copied. CC BY 4.0 covers the FitLogs JSON
arrangement and original notes, not the linked authors' articles or media.

## Validation

- Python schema/catalog checks verify identities, SHA-256, attribution, mappings,
  bounds, paired supersets, absence of personal loads and published-file retention.
- Source table fixtures check all 90 rows, warm-ups, work reps, phase rests,
  final-set symbols, tempo and source omissions.
- Android tests pass every file through the production parser and explicit
  resolver, round-trip source/schedule, install all three in a synthetic Room
  database and start Spring Shred in the flexible queue with empty actual sets.
  Warm-up flags and superset groups survive into the workout snapshot.
- Test fixtures remain separate from personal workouts/accounts. This work does
  not install an APK, release the app or merge the PR.
