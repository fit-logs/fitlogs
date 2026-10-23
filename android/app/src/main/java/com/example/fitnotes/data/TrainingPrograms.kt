package com.example.fitnotes.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.util.UUID

@Entity(tableName = "training_programs")
data class TrainingProgram(@PrimaryKey val id: String, val title: String, val weeks: Int, val status: String = "paused",
    val nextIndex: Int = 0, val notBefore: String = "", val createdAt: Long = System.currentTimeMillis())
@Entity(tableName = "program_days", foreignKeys = [
    ForeignKey(entity = TrainingProgram::class, parentColumns = ["id"], childColumns = ["programId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = PlannedWorkout::class, parentColumns = ["id"], childColumns = ["planId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["programId", "position"], unique = true), Index(value = ["planId"], unique = true)])
data class ProgramDay(@PrimaryKey val id: String, val programId: String, val position: Int, val planId: String)
@Entity(tableName = "program_sessions", foreignKeys = [
    ForeignKey(entity = TrainingProgram::class, parentColumns = ["id"], childColumns = ["programId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = Workout::class, parentColumns = ["id"], childColumns = ["workoutId"], onDelete = ForeignKey.CASCADE)], indices = [Index("programId")])
data class ProgramSession(@PrimaryKey val workoutId: Long, val programId: String, val dayIndex: Int)
@Entity(tableName = "program_steps", foreignKeys = [
    ForeignKey(entity = TrainingProgram::class, parentColumns = ["id"], childColumns = ["programId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = Workout::class, parentColumns = ["id"], childColumns = ["workoutId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index(value = ["programId", "dayIndex"], unique = true), Index(value = ["workoutId"], unique = true)])
data class ProgramStep(@PrimaryKey val id: String, val programId: String, val dayIndex: Int, val action: String, val workoutId: Long?, val recordedAt: Long)
data class ProgramDayDetail(@Embedded val day: ProgramDay,
    @Relation(entity = PlannedWorkout::class, parentColumn = "planId", entityColumn = "id") val plan: PlannedWorkoutDetail)
data class ProgramDetail(@Embedded val program: TrainingProgram,
    @Relation(entity = ProgramDay::class, parentColumn = "id", entityColumn = "programId") val days: List<ProgramDayDetail>,
    @Relation(parentColumn = "id", entityColumn = "programId") val steps: List<ProgramStep>) {
    val ordered get() = days.sortedBy { it.day.position }
    val total get() = program.weeks * days.size
    val next get() = if (program.nextIndex < total && days.isNotEmpty()) ordered[program.nextIndex % days.size] else null
    val week get() = if (days.isEmpty()) 1 else program.nextIndex / days.size + 1
    val progress get() = "${steps.count { it.action == "completed" }} completed · ${steps.count { it.action == "skipped" }} skipped"
}
@Dao interface TrainingProgramDao {
    @Transaction @Query("SELECT * FROM training_programs ORDER BY createdAt DESC, id") fun observe(): Flow<List<ProgramDetail>>
    @Transaction @Query("SELECT * FROM training_programs WHERE id = :id") suspend fun detail(id: String): ProgramDetail?
    @Query("SELECT * FROM training_programs ORDER BY id") suspend fun programs(): List<TrainingProgram>
    @Query("SELECT * FROM program_days ORDER BY programId, position") suspend fun days(): List<ProgramDay>
    @Query("SELECT * FROM program_sessions ORDER BY workoutId") suspend fun sessions(): List<ProgramSession>
    @Query("SELECT * FROM program_sessions ORDER BY workoutId") fun observeSessions(): Flow<List<ProgramSession>>
    @Query("SELECT * FROM program_steps ORDER BY programId, dayIndex") suspend fun steps(): List<ProgramStep>
    @Insert suspend fun insert(program: TrainingProgram)
    @Insert suspend fun insertDays(days: List<ProgramDay>)
    @Insert suspend fun insertSession(session: ProgramSession)
    @Insert suspend fun insertStep(step: ProgramStep)
    @Update suspend fun update(program: TrainingProgram)
    @Query("UPDATE training_programs SET status = 'paused' WHERE status = 'active'") suspend fun pauseActive()
    @Query("DELETE FROM training_programs") suspend fun clear()
}
val PROGRAM_MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE planned_workouts ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE planned_workouts ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE TABLE training_programs (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, weeks INTEGER NOT NULL, status TEXT NOT NULL, nextIndex INTEGER NOT NULL, notBefore TEXT NOT NULL, createdAt INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE program_days (id TEXT NOT NULL PRIMARY KEY, programId TEXT NOT NULL, position INTEGER NOT NULL, planId TEXT NOT NULL, FOREIGN KEY(programId) REFERENCES training_programs(id) ON DELETE CASCADE, FOREIGN KEY(planId) REFERENCES planned_workouts(id) ON DELETE RESTRICT)")
        db.execSQL("CREATE UNIQUE INDEX index_program_days_programId_position ON program_days(programId, position)")
        db.execSQL("CREATE UNIQUE INDEX index_program_days_planId ON program_days(planId)")
        db.execSQL("CREATE TABLE program_sessions (workoutId INTEGER NOT NULL PRIMARY KEY, programId TEXT NOT NULL, dayIndex INTEGER NOT NULL, FOREIGN KEY(programId) REFERENCES training_programs(id) ON DELETE CASCADE, FOREIGN KEY(workoutId) REFERENCES workouts(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX index_program_sessions_programId ON program_sessions(programId)")
        db.execSQL("CREATE TABLE program_steps (id TEXT NOT NULL PRIMARY KEY, programId TEXT NOT NULL, dayIndex INTEGER NOT NULL, action TEXT NOT NULL, workoutId INTEGER, recordedAt INTEGER NOT NULL, FOREIGN KEY(programId) REFERENCES training_programs(id) ON DELETE CASCADE, FOREIGN KEY(workoutId) REFERENCES workouts(id) ON DELETE SET NULL)")
        db.execSQL("CREATE UNIQUE INDEX index_program_steps_programId_dayIndex ON program_steps(programId, dayIndex)")
        db.execSQL("CREATE UNIQUE INDEX index_program_steps_workoutId ON program_steps(workoutId)")
    }
}

/** A program owns copies of its prescriptions. Template edits/deletion cannot
 * change a running program or any workout's recorded target snapshot. */
class TrainingProgramRepository(private val db: FitDatabase) {
    private val dao = db.programDao()
    suspend fun create(title: String, weeks: Int, templateIds: List<String>): String = db.withTransaction {
        require(title.trim().isNotEmpty() && title.trim().length <= 200 && title.none(Char::isISOControl)) { "Enter a readable program title (up to 200 characters)." }
        require(weeks in 1..52 && templateIds.size in 1..14) { "Choose 1–52 weeks and 1–14 ordered workout days." }
        val id = UUID.randomUUID().toString()
        dao.insert(TrainingProgram(id, title.trim(), weeks))
        val plans = WorkoutPlanRepository(db)
        templateIds.forEachIndexed { position, template ->
            val detail = requireNotNull(db.planDao().detail(template)) { "A template was removed. Review the days again." }
            require(detail.exercises.isNotEmpty()) { "Add explicit exercises before using this template as a program day." }
            require(!detail.plan.archived && dao.days().none { it.planId == template }) { "Choose an available template." }
            val copy = plans.duplicate(template)
            plans.rename(copy, detail.plan.title)
            dao.insertDays(listOf(ProgramDay(UUID.randomUUID().toString(), id, position, copy)))
        }
        id
    }
    suspend fun activate(id: String) = db.withTransaction {
        val p = requireNotNull(dao.detail(id)); require(p.next != null) { "This program is complete." }
        dao.pauseActive(); dao.update(p.program.copy(status = "active"))
    }
    suspend fun pause(id: String) = db.withTransaction {
        val p = requireNotNull(dao.detail(id)); if (p.program.status == "active") dao.update(p.program.copy(status = "paused"))
    }
    suspend fun postpone(id: String, date: String) = db.withTransaction {
        require(date.isEmpty() || validHistoryDate(date)) { "Choose a valid date." }
        val p = requireNotNull(dao.detail(id)); dao.update(p.program.copy(notBefore = date))
    }
    suspend fun start(id: String): Long = db.withTransaction {
        val p = requireNotNull(dao.detail(id)); val next = requireNotNull(p.next) { "This program is complete." }
        require(p.program.status == "active") { "Set this as your active program first." }
        require(p.program.notBefore.isBlank() || LocalDate.now().toString() >= p.program.notBefore) { "Postponed until ${p.program.notBefore}. Clear the postponement to start earlier." }
        val active = db.dao().active()
        if (active != null) {
            require(dao.sessions().any { it.workoutId == active.id && it.programId == id && it.dayIndex == p.program.nextIndex }) { "Finish or discard the active workout first. Ad-hoc workouts do not advance a program." }
            return@withTransaction active.id
        }
        val workout = WorkoutPlanRepository(db).start(next.day.planId)
        dao.insertSession(ProgramSession(workout, id, p.program.nextIndex))
        workout
    }
    /** Completion is an explicit separate action after saving actual results.
     * Reopens, clock changes, past logs and ad-hoc workouts never advance it. */
    suspend fun complete(id: String, workoutId: Long) = db.withTransaction {
        val p = requireNotNull(dao.detail(id))
        if (p.steps.any { it.workoutId == workoutId && it.action == "completed" }) return@withTransaction
        require(dao.sessions().any { it.workoutId == workoutId && it.programId == id && it.dayIndex == p.program.nextIndex }) { "This workout does not match the next program day." }
        val active = db.dao().active()
        require(active == null || dao.sessions().none { it.workoutId == active.id && it.programId == id && it.dayIndex == p.program.nextIndex }) { "Finish or discard the running program session before completing this day." }
        val w = requireNotNull(db.dao().workout(workoutId))
        require(w.isComplete && !w.historicalDraft && db.dao().workoutEntries(workoutId).any { db.dao().entrySets(it.id).isNotEmpty() }) { "Finish the session with saved performed sets before completing this day." }
        advance(p, "completed", workoutId)
    }
    suspend fun skip(id: String) = db.withTransaction {
        val p = requireNotNull(dao.detail(id)); require(p.next != null) { "This program is complete." }
        require(db.dao().active()?.let { w -> dao.sessions().none { it.workoutId == w.id && it.programId == id && it.dayIndex == p.program.nextIndex } } != false) { "Finish or discard this program's running session before skipping the day." }
        advance(p, "skipped", null)
    }
    private suspend fun advance(p: ProgramDetail, action: String, workoutId: Long?) {
        dao.insertStep(ProgramStep(UUID.randomUUID().toString(), p.program.id, p.program.nextIndex, action, workoutId, System.currentTimeMillis()))
        val next = p.program.nextIndex + 1
        dao.update(p.program.copy(nextIndex = next, notBefore = "", status = if (next == p.total) "completed" else p.program.status))
    }
}
