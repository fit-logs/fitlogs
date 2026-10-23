# Workout Library

**Train → Explore programs / Import → Workout Library** browses eight original,
generic programs independently of local templates. Search and combine goal,
experience, days/week, duration and available-equipment filters. Equipment
selection requires everything a program lists; no selection means any equipment.

Preview the complete week, including working sets, rep ranges, rest, RIR,
alternatives and progression guidance. **Review installation** opens the same
editable whole-program preview and exercise resolver used for ChatGPT/paste JSON.
Unknown exercises require explicit matching/creation. **Save program** creates
one local program with owned workout days, without recording any actual sets.
Activate/pause it in **My programs** and follow **Start next workout**.

Installed definitions and editable targets are independent of the public cache.
Same-version duplicates warn before another copy is saved. New catalog versions
are separate installations and never upgrade/replace an edited local copy.
Personal numeric weights use the [offline history engine](history-load-suggestions.md),
without sending history to the library.

## Offline and network behavior

- Full Body Beginner, Full Body Express and Dumbbell Home are bundled as exact
  copies of the public JSON. Their previews/installations work on first launch
  with Wi-Fi/mobile data off. The manifest lists all eight programs and labels
  which are available offline.
- **Refresh catalog** fetches only the manifest. Opening an uncached program
  downloads its versioned JSON. Closing the library leaves logging usable during
  a slow request; catalog work has its own state/mutex and no database reference.
- Requests are fixed-host HTTPS GETs to
  `https://avivajpeyi.github.io/fitnotes/catalog/`, with relative allowlisted paths,
  no redirects, JSON content-type checks, 8-second connect/read timeouts and
  256-KB response limits. No account, tokens, analytics, upload or personal query
  parameters are used.
- Validate a manifest before replacing it. Verify the program's SHA-256, identity,
  schema, equipment/schedule and canonical exercise references before caching.
  Invalid downloads never replace a valid file. Atomic writes protect interrupted
  saves; corrupt/unknown cache envelopes fall back to bundled starters.
- Cache envelope v1 records the last successful refresh. Failed refreshes show an
  error while keeping cached entries; the current attempt's error is transient.
  At most 64 public version files are retained, preferring current manifest files.
  Installed programs remain usable even if the cache or network disappears.

Room stays at 15; no personal database migration is needed for the public cache.
Installed copies remain in backup v13. The separate cache is excluded from
training backups and the existing automatic cloud/device-transfer exclusions
cover its private files.

## Public content and release rules

The [website](https://avivajpeyi.github.io/fitnotes/library/) and static JSON live
under `web/public/catalog/`. These are independently authored generic four-week
prescriptions, with two working sets as the usual starting volume (three for
Strength Fundamentals), no preset personal weights, warm-ups kept separate,
spare-rep guidance and explicit alternatives. The six-day split is labelled
intermediate and points people with limited recovery/time to the three-day
option. These are internally reviewed starting routines, without an external
coach endorsement or promised outcomes.

Original definitions/descriptions are **CC BY 4.0**, attributed to FitNotes
contributors; credit and license links appear in JSON, Android and the website.
No third-party articles, graphics, screenshots or branded routine packages are
reproduced. The private ChatGPT conversion path remains separate.

`data/programs/schema-v1.json` is canonical. Web prebuild mirrors it to
`catalog/schema-v1.json`; Android uses `PortableProgramParser` for both sources.
Gradle copies the three exact public starter files into generated assets.
Keep program IDs stable, add a new `programVersion`/file for content changes,
update the manifest/hash, and retain previously published files. PR CI rejects
modified/deleted published program versions and validates old versions against
the shared schema. Local edits remain independent of either release.

## Verification

- Python schema/content checks and negative fixtures cover malformed JSON,
  duplicate IDs, missing files/exercises, incompatible schemas, rep ranges,
  personal weights and licensing. Static export checks compare all JSON/schema
  bytes with source. The Pages build runs the same gate before publishing.
- Android unit/Room tests run all eight through the actual paste parser/resolver,
  test offline starters, cached restart, invalid/interrupted caches, safe updates
  alongside edited/installed programs and actual history, filters and HTTP
  failures/response bounds.
- Isolated preview emulator flows cover offline filters/detail, installation,
  activation, untouched actual history, empty target snapshots, duplicate review,
  failed refresh and force-stop restart in light and small dark/150% text modes.
  Captures are retained under ignored `captures/issues-20261023/`.
- Browser checks cover combined filters, no-match state, full-week preview,
  copying JSON and visible feedback. Local static export and live Pages responses
  are checked separately. Physical-device network and personal-history checks
  are outside these synthetic tests.
