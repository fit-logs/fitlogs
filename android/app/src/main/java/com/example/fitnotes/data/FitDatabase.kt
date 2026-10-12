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
)

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
)

@Entity(
    tableName = "workout_sets",
    foreignKeys = [ForeignKey(entity = WorkoutExercise::class, parentColumns = ["id"], childColumns = ["entryId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("entryId")],
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
)

data class EntryDetail(
    @Embedded val entry: WorkoutExercise,
    @Relation(parentColumn = "exerciseId", entityColumn = "id") val exercise: Exercise,
    @Relation(parentColumn = "id", entityColumn = "entryId") val sets: List<WorkoutSet>,
)

data class WorkoutDetail(
    @Embedded val workout: Workout,
    @Relation(entity = WorkoutExercise::class, parentColumn = "id", entityColumn = "workoutId") val entries: List<EntryDetail>,
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
    @Query("SELECT * FROM workouts WHERE completedAt IS NULL LIMIT 1") suspend fun active(): Workout?
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

@Database(entities = [Exercise::class, Workout::class, WorkoutExercise::class, WorkoutSet::class, HistoryImport::class, ImportedWorkout::class, ImportedSet::class, BodyMeasurement::class], version = 3, exportSchema = true)
abstract class FitDatabase : RoomDatabase() {
    abstract fun dao(): FitDao
    abstract fun importedDao(): ImportedHistoryDao

    companion object {
        fun open(context: Context): FitDatabase =
            Room.databaseBuilder(context.applicationContext, FitDatabase::class.java, "fitnotes.db")
                .addMigrations(IMPORT_MIGRATION_1_2, NOTEBOOK_MIGRATION_2_3)
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
