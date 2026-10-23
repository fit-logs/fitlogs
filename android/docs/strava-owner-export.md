# Strava activity files and API decision

Issue #7 remains open for live account authorization/incremental sync. This change
implements its owner-export fallback and duplicate-review step.

## Current API decision — checked 2026-10-23

The [API policy](https://www.strava.com/legal/api_policy), effective June 1, 2026,
limits caching to seven days (§6.2), disallows persistent archives (§5.5) and
prohibits API-derived data in AI context (§5.3, with a Strava MCP exception).
It also requires reflecting remote deletions and removing API data after access
revocation. Those restrictions conflict with FitNotes' durable offline history,
training backups and ChatGPT-context workflow. The policy separately preserves
the user's account bulk-export right (§6.6).

The [API agreement](https://www.strava.com/legal/api) says API access itself is
currently uncharged; policy §3.3 also describes tier subscription requirements.
The owner's registered app/tier/subscription has not been verified, so this is
not a claim about the owner's actual cost or eligibility. The repository has no
registered client or token service. No live account/API test was performed.

Published [rate limits](https://developers.strava.com/docs/rate-limits/) currently
start new apps at one athlete, with default read limits of 100 requests per
15 minutes and 1,000 per day. Account-specific limits must be checked in that
app's dashboard. No capacity increase is assumed.

[OAuth](https://developers.strava.com/docs/authentication/) would require a
registered client, validated state/callback, granted read scopes, and a trusted
secret-holding code-exchange/rotating-refresh service. It must not embed a client
secret in an APK. That flow is not enabled here. API authorization, 401/429 handling,
revocation/retention, scheduled sync and per-metric provider preferences remain
unimplemented pending a compatible approved workflow. This change does not claim
that Strava is connected or that every issue #7 acceptance criterion is complete.

## Supported owner-export flow

1. Request your archive using Strava's own [account export tool](https://support.strava.com/en-us/articles/15401919-how-do-i-export-my-strava-data).
2. Extract the archive. Open **Settings → Strava activity files** (also available
   through Import history), then choose its `activities.csv`.
3. Enter a stable owner key (athlete ID or label); reuse it for future exports from
   that owner. It namespaces source IDs and is explicitly not authenticated.
4. Confirm timezone for timestamps without an offset and units for the selected
   columns. Repeated metric headers show column numbers; choose deliberately.
5. Load a date-range preview, resolve possible matches, then confirm the selected
   batch once. **Keep separate** explicitly retains a distinct activity. **Ignore**
   persists a source decision. A different saved local/original workout can be
   selected explicitly for attachment.

Only use the account export supplied by Strava's user-facing tool. This importer
is not an API harvesting/conversion service. It performs no HTTP requests and
stores no OAuth credentials.

The parser supports UTF-8 CSV (20 MB maximum), quoted/multiline fields, stable
numeric Activity ID, Activity Date, Activity Name and Activity Type. Elapsed Time
and its units are required. Optional named columns: Activity Description, Distance,
Moving Time, Elevation Gain, Average Heart Rate and Max Heart Rate. Unknown columns
are ignored; malformed/unsupported rows are reported. Conflicting repeated IDs
are excluded, not resolved by row order. Real export variants not represented by
the synthetic fixtures remain unverified and may need explicit column adaptation.

- Offset-bearing ISO timestamps retain that offset. Offset-free ISO/English
  export timestamps need a confirmed timezone. Ambiguous/nonexistent DST times
  need an explicit offset; no UTC/local assumption is made.
- Time units: seconds, minutes or h:m:s. Distance: m/km/ft/mi. Elevation: m/ft.
  Missing metrics stay unknown. Unselected optional columns retain previously
  saved snapshots; selected blank fields clear that imported metric. Source-reported moving time stays separate from
  elapsed time/pace. HR summaries never manufacture per-second samples or sets.
- Matching suggestions use an indexed start-time window off the UI thread and
  require activity type, known overlapping local or attached source intervals and
  compatible distance when both are known. Titles/dates alone never match.
  Suggestions always need explicit attachment; previously deleted/ignored nearby
  sources also hold a new provider record for review. There is no inferred shared
  external-ID match when the export contains no documented cross-provider ID.
- The importer shares the connection's execution mutex, writes in 20-record Room
  batches, and keeps its progress separate from the workout logger's busy flag.
  Reads/parsing happen outside write transactions. Cancellation retains committed
  batches; reselecting the same owner/IDs safely refreshes them.
- Source snapshots are separate from local workout fields. Attachments preserve
  local IDs, actual sets, notes and recorded times. CSV absence cannot establish a
  remote deletion. Deleting a local workout or ignoring a record retains its
  provider/account/activity tombstone, including through backup restore.

## Persistence and verification

The existing source-link model now carries a provider and account namespace;
Health Connect records retain their original origin/record keys. Owner-export
keys are `strava_owner_export:<owner-key>:<activity-id>`. This is not an authenticated
API identity. Raw sport and source-reported HR summaries retain provenance.

Room 10 → 11 adds these fields without replacing tables or records. Backup v9 and
analysis JSON v4 retain them; earlier backups remain readable. Encoding an older
format cannot silently discard provider metadata. Health Connect Changes/deletion
feeds operate only on Health Connect records, never on owner-export identities.

Synthetic unit tests cover timezone/DST/units, column ambiguity, optional metrics,
row errors/conflicting IDs, same-day distinct activities, stable-ID refresh,
Garmin duplicate review/attachment, original-history attachments, gym preservation,
interrupted batches, deletion/ignore replay, migration and backup/analysis.
The isolated emulator check reviews a Garmin attachment, imports a new ride and
then saves another gym set while preserving the active session.

Validation: normal debug and isolated preview builds pass; 181 unit tests,
0 failures/errors, 1 optional skip. Lint: 0 errors and 50 warnings. The isolated
API 37 emulator interaction passes in normal/light and 150%/dark settings.
No real owner export, OAuth session, account credentials or physical phone was used.
