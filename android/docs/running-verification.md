# Running verification — 16 October 2026

Latest History presentation: [shared activity list, filters and new screenshots](history-filter-verification.md).
Implemented in the existing Kotlin/Compose/Room app, with the same application ID, signing identity and theme. Existing dirty/newer work was retained. No storage clearing or destructive reinstall was used.

## Delivered

- Reuse of completed FitNotes running rows, including components of mixed strength/running workouts, with correct units and date-only handling.
- Running filter and details in History; Strength/Running switch and weekly recorded-distance statistics in Progress.
- Foreground Garmin run refresh/import through Health Connect. History periods, paginated preview, cancellation, transactional batches, concise actual counts and safe retry.
- Stable record/client identities, explicit cross-source match review, saved separate/merge decisions and deletion tombstones. Source values, notes and edits remain separate. Existing non-Garmin associations can refresh without creating new non-Garmin runs.
- Additive Room 11→12 migration and current backup format 9 with running associations/source facts. The installed phone migrated from Room 10 through 11 to 12.

## Automated evidence

Final build command used Android Studio’s bundled Java runtime:

```sh
FITNOTES_RUNNING_FIXTURE=/path/to/authorized/private/copy.db ./gradlew \
  :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

- 210 unit tests: **207 passed, 3 optional unrelated private-fixture tests skipped**, no failures/errors.
- Includes pace, exact mile conversion, rounding, invalid values, distinct duration meanings, date-only/explicit timestamps, timezone/DST weeks, same-day runs, record/client-ID refresh, deleted-source tombstones, interrupted/repeated import, distance overlaps/partial records/origins, historical permission denial, pagination/empty results, cross-source review, mixed workouts, preservation and backup round trips. Existing native duplicate repair remaps running associations; logger deletion/Undo keeps them restorable.
- The existing CSV export and authorized local private FitNotes database copy were exercised read-only. Originals were not modified or packaged as fixtures.
- Build and Android lint passed. Lint warnings remain; no lint errors.
- Synthetic emulator workflows covered history/details, editing, selection restoration, import counts/retry, mixed-workout preservation and empty states in light and dark themes, 200% system text, long titles and an explicitly open keyboard. Pixel 6 dimensions: 1080×2400, density 420. Android pointer developer settings on the phone were not changed.

## Physical Pixel 6

The final build was installed with `adb install -r` over Wi-Fi. A recoverable local archive of app databases, private files and preferences was created first. The signing certificate matches the installed app. Comparing all original columns and rows in private pre/post copies found **all 23 original tables unchanged** across the Room 10→12 update. Private archives and record IDs remain ignored local artifacts.

The opt-in device check read the actual Garmin session and distance records. Current access returned one Garmin run, dated **5 October 2026**, with:

| Field | Observed value |
|---|---|
| Distance | 10,190.16015625 m |
| Elapsed time | 4,495.308 s (1:14:55 displayed) |
| Derived elapsed pace | 7:21 /km |
| Start offset | UTC+13 supplied by the source |
| Timer / moving duration | Not supplied; no pause segments were present |
| Source average speed/pace summary | Not supplied by the exercise-session record |

A Strava-origin distance record also overlapped this session, with a different end time. It was not combined with the Garmin distance or timing.

The app already has a plausible FitNotes entry on that date: **10,019 m and 4,486 s (1:14:46)**. This is similarity, not established identity. Two attempted imports correctly required review and left the existing workouts, sets and health associations unchanged. **The real run was not forcibly imported or merged.** Import persistence/retry was verified with synthetic records; the actual cross-source association still needs the user’s “Same run” or “Keep separate” decision in the preview.

The phone supports extended historical reads, but historical permission was not granted. **5 October is the earliest currently accessible Garmin run, not an established archive start.** Older coverage remains unverified. The Garmin app could be launched, but its summary was inaccessible behind the sleeping phone’s lock screen. Device settings and the lock screen were left unchanged. Its own summary was not separately compared; the numbers above come directly from Garmin-origin Health Connect records.

## Screenshots

All images below use synthetic data in the separate preview application. No personal records were replaced for screenshots.

| Screen | Light | Dark, 200% text |
|---|---|---|
| Running history | [Image](../captures/running-20261016/synthetic-running-history-light.png) | [Image](../captures/running-20261016/synthetic-running-history-dark-large.png) |
| Run details | [Image](../captures/running-20261016/synthetic-running-details-light.png) | [Image](../captures/running-20261016/synthetic-running-details-dark-large.png) |
| Weekly progress | [Image](../captures/running-20261016/synthetic-running-progress-light.png) | [Image](../captures/running-20261016/synthetic-running-progress-dark-large.png) |
| Import preview | [Image](../captures/running-20261016/synthetic-running-import-preview-light.png) | [Image](../captures/running-20261016/synthetic-running-import-preview-dark-large.png) |
| Match review | [Image](../captures/running-20261016/synthetic-running-import-review-light.png) | [Image](../captures/running-20261016/synthetic-running-import-review-dark-large.png) |
| Import result | [Image](../captures/running-20261016/synthetic-running-import-result-light.png) | [Image](../captures/running-20261016/synthetic-running-import-result-dark-large.png) |
| Partially populated run | [Image](../captures/running-20261016/synthetic-running-partial-details-light.png) | [Image](../captures/running-20261016/synthetic-running-partial-details-dark-large.png) |
| Empty history | [Image](../captures/running-20261016/synthetic-running-empty-history-light.png) | [Image](../captures/running-20261016/synthetic-running-empty-history-dark-large.png) |
| Empty progress | [Image](../captures/running-20261016/synthetic-running-empty-progress-light.png) | [Image](../captures/running-20261016/synthetic-running-empty-progress-dark-large.png) |
| Keyboard-open edit | [Image](../captures/running-20261016/synthetic-running-edit-keyboard-light.png) | [Image](../captures/running-20261016/synthetic-running-edit-keyboard-dark-large.png) |

## Remaining decisions and limitations

- Review the actual FitNotes/Garmin candidate before associating its records; the difference in source distance/duration was retained.
- Grant historical access, then use All available to determine whether Garmin has written older runs. Permission cannot make Garmin backfill its archive.
- Unsupported or ambiguous distance is omitted; missing distance never becomes zero. No sparse-speed integration, inferred moving time or cross-provider distance fallback is used for runs.
- No Strava API connection was added. Current API policy restrictions conflict with a persistent combined archive and ChatGPT exports. Registration/OAuth/server-side secret handling alone would not resolve those restrictions. See the [official-source assessment and conditional setup requirements](running.md#strava-assessment).

No source-data discrepancy is claimed corrected without established identity. No live GPS, coaching, routes or race estimates were added.
