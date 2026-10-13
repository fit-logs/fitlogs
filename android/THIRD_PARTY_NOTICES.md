# LiftLog reference and attribution

The Android application's adapted exercise-search scorer is licensed under
AGPL-3.0-only, with the full licence in [LICENSE](LICENSE). Distribution of the
combined Android application must preserve these notices and provide its
corresponding source under that licence. No upstream datasets, images, Expo
modules, backend or network service are included.

Upstream: [LiamMorrow/LiftLog](https://github.com/LiamMorrow/LiftLog), Liam Morrow
and contributors. Reviewed revision: `e7004215562994be7e1640172b4d42145ed76a07`.

| Upstream file | Local use |
| --- | --- |
| `app/src/components/presentation/workout-editor/exercise-fuzzy-match.ts` | `data/ExerciseSearchScore.kt`: Kotlin adaptation of the search scorer |
| `app/src/models/session-models/session.ts`, `recorded-weighted-exercise.ts` | Behaviour references for independent target/result linking and next-set navigation in `TrainingLogic.kt` and `TrainingRepository.kt` |
| `app/src/models/session-models/rest-timer.ts` and tests | Pause/resume reference; independent persisted Android elapsed-clock implementation in `RestTimerController.kt` and `TrainingLogic.kt` |
| `docs/Progression.md`, progression tests | Requirements reference for an independent bounded, opt-in double-progression suggestion |
| `app/src/store/stats/calculate-stats.ts`, `personal-records.ts` | Behaviour reference for independent canonical-ID statistics, with explicit load semantics |

Local Kotlin paths above are relative to `app/src/main/java/com/example/fitnotes/`.

The source review in `docs/liftlog-adoption-review.md` is a proposal, not a claim
that every upstream component was copied. Only the search scorer is a direct
port. Tests and remaining Kotlin implementations are written for this app's
Room model and source-preserving history.
