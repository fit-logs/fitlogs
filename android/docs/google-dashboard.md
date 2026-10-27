# Google Sheets dashboard

Room remains the source of truth. A dashboard is an optional copy of completed
training records in the Google account chosen by each user. Every user gets
their own spreadsheet and FitLogs folder; no shared developer Drive is used.

## Projection and template

- `DashboardSnapshot.project` consumes one transactional `Backup` snapshot.
- Schema version 1 exports stable session, set, activity and measurement IDs.
- In-progress workouts, historical drafts and prescriptions are excluded.
- Notes and body measurements are separately opt-in, both off by default.
- Photos, body profiles, birth dates, source payloads, heart-rate samples and
  credentials never enter the dashboard projection.
- Existing exercise identities, explicit running associations and strength
  statistics determine the output. Unknown loads/times stay blank. Epley
  estimates use external working sets of 1–10 reps only.
- Source dates/offsets are retained; timed local sessions use the captured
  calendar time zone. UTC timestamp columns are labelled explicitly.
- Native tabs: Dashboard, Strength progress, Workout history, Running and
  activities, and optional Body measurements. Hidden tables and `_meta` hold
  the schema, chart inputs and managed range sizes.
- Fixed sheet/chart IDs prevent duplicate tabs and charts. Four charts maximum:
  weekly sessions, recorded running distance, one exercise's strength, and one
  measurement type/unit. User strings are literal values, never formulas.

The synthetic template is reproduced by `DashboardWorkbookTest` at
`android/app/build/dashboard-synthetic-create.json`. Formatting requests come
from `DashboardWorkbook.formatting`; no personal records are used by the test.
The native Sheets API accepted these chart/format requests against a separate
synthetic preview workbook. Sheets requires the supported `en_GB` locale;
`en_NZ` is rejected. Local calendar grouping still uses the captured time zone.

## Android Google registration

Google Home APIs are unrelated to Sheets/Drive. Follow the
[Android authorization guide](https://developer.android.com/identity/authorization)
and request only [`drive.file`](https://developers.google.com/workspace/drive/api/guides/api-specific-auth).
The Sheets API accepts this scope; full Drive or full Sheets access is not needed.

- Cloud project: `fitlogs-512910` (FitLogs).
- Enable `sheets.googleapis.com` and `drive.googleapis.com`.
- Android package: `com.example.fitnotes`.
- Existing release certificate SHA-1:
  `D5:06:2B:36:E5:8B:41:69:89:56:D5:1B:FF:54:4B:9E:83:8F:C4:10`.
- Android client: `FitLogs Android signed releases`. No client secret belongs
  in the APK or GitHub secrets. Android identity uses the package/certificate.
- During setup, use External/Testing and explicitly allow test accounts.
  Before general availability, finish Branding and switch to Production so
  every app user can authorize their own account; a test-user list is temporary.
- Public app/privacy documentation is built and checked by
  `.github/workflows/public-pages.yml`, deployed from main at
  `https://fit-logs.github.io/fitlogs/`. These static pages receive no training
  records or Google credentials.
- Each phone must approve its own Google access. Console registration does not
  grant the developer access to anyone's files.

## Delivery stages

1. Projection/template and synthetic correctness checks.
2. Google Identity authorization, private per-account folder, manual idempotent
   snapshot sync, account/file ownership validation, literal `RAW` values,
   stale-row clearing, disconnect and actionable errors.
3. Optional coalesced WorkManager sync after relevant local changes.

Live account consent, expired/revoked access, account switching and a real
spreadsheet render need separate verification. A local template test does not
prove that an account has authorized the Android client.
