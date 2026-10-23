# Import a complete program

**Train → Explore programs / Import → Import program** (also My programs →
Import program). Paste a converted routine, Preview, resolve exercise identities,
then **Save one program**. Installed programs start paused; activate deliberately.
Targets never become performed sets until you log results.

- Primary input: offline JSON or the bounded text format below. No AI service,
  subscription, account, API key or remote parser is used by the importer.
- **Choose PDF / file:** JSON/plain UTF-8 files load locally (256 KB maximum).
  PDFs show a clear conversion-and-Paste fallback; no PDF is uploaded or retained.
  Encrypted/scanned PDFs and arbitrary table layouts are not extracted.
- **From URL:** validates and retains an http/https source link without credentials.
  It does not fetch a page. Convert the webpage/PDF with your chosen tool, then
  paste the result. This works around unsupported layouts/access controls without
  scraping, proxies or bypassing a site's controls.
- **Copy conversion format** copies instructions only on tap. Nothing is sent.
  Android text/JSON shares open this same review screen; a shared link opens its
  conversion fallback. Shares never save a program automatically.
- Preview shows all ordered days, original units, effort/rest/progression/source,
  and optional substitutions. Rename, change description/weeks/day notes, edit
  set targets, and move/remove days or exercises before saving.
- Exact labels and saved aliases can match. Ambiguous names, unmatched labels or
  incompatible cardio-only entries require review. Choose an existing catalogue
  entry or explicitly create one on Save. Incline/flat, cable/barbell and other
  movement variants are not guessed. Substitutions remain guidance.
- No working weight is required or estimated. Supplied kg/lb values retain their
  original units; unspecified loads/modes remain unspecified. A numeric kg/lb value
  alone cannot identify external load versus assistance. Per-set prescriptions
  override exercise defaults, including top/back-off rep targets.
- Saving installs one program and its owned day prescriptions in one transaction.
  Preview/Cancel/malformed input create no training or catalogue rows. A failed
  last day rolls back every new program/template/exercise row.
- Re-importing the same source identity/version or definition warns. Open the
  existing copy or deliberately install another; upstream content never replaces
  local edits or performed history.
- Installed identity/description and day targets remain editable. Provenance and
  the imported definition are retained; current day prescriptions are separate
  local copies. Activate/pause, offline scheduling, explicit completions/skips,
  backup/restore and actual logging reuse the program workflow.

## Portable definition v1

Shared [JSON Schema](../../data/programs/schema-v1.json) and
[four-day fixture](../../data/programs/examples/four-day.json) are bundled assets.
The fixture intentionally contains ambiguous/unknown names; it is a parser example,
not a training recommendation. The older `data/routines.json` web format is not
silently installed or used as an Android schema; its configured starting-load
fallbacks are not imported.

Use `schemaVersion: 1`, stable string `id` and `programVersion`, `name`, `weeks`
(1–52), and 1–14 ordered `days`. Each day has `id`, `name`, optional notes/minutes,
and 1–50 ordered exercises. `daysPerWeek`, when supplied, must match the weekly
ordered day list. Optional start/day dates must be valid ISO dates; they do not
start timers or mark training completed.

Each exercise holds a display `name`, optional `sourceName`/`canonicalId`, notes,
substitutions and `sets`. Sets can be a count with common exercise defaults, or an
ordered array of per-set targets. Supported targets: reps or rep range (1–1000),
optional explicit weight/unit/load mode, rest (0–3600 seconds), RIR (0–10), RPE
(1–10), and warm-up flag. At most 100 sets per exercise. Missing targets can remain
unspecified; no weights, timestamps or performed results are synthesized.

Canonical IDs are portable source identities, never local numeric database IDs.
An unfamiliar ID cannot force a catalogue mapping: the label/alias match and
explicit resolution determine the local movement. Source title, URL, author and
attribution are retained. Preserve the source's short prescription/guidance
privately; public program files must use original or licensed material.

Unknown optional fields survive normalization and preview edits. Unsupported
major schema versions, duplicate JSON keys, nesting beyond 32 levels, invalid
ranges/URLs, and expanded definitions beyond 256 KB are rejected. Runtime cross-
field checks supplement JSON Schema (range ordering, unique day IDs, unit/load
compatibility, readable exercise names up to 100 characters). Notes/guidance fit
the existing 2,000-character per-exercise limit. Goals/equipment accept short
lists; progression/schedule can be text or structured guidance up to 4,000
characters and are never executed as automatic progression rules.

Common input aliases (`title`/`workouts`/`exercise`, count + `reps` or
`repRangeMin`/`repRangeMax`) normalize to the same model. Missing program identity
gets a deterministic private ID; missing version defaults to `1`, weeks to the
visible/editable `4`. JSON can have one Markdown code fence or optional
`FITNOTES_PROGRAM_V1` / `END_FITNOTES_PROGRAM` markers.

Supported plain text is deliberately bounded; other layouts fall back to JSON:

```text
Program: My routine
Weeks: 4
Day 1: Upper
Bench Press | 3 x 8-12 | rest 90s | RIR 2
Day 2: Lower
Squat | 3 x 6-8 | RPE 8 | Review load manually
```

Room 13 → 14 adds portable identity/provenance fields without rewriting existing
programs, templates, workouts, photos or health records. Backup v12 retains them;
versions 1–11 remain readable. Analysis JSON v5 remains actual training only and
excludes program definitions/targets and unsaved retrospective drafts.

## Verification

219 unit tests: zero failures/errors, one optional skip. Normal and isolated
preview APKs build; lint reports zero errors and 50 existing warnings. The shared
four-day fixture also validates against JSON Schema draft 2020-12.

Offline emulator flows pass in light/standard and dark/150% text at 360×640 dp:
four-day preview, alias/ambiguous/custom resolution, reorder/edit, atomic save,
editable installed identity, activation/start with empty actual sets, duplicates,
malformed input, JSON/PDF content URI handling, URL fallback, repeated text/link
and JSON-file shares, and force-stop persistence. A separate cold-start
ACTION_SEND check opens the source-link fallback. Rendered screens are inspected.
File-reader checks use provider URI fixtures; physical-device checks remain
unperformed. The personal package and original dirty checkout are untouched.
