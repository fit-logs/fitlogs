# Google Sheets dashboard

Room remains the source of truth. A dashboard is an optional copy of completed
training records in the Google account chosen by each user. Every user gets
their own spreadsheet and FitLogs folder; no shared developer Drive is used.

## Projection and template

- `DashboardSnapshot.project` consumes one transactional `Backup` snapshot.
- Schema version 1 exports stable session, set, activity and measurement IDs.
- In-progress workouts, historical drafts and prescriptions are excluded.
- Notes and body measurements are always included when Google is connected.
  There are no inclusion switches or saved inclusion preferences.
- Photos, body profiles, birth dates, source payloads, heart-rate samples and
  credentials never enter the dashboard projection.
- Existing exercise identities, explicit running associations and strength
  statistics determine the output. Unknown loads/times stay blank. Epley
  estimates use external working sets of 1–10 reps only.
- Source dates/offsets are retained; timed local sessions use the captured
  calendar time zone. UTC timestamp columns are labelled explicitly.
- Native tabs: Dashboard, Strength progress, Workout history, Running and
  activities, and Body measurements. Hidden tables and `_meta` hold
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
- Sheets and Drive APIs are enabled. Branding points to the public homepage
  and privacy policy. The External authorization app was moved to **Production**
  on 2026-10-28, with explicit owner approval, so each user can authorize their
  own account without being added to a test-user list. Only the non-sensitive
  `drive.file` scope is declared and requested.
- Public app/privacy documentation is built and checked by
  `.github/workflows/public-pages.yml`, deployed from main at
  `https://fit-logs.github.io/fitlogs/`. These static pages receive no training
  records or Google credentials.
- Each phone must approve its own Google access. Console registration does not
  grant the developer access to anyone's files.

## Use and privacy

- Settings → Google training dashboard → Connect and create dashboard.
- Google chooses the account and asks for permission. There is no client
  secret, service account, developer data server or custom password form.
- The dashboard always includes notes and body measurements. Automatic sync
  starts disabled and can be enabled after the first successful manual sync.
- Open dashboard/Drive folder use the selected account's private files.
- Account switches require an explicit action and create a new private dashboard.
  Existing files are kept, and automatic sync starts disabled on the new connection.
- Disconnect revokes Google access when online and always clears local linkage
  and queued sync. Offline users can also revoke it in Google account settings.
- Connection metadata is device-local, excluded from training exports and the
  Android backup whitelist. Bearer tokens exist only in memory and request
  headers to fixed Google API origins; redirects are disabled.
- Shared, foreign-owned, moved, missing or renamed managed files/tabs are
  refused before training writes. Create a new dashboard to keep an altered
  file intact. The app never calls a sharing/permissions mutation endpoint.

## Refresh and recovery

- A transactional Room projection supplies one completed-training snapshot.
- Fixed managed IDs, literal `RAW` strings and bounded batches replace only
  app-managed columns. Unrelated tabs, columns and charts are preserved.
- Durable pending row counts allow a smaller retry to clear partial upload
  leftovers. `_meta` and the local success time advance only after all writes.
- Each publish is bound to the connection generation captured before
  authorization. Queued old authorizations cannot re-bind an account after a
  disconnect or account switch. Sheet locale/time zone follow the snapshot.
- A lost file-creation response is recovered using the account-bound app
  marker. Existing recovered tabs are preserved.
- WorkManager coalesces opted-in changes for 15 seconds, requires a network,
  waits out active workouts, and retries temporary failures with exponential
  backoff. Background work never opens a consent prompt.
- Rebuild dashboard charts restores the managed chart positions and formatting.
  Create a new dashboard preserves the old file. Deleting remote files stays
  under the user's control in Drive.

## Verification

- Pure projection/template tests cover completed results, units, provenance,
  unknown values, stable IDs and privacy exclusions.
- Fake API tests cover account/shared-file guards, idempotence, partial writes,
  stale rows, recovery, cancellation, managed layout and bounded Unicode batches.
- WorkManager tests cover disabled behavior, coalescing/network constraints,
  cancellation and safe HTTP error classification.
- A fresh synthetic emulator verified the inclusion disclosure, absence of
  inclusion switches, behavior after rotation and unchanged local training
  records. No personal app was cleared.
- A separate native Google Sheets preview accepted the template and charts and
  was inspected in the browser using synthetic data only.

Real-device Android consent, token expiry/revocation, account switching and
manual/automatic sync with `drive.file` still need live validation. Console
Production status and connector-created previews do not prove that the Android
client has authorized an account. Optional recoverable Drive backup/restore
remains a separate follow-up; manual JSON/photo backup is unchanged.
