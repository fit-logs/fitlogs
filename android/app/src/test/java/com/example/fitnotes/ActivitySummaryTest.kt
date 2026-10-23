package com.example.fitnotes

import android.content.Context
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.fitnotes.data.*
import com.example.fitnotes.ui.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ActivitySummaryTest {
    private val start = Instant.parse("2026-10-22T11:30:00Z").toEpochMilli()
    private fun source(id: String = "run", owner: Boolean = true, origin: String = GARMIN_PACKAGE) = HealthSession(
        healthKey(origin, id), 1, id, origin, "Synthetic Garmin", id, 1, start, start, start, start + 1800000,
        46800, 46800, "running", ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, "Morning run", "Source note", 5000.0, origin, "", "",
        sourceCreated = owner)
    private fun detail(s: HealthSession = source(), w: Workout = Workout(1, 0, sessionDate = "2026-10-22", finished = true, activityType = "running")) =
        WorkoutDetail(w, emptyList(), listOf(HealthDetail(s, emptyList())))
    @Test fun knownSourceTimeUsesOriginalOffsetAndCorrectsMidnightDateWithoutWritingALocalStart() {
        val d = detail(); val summary = activitySummary(d)
        assertEquals("2026-10-23", summary.date); assertEquals("00:30 +13:00 · source", summary.timeLabel)
        assertEquals(start, summary.start); assertEquals("Morning run", summary.title)
        assertEquals("6:00 min/km elapsed pace", summary.pace)
        assertNull(d.workout.knownStart); assertEquals("2026-10-22", d.workout.sessionDate)
        val session = notebookSessions(AppData(workouts = listOf(d))).single()
        assertFalse(session.dateOnly); assertEquals(summary.start, session.startedMillis); assertEquals(summary.date, session.date)
    }
    @Test fun activitiesSortByKnownSourceTimesAndSourceCorrectionsMoveOnlyOwnedSessions() {
        val late = detail(source("late").copy(workoutId = 2, start = start + 3600000, end = start + 5400000), detail().workout.copy(id = 2))
        val data = AppData(workouts = listOf(detail(), late))
        assertEquals(listOf("local:2", "local:1"), notebookSessions(data).map { it.key })
        val correction = source().copy(start = start - 86400000, end = start - 84600000)
        assertEquals("2026-10-22", activitySummary(detail(correction)).date)
        val attached = activitySummary(detail(correction.copy(sourceCreated = false)))
        assertNull(attached.start); assertEquals("2026-10-22", attached.date); assertEquals("Date-only", attached.timeLabel)
    }
    @Test fun explicitLocalTitleDistanceNotesTimingAndCompletionTakePrecedence() {
        val local = Workout(1, start - 3600000, start - 1800000, "My run", "My notes", activityType = "running", distance = 3.0, distanceUnit = "mi")
        val summary = activitySummary(detail(w = local))
        assertEquals("My run", summary.title); assertEquals(local.startedAt, summary.start); assertFalse(summary.sourceTiming)
        assertEquals(4828.032, summary.distanceMeters!!, 0.0001); assertEquals("3 mi", summary.distanceLabel)
        assertEquals(local, detail(w = local).workout)
        val strength = local.copy(activityType = "strength", title = "My gym")
        assertEquals("Strength training", activitySummary(detail(source().copy(sourceCreated = false), strength)).sport)
    }
    @Test fun attachmentsAndAmbiguousLegacyEmptyWorkoutsStayLocallyDateOnly() {
        val d = detail(source(owner = false)); val summary = activitySummary(d)
        assertNull(summary.start); assertNull(summary.end); assertEquals("Date-only", summary.timeLabel)
        assertEquals("2026-10-22", summary.date); assertEquals(1800.0, summary.seconds!!, 0.0)
        assertTrue(sessionText(listOf(d)).contains("Date-only"))
        assertTrue(sessionText(listOf(d)).contains("Source timing: 2026-10-23 00:30 +13:00"))
    }
    @Test fun preferredSourceNeverDependsOnRelationOrderOrAddsProviderTotals() {
        val garmin = HealthDetail(source(owner = false), emptyList())
        val other = HealthDetail(source("duplicate", false, "org.synthetic.other").copy(distanceMeters = 9000.0), emptyList())
        val d = detail().copy(health = listOf(other, garmin))
        assertEquals(activitySummary(d), activitySummary(d.copy(health = d.health.reversed())))
        assertEquals(sessionText(listOf(d)), sessionText(listOf(d.copy(health = d.health.reversed()))))
        assertEquals(5000.0, activitySummary(d).distanceMeters!!, 0.0)
        val owner = other.copy(session = other.session.copy(sourceCreated = true))
        assertEquals(owner, preferredActivitySource(listOf(garmin, owner)))
    }
    @Test fun sportLabelsUseRawSourceTypeAndPaceRequiresValidDistanceAndInterval() {
        for ((type, label) in listOf(ExerciseSessionRecord.EXERCISE_TYPE_WALKING to "Walking", ExerciseSessionRecord.EXERCISE_TYPE_HIKING to "Hiking", ExerciseSessionRecord.EXERCISE_TYPE_BIKING to "Cycling")) {
            val key = activityType(type)
            val d = detail(source().copy(exerciseType = type, activityType = key), detail().workout.copy(activityType = key, activityLabel = if (key == "other") "Provider title" else ""))
            assertEquals(label, activitySummary(d).sport)
        }
        assertNull(elapsedPace(1800.0, null, "Running")); assertNull(elapsedPace(1800.0, 0.0, "Running"))
        assertNull(elapsedPace(1800.0, 5000.0, "Cycling")); assertNull(elapsedPace(Double.NaN, 5000.0, "Running"))
    }
    @Test fun rangeSharingIncludesOneActivityAndBackupAnalysisRetainOptionalMetricOrigins() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, FitDatabase::class.java).build()
        try {
            val s = source().copy(elevationMeters = 123.5, elevationOrigins = GARMIN_PACKAGE, movingSeconds = 1500.0, movingOrigin = GARMIN_PACKAGE)
            val backup = Backup(exercises = emptyList(), workouts = listOf(detail().workout), entries = emptyList(), sets = emptyList(), healthSessions = listOf(s))
            assertEquals(backup, BackupCodec.decode(BackupCodec.encode(backup)))
            WorkoutRepository(db).restore(backup)
            val actual = WorkoutRepository(db).backup(); assertEquals(listOf(s), actual.healthSessions)
            val analysis = JSONObject(BackupCodec.analysis(actual)); assertEquals(9, analysis.getInt("backupSchemaVersion"))
            assertEquals(123.5, analysis.getJSONArray("healthSessions").getJSONObject(0).getDouble("elevationMeters"), 0.0)
            val text = rangeContext(AppData(workouts = listOf(detail(s))), "2026-10-23", "2026-10-23")!!
            assertEquals(1, Regex("FitNotes training context").findAll(text).count()); assertTrue(text.contains("6:00 min/km elapsed pace"))
            assertTrue(text.contains("Elevation gain: 123.5 m")); assertTrue(text.contains("Source-reported moving time: 25m 0s"))
            try { BackupCodec.encode(backup.copy(version = 7)); fail() } catch (_: IllegalArgumentException) { }
            val old = backup.copy(version = 7, healthSessions = listOf(source(owner = false)))
            assertEquals(old, BackupCodec.decode(BackupCodec.encode(old)))
        } finally { db.close() }
    }
    @Test fun versionNineMigrationUsesExplicitProvenanceOnlyAndPreservesLegacyRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val name = "synthetic-summary-migration.db"
        context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/com.example.fitnotes.data.FitDatabase/9.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("$" + "{TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                for (j in 0 until (indices?.length() ?: 0)) old.execSQL(indices!!.getJSONObject(j).getString("createSql").replace("$" + "{TABLE_NAME}", table))
            }
            old.execSQL("INSERT INTO workouts(id, startedAt, title, notes) VALUES(1,0,'Keep title','Keep notes')")
            for (s in listOf(source(owner = false), source("explicit", false).copy(sourceStatus = SOURCE_CREATED_STATUS_PREFIX + "Imported"))) {
                val json = HealthBackupJson.encode(s, 7); val keys = json.keys().asSequence().toList()
                old.execSQL("INSERT INTO health_sessions(" + keys.joinToString(",") { "`$it`" } + ") VALUES(" + keys.joinToString(",") { "?" } + ")", keys.map { if (json.isNull(it)) null else json.get(it) }.toTypedArray())
            }
            old.version = 9
        }
        val db = Room.databaseBuilder(context, FitDatabase::class.java, name).addMigrations(ACTIVITY_SUMMARY_MIGRATION_9_10, ACTIVITY_PROVIDER_MIGRATION_10_11).build()
        try {
            assertEquals("Keep title", db.dao().workout(1)!!.title)
            assertFalse(db.healthDao().session(source().key)!!.isSourceCreated)
            assertTrue(db.healthDao().session(source("explicit").key)!!.sourceCreated)
            assertTrue(db.healthDao().sessions().all { it.elevationMeters == null && it.movingSeconds == null })
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
