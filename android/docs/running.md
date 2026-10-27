# Running

FitNotes uses its existing workouts, imported FitNotes rows and Health Connect associations. Running does not add another activity store.

- **History:** runs, gym and climbing sessions share one chronological list and the same dated rows. Use the activity filter for All activities, Gym, Climbing or Running (other recorded activity types appear when available). Pure running imports show running summaries; mixed workouts stay together and appear under both Gym and Running. Tap a run for distance, duration, pace, notes and available heart rate.
- **Progress → Running:** recorded distance by Monday–Sunday week, period total, run count, longest recorded run and recent runs. The current week is marked as incomplete. Missing distances count as runs but add no distance.
- **Settings → Health sync → Import running history:** last three months, last year or all accessible Garmin records. Preview dates/counts, review possible matches, then import. Foreground sync refreshes associations and imports new Garmin runs without plausible existing matches.

## Data rules

Distance is stored in metres. Elapsed, timer, moving and unspecified recorded duration stay distinct. Pace uses positive, finite distance and compatible time; display preference is timer, moving, elapsed, then recorded duration. The detail screen names that basis. Mile conversion is exactly 1,609.344 metres. Display rounding carries seconds into the next minute.

FitNotes CSV/native distance units and original source rows are preserved. Recognised existing names include Running (Outdoor), Running (Treadmill), 2Km Run and Jog. Aliases resolve through the current exercise identity index. A mixed workout keeps its strength sets; its aggregate timestamps are not assigned to an individual running component. An explicit timestamp on a sole running record is retained. Date-only rows receive no invented times.

Health Connect running summaries use Garmin-origin exercise and distance records. A distance total requires complete, unambiguous same-origin coverage of one session: either one exact interval or adjacent nonoverlapping intervals covering that session. Overlapping totals/components, partial records, multiple overlapping same-origin sessions and other providers cannot supply an inferred total. Unsupported distance is omitted. Speed samples are never integrated. Timer duration is derived only from validated explicit pause segments; no pauses is not evidence of timer duration. Source facts and distance evidence remain in the association for diagnostics.

Source record IDs and provider client IDs support refresh/replacement without new activities. Similarity across sources only creates review candidates. “Same run” keeps both records, source facts and notes and changes their canonical presentation; the destination supplies totals. A recovery backup is created first. “Keep separate” is saved. Deleted runs retain source tombstones so automatic/repeated imports do not resurrect them. Local deletion does not delete provider data.

Historical reads exhaust page tokens, including platforms returning an empty final token. Reads can be cancelled. Writes commit in transactional batches of 20; restart/retry safely reuses committed source identities. New backups contain associations, source facts, review decisions and tombstones; the additive Room 11→12 migration keeps existing data and photos.

## Health Connect access

The running flow requests exercise and distance reads. It uses existing heart-rate permission if granted, without requiring it. Historical access is requested only when the platform reports support. Background read is not requested. Without extended history permission, the platform restricts reads to its first-permission history boundary. Access does not cause Garmin to backfill older data. Empty results do not establish absence of older activity.

Reviewed 16 October 2026: [Android raw reads, history limits and pagination](https://developer.android.com/health-and-fitness/health-connect/read-data), [feature availability](https://developer.android.com/health-and-fitness/health-connect/features/availability), and the official AndroidX Health Connect 1.1.0 record/permission sources bundled with the project dependency.

## Strava assessment

No Strava API import or Connect button is included. The existing application has no Strava OAuth/token service. More importantly, the current API Policy restricts persistent archives and combined analytics (§5.4–5.5, §6.2) and prohibits sending API-derived data to AI context windows (§5.3). A normal OAuth setup alone does not resolve those restrictions for this notebook and its ChatGPT export workflow. Written authorization for the intended storage/analytics/export use would be needed before implementing that API pipeline.

If permitted, setup would require a registered application and callback domain, a secure server-held client secret for code exchange/refresh, read-only activity scopes (`activity:read`, or `activity:read_all` for private activities), protected rotating tokens, revocation/deletion handling, and capacity approval where applicable. No secret should enter the APK. Fetch paginated activity summaries, preserve moving/elapsed time and IDs, and obey response quota headers/429 backoff. Current documentation describes single-user initial capacity; dashboard upgrades/review change capacity and limits. Published default read quotas are 100/15 minutes and 1,000/day, with upgraded limits differing; actual response headers/dashboard govern.

Official sources reviewed 16 October 2026: [registration/capacity](https://developers.strava.com/docs/getting-started/), [OAuth/scopes/token handling](https://developers.strava.com/docs/authentication/), [rate limits](https://developers.strava.com/docs/rate-limits/), [activity summary reference](https://developers.strava.com/docs/reference/#api-Activities-getLoggedInAthleteActivities), [API agreement](https://www.strava.com/legal/api), [API policy](https://www.strava.com/legal/api_policy). Garmin-origin Health Connect data is used directly; no Strava API credentials, scraping or backend were added.


### Personal Strava download

Strava also provides a user-operated account export, separate from connecting an application to its API. On the Strava **website**, choose **Settings → My Account → Download your account → Get Started → Request download**. Strava emails the archive link. Individual activities can also be exported in their original format (often FIT), or TCX/GPX where supported. This is a practical way to obtain older personal recordings without an OAuth backend. It does not establish that an API integration has been authorized or connected.

Reviewed 16 October 2026: [Strava account and individual-activity export instructions](https://support.strava.com/en-us/articles/15401919-how-do-i-export-my-strava-data), and [API Policy §6.6](https://www.strava.com/legal/api_policy) confirming the user-facing bulk-export right. FitNotes does **not yet parse the Strava archive**. An archive importer should inspect actual supplied files, preserve source activity identities and distinct duration fields, then use the existing candidate review/canonical-run pipeline. No CSV column order, distance units or timezone interpretation should be guessed from an absent archive. No Strava-derived data is being sent to ChatGPT by a new connector.
