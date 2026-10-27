# Shared activity history — 16 October 2026

History now uses one month-grouped, chronological list for gym, climbing and running. A single activity filter replaces the separate running-list branch. The filter is remembered. Other recorded activity types are also available in the menu.

Pure running imports use canonical run identities, statistics and run details. Mixed workouts stay together, retain every strength/cardio record, and appear under both Gym and Running. Distinct date-only runs on the same day remain separate. Merged/deleted running components cannot fall back into the list as gym workouts. No database migration or stored-record repair was needed for this presentation change.

## Checks

- Production debug APK built successfully. Lint: zero errors, 51 warnings. Unit suite: 214 tests, 210 passed, four optional fixture checks skipped, no failures/errors. Four new tests cover activity membership, mixed-workout preservation, same-day source identities and ignored/merged records.
- Four synthetic emulator workflows passed: shared history/filtering/recreation/navigation, details/editing/progress, import preview/retry/counts, and empty states. The shared-history workflow also passed with dark theme and 200% system text at Pixel 6 dimensions (1080×2400, density 420).
- Visual review caught wrapping of the existing Progress bottom-navigation label at 200%. Labels now use the available item width without shrinking text.
- Updated the Pixel 6 through Wi-Fi with `adb install -r`. Application ID and signing certificate match the installed app. A private, ignored local backup retains the original installed APK, database, files and preferences. Comparing all 27 database tables before/after installation found no changed tables; Room remains version 12. The original photo table was empty. No phone developer settings were changed. The shared-History build was installed successfully. The subsequent large-text navigation-label adjustment was built and tested, but the phone disconnected from Wi-Fi debugging before that final APK could be reinstalled. [Latest APK](../captures/history-filter-20261016/fitnotes-history.apk).

## Screenshots

These use synthetic records in a separate preview application. The earlier screenshot shows the previous separate running list; the new all-activities fixture also includes gym and climbing records.

| View | Light | Dark, 200% text |
|---|---|---|
| Earlier running list | [Before](../captures/running-20261016/synthetic-running-history-light.png) | [Before](../captures/running-20261016/synthetic-running-history-dark-large.png) |
| Shared History | [After](../captures/history-filter-20261016/synthetic-running-unified-history-light.png) | [After](../captures/history-filter-20261016/synthetic-running-unified-history-dark-large.png) |
| Climbing filter | [Image](../captures/history-filter-20261016/synthetic-running-climbing-filter-light.png) | [Image](../captures/history-filter-20261016/synthetic-running-climbing-filter-dark-large.png) |
| Gym filter, mixed workout | [Image](../captures/history-filter-20261016/synthetic-running-gym-filter-mixed-light.png) | [Image](../captures/history-filter-20261016/synthetic-running-gym-filter-mixed-dark-large.png) |
| Running filter, mixed workout | [Image](../captures/history-filter-20261016/synthetic-running-running-filter-mixed-light.png) | [Image](../captures/history-filter-20261016/synthetic-running-running-filter-mixed-dark-large.png) |

## Strava

[Strava's official account export](https://support.strava.com/en-us/articles/15401919-how-do-i-export-my-strava-data) provides a practical way to obtain older personal recordings. Website: Settings → My Account → Download your account → Get Started → Request download. Strava emails the archive link. Original individual activity files can also be exported. The current app does not parse that archive yet, and no automatic Strava connection has been added.

Automatic API access requires registration, OAuth and secure server-side secret/token handling. Current [API Policy](https://www.strava.com/legal/api_policy) restrictions on permanent archives, combined analytics and AI ingestion also need to be resolved for this app's intended use. The policy separately preserves the user's bulk-export right (§6.6). See [the running source assessment](running.md#strava-assessment). No archive format or fields have been guessed, no credentials were requested, and no Strava data is claimed imported by this change.
