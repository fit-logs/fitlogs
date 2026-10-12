package com.example.fitnotes.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Entity(tableName = "history_imports")
data class HistoryImport(
    @PrimaryKey val id: String,
    val source: String,
    val fileName: String,
    val importedAt: Long,
    val units: String,
    val skippedRows: Int,
    val unsupported: String,
    val metadataJson: String,
)

@Entity(
    tableName = "imported_workouts",
    foreignKeys = [ForeignKey(entity = HistoryImport::class, parentColumns = ["id"], childColumns = ["importId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("importId")],
)
data class ImportedWorkout(
    @PrimaryKey val id: String,
    val importId: String,
    val date: String,
    val recordedStart: String? = null,
    val recordedEnd: String? = null,
    val notes: String = "",
)

@Entity(
    tableName = "imported_sets",
    foreignKeys = [ForeignKey(entity = ImportedWorkout::class, parentColumns = ["id"], childColumns = ["workoutId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("workoutId")],
)
data class ImportedSet(
    @PrimaryKey val id: String,
    val workoutId: String,
    val sourceRecordId: String,
    val sourceOrder: Int,
    val exercise: String,
    val category: String,
    val weightKg: Double?,
    val reps: Int?,
    val distance: Double?,
    val distanceUnit: String?,
    val durationSeconds: Double?,
    val comment: String,
    val rawValues: String,
)

@Entity(
    tableName = "body_measurements",
    foreignKeys = [ForeignKey(entity = HistoryImport::class, parentColumns = ["id"], childColumns = ["importId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("importId")],
)
data class BodyMeasurement(
    @PrimaryKey val id: String,
    val importId: String,
    val sourceRecordId: String,
    val date: String,
    val time: String,
    val name: String,
    val value: Double,
    val unit: String,
    val unitName: String,
    val comment: String,
    val rawValues: String,
)

data class ImportedWorkoutDetail(
    @Embedded val workout: ImportedWorkout,
    @Relation(parentColumn = "id", entityColumn = "workoutId") val sets: List<ImportedSet>,
    @Relation(entity = HealthSession::class, parentColumn = "id", entityColumn = "importedWorkoutId") val health: List<HealthDetail> = emptyList(),
)

@Dao
interface ImportedHistoryDao {
    @Query("SELECT * FROM history_imports ORDER BY importedAt DESC") fun observeImports(): Flow<List<HistoryImport>>
    @Transaction @Query("SELECT * FROM imported_workouts ORDER BY date DESC, id DESC")
    fun observeWorkouts(): Flow<List<ImportedWorkoutDetail>>
    @Query("SELECT * FROM body_measurements ORDER BY date DESC, time DESC, id DESC")
    fun observeBody(): Flow<List<BodyMeasurement>>
    @Query("SELECT * FROM history_imports WHERE id = :id") suspend fun imported(id: String): HistoryImport?
    @Query("SELECT * FROM history_imports ORDER BY id") suspend fun imports(): List<HistoryImport>
    @Query("SELECT * FROM imported_workouts ORDER BY id") suspend fun workouts(): List<ImportedWorkout>
    @Query("SELECT * FROM imported_sets ORDER BY workoutId, sourceOrder, id") suspend fun sets(): List<ImportedSet>
    @Query("SELECT * FROM body_measurements ORDER BY id") suspend fun body(): List<BodyMeasurement>
    @Transaction @Query("SELECT * FROM imported_workouts WHERE id = :id") suspend fun workout(id: String): ImportedWorkoutDetail?
    @Query("SELECT * FROM imported_sets WHERE id = :id") suspend fun set(id: String): ImportedSet?
    @Update suspend fun updateWorkout(value: ImportedWorkout)
    @Update suspend fun updateSet(value: ImportedSet)
    @Update suspend fun updateBody(value: BodyMeasurement)
    @Insert suspend fun insertMeasurement(value: BodyMeasurement)
    @Query("SELECT * FROM body_measurements WHERE id = :id") suspend fun measurement(id: String): BodyMeasurement?
    @Query("DELETE FROM body_measurements WHERE id = :id") suspend fun deleteMeasurement(id: String)
    @Query("DELETE FROM imported_sets WHERE id = :id") suspend fun deleteSet(id: String)
    @Query("DELETE FROM imported_workouts WHERE id = :id") suspend fun deleteWorkout(id: String)
    @Insert suspend fun insertSet(value: ImportedSet)
    @Insert suspend fun insertImport(value: HistoryImport)
    @Insert suspend fun insertImports(values: List<HistoryImport>)
    @Insert suspend fun insertWorkouts(values: List<ImportedWorkout>)
    @Insert suspend fun insertSets(values: List<ImportedSet>)
    @Insert suspend fun insertBody(values: List<BodyMeasurement>)
    @Query("DELETE FROM history_imports") suspend fun clear()
}

val IMPORT_MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Only add tables. Existing exercises, sessions and sets are left intact.
        db.execSQL("CREATE TABLE IF NOT EXISTS history_imports (id TEXT NOT NULL PRIMARY KEY, source TEXT NOT NULL, fileName TEXT NOT NULL, importedAt INTEGER NOT NULL, units TEXT NOT NULL, skippedRows INTEGER NOT NULL, unsupported TEXT NOT NULL, metadataJson TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS imported_workouts (id TEXT NOT NULL PRIMARY KEY, importId TEXT NOT NULL, date TEXT NOT NULL, recordedStart TEXT, recordedEnd TEXT, notes TEXT NOT NULL, FOREIGN KEY(importId) REFERENCES history_imports(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_imported_workouts_importId ON imported_workouts(importId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS imported_sets (id TEXT NOT NULL PRIMARY KEY, workoutId TEXT NOT NULL, sourceRecordId TEXT NOT NULL, sourceOrder INTEGER NOT NULL, exercise TEXT NOT NULL, category TEXT NOT NULL, weightKg REAL, reps INTEGER, distance REAL, distanceUnit TEXT, durationSeconds REAL, comment TEXT NOT NULL, rawValues TEXT NOT NULL, FOREIGN KEY(workoutId) REFERENCES imported_workouts(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_imported_sets_workoutId ON imported_sets(workoutId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS body_measurements (id TEXT NOT NULL PRIMARY KEY, importId TEXT NOT NULL, sourceRecordId TEXT NOT NULL, date TEXT NOT NULL, time TEXT NOT NULL, name TEXT NOT NULL, value REAL NOT NULL, unit TEXT NOT NULL, unitName TEXT NOT NULL, comment TEXT NOT NULL, rawValues TEXT NOT NULL, FOREIGN KEY(importId) REFERENCES history_imports(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_body_measurements_importId ON body_measurements(importId)")
    }
}

fun validHistoryDate(raw: String): Boolean {
    if (raw.length != 10 || raw[4] != '-' || raw[7] != '-' ||
        raw.withIndex().any { (index, char) -> index != 4 && index != 7 && char !in '0'..'9' }) return false
    val year = raw.substring(0, 4).toInt()
    val month = raw.substring(5, 7).toInt()
    val day = raw.substring(8, 10).toInt()
    if (year !in 1..9999 || month !in 1..12) return false
    val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    val days = when (month) { 2 -> if (leap) 29 else 28; 4, 6, 9, 11 -> 30; else -> 31 }
    return day in 1..days
}

fun validHistoryTime(raw: String): Boolean {
    if (raw.length != 8 || raw[2] != ':' || raw[5] != ':' ||
        raw.withIndex().any { (index, char) -> index != 2 && index != 5 && char !in '0'..'9' }) return false
    return raw.substring(0, 2).toInt() in 0..23 && raw.substring(3, 5).toInt() in 0..59 && raw.substring(6, 8).toInt() in 0..59
}

fun validRecordedTimestamp(raw: String): Boolean {
    if (raw.length !in 25..29 || !validHistoryDate(raw.substring(0, 10)) || raw[10] != 'T' ||
        !validHistoryTime(raw.substring(11, 19))) return false
    val offset = raw.length - 6
    if (offset != 19) {
        if (raw[19] != '.' || offset - 20 !in 1..3 || raw.substring(20, offset).any { it !in '0'..'9' }) return false
    }
    if (raw[offset] !in "+-" || raw[offset + 3] != ':' ||
        raw.substring(offset + 1, offset + 3).any { it !in '0'..'9' } ||
        raw.substring(offset + 4).any { it !in '0'..'9' }) return false
    return raw.substring(offset + 1, offset + 3).toInt() in 0..23 && raw.substring(offset + 4).toInt() in 0..59
}

fun localWorkoutDate(workout: Workout): String =
    workout.sessionDate.ifBlank { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(workout.startedAt)) }

fun importedSetText(set: ImportedSet): String = buildList {
    if (set.weightKg != null && set.reps != null) add(weightText(set.weightKg) + " kg × " + set.reps + " reps")
    if (set.distance != null) add(weightText(set.distance) + " " + set.distanceUnit)
    if (set.durationSeconds != null) add(weightText(set.durationSeconds) + " seconds")
}.joinToString(" · ")

fun importedSessionText(detail: ImportedWorkoutDetail, source: HistoryImport?): String = buildString {
    appendLine("FitNotes training context")
    appendLine(detail.workout.date + if (detail.workout.recordedStart == null) " · date-only" else " · recorded timer")
    appendLine("Source: " + (source?.source ?: "Original FitNotes"))
    if (detail.workout.recordedStart != null) appendLine("Timer: " + detail.workout.recordedStart + " to " + detail.workout.recordedEnd)
    detail.sets.sortedBy { it.sourceOrder }.forEachIndexed { index, set ->
        appendLine((index + 1).toString() + ". " + set.exercise + " · " + importedSetText(set))
        if (set.comment.isNotBlank()) appendLine("   Comment: " + set.comment)
    }
    if (detail.workout.notes.isNotBlank()) appendLine("Notes: " + detail.workout.notes)
    appendLine(healthContext(detail.health))
}.trim()

fun bodyContext(body: List<BodyMeasurement>): String = buildString {
    if (body.isNotEmpty()) {
        appendLine("\nBody measurements")
        body.sortedWith(compareBy({ it.date }, { it.time }, { it.id })).forEach {
            appendLine(it.date + (if (it.time.isEmpty()) " · date-only" else " " + it.time) + " · " + it.name + ": " + weightText(it.value) + " " + it.unit)
            if (it.comment.isNotBlank()) appendLine("  Comment: " + it.comment)
        }
    }
}.trimEnd()
