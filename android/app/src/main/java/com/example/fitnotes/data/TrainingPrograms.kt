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
    val nextIndex: Int = 0, val notBefore: String = "", val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val definitionId: String = "", @ColumnInfo(defaultValue = "''") val definitionVersion: String = "",
    @ColumnInfo(defaultValue = "''") val definitionJson: String = "", @ColumnInfo(defaultValue = "''") val sourceFingerprint: String = "",
    @ColumnInfo(defaultValue = "0") val flexibleQueue: Boolean = false)
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
    ForeignKey(entity = Workout::class, parentColumns = ["id"], childColumns = ["workoutId"], onDelete = ForeignKey.SET_NULL),
    ForeignKey(entity = PlannedWorkout::class, parentColumns = ["id"], childColumns = ["planId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["programId", "dayIndex"], unique = true), Index(value = ["workoutId"], unique = true), Index("planId")])
data class ProgramStep(@PrimaryKey val id: String, val programId: String, val dayIndex: Int, val action: String, val workoutId: Long?, val recordedAt: Long,
    @ColumnInfo(defaultValue = "-1") val queuePosition: Int = -1, val planId: String? = null,
    @ColumnInfo(defaultValue = "''") val scheduledDate: String = "")
data class ProgramDayDetail(@Embedded val day: ProgramDay,
    @Relation(entity = PlannedWorkout::class, parentColumn = "planId", entityColumn = "id") val plan: PlannedWorkoutDetail)
data class ProgramOccurrence(@Embedded val step: ProgramStep,
    @Relation(entity = PlannedWorkout::class, parentColumn = "planId", entityColumn = "id") val plan: PlannedWorkoutDetail?)
data class ProgramDetail(@Embedded val program: TrainingProgram,
    @Relation(entity = ProgramDay::class, parentColumn = "id", entityColumn = "programId") val days: List<ProgramDayDetail>,
    @Relation(parentColumn = "id", entityColumn = "programId") val steps: List<ProgramStep>,
    @Relation(entity = ProgramStep::class, parentColumn = "id", entityColumn = "programId") val occurrences: List<ProgramOccurrence> = emptyList()) {
    val queue by lazy(LazyThreadSafetyMode.PUBLICATION) { occurrences.sortedWith(compareBy<ProgramOccurrence> { it.step.queuePosition }.thenBy { it.step.id }) }
    val nextOccurrence get() = queue.firstOrNull { it.step.action == "pending" }
    val inProgress get() = queue.firstOrNull { it.step.action == "in_progress" }

    val ordered by lazy(LazyThreadSafetyMode.PUBLICATION) { days.sortedBy { it.day.position } }
    val total get() = program.weeks * days.size
    val next get() = if (program.flexibleQueue) nextOccurrence?.let { o -> o.plan?.let { ProgramDayDetail(ProgramDay(o.step.id, program.id, o.step.queuePosition, it.plan.id), it) } } else if (program.nextIndex < total && days.isNotEmpty()) ordered[program.nextIndex % days.size] else null
    val week get() = if (days.isEmpty()) 1 else (if (program.flexibleQueue) nextOccurrence?.step?.dayIndex ?: program.nextIndex else program.nextIndex) / days.size + 1
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
    @Update suspend fun updateStep(step: ProgramStep)
    @Query("DELETE FROM program_sessions WHERE workoutId = :workoutId") suspend fun unlinkSession(workoutId: Long)
    @Query("DELETE FROM program_steps WHERE programId = :id") suspend fun clearSteps(id: String)
    @Query("SELECT * FROM program_steps WHERE id = :id") suspend fun step(id: String): ProgramStep?
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
    suspend fun create(title: String, weeks: Int, templateIds: List<String>, flexible: Boolean = false): String = db.withTransaction {
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
        if (flexible) enableQueue(id)
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
        val p = requireNotNull(dao.detail(id))
        if (p.program.flexibleQueue) return@withTransaction startOccurrence(id, requireNotNull(p.inProgress ?: p.nextOccurrence) { "This program is complete." }.step.id)
        val next = requireNotNull(p.next) { "This program is complete." }
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
        if (p.program.flexibleQueue) {
            require(dao.sessions().any { it.workoutId == workoutId && it.programId == id }) { "This workout is not linked to this program." }
            completeLinked(workoutId); return@withTransaction
        }
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
        if (p.program.flexibleQueue) { skipOccurrence(id, requireNotNull(p.nextOccurrence).step.id); return@withTransaction }
        require(db.dao().active()?.let { w -> dao.sessions().none { it.workoutId == w.id && it.programId == id && it.dayIndex == p.program.nextIndex } } != false) { "Finish or discard this program's running session before skipping the day." }
        advance(p, "skipped", null)
    }
    /** Explicit opt-in preserves legacy completion records and stable dayIndex links. */
    suspend fun enableQueue(id: String) = db.withTransaction {
        val p = requireNotNull(dao.detail(id))
        if (p.program.flexibleQueue) return@withTransaction
        val links = dao.sessions().filter { it.programId == id }
        for (index in 0 until p.total) {
            val day = p.ordered[index % p.days.size]
            val old = p.steps.firstOrNull { it.dayIndex == index }
            val linked = links.lastOrNull { it.dayIndex == index && db.dao().workout(it.workoutId)?.isComplete == false }
            val step = old?.copy(queuePosition = index, planId = day.day.planId)
                ?: ProgramStep(UUID.randomUUID().toString(), id, index, if (linked == null) "pending" else "in_progress", linked?.workoutId,
                    System.currentTimeMillis(), index, day.day.planId, if (index == p.program.nextIndex) p.program.notBefore else day.plan.plan.scheduledDate)
            if (old == null) dao.insertStep(step) else dao.updateStep(step)
        }
        dao.update(p.program.copy(flexibleQueue = true, notBefore = ""))
    }
    private suspend fun occurrence(programId: String, id: String): ProgramStep {
        val p = requireNotNull(dao.detail(programId))
        require(p.program.flexibleQueue) { "Enable the flexible queue first." }
        return requireNotNull(dao.step(id)?.takeIf { it.programId == programId }) { "Session no longer exists." }
    }
    suspend fun move(programId: String, id: String, destination: Int) = db.withTransaction {
        val step = occurrence(programId, id)
        require(step.action == "pending") { "Only pending sessions can move." }
        val rows = requireNotNull(dao.detail(programId)).queue.map { it.step }.toMutableList()
        val from = rows.indexOfFirst { it.id == id }
        require(destination in rows.indices) { "Choose a position in this program." }
        rows.add(destination, rows.removeAt(from))
        rows.forEachIndexed { index, row -> dao.updateStep(row.copy(queuePosition = index)) }
        // Dates are explicit guidance, never inferred from order or the clock.
    }
    suspend fun doLater(programId: String, id: String) = db.withTransaction {
        val p = requireNotNull(dao.detail(programId)); move(programId, id, p.queue.lastIndex)
    }
    suspend fun schedule(programId: String, id: String, date: String) = db.withTransaction {
        require(date.isBlank() || validHistoryDate(date)) { "Use a valid YYYY-MM-DD date, or leave blank." }
        val step = occurrence(programId, id)
        require(step.action == "pending" || step.action == "skipped") { "Only upcoming sessions can change dates." }
        dao.updateStep(step.copy(scheduledDate = date))
        val p=requireNotNull(dao.detail(programId))
        if(step.planId != p.ordered[step.dayIndex % p.days.size].day.planId) {
            val plan=requireNotNull(db.planDao().detail(requireNotNull(step.planId)))
            db.planDao().updatePlan(plan.plan.copy(scheduledDate=date))
        }
    }
    suspend fun skipOccurrence(programId: String, id: String) = db.withTransaction {
        val step = occurrence(programId, id)
        require(step.action == "pending") { "Only a pending session can be skipped." }
        editableOccurrence(programId,id)
        dao.updateStep(requireNotNull(dao.step(id)).copy(action = "skipped", recordedAt = System.currentTimeMillis()))
        refreshProgress(programId)
    }
    suspend fun restoreOccurrence(programId: String, id: String) = db.withTransaction {
        val step = occurrence(programId, id)
        require(step.action == "skipped") { "Only a skipped session can be restored." }
        dao.updateStep(step.copy(action = "pending", recordedAt = System.currentTimeMillis()))
        refreshProgress(programId)
    }
    /** Copy-on-edit isolates this occurrence from its repeated definition and snapshots. */
    suspend fun editableOccurrence(programId: String, id: String, future: Boolean = false): String = db.withTransaction {
        val step = occurrence(programId, id)
        require(step.action == "pending" || step.action == "skipped") { "Active and completed sessions retain their prescriptions." }
        val p = requireNotNull(dao.detail(programId))
        val original = p.ordered[step.dayIndex % p.days.size].day.planId
        if (future) return@withTransaction original
        if (step.planId != original) return@withTransaction requireNotNull(step.planId)
        val copy = WorkoutPlanRepository(db).duplicate(original)
        val source=requireNotNull(db.planDao().detail(original)).plan
        val copyPlan=requireNotNull(db.planDao().detail(copy)).plan
        db.planDao().updatePlan(copyPlan.copy(title=source.title,scheduledDate=step.scheduledDate))
        dao.updateStep(step.copy(planId = copy))
        copy
    }
    suspend fun startOccurrence(programId: String, id: String): Long = db.withTransaction {
        val step = occurrence(programId, id)
        val active = db.dao().active()
        if (active != null) {
            require(step.workoutId == active.id && step.action == "in_progress") { "Resume, finish or discard the active workout first." }
            return@withTransaction active.id
        }
        require(step.action == "pending") { "This session is no longer pending." }
        val p = requireNotNull(dao.detail(programId))
        require(p.program.status == "active") { "Set this program active first." }
        val planId = editableOccurrence(programId, id)
        val current = requireNotNull(dao.step(id))
        val plan = requireNotNull(db.planDao().detail(planId))
        require(plan.plan.activityType == "strength") { "Choose a completed recorded run to link this running prescription." }
        val workout = WorkoutPlanRepository(db).start(plan.plan.id)
        dao.insertSession(ProgramSession(workout, programId, step.dayIndex))
        dao.updateStep(current.copy(action = "in_progress", workoutId = workout))
        workout
    }
    /** Runs use explicit recorded IDs; targets never create a run or fill actual metrics. */
    suspend fun linkRun(programId: String, id: String, workoutId: Long) = db.withTransaction {
        val step = occurrence(programId, id)
        require(step.action == "pending") { "Choose a pending running session." }
        val plan = requireNotNull(db.planDao().detail(requireNotNull(step.planId)))
        require(plan.plan.activityType == "running")
        val workout = requireNotNull(db.dao().workout(workoutId))
        require(workout.activityType == "running" && workout.isComplete && !workout.historicalDraft) { "Choose an actual completed run." }
        require(dao.sessions().none { it.workoutId == workoutId }) { "That run is already linked to a program session." }
        require(runningRecords(runningSnapshot(db)).any { it.parentKey == "local:$workoutId" }) { "That run was removed or merged. Select its current recorded run." }
        editableOccurrence(programId,id)
        dao.insertSession(ProgramSession(workoutId, programId, step.dayIndex))
        dao.updateStep(requireNotNull(dao.step(id)).copy(action = "completed", workoutId = workoutId, recordedAt = System.currentTimeMillis()))
        refreshProgress(programId)
    }
    suspend fun completeLinked(workoutId: Long) = db.withTransaction {
        val link = dao.sessions().firstOrNull { it.workoutId == workoutId } ?: return@withTransaction
        val p = requireNotNull(dao.detail(link.programId))
        if (!p.program.flexibleQueue) return@withTransaction
        val step = p.steps.firstOrNull { it.workoutId == workoutId } ?: return@withTransaction
        if (step.action == "completed") return@withTransaction
        val workout = requireNotNull(db.dao().workout(workoutId))
        require(workout.isComplete && !workout.historicalDraft && db.dao().workoutEntries(workoutId).any { db.dao().entrySets(it.id).isNotEmpty() }) { "Finish the workout with actual saved sets first." }
        dao.updateStep(step.copy(action = "completed", recordedAt = System.currentTimeMillis()))
        refreshProgress(link.programId)
    }
    suspend fun discarded(workoutId: Long) = db.withTransaction {
        dao.steps().firstOrNull { it.workoutId == workoutId && it.action == "in_progress" }?.let { step ->
            dao.updateStep(step.copy(action = "pending", workoutId = null))
        }
        // Empty legacy attempts also lose their association when retained as drafts.
        dao.unlinkSession(workoutId)
    }
    private suspend fun refreshProgress(id: String) {
        val p = requireNotNull(dao.detail(id))
        val closed = p.steps.count { it.action in setOf("completed", "skipped") }
        dao.update(p.program.copy(nextIndex = closed, status = if (closed == p.total) "completed" else if (p.program.status == "completed") "paused" else p.program.status))
    }
    private suspend fun advance(p: ProgramDetail, action: String, workoutId: Long?) {
        dao.insertStep(ProgramStep(UUID.randomUUID().toString(), p.program.id, p.program.nextIndex, action, workoutId, System.currentTimeMillis()))
        val next = p.program.nextIndex + 1
        dao.update(p.program.copy(nextIndex = next, notBefore = "", status = if (next == p.total) "completed" else p.program.status))
    }
}
