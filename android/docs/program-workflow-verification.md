# Program workflow delivery

| Step | Delivered behavior | Change |
| --- | --- | --- |
| Train | One active multi-day program, explicit next day, separate reusable templates | [PR #21](https://github.com/avivajpeyi/fitnotes/pull/21) |
| Import | Whole-program JSON/text, editable preview, explicit exercise matching, file/share and PDF/URL conversion fallbacks | [PR #22](https://github.com/avivajpeyi/fitnotes/pull/22) |
| Personal targets | Offline actual-history engine, setup isolation, Use/Change, actual effort and session locks | [PR #23](https://github.com/avivajpeyi/fitnotes/pull/23) |
| Public library | Eight original versioned definitions, three offline starters, cache and safe installation | [PR #24](https://github.com/avivajpeyi/fitnotes/pull/24) |

The earlier Android baseline reconciliation is in
[PR #14](https://github.com/avivajpeyi/fitnotes/pull/14). Work was isolated from the
original dirty checkout and synthetic emulator fixtures use the separate preview
application ID. No personal-app clearing or physical-device result is claimed.

## End-to-end synthetic regression

[`ProgramWorkflowTest`](../app/src/test/java/com/example/fitnotes/ProgramWorkflowTest.kt)
exercises the real Room repositories through the complete workflow:

1. Parse a synthetic private conversion with two ordered days. “Press” has two
   possible aliases, so saving fails until the user explicitly selects Bench Press.
2. Save one three-week program with two owned days and no performed sets added.
3. Review history of 80 kg × 6 × 3 with reported RIR 2 on two distinct dates.
   For 8–10-rep targets at RIR 2, the reviewed proposal is 75 kg.
4. Use the targets, start the next day, lock the session and log actual
   75 kg × 10 × 3 at reported RIR 3. Retried completion adds no duplicate actuals.
   Finishing alone never advances the program; explicit day completion does.
5. Repeat the next prescribed day on another date, then review the following
   session. Two full upper-range sessions with headroom propose one step to
   77.5 kg. Applying it preserves all earlier actuals and target snapshots.
6. Round-trip the full backup and refresh the separate public catalog. Actual
   history, locks, program position and the next suggestion remain intact.

Fixture dates are adjusted through the supported retrospective timing editor;
the test does not wait multiple days or imply a real training outcome. The
conversion fixture contains original synthetic text, not a copied routine.

## Request lifecycle regression

Import request production and consumption now share the ViewModel's lifetime.
Previously, Compose saved a consumed counter while a recreated model restarted
its counter at zero. A new first request could reach the model without opening
its preview. The UI regression reproduced this failure before the fix.

[`ProgramWorkflowInteractionTest`](../app/src/androidTest/java/com/example/fitnotes/ProgramWorkflowInteractionTest.kt)
checks that rotation does not replay a closed import, a fresh model with retained
UI state accepts a new conversion, and another subsequent request also opens.
The fresh-model boundary is simulated by clearing only the isolated test
activity's model store before recreation. Training data must remain unchanged.
Existing actual Android text/URL/file-share regressions run alongside it.

## Public hosting and offline boundaries

The [live library](https://avivajpeyi.github.io/fitnotes/library/) was published by
[Pages run 39928558542](https://github.com/avivajpeyi/fitnotes/actions/runs/39928558542).
Live checks verified HTTP 200 for HTML, the manifest, shared schema and all eight
program files; JSON MIME and all program SHA-256 digests matched source. Pages
returned ETags and `Cache-Control: max-age=600`; the app requests revalidation.

The opt-in emulator network test fetches the actual public manifest and an
unbundled program using Android's real HTTPS transport, then reopens it through
a repository that rejects network access. A separate force-stop test runs with
Wi-Fi/mobile data off and checks the downloaded version plus unchanged training.
Normal automated runs skip this live test unless `allowCatalogNetwork=true`.
The paired offline restart check is explicitly selected with
`verifyDownloadedCatalogRestart=true` after the download fixture exists.

All numeric advice remains local. Public requests contain only fixed manifest
and version-file paths. Installed programs are editable local copies, not live
catalog references. Room 15, backup v13 and analysis v6 remain unchanged by this
verification/lifecycle fix. Physical-device network checks are not substituted
by the isolated emulator results.

Strava API connection/incremental sync remains separate in issue #7; the merged
owner-export route and its API-policy limitations are documented in
[Strava owner export](strava-owner-export.md).
