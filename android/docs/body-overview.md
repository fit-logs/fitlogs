# Body overview

Body opens on the existing measurement history: latest weight, a raw weight chart,
a four-week comparison when supported, and BMI. Other measurement types remain
under **All body measurements**; the existing **Photos** tab is unchanged.

## Data and profile

- Room schema 11 adds `body_profile` and a `createdAt` metadata column to schema 10. There is no destructive
  fallback. Existing measurement fields, photos, workouts and preferences are preserved; old creation order is left unknown (0).
- Body profile stores height in centimetres, optional ISO birth date, preferred
  kg/lb and cm/feet-inches display units, and chart range (default 3M).
  There were no existing Body height/unit/profile settings to migrate.
- Age is derived from birth date and the current local date, never stored.
  A future birthday is rejected. Weight logging does not require a birthday.
- Height and weight must be positive and finite. Entry checks ask confirmation
  for height below 100 or above 250 cm and weight below 20 or above 350 kg.
  These are unit-error guards, not medical cutoffs. Confirmed values are saved
  without clamping. Invalid historical values remain in the generic measurement
  list and are excluded from calculations.
- Profile data is included in current JSON backups (schema 8), photo ZIP backups,
  and the existing opt-in system backup snapshot. Photos remain explicitly opt-in
  to ZIP backup; the profile adds no new photo permissions or cloud transport.
- The profile is excluded from `BackupCodec.analysis` and ChatGPT handoff data.
  Birth date is never automatically shared. No exported payload is saved here.

## Weight history and calculations

- Recognised weight names, after case/whitespace normalisation: `Bodyweight`,
  `Body weight`, `Weight`. Units are explicit kg/kgs/kilogram(s) or
  lb/lbs/pound(s). Unknown units are never guessed or interpreted as zero.
  Original records, names, values, units, notes and IDs are retained.
- One pound is exactly 0.45359237 kg; one inch is exactly 2.54 cm. Feet are whole
  non-negative integers and inches are in [0,12); the total must be positive.
- The chart converts only its presentation into the preferred weight unit.
  Selecting a point opens the original record for inspection/editing. Its record
  list also makes overlapping or same-day points accessible. No readings are
  averaged or removed from the chart. Lines connect observed points; no daily
  readings are generated. Differing readings on the same day break the line.
- 1M/3M/1Y use calendar-month/year subtraction from the device-local current
  date; All has no lower bound. Selection is persisted. Empty, single-point and
  constant series reuse the existing chart. Extremely large values use a list
  fallback if ordinary axes cannot represent them.
- Latest weight uses the latest measurement date. If every reading on that day
  has a time, use the latest recorded time. Otherwise use known local creation
  order, followed by recorded time and stable ID for older records. Creation
  metadata preserves consecutive date-only logging across restart and backup;
  it is not a measurement timestamp and stays out of analysis exports. Legacy
  same-day readings without that metadata have an unknown order and a stable
  tie-break. Their dates remain visible. Nothing fabricates midnight.
- The recent comparison is [today−27 days, today], inclusive; the previous one is
  [today−55 days, today−28 days]. Dates are local calendar dates, so DST does not
  change window membership. Average valid kg readings per day, then average the
  daily means within each window. Each needs at least three distinct measured
  days with earliest/latest dates at least seven days apart. Otherwise omit it.
- Display the recent average and difference in the preferred unit to one decimal,
  with decimal HALF_UP rounding (decimal means/differences use DECIMAL128 arithmetic). A difference rounding to zero says “About the
  same as the previous four weeks.” The detail sheet shows exact windows, means,
  day counts and the method. No interpolation, targets or AI interpretation.

## BMI standard (verified 15 October 2026)

BMI = latest weight in kg / (height in metres × height in metres). Display one
decimal with HALF_UP rounding; classify the **unrounded** result.

The [CDC adult categories](https://www.cdc.gov/bmi/adult-calculator/bmi-categories.html)
apply from the 20th birthday: below 18.5, Underweight range; 18.5 to below 25,
Healthy weight range; 25 to below 30, Overweight range; 30 and above, Obesity range.
Birth date is optional. Unknown age and ages under 20 show the number without
an adult category. There are no child percentiles in this implementation.
The existing app has no pregnancy setting; no medical questionnaire was added.

The information sheet links to that standard and [About BMI](https://www.cdc.gov/bmi/about/index.html),
and explains that BMI does not distinguish muscle from fat and is a screening
measure, not a diagnosis. Sex, ethnicity and age do not enter the formula. There
is no body-fat estimate, body-composition claim, historical BMI graph, ideal
weight, health score or automatic weight-loss advice.

## Imports

The current verified Body Tracker import is the native FitNotes version-22
SQLite backup reader, sharing `body_measurements` with manual logging. This task
also corrects rejection of date-only body rows: empty/null source times remain
empty. Reimporting the same file is a no-op; native record identity and its source
payload prevent duplicates across later backups. Equal weights with distinct
source IDs (including on one day) are retained. No value-based cleanup runs.

No `FitNotes_BodyTracker_Export.csv` was found in the workspace, attachments or
Downloads. Body Tracker CSV support was already explicitly deferred in README;
its unknown columns have not been guessed. Workout CSV and native Body Tracker
imports remain supported. A real Body Tracker CSV still needs format verification.
