package com.example.fitnotes.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "planned_sets", foreignKeys = [ForeignKey(entity = PlannedExercise::class, parentColumns = ["id"], childColumns = ["plannedExerciseId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["plannedExerciseId", "position"], unique = true)])
data class PlannedSet(@PrimaryKey(autoGenerate = true) val id: Long = 0, val plannedExerciseId: Long, val position: Int,
    val repsMin: Int?, val repsMax: Int?, val weightValue: String?, val weightUnit: String?, val restSeconds: Int?) {
    val target get() = PlanSetTarget(repsMin, repsMax, PlanWeight(weightValue, weightUnit), restSeconds)
}
@Entity(tableName = "workout_plan_runs", foreignKeys = [
    ForeignKey(entity = Workout::class, parentColumns = ["id"], childColumns = ["workoutId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = PlannedWorkout::class, parentColumns = ["id"], childColumns = ["planId"], onDelete = ForeignKey.SET_NULL)], indices = [Index("planId")])
data class WorkoutPlanRun(@PrimaryKey val workoutId: Long, val planId: String?, val title: String, val warmUp: String, val legacyGuidance: String)
@Entity(tableName = "workout_set_targets", foreignKeys = [ForeignKey(entity = WorkoutExercise::class, parentColumns = ["id"], childColumns = ["entryId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["entryId", "position"], unique = true)])
data class WorkoutSetTarget(@PrimaryKey(autoGenerate = true) val id: Long = 0, val entryId: Long, val position: Int,
    val repsMin: Int?, val repsMax: Int?, val weightValue: String?, val weightUnit: String?, val restSeconds: Int?) {
    val target get() = PlanSetTarget(repsMin, repsMax, PlanWeight(weightValue, weightUnit), restSeconds)
}
data class RunExerciseDetail(@Embedded val entry: WorkoutExercise,
    @Relation(parentColumn = "exerciseId", entityColumn = "id") val exercise: Exercise,
    @Relation(parentColumn = "id", entityColumn = "entryId") val targets: List<WorkoutSetTarget>) {
    val ordered get() = targets.sortedBy { it.position }
}
data class PlanRunDetail(@Embedded val run: WorkoutPlanRun,
    @Relation(entity = WorkoutExercise::class, parentColumn = "workoutId", entityColumn = "workoutId") val exercises: List<RunExerciseDetail>)

val PLAN_MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE planned_workouts ADD COLUMN warmUp TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE planned_workouts ADD COLUMN legacyGuidance TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE workout_exercises ADD COLUMN planNote TEXT NOT NULL DEFAULT ''")
        db.execSQL("""CREATE TABLE planned_sets (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, plannedExerciseId INTEGER NOT NULL, position INTEGER NOT NULL, repsMin INTEGER, repsMax INTEGER, weightValue TEXT, weightUnit TEXT, restSeconds INTEGER, FOREIGN KEY(plannedExerciseId) REFERENCES planned_exercises(id) ON DELETE CASCADE)""")
        db.execSQL("CREATE UNIQUE INDEX index_planned_sets_plannedExerciseId_position ON planned_sets(plannedExerciseId, position)")
        db.execSQL("""CREATE TABLE workout_plan_runs (workoutId INTEGER NOT NULL PRIMARY KEY, planId TEXT, title TEXT NOT NULL, warmUp TEXT NOT NULL, legacyGuidance TEXT NOT NULL, FOREIGN KEY(workoutId) REFERENCES workouts(id) ON DELETE CASCADE, FOREIGN KEY(planId) REFERENCES planned_workouts(id) ON DELETE SET NULL)""")
        db.execSQL("CREATE INDEX index_workout_plan_runs_planId ON workout_plan_runs(planId)")
        db.execSQL("""CREATE TABLE workout_set_targets (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, entryId INTEGER NOT NULL, position INTEGER NOT NULL, repsMin INTEGER, repsMax INTEGER, weightValue TEXT, weightUnit TEXT, restSeconds INTEGER, FOREIGN KEY(entryId) REFERENCES workout_exercises(id) ON DELETE CASCADE)""")
        db.execSQL("CREATE UNIQUE INDEX index_workout_set_targets_entryId_position ON workout_set_targets(entryId, position)")
        db.execSQL("""WITH RECURSIVE n(position) AS (SELECT 0 UNION ALL SELECT position + 1 FROM n WHERE position < 99)
            INSERT INTO planned_sets(plannedExerciseId, position, repsMin, repsMax, weightValue, weightUnit, restSeconds)
            SELECT e.id, n.position, e.repsMin, e.repsMax, e.weightValue, e.weightUnit, NULL FROM planned_exercises e JOIN n ON n.position < e.sets""")
        db.execSQL("INSERT INTO workout_plan_runs SELECT startedWorkoutId, id, title, warmUp, legacyGuidance FROM planned_workouts WHERE startedWorkoutId IS NOT NULL")
        db.execSQL("""UPDATE workout_exercises SET planNote = COALESCE((SELECT p.note FROM planned_exercises p JOIN workout_plan_runs r ON p.planId = r.planId WHERE r.workoutId = workout_exercises.workoutId AND p.exerciseId = workout_exercises.exerciseId), '')""")
        db.execSQL("""INSERT INTO workout_set_targets(entryId, position, repsMin, repsMax, weightValue, weightUnit, restSeconds)
            SELECT e.id, s.position, s.repsMin, s.repsMax, s.weightValue, s.weightUnit, s.restSeconds FROM workout_plan_runs r
            JOIN workout_exercises e ON e.workoutId = r.workoutId JOIN planned_exercises p ON p.planId = r.planId AND p.exerciseId = e.exerciseId JOIN planned_sets s ON s.plannedExerciseId = p.id""")
    }
}

fun eligibleEmptySuggestion(detail: WorkoutDetail, hasPlanRun: Boolean = false): Boolean {
    val workout = detail.workout
    return (workout.notes.startsWith(SESSION_PLAN_PREFIX) || hasPlanRun) && workout.activityType == "strength" &&
        !workout.isComplete && workout.durationSeconds == null && workout.distance == null && workout.effort.isNullOrBlank() &&
        detail.health.isEmpty() && detail.entries.all { it.sets.isEmpty() }
}
