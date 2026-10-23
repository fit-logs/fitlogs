package com.example.fitnotes.data

import android.content.Context
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "exercises", indices = [Index(value = ["nameKey"], unique = true)])
data class Exercise(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val nameKey: String,
)

@Entity(tableName = "workouts")
data class Workout(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val completedAt: Long? = null,
    val title: String = "",
    val notes: String = "",
    @ColumnInfo(defaultValue = "'strength'") val activityType: String = "strength",
    @ColumnInfo(defaultValue = "''") val activityLabel: String = "",
    @ColumnInfo(defaultValue = "''") val sessionDate: String = "",
    @ColumnInfo(defaultValue = "0") val finished: Boolean = false,
    val durationSeconds: Double? = null,
    val effort: String? = null,
    val distance: Double? = null,
    val distanceUnit: String? = null,
) {
    // Legacy startedAt remains the persisted timer. Zero explicitly means unknown;
    // date-only activities carry sessionDate and never manufacture midnight.
    val knownStart: Long? get() = startedAt.takeIf { it > 0 }
    val isComplete: Boolean get() = finished || completedAt != null
}

@Entity(
    tableName = "workout_exercises",
    foreignKeys = [
        ForeignKey(entity = Workout::class, parentColumns = ["id"], childColumns = ["workoutId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Exercise::class, parentColumns = ["id"], childColumns = ["exerciseId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("workoutId"), Index("exerciseId"), Index(value = ["workoutId", "exerciseId"], unique = true)],
)
data class WorkoutExercise(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutId: Long,
    val exerciseId: Long,
    val position: Int,
    @ColumnInfo(defaultValue = "''") val planNote: String = "",
    @ColumnInfo(defaultValue = "''") val supersetGroup: String = "",
)

@Entity(
    tableName = "workout_sets",
    foreignKeys = [ForeignKey(entity = WorkoutExercise::class, parentColumns = ["id"], childColumns = ["entryId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = WorkoutSetTarget::class, parentColumns = ["id"], childColumns = ["targetId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("entryId"), Index(value = ["targetId"], unique = true)],
)
data class WorkoutSet(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entryId: Long,
    val weightKg: Double,
    val reps: Int,
    val position: Int,
    val distance: Double? = null,
    val distanceUnit: String? = null,
    val durationSeconds: Double? = null,
    @ColumnInfo(defaultValue = "''") val comment: String = "",
    val targetId: Long? = null, val completedAt: Long? = null,
    @ColumnInfo(defaultValue = "'unknown'") val loadMode: String = LoadMode.UNKNOWN,
    @ColumnInfo(defaultValue = "0") val isWarmUp: Boolean = false,
    @ColumnInfo(defaultValue = "1") val weightSpecified: Boolean = true,
)

data class EntryDetail(
    @Embedded val entry: WorkoutExercise,
    @Relation(parentColumn = "exerciseId", entityColumn = "id") val exercise: Exercise,
    @Relation(parentColumn = "id", entityColumn = "entryId") val sets: List<WorkoutSet>,
)

data class WorkoutDetail(
    @Embedded val workout: Workout,
    @Relation(entity = WorkoutExercise::class, parentColumn = "id", entityColumn = "workoutId") val entries: List<EntryDetail>,
    @Relation(entity = HealthSession::class, parentColumn = "id", entityColumn = "workoutId") val health: List<HealthDetail> = emptyList(),
)

@Dao
interface FitDao {
    @Query("SELECT * FROM exercises ORDER BY nameKey") fun observeExercises(): Flow<List<Exercise>>
    @Transaction @Query("SELECT * FROM workouts ORDER BY startedAt DESC, id DESC")
    fun observeWorkouts(): Flow<List<WorkoutDetail>>
    @Query("SELECT * FROM exercises ORDER BY id") suspend fun exercises(): List<Exercise>
    @Query("SELECT * FROM workouts ORDER BY id") suspend fun workouts(): List<Workout>
    @Query("SELECT * FROM workout_exercises ORDER BY position, id") suspend fun entries(): List<WorkoutExercise>
    @Query("SELECT * FROM workout_sets ORDER BY position, id") suspend fun sets(): List<WorkoutSet>
    @Query("SELECT * FROM workouts WHERE completedAt IS NULL AND finished = 0 LIMIT 1") suspend fun active(): Workout?
    @Query("SELECT * FROM workouts WHERE id = :id") suspend fun workout(id: Long): Workout?
    @Query("SELECT * FROM exercises WHERE id = :id") suspend fun exercise(id: Long): Exercise?
    @Query("SELECT * FROM exercises WHERE nameKey = :key LIMIT 1") suspend fun exerciseByKey(key: String): Exercise?
    @Query("SELECT * FROM workout_exercises WHERE id = :id") suspend fun entry(id: Long): WorkoutExercise?
    @Query("SELECT * FROM workout_exercises WHERE workoutId = :id ORDER BY position, id") suspend fun workoutEntries(id: Long): List<WorkoutExercise>
    @Query("SELECT * FROM workout_sets WHERE id = :id") suspend fun set(id: Long): WorkoutSet?
    @Query("SELECT * FROM workout_sets WHERE entryId = :id ORDER BY position, id") suspend fun entrySets(id: Long): List<WorkoutSet>
    @Insert suspend fun insertExercise(value: Exercise): Long
    @Insert suspend fun insertWorkout(value: Workout): Long
    @Insert suspend fun insertEntry(value: WorkoutExercise): Long
    @Insert suspend fun insertSet(value: WorkoutSet): Long
    @Insert suspend fun insertExercises(values: List<Exercise>)
    @Insert suspend fun insertWorkouts(values: List<Workout>)
    @Insert suspend fun insertEntries(values: List<WorkoutExercise>)
    @Insert suspend fun insertSets(values: List<WorkoutSet>)
    @Update suspend fun updateWorkout(value: Workout)
    @Update suspend fun updateEntry(value: WorkoutExercise)
    @Update suspend fun updateSet(value: WorkoutSet)
    @Query("DELETE FROM workout_sets WHERE id = :id") suspend fun deleteSet(id: Long)
    @Query("DELETE FROM workout_exercises WHERE id = :id") suspend fun deleteEntry(id: Long)
    @Query("DELETE FROM workouts WHERE id = :id") suspend fun deleteWorkout(id: Long)
    @Query("DELETE FROM workout_sets") suspend fun clearSets()
    @Query("DELETE FROM workout_exercises") suspend fun clearEntries()
    @Query("DELETE FROM workouts") suspend fun clearWorkouts()
    @Query("DELETE FROM exercises") suspend fun clearExercises()
}

val NOTEBOOK_MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE workout_sets ADD COLUMN distance REAL")
        db.execSQL("ALTER TABLE workout_sets ADD COLUMN distanceUnit TEXT")
        db.execSQL("ALTER TABLE workout_sets ADD COLUMN durationSeconds REAL")
        db.execSQL("ALTER TABLE workout_sets ADD COLUMN comment TEXT NOT NULL DEFAULT ''")
    }
}

@Database(entities = [Exercise::class, Workout::class, WorkoutExercise::class, WorkoutSet::class, HistoryImport::class, ImportedWorkout::class, ImportedSet::class, BodyMeasurement::class, HealthSession::class, HealthSample::class, ProgressPhoto::class, PlannedWorkout::class, PlannedExercise::class, HandoffPreferences::class, PlannedSet::class, WorkoutPlanRun::class, WorkoutSetTarget::class, ExerciseAlias::class, ExerciseSourceMapping::class, ExerciseRedirect::class, ExerciseProgression::class, TrainingPreferences::class, RestTimerState::class], version = 11, exportSchema = true)
abstract class FitDatabase : RoomDatabase() {
    abstract fun dao(): FitDao
    abstract fun importedDao(): ImportedHistoryDao
    abstract fun healthDao(): HealthDao
    abstract fun photoDao(): ProgressPhotoDao
    abstract fun planDao(): WorkoutPlanDao
    abstract fun trainingDao(): TrainingDao

    companion object {
        fun open(context: Context): FitDatabase =
            Room.databaseBuilder(context.applicationContext, FitDatabase::class.java, "fitnotes.db")
                .enableMultiInstanceInvalidation()
                .addMigrations(IMPORT_MIGRATION_1_2, NOTEBOOK_MIGRATION_2_3, ACTIVITY_MIGRATION_3_4, ACTIVITY_MIGRATION_4_5, PHOTO_MIGRATION_5_6, PLAN_MIGRATION_6_7, PLAN_MIGRATION_7_8, TRAINING_MIGRATION_8_9, ACTIVITY_SUMMARY_MIGRATION_9_10, ACTIVITY_PROVIDER_MIGRATION_10_11)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // Seed names only on database creation, never after restoring an empty backup.
                        val names = listOf("Squat", "Bench Press", "Deadlift", "Overhead Press", "Barbell Row", "Pull-up", "Push-up", "Lunge")
                        for (name in names) {
                            db.execSQL("INSERT INTO exercises (name, nameKey) VALUES (?, ?)", arrayOf(name, name.lowercase(java.util.Locale.ROOT)))
                        }
                    }
                })
                .build()
    }
}
