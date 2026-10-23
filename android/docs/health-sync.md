# Health Connect activity import

- Open **Settings → Garmin via Health Connect**, grant exercise read access,
  choose sources/activity types, and enable automatic import or use **Sync now**.
  Garmin Connect must publish the activities to Health Connect first.
- New selected activities become completed sessions. Timed overlaps, same-day
  date-only local sessions and overlapping provider copies require explicit
  review. The importer never silently merges these potential duplicates.
- Existing links refresh by origin/record ID or origin/client ID and version.
  Local sets, notes, workout times and IDs remain intact. Source deletions and
  unavailable records retain the local activity and explain the source status.
  Ignore and local-deletion decisions survive source ID replacement and backups.
- The connection shows last attempt, last completed sync, imported/refreshed,
  review, missing-source and error counts. Sync has its own busy state; workout
  logging, navigation and set saves remain available during source reads.
- Foreground/resume reads have a 60-second cooldown. Leaving the foreground
  cancels that read. Separately supported/granted background access can enable
  one WorkManager job (hourly target, Android-controlled timing, retry backoff).
  Turning import/background off or revoking its permission cancels the job.
- Heart rate and distance are optional. Their permission changes do not erase
  saved samples. Read failures retain base activities and leave the page cursor
  behind those writes so supplemental data can retry safely.
- **Custom dates** deliberately reads older history. Request optional extended
  history only on supported devices. Default access may start only 30 days before
  the first grant; requested bounds and returned counts do not prove complete
  Garmin history. No results is distinct from source failure or confirmed deletion.

## Persistence and replay

- One coordinator and mutex serve manual, foreground and worker reads. SDK work
  happens outside Room write transactions. Source identities, attachments and
  deletion/ignore decisions are rechecked inside each 20-record write batch.
- Separate exercise, heart-rate and distance Changes tokens are obtained before
  the bounded initial snapshot. Every feed drains all pages, including metric-only
  corrections/deletions. Tokens checkpoint after the whole page has applied.
  Interrupted writes/checkpoints replay by stable identities without new workouts.
- Expired tokens trigger a fresh bounded 30-day reconciliation. All linked selected
  source intervals are conservatively refreshed after metric changes, because a
  deleted metric record provides no previous interval. Repeated/blank cursors fail
  visibly without skipping the unconsumed page.
- Device preferences store connection choices, cursors and status; Room v9 and
  backup v7 are unchanged. Backup restore disables the connection and resets its
  device cursors before replacing data. Reconnect deliberately on that device.
  Existing healthSessions persist source provenance and tombstones in backups.
- New source-created workouts carry an explicit persisted provenance marker;
  attached or legacy local workouts are never inferred as source-owned from an
  empty set list or date alone. Worker Room instances use multi-instance invalidation.

## Verification

Automated fixtures use private databases and the isolated preview application
`com.example.fitnotes.preview.photos`, never the personal application's data.

- Deterministic stream tests cover pre-snapshot tokens, multi-page races, partial
  batches/checkpoints, token expiry, deletion/ignore replay, source-ID replacements,
  revoked grants, metric-only updates/deletions, malformed cursors, source failure,
  filters and concurrent reads/disable. Set saves succeed while the source is blocked.
- Official FakeHealthConnectClient tests exercise real SDK records/paging,
  pre-snapshot race replay, late metrics, expiry, permissions and older-history
  enrichment after a recent cached read. The alpha02 fake's empty-feed cursor bug
  is isolated in the fixture; deterministic stream tests cover cross-run cursors.
- WorkManager tests check one unique job, permission/feature gates, cancellation,
  and disabled-worker completion without a Health Connect read.
- Emulator interaction checks exercise connection status/source choices and save
  an actual gym set while a synthetic SDK snapshot is paused, then complete two
  source activity imports without navigating away from the logger.

Final automated build/unit/lint checks: 158 tests, 0 failures/errors, 1 optional
skip; lint 0 errors and 50 warnings. Normal debug and isolated preview APKs
compile. Emulator checks passed at normal/light and 150%/dark settings.

Real Garmin publication, a physical phone's grants and Android's eventual
background scheduling have not been verified by these synthetic checks.

Official references:

- [History, feature gates and paged reads](https://developer.android.com/health-and-fitness/health-connect/read-data)
- [Changes tokens and synchronization](https://developer.android.com/health-and-fitness/health-connect/sync-data)
- [WorkManager releases](https://developer.android.com/jetpack/androidx/releases/work)
