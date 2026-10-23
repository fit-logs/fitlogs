# History-based weight suggestions

- On Train, review the next program day's **Suggested weights**. Templates also
  offer a review. In the actual logger, choose **Suggested weight · Use / Change**.
- Confirm a gym, specific equipment, total/per-hand/stack basis, load mode,
  available increment, kg/lb, lower/upper rep goal and default target RIR.
  Different machines or gyms need distinct setups. Actual entry remains in kg;
  a per-hand value stays per hand, without doubling.
- **Use** changes target weights only. **Change** permits a manual target.
  A session can lock accepted targets. Unlock explicitly before changing them.
  Once actual logging starts, advice cannot rewrite that entry's targets.
  Actual weights always remain editable.
- **Record RIR / RPE** records optional *actual* effort. RIR means spare reps;
  RIR-based RPE 10 means no spare reps, 9 means one, and 8 means two. Missing
  effort stays unknown. Prescribed RIR/RPE never becomes reported actual effort.

## Evidence and limits

Only completed, non-draft performed sets count. The engine requires the same
exercise ID, gym, equipment, basis and external-load mode. It excludes planned
weights, flagged warm-ups, invalid values, future records, history older than
90 days, reps outside 3–10 and reported reserve above four. Within a session,
loads outside 0.5–1.5 times its median are excluded. Explanations show retained
and excluded actual-set counts; these exclusions never delete records.

History imports use explicit exercise mappings or unambiguous exact names/aliases.
Legacy records have no invented gym, warm-up or effort metadata. An explicit
same-setup review preference may include them with lower confidence and no
progression increment. It can apply to only one setup per exercise. Comments
are never parsed as reported effort. Matching same-day records with missing or
overlapping times stop numeric advice for duplicate review.

The last six comparable dates are considered, with at least two required. The
latest date must be within 42 days. Sparse, contradictory, sharply inconsistent
or stale history returns **Manual start / calibration**, without a fabricated
weight. Assistance, bodyweight and reps-only modes require manual calibration.

## Deterministic planning rules

These are conservative engineering defaults, not validated measurements of an
individual's capacity or an optimal RIR prescription. For each latest working
set, calculate `kg × (30 + performed reps + reported reserve)`. Take the lowest
numerator and divide once by `30 + selected target reps + target reserve`.
Reported reserve is RIR, or `10 − RPE` if only RPE was recorded. If both are
present they must agree within 0.5. Missing reserve contributes no headroom to
this bound; it is never stored as zero. Missing effort cannot cause an increase;
if logged reps already meet the goal, the starting proposal holds the old load.

- Increases require two recent comparable dates at the same working weight,
  every requested upper rep target met, and reported reserve at least two and
  at least the target reserve. Incomplete/skipped, assumed legacy or mixed-load
  evidence blocks progression. Propose at most one configured step, and only
  if that step is at most 10% of the baseline; the rep/reserve bound still caps it.
- Fewer working sets than prescribed/requested or a skipped target reduces the
  proposal by 5%. Three dates without rep improvement at the same load can
  propose a 5% deload. A 22–42-day gap also caps reuse at 95% of the old load.
- Convert kg/lb using exactly 0.45359237 kg/lb and round **down** to the equipment
  step. A result below one step, below 80% of baseline, or above the supported
  load limit returns manual calibration. No body mass or assistance conversion
  is inferred.

Fresh reviews recompute from saved history and targets. Use revalidates the
review fingerprint in a transaction, rejecting changed evidence/setup/targets.
The bounded in-memory cache has no persisted derived facts. Future template
changes cannot alter an active workout's existing target snapshot.

The [2026 ACSM position stand](https://doi.org/10.1249/MSS.0000000000003897)
emphasizes individualization and does not establish precise universal RIR targets.
The [RIR scale study](https://pubmed.ncbi.nlm.nih.gov/26049792/) supports treating
effort as reported information; the app's formula and cutoffs remain transparent
planning choices, without a guarantee of subsequent performance.

## Persistence and verification

Room 14 → 15 adds load profiles, actual-set context, session locks and nullable
prescribed RIR/RPE. Migration leaves old metadata unknown. Backup v13 preserves
these fields. Analysis JSON v6 includes actual-set context/effort for non-draft
sets, but excludes setup preferences, locks and planned targets.

Unit/Room checks cover determinism, kg/lb rounding, warm-ups/outliers, missing and
conflicting effort, plateau/deload, setup isolation, legacy CSV assumptions,
stale-review rejection, actual overrides, lock persistence, exercise merges and
backup/migration. The isolated preview emulator checks review/setup/Use, an
imported program's next day, locking, actual effort/weight override and force-stop
restart offline in light and small-screen dark/150% text modes. Physical-device
and personal-history performance are not established by those synthetic checks.
