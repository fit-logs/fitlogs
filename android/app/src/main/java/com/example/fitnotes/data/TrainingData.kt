package com.example.fitnotes.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

object LoadMode {
    const val UNKNOWN = "unknown"
    const val EXTERNAL = "external"
    const val BODYWEIGHT = "bodyweight"
    const val ASSISTANCE = "assistance"
    const val REPS_ONLY = "reps_only"
    val values = setOf(UNKNOWN, EXTERNAL, BODYWEIGHT, ASSISTANCE, REPS_ONLY)
    fun label(mode: String) = when (mode) {
        EXTERNAL -> "External load"; BODYWEIGHT -> "Bodyweight + added load"
        ASSISTANCE -> "Assistance"; REPS_ONLY -> "Reps only"; else -> "Load mode unknown"
    }
    fun target(weight: PlanWeight) = when (weight.unit) { "bodyweight" -> BODYWEIGHT; "kg", "lb" -> EXTERNAL; else -> UNKNOWN }
}

@Entity(tableName = "exercise_aliases", foreignKeys = [ForeignKey(entity = Exercise::class, parentColumns = ["id"], childColumns = ["exerciseId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("exerciseId"), Index(value = ["exerciseId", "nameKey"], unique = true)])
data class ExerciseAlias(@PrimaryKey(autoGenerate = true) val id: Long = 0, val exerciseId: Long, val name: String, val nameKey: String)
@Entity(tableName = "exercise_source_mappings", primaryKeys = ["importId", "sourceName", "category"], foreignKeys = [
    ForeignKey(entity = HistoryImport::class, parentColumns = ["id"], childColumns = ["importId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = Exercise::class, parentColumns = ["id"], childColumns = ["exerciseId"], onDelete = ForeignKey.CASCADE)], indices = [Index("exerciseId")])
data class ExerciseSourceMapping(val importId: String, val sourceName: String, val category: String, val exerciseId: Long)
@Entity(tableName = "exercise_redirects", foreignKeys = [ForeignKey(entity = Exercise::class, parentColumns = ["id"], childColumns = ["exerciseId"], onDelete = ForeignKey.CASCADE)], indices = [Index("exerciseId")])
data class ExerciseRedirect(@PrimaryKey val oldId: Long, val exerciseId: Long)
@Entity(tableName = "exercise_progression", foreignKeys = [ForeignKey(entity = Exercise::class, parentColumns = ["id"], childColumns = ["exerciseId"], onDelete = ForeignKey.CASCADE)])
data class ExerciseProgression(@PrimaryKey val exerciseId: Long, val enabled: Boolean = false, val increment: String = "2.5", val unit: String = "kg")
@Entity(tableName = "training_preferences")
data class TrainingPreferences(@PrimaryKey val id: Int = 1, val automaticRest: Boolean = true, val restAlerts: Boolean = false, @ColumnInfo(defaultValue = "'[]'") val favouriteKeys: String = "[]")
@Entity(tableName = "rest_timer", foreignKeys = [ForeignKey(entity = Workout::class, parentColumns = ["id"], childColumns = ["workoutId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("workoutId")])
data class RestTimerState(@PrimaryKey val id: Int = 1, val workoutId: Long, val token: String, val sourceTargetId: Long?,
    val remainingMillis: Long, val anchorElapsed: Long, val bootCount: Int, val running: Boolean, val alerted: Boolean = false)

@Dao interface TrainingDao {
    @Query("SELECT * FROM exercise_aliases ORDER BY nameKey, id") fun observeAliases(): Flow<List<ExerciseAlias>>
    @Query("SELECT * FROM exercise_source_mappings") fun observeMappings(): Flow<List<ExerciseSourceMapping>>
    @Query("SELECT * FROM exercise_redirects") fun observeRedirects(): Flow<List<ExerciseRedirect>>
    @Query("SELECT * FROM exercise_progression") fun observeProgression(): Flow<List<ExerciseProgression>>
    @Query("SELECT * FROM training_preferences WHERE id = 1") fun observePreferences(): Flow<TrainingPreferences?>
    @Query("SELECT * FROM rest_timer WHERE id = 1") fun observeTimer(): Flow<RestTimerState?>
    @Query("SELECT * FROM exercise_aliases ORDER BY id") suspend fun aliases(): List<ExerciseAlias>
    @Query("SELECT * FROM exercise_source_mappings") suspend fun mappings(): List<ExerciseSourceMapping>
    @Query("SELECT * FROM exercise_redirects") suspend fun redirects(): List<ExerciseRedirect>
    @Query("SELECT * FROM exercise_progression") suspend fun progression(): List<ExerciseProgression>
    @Query("SELECT * FROM training_preferences WHERE id = 1") suspend fun preferences(): TrainingPreferences?
    @Query("SELECT * FROM rest_timer WHERE id = 1") suspend fun timer(): RestTimerState?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAlias(value: ExerciseAlias)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMapping(value: ExerciseSourceMapping)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putRedirect(value: ExerciseRedirect)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putProgression(value: ExerciseProgression)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPreferences(value: TrainingPreferences)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTimer(value: RestTimerState)
    @Query("DELETE FROM exercise_aliases WHERE id = :id") suspend fun deleteAlias(id: Long)
    @Query("DELETE FROM rest_timer") suspend fun clearTimer()
    @Query("DELETE FROM training_preferences") suspend fun clearPreferences()
    @Query("DELETE FROM exercise_progression") suspend fun clearProgression()
    @Query("DELETE FROM exercise_redirects") suspend fun clearRedirects()
    @Query("DELETE FROM exercise_source_mappings") suspend fun clearMappings()
    @Query("DELETE FROM exercise_aliases") suspend fun clearAliases()
    @Query("DELETE FROM exercises WHERE id = :id") suspend fun deleteExercise(id: Long)
    @Query("SELECT * FROM workout_sets WHERE targetId = :id LIMIT 1") suspend fun result(id: Long): WorkoutSet?
    @Query("SELECT * FROM workout_set_targets WHERE id = :id") suspend fun target(id: Long): WorkoutSetTarget?
    @Update suspend fun updateTarget(value: WorkoutSetTarget)
    @Update suspend fun updatePlannedSet(value: PlannedSet)
    @Update suspend fun updatePlannedExercise(value: PlannedExercise)
    @Query("DELETE FROM planned_exercises WHERE id = :id") suspend fun deletePlannedExercise(id: Long)
}

val TRAINING_MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE workout_exercises ADD COLUMN supersetGroup TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE planned_exercises ADD COLUMN supersetGroup TEXT NOT NULL DEFAULT ''")
        for (table in listOf("planned_sets", "workout_set_targets")) {
            db.execSQL("ALTER TABLE $table ADD COLUMN loadMode TEXT NOT NULL DEFAULT 'unknown'")
            db.execSQL("ALTER TABLE $table ADD COLUMN isWarmUp INTEGER NOT NULL DEFAULT 0")
            // A prescription's explicit unit identifies its intended load, never an actual historical result.
            db.execSQL("UPDATE $table SET loadMode = CASE WHEN weightUnit = 'bodyweight' THEN 'bodyweight' WHEN weightUnit IN ('kg','lb') THEN 'external' ELSE 'unknown' END")
        }
        db.execSQL("ALTER TABLE workout_set_targets ADD COLUMN skipped INTEGER NOT NULL DEFAULT 0")
        db.execSQL("""CREATE TABLE workout_sets_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, entryId INTEGER NOT NULL, weightKg REAL NOT NULL, reps INTEGER NOT NULL, position INTEGER NOT NULL, distance REAL, distanceUnit TEXT, durationSeconds REAL, comment TEXT NOT NULL DEFAULT '', targetId INTEGER, completedAt INTEGER, loadMode TEXT NOT NULL DEFAULT 'unknown', isWarmUp INTEGER NOT NULL DEFAULT 0, weightSpecified INTEGER NOT NULL DEFAULT 1, FOREIGN KEY(entryId) REFERENCES workout_exercises(id) ON DELETE CASCADE, FOREIGN KEY(targetId) REFERENCES workout_set_targets(id) ON DELETE SET NULL)""")
        db.execSQL("INSERT INTO workout_sets_new (id,entryId,weightKg,reps,position,distance,distanceUnit,durationSeconds,comment) SELECT id,entryId,weightKg,reps,position,distance,distanceUnit,durationSeconds,comment FROM workout_sets")
        db.execSQL("DROP TABLE workout_sets")
        db.execSQL("ALTER TABLE workout_sets_new RENAME TO workout_sets")
        db.execSQL("CREATE INDEX index_workout_sets_entryId ON workout_sets(entryId)")
        db.execSQL("CREATE UNIQUE INDEX index_workout_sets_targetId ON workout_sets(targetId)")
        db.execSQL("""CREATE TABLE exercise_aliases (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, exerciseId INTEGER NOT NULL, name TEXT NOT NULL, nameKey TEXT NOT NULL, FOREIGN KEY(exerciseId) REFERENCES exercises(id) ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX index_exercise_aliases_exerciseId ON exercise_aliases(exerciseId)")
        db.execSQL("CREATE UNIQUE INDEX index_exercise_aliases_exerciseId_nameKey ON exercise_aliases(exerciseId,nameKey)")
        db.execSQL("""CREATE TABLE exercise_source_mappings (importId TEXT NOT NULL, sourceName TEXT NOT NULL, category TEXT NOT NULL, exerciseId INTEGER NOT NULL, PRIMARY KEY(importId,sourceName,category), FOREIGN KEY(importId) REFERENCES history_imports(id) ON DELETE CASCADE, FOREIGN KEY(exerciseId) REFERENCES exercises(id) ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX index_exercise_source_mappings_exerciseId ON exercise_source_mappings(exerciseId)")
        db.execSQL("""CREATE TABLE exercise_redirects (oldId INTEGER NOT NULL PRIMARY KEY, exerciseId INTEGER NOT NULL, FOREIGN KEY(exerciseId) REFERENCES exercises(id) ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX index_exercise_redirects_exerciseId ON exercise_redirects(exerciseId)")
        db.execSQL("""CREATE TABLE exercise_progression (exerciseId INTEGER NOT NULL PRIMARY KEY, enabled INTEGER NOT NULL, increment TEXT NOT NULL, unit TEXT NOT NULL, FOREIGN KEY(exerciseId) REFERENCES exercises(id) ON DELETE CASCADE)""")
        db.execSQL("CREATE TABLE training_preferences (id INTEGER NOT NULL PRIMARY KEY, automaticRest INTEGER NOT NULL, restAlerts INTEGER NOT NULL, favouriteKeys TEXT NOT NULL DEFAULT '[]')")
        db.execSQL("""CREATE TABLE rest_timer (id INTEGER NOT NULL PRIMARY KEY, workoutId INTEGER NOT NULL, token TEXT NOT NULL, sourceTargetId INTEGER, remainingMillis INTEGER NOT NULL, anchorElapsed INTEGER NOT NULL, bootCount INTEGER NOT NULL, running INTEGER NOT NULL, alerted INTEGER NOT NULL, FOREIGN KEY(workoutId) REFERENCES workouts(id) ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX index_rest_timer_workoutId ON rest_timer(workoutId)")
    }
}
