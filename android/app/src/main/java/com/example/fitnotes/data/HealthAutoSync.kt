package com.example.fitnotes.data

data class HealthSyncResult(val refreshed: Int, val linked: Int, val needsReview: Int, val missing: Int)

// A link needs one overlapping local session and one Garmin record for that local
// session. A small incidental overlap, unfinished session or generic Other type
// is insufficient. Date-only sessions have no interval and cannot be matched.
fun automaticHealthTargets(candidates: List<HealthCandidate>, workouts: List<Workout>, known: List<HealthSession>): Map<String, Long> {
    val garmin = candidates.distinctBy { it.session.key }.filter { isGarminOrigin(it.session.originPackage) }
    val knownKeys = known.map { it.key }.toSet()
    val linkedIds = known.mapNotNull { it.workoutId }.toSet()
    val overlaps = garmin.associate { it.session.key to matchingWorkouts(it.session, workouts) }
    return buildMap {
        for (candidate in garmin) {
            val s = candidate.session
            if (s.key in knownKeys || s.activityType == "other" || s.end <= s.start) continue
            val w = overlaps[s.key]?.singleOrNull() ?: continue
            val localEnd = w.completedAt ?: continue
            if (!w.isComplete || w.id in linkedIds || localEnd <= w.startedAt) continue
            val overlap = minOf(localEnd, s.end) - maxOf(w.startedAt, s.start)
            if (overlap.toDouble() / (localEnd - w.startedAt) < 0.6 || overlap.toDouble() / (s.end - s.start) < 0.6) continue
            if (overlaps.values.count { choices -> choices.any { it.id == w.id } } != 1) continue
            put(s.key, w.id)
        }
    }
}
