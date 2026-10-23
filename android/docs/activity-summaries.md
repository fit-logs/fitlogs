# Imported activity summaries

- History, workout details and training-context previews share one resolver.
  Local title, recorded times, reported duration and distance take precedence.
  Source fields stay separate from those editable local records.
- Explicitly source-created activities use their source start, original offset
  and source date for display and ordering. Corrections update that presentation
  without inventing a local start. A local/date-only attachment keeps its original
  date and unknown local time, with source timing shown separately.
- Preferred summary source: creator link first, then Garmin, then lexical source
  package and record key. Relation order does not affect the result. Duplicate
  provider distances and heart-rate samples are never combined into a total.
- Raw Health Connect types distinguish Running, Walking, Hiking and Cycling.
  Stored activity keys stay compatible, including cycling's existing `other` key.
  Unsupported sports retain a readable type/code fallback and any source title.
- Distance and elapsed duration are prominent. Valid run/walk/hike intervals can
  show **elapsed pace**: 5 km over 30 minutes is 6:00 min/km. Zero/missing distance
  has no pace. Elapsed pace is not a moving-time estimate.
- Optional elevation gain reads fully contained, nonoverlapping records from one
  preferred source. Overlapping/signed records remain ambiguous; boundary records
  are excluded. No GPS/route/location access is requested. Permission loss retains
  the saved snapshot; a separate Changes feed handles late elevation corrections.
- Moving time stays unknown for Health Connect: its exercise record has no
  source-reported moving-time field. Nullable moving-time/origin fields support
  providers that explicitly supply it; elapsed time or pauses never fabricate it.
- Sharing remains a preview followed by an explicit user copy/share action.
  Each activity is included once in the resolved date range, without strength sets
  invented for imported cardio. Raw source identities/timing remain in provenance.

## Persistence and verification

Room 9 → 10 adds provenance and nullable elevation/moving-time metadata only.
The earlier importer's explicit creation marker migrates to `sourceCreated`.
Empty sets or absent local timestamps never infer legacy ownership. Backup v8
retains these fields; versions 1–7 still load. Analysis JSON v3 identifies backup
schema v8. Deliberately encoding older backups cannot silently discard metrics.

Synthetic unit checks cover original offsets/midnight, same-day order, source
corrections, legacy/date-only attachments, local precedence, deterministic provider
selection, pace bounds, optional-metric provenance, backup/analysis round-trips,
the 9 → 10 migration and all earlier migration paths. Official SDK fake tests
cover elevation source preference/ambiguity/permissions; coordinator tests cover
late elevation-only changes and revoked access.

Isolated emulator checks inspect History, run/hike/ride details, elapsed pace,
elevation, sharing preview and attached date-only strength data. They do not
establish what a real Garmin device publishes.

[Health Connect workout records and associated metrics](https://developer.android.com/health-and-fitness/health-connect/experiences/workouts)

Validation: debug and isolated preview builds pass; 168 unit tests, 0 failures/errors,
1 optional skip. Lint: 0 errors, 50 warnings. The emulator interaction passes at
normal/light and 150%/dark settings.
