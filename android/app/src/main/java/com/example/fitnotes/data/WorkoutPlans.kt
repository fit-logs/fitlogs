package com.example.fitnotes.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.util.Locale
import java.util.UUID

@Entity(tableName = "planned_workouts", foreignKeys = [ForeignKey(entity = Workout::class, parentColumns = ["id"], childColumns = ["startedWorkoutId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("scheduledDate"), Index("sourceFingerprint"), Index(value = ["startedWorkoutId"], unique = true)])
data class PlannedWorkout(@PrimaryKey val id: String, val title: String, val scheduledDate: String, val durationMinutes: Int,
    val notes: String, val createdAt: Long, val sourceFingerprint: String, val startedWorkoutId: Long? = null)

@Entity(tableName = "planned_exercises", foreignKeys = [
    ForeignKey(entity = PlannedWorkout::class, parentColumns = ["id"], childColumns = ["planId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = Exercise::class, parentColumns = ["id"], childColumns = ["exerciseId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("exerciseId"), Index(value = ["planId", "position"], unique = true), Index(value = ["planId", "exerciseId"], unique = true)])
data class PlannedExercise(@PrimaryKey(autoGenerate = true) val id: Long = 0, val planId: String, val exerciseId: Long,
    val position: Int, val sourceName: String, val sets: Int, val repsMin: Int, val repsMax: Int,
    val weightValue: String?, val weightUnit: String?, val note: String)
data class PlannedExerciseDetail(@Embedded val target: PlannedExercise,
    @Relation(parentColumn = "exerciseId", entityColumn = "id") val exercise: Exercise) {
    val prescription get() = PlanPrescription(exercise.name, target.sets, target.repsMin, target.repsMax, PlanWeight(target.weightValue, target.weightUnit), target.note)
}
data class PlannedWorkoutDetail(@Embedded val plan: PlannedWorkout,
    @Relation(entity = PlannedExercise::class, parentColumn = "id", entityColumn = "planId") val exercises: List<PlannedExerciseDetail>) {
    val ordered get() = exercises.sortedBy { it.target.position }
    val spec get() = PlanSpec(plan.title, plan.scheduledDate, plan.durationMinutes, plan.notes, ordered.map { it.prescription }, plan.sourceFingerprint)
}
@Entity(tableName = "handoff_preferences")
data class HandoffPreferences(@PrimaryKey val id: Int = 1, val goals: String = "", val equipment: String = "",
    val constraints: String = "", val activityPreference: String = "", val availableMinutes: Int? = null, val lastTargetDate: String = "")

@Dao interface WorkoutPlanDao {
    @Transaction @Query("SELECT * FROM planned_workouts ORDER BY scheduledDate, createdAt DESC, id") fun observe(): Flow<List<PlannedWorkoutDetail>>
    @Transaction @Query("SELECT * FROM planned_workouts WHERE id = :id") suspend fun detail(id: String): PlannedWorkoutDetail?
    @Query("SELECT * FROM planned_workouts ORDER BY id") suspend fun plans(): List<PlannedWorkout>
    @Query("SELECT * FROM planned_exercises ORDER BY planId, position, id") suspend fun exercises(): List<PlannedExercise>
    @Query("SELECT * FROM planned_workouts WHERE sourceFingerprint = :fingerprint ORDER BY createdAt, id LIMIT 1") suspend fun duplicate(fingerprint: String): PlannedWorkout?
    @Insert suspend fun insert(plan: PlannedWorkout)
    @Insert suspend fun insertTargets(targets: List<PlannedExercise>)
    @Query("UPDATE planned_workouts SET startedWorkoutId = :workoutId WHERE id = :id") suspend fun markStarted(id: String, workoutId: Long)
    @Query("DELETE FROM planned_workouts WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM planned_workouts") suspend fun clearPlans()
    @Query("SELECT * FROM handoff_preferences WHERE id = 1") fun observePreferences(): Flow<HandoffPreferences?>
    @Query("SELECT * FROM handoff_preferences WHERE id = 1") suspend fun preferences(): HandoffPreferences?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPreferences(preferences: HandoffPreferences)
    @Query("DELETE FROM handoff_preferences") suspend fun clearPreferences()
}

val PLAN_MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS planned_workouts (id TEXT NOT NULL, title TEXT NOT NULL, scheduledDate TEXT NOT NULL, durationMinutes INTEGER NOT NULL, notes TEXT NOT NULL, createdAt INTEGER NOT NULL, sourceFingerprint TEXT NOT NULL, startedWorkoutId INTEGER, PRIMARY KEY(id), FOREIGN KEY(startedWorkoutId) REFERENCES workouts(id) ON DELETE SET NULL)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_planned_workouts_scheduledDate ON planned_workouts(scheduledDate)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_planned_workouts_sourceFingerprint ON planned_workouts(sourceFingerprint)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_planned_workouts_startedWorkoutId ON planned_workouts(startedWorkoutId)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS planned_exercises (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, planId TEXT NOT NULL, exerciseId INTEGER NOT NULL, position INTEGER NOT NULL, sourceName TEXT NOT NULL, sets INTEGER NOT NULL, repsMin INTEGER NOT NULL, repsMax INTEGER NOT NULL, weightValue TEXT, weightUnit TEXT, note TEXT NOT NULL, FOREIGN KEY(planId) REFERENCES planned_workouts(id) ON DELETE CASCADE, FOREIGN KEY(exerciseId) REFERENCES exercises(id) ON DELETE RESTRICT)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_planned_exercises_exerciseId ON planned_exercises(exerciseId)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_planned_exercises_planId_position ON planned_exercises(planId, position)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_planned_exercises_planId_exerciseId ON planned_exercises(planId, exerciseId)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS handoff_preferences (id INTEGER NOT NULL, goals TEXT NOT NULL, equipment TEXT NOT NULL, constraints TEXT NOT NULL, activityPreference TEXT NOT NULL, availableMinutes INTEGER, lastTargetDate TEXT NOT NULL, PRIMARY KEY(id))""")
    }
}

data class ResolvedPlanExercise(val prescription: PlanPrescription, val exerciseId: Long? = null, val createName: String? = null)
data class PlanPreview(val spec: PlanSpec, val resolutions: List<ResolvedPlanExercise>)
data class EditablePlanExercise(val key: String, val sourceName: String, val sets: String, val reps: String,
    val weight: String, val note: String, val exerciseId: Long?, val createName: String? = null)
data class EditablePlan(val title: String, val date: String, val minutes: String, val note: String, val fingerprint: String,
    val exercises: List<EditablePlanExercise>) {
    fun validated(): PlanPreview {
        val resolutions = exercises.map { e ->
            fun <T> field(label: String, parse: () -> T): T = try { parse() } catch (failure: IllegalArgumentException) {
                throw PlanParseException(null, label, e.sourceName, failure.message ?: "Check this target.")
            }
            val sets = field("Sets") { WorkoutPlanParser.positiveInteger(e.sets, WorkoutPlanParser.MAX_SETS, "Sets") }
            val reps = field("Reps") { WorkoutPlanParser.parseReps(e.reps) }
            val weight = field("Weight") { WorkoutPlanParser.parseWeight(e.weight) }
            ResolvedPlanExercise(PlanPrescription(e.sourceName, sets, reps.first, reps.second, weight, e.note), e.exerciseId, e.createName)
        }
        val spec = PlanSpec(title, date, WorkoutPlanParser.positiveInteger(minutes, WorkoutPlanParser.MAX_MINUTES, "Minutes"), note,
            resolutions.map { it.prescription }, fingerprint)
        WorkoutPlanParser.validate(spec)
        return PlanPreview(spec, resolutions)
    }
}
sealed interface PlanSaveResult { data class Saved(val id: String) : PlanSaveResult; data class Duplicate(val id: String) : PlanSaveResult }

fun validateHandoffPreferences(preferences: HandoffPreferences) {
    require(preferences.id == 1 && listOf(preferences.goals, preferences.equipment, preferences.constraints).all { text -> text.length <= 4000 && text.none { it.isISOControl() && it !in "\n\r\t" } }) { "Goals, equipment and constraints support up to 4,000 readable characters each." }
    require(preferences.activityPreference == "" || ActivityType.entries.any { it.key == preferences.activityPreference }) { "Choose a supported activity preference." }
    require(preferences.availableMinutes == null || preferences.availableMinutes in 1..WorkoutPlanParser.MAX_MINUTES) { "Available time must be 1-720 whole minutes." }
    require(preferences.lastTargetDate.isBlank() || validHistoryDate(preferences.lastTargetDate)) { "Choose a valid target date." }
}

fun incompatiblePlanExercises(exercises: List<Exercise>, localEntries: List<WorkoutExercise>, localSets: List<WorkoutSet>, imported: List<ImportedSet>): Set<Long> {
    val entryNames = localEntries.associate { it.id to it.exerciseId }
    val strength = localSets.filter { it.reps > 0 }.mapNotNull { entryNames[it.entryId] }.toMutableSet()
    val cardio = localSets.filter { it.reps == 0 && (it.distance != null || it.durationSeconds != null) }.mapNotNull { entryNames[it.entryId] }.toMutableSet()
    val byName = exercises.groupBy { normaliseExerciseName(it.name) }
    imported.forEach { set ->
        val ids = byName[normaliseExerciseName(set.exercise)].orEmpty().map { it.id }
        if (set.weightKg != null && set.reps != null && (set.reps > 0 || (!set.category.equals("Cardio", true) && set.distance == null && set.durationSeconds == null))) strength.addAll(ids)
        else if (set.distance != null || set.durationSeconds != null || set.category.equals("Cardio", true)) cardio.addAll(ids)
    }
    return cardio - strength
}

class WorkoutPlanRepository(private val database: FitDatabase) {
    private val dao = database.planDao()
    private val workouts = WorkoutRepository(database)
    val plans = dao.observe()
    val preferences = dao.observePreferences()

    suspend fun savePreferences(preferences: HandoffPreferences) { validateHandoffPreferences(preferences); dao.putPreferences(preferences) }
    suspend fun save(preview: PlanPreview, allowDuplicate: Boolean = false): PlanSaveResult = database.withTransaction {
        WorkoutPlanParser.validate(preview.spec)
        require(preview.resolutions.map { it.prescription } == preview.spec.exercises) { "Review the edited exercise targets before saving." }
        require(preview.resolutions.all { (it.exerciseId != null) xor (it.createName != null) }) { "Resolve each exercise by selecting a catalogue entry or explicitly creating a strength exercise." }
        if (!allowDuplicate) dao.duplicate(preview.spec.fingerprint)?.let { return@withTransaction PlanSaveResult.Duplicate(it.id) }
        val local = database.dao()
        val catalogue = local.exercises()
        val incompatible = incompatiblePlanExercises(catalogue, local.entries(), local.sets(), database.importedDao().sets())
        val ids = preview.resolutions.map { resolution ->
            if (resolution.exerciseId != null) {
                val exercise = requireNotNull(local.exercise(resolution.exerciseId)) { "A selected exercise no longer exists. Resolve it again." }
                require(exercise.id !in incompatible) { "${exercise.name} is recorded as cardio only. Select a compatible strength exercise." }
                exercise.id
            } else {
                val name = InputRules.exerciseName(requireNotNull(resolution.createName))
                val key = name.lowercase(Locale.ROOT)
                require(local.exerciseByKey(key) == null) { "$name already exists. Select its catalogue entry instead of creating it." }
                local.insertExercise(Exercise(name = name, nameKey = key))
            }
        }
        require(ids.distinct().size == ids.size) { "The same catalogue exercise was selected twice. Combine its prescriptions or choose distinct exercises." }
        val planId = UUID.randomUUID().toString()
        val spec = preview.spec
        dao.insert(PlannedWorkout(planId, spec.title.trim(), spec.date, spec.durationMinutes, spec.note, System.currentTimeMillis(), spec.fingerprint))
        dao.insertTargets(preview.resolutions.mapIndexed { position, resolution ->
            val target = resolution.prescription
            PlannedExercise(planId = planId, exerciseId = ids[position], position = position, sourceName = target.name,
                sets = target.sets, repsMin = target.repsMin, repsMax = target.repsMax, weightValue = target.weight.value, weightUnit = target.weight.unit, note = target.note)
        })
        PlanSaveResult.Saved(planId)
    }
    suspend fun start(planId: String): Long = database.withTransaction {
        val plan = requireNotNull(dao.detail(planId)) { "The planned workout no longer exists." }
        plan.plan.startedWorkoutId?.let { return@withTransaction it }
        require(database.dao().active() == null) { "Finish or delete the unfinished workout before starting a plan." }
        val incompatible = incompatiblePlanExercises(database.dao().exercises(), database.dao().entries(), database.dao().sets(), database.importedDao().sets())
        require(plan.ordered.none { it.exercise.id in incompatible }) { "A planned exercise is now recorded as cardio only. Import a corrected strength plan before starting." }
        val id = workouts.startActivity("strength", "", plan.plan.title, plan.plan.notes, null)
        workouts.addExercises(id, plan.ordered.map { it.exercise.id })
        dao.markStarted(planId, id)
        id
    }
    suspend fun delete(id: String) = dao.delete(id)
}
