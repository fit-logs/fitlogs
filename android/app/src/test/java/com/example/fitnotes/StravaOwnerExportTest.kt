package com.example.fitnotes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.fitnotes.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
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
class StravaOwnerExportTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: FitDatabase
    private val lock = Mutex()
    private val headers = "Activity ID,Activity Date,Activity Name,Activity Type,Activity Description,Elapsed Time,Distance,Moving Time,Elevation Gain,Average Heart Rate,Max Heart Rate"
    private val options = StravaExportOptions("synthetic-owner", elapsedColumn = 5, distanceColumn = 6, movingColumn = 7,
        elevationColumn = 8, averageHrColumn = 9, maximumHrColumn = 10, timeUnit = "seconds", distanceUnit = "km", elevationUnit = "m")
    private fun csv(id: Int = 1, date: String = "2026-10-23T00:30:00+13:00", title: String = "Synthetic run", type: String = "Run", elapsed: String = "1800", moving: String = "1500", distance: String = "5", elevation: String = "123.5", avg: String = "125", max: String = "150") =
        "$id,\"$date\",\"$title\",$type,\"Synthetic note\",$elapsed,$distance,$moving,$elevation,$avg,$max"
    private fun preview(text: String = headers + "\n" + csv(), opts: StravaExportOptions = options) = StravaOwnerExport.preview(text, opts)
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(context, FitDatabase::class.java).build() }
    @After fun close() { db.close() }
    @Test fun sourceSummariesKeepUnitsOffsetsMovingAndElevationWithoutCreatingSamplesOrStrengthSets() = runBlocking {
        val p = preview(); assertTrue(p.issues.isEmpty())
        val s = p.candidates.single().session
        assertEquals(STRAVA_EXPORT_PROVIDER, s.provider); assertEquals("strava_owner_export:synthetic-owner:1", s.key)
        assertEquals(46800, s.startOffsetSeconds); assertEquals(5000.0, s.distanceMeters!!, 0.0)
        assertEquals(1500.0, s.movingSeconds!!, 0.0); assertEquals(123.5, s.elevationMeters!!, 0.0)
        assertEquals(125.0, s.averageBpm!!, 0.0); assertEquals("Run", s.sportLabel)
        assertEquals(1, StravaExportImporter(db, lock).apply(p.candidates.map { StravaImportChoice(it) }))
        assertEquals(1, db.dao().workouts().size); assertTrue(db.dao().sets().isEmpty()); assertTrue(db.healthDao().samples().isEmpty())
        assertEquals(s.provider, db.healthDao().sessions().single().provider)
    }
    @Test fun noOffsetNeedsConfirmedTimezoneAndExplicitOffsetsAlwaysWin() {
        val text = headers + "\n" + csv(date = "Oct 23, 2026, 12:30:00 AM")
        val unconfirmed = preview(text); assertTrue(unconfirmed.candidates.isEmpty()); assertTrue(unconfirmed.issues.single().contains("Confirm its timezone"))
        assertEquals(46800, preview(text, options.copy(timezone = "Pacific/Auckland")).candidates.single().session.startOffsetSeconds)
        val explicit = preview(opts = options.copy(timezone = "UTC")).candidates.single().session
        assertEquals(46800, explicit.startOffsetSeconds); assertEquals(46800, explicit.endOffsetSeconds)
        assertEquals(Instant.parse("2026-10-22T11:30:00Z").toEpochMilli(), explicit.start)
        assertTrue(preview(headers + "\n" + csv(date = "2026-09-27T02:30:00"), options.copy(timezone = "Pacific/Auckland")).candidates.isEmpty())
        assertTrue(preview(headers + "\n" + csv(date = "2026-04-05T02:30:00"), options.copy(timezone = "Pacific/Auckland")).candidates.isEmpty())
    }
    @Test fun repeatedMetricColumnsRequireExplicitChoiceAndUnitsAreNeverGuessed() {
        val text = headers + ",Distance\n" + csv() + ",5000"
        assertNull(StravaOwnerExport.uniqueColumn(StravaOwnerExport.headers(text), "Distance"))
        val meters = preview(text, options.copy(distanceColumn = 11, distanceUnit = "m")).candidates.single().session
        assertEquals(5000.0, meters.distanceMeters!!, 0.0)
        try { preview(opts = options.copy(distanceUnit = "")); fail() } catch (_: IllegalArgumentException) { }
        try { preview(opts = options.copy(timeUnit = "")); fail() } catch (_: IllegalArgumentException) { }
        try { preview(opts = options.copy(owner = "owner:collision")); fail() } catch (_: IllegalArgumentException) { }
        assertNull(preview(opts = options.copy(distanceColumn = null)).candidates.single().session.distanceMeters)
    }
    @Test fun rowErrorsConflictsMissingMetricsAndDateFiltersAreExplicit() {
        val conflicting = headers + "\n" + csv() + "\n" + csv(title = "Conflicting title") + "\n" + csv(2, moving = "9999") + "\n" + csv(3, avg = "999")
        val p = preview(conflicting); assertTrue(p.candidates.isEmpty()); assertEquals(3, p.issues.size)
        val absent = preview(headers + "\n" + csv(moving = "", distance = "", elevation = "", avg = "", max = "")).candidates.single().session
        assertNull(absent.movingSeconds); assertNull(absent.distanceMeters); assertNull(absent.elevationMeters); assertNull(absent.averageBpm)
        assertEquals(0.0, preview(headers + "\n" + csv(moving = "0")).candidates.single().session.movingSeconds!!, 0.0)
        assertTrue(preview(opts = options.copy(lastDate = "2026-10-22")).candidates.isEmpty())
    }
    @Test fun distinctSameDaySessionsAreNotMergedAndReloadingTheSameFileOnlyRefreshesStableIds() = runBlocking {
        val p = preview(headers + "\n" + csv() + "\n" + csv(2, date = "2026-10-23T02:30:00+13:00", title = "Synthetic hike", type = "Hike"))
        val importer = StravaExportImporter(db, lock)
        repeat(2) { importer.apply(p.candidates.map { StravaImportChoice(it) }) }
        assertEquals(2, db.dao().workouts().size); assertEquals(2, db.healthDao().sessions().size)
        assertTrue(db.dao().workouts().all { it.isComplete }); assertNull(db.dao().active())
        val changed = preview(headers + "\n" + csv(distance = "5.1")).candidates.single()
        importer.apply(listOf(StravaImportChoice(changed))); assertEquals(2, db.dao().workouts().size)
        assertEquals(5100.0, db.healthDao().session(changed.session.key)!!.distanceMeters!!, 0.0)
        val otherAccount = changed.copy(session = changed.session.copy(key = activitySourceKey(STRAVA_EXPORT_PROVIDER, "other-owner", changed.session.originPackage, "1"), accountId = "other-owner"))
        importer.apply(listOf(StravaImportChoice(otherAccount, keepSeparate = true))); assertEquals(3, db.healthDao().sessions().size)
    }
    @Test fun unselectedOptionalColumnsRetainSavedSnapshotsButSelectedBlankFieldsBecomeUnknown() = runBlocking {
        val importer = StravaExportImporter(db, lock)
        importer.apply(preview().candidates.map { StravaImportChoice(it) })
        val before = db.healthDao().sessions().single()
        val unselected = options.copy(distanceColumn = null, movingColumn = null, elevationColumn = null, averageHrColumn = null, maximumHrColumn = null)
        importer.apply(preview(opts = unselected).candidates.map { StravaImportChoice(it) })
        val kept = db.healthDao().sessions().single()
        assertEquals(before.distanceMeters, kept.distanceMeters); assertEquals(before.movingSeconds, kept.movingSeconds)
        assertEquals(before.elevationMeters, kept.elevationMeters); assertEquals(before.averageBpm, kept.averageBpm)
        importer.apply(preview(headers + "\n" + csv(moving = "", distance = "", elevation = "", avg = "", max = "")).candidates.map { StravaImportChoice(it) })
        val unknown = db.healthDao().sessions().single()
        assertNull(unknown.distanceMeters); assertNull(unknown.movingSeconds); assertNull(unknown.elevationMeters); assertNull(unknown.averageBpm)
    }
    @Test fun garminDuplicateRequiresReviewThenCanAttachWithoutAddingHistoryOrChangingLocalFields() = runBlocking {
        val exported = preview().candidates.single()
        val garmin = exported.copy(session = exported.session.copy(key = healthKey(GARMIN_PACKAGE, "garmin-one"), originPackage = GARMIN_PACKAGE,
            recordId = "garmin-one", provider = HEALTH_CONNECT_PROVIDER, accountId = "", sportLabel = "", averageBpm = null, maximumBpm = null, heartOrigin = ""))
        HealthImportRepository(db).import(garmin)
        val original = db.dao().workouts().single()
        assertEquals(original.id, likelySourceMatches(exported.session, db.dao().workouts(), db.healthDao().sessions()).single().workoutId)
        try { StravaExportImporter(db, lock).apply(listOf(StravaImportChoice(exported))); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(1, db.dao().workouts().size)
        StravaExportImporter(db, lock).apply(listOf(StravaImportChoice(exported, attachTo = original.id)))
        assertEquals(listOf(original), db.dao().workouts()); assertEquals(2, db.healthDao().sessions().size)
        assertEquals(original.id, db.healthDao().session(exported.session.key)!!.workoutId)
    }
    @Test fun explicitAttachmentPreservesGymSetsNotesTimesAndOriginalHistoryCanAlsoAttach() = runBlocking {
        db.dao().insertExercise(Exercise(1, "Synthetic press", "synthetic press"))
        val repo = WorkoutRepository(db); val gym = repo.start(); repo.addExercise(gym, 1)
        repo.saveSet(db.dao().workoutEntries(gym).single().id, "40", "8"); repo.saveDetails(gym, "My gym", "Keep local notes")
        val before = repo.backup(); val c = preview().candidates.single()
        StravaExportImporter(db, lock).apply(listOf(StravaImportChoice(c, attachTo = gym)))
        val after = repo.backup(); assertEquals(before.workouts, after.workouts); assertEquals(before.sets, after.sets); assertEquals(before.entries, after.entries)
        assertEquals(gym, db.dao().active()!!.id)
        val base = Backup(exercises = emptyList(), workouts = emptyList(), entries = emptyList(), sets = emptyList(),
            imports = listOf(HistoryImport("original", "Synthetic", "fixture", 1000, "kg", 0, "", "{}")), importedWorkouts = listOf(ImportedWorkout("dated", "original", "2026-10-23", notes = "Keep original notes")))
        repo.restore(base)
        StravaExportImporter(db, lock).apply(listOf(StravaImportChoice(c, attachImported = "dated")))
        assertTrue(db.dao().workouts().isEmpty()); assertEquals("dated", db.healthDao().sessions().single().importedWorkoutId)
        assertEquals(base.importedWorkouts, db.importedDao().workouts())
    }
    @Test fun partialBatchesRetryAndDeletedOrIgnoredSourceIdentitiesSurviveRestore() = runBlocking {
        val rows = (1..21).joinToString("\n") { id -> csv(id, date = Instant.parse("2026-10-01T00:00:00Z").plusSeconds(id * 7200L).toString()) }
        val candidates = preview(headers + "\n" + rows).candidates
        val importer = StravaExportImporter(db, lock)
        val job = launch { importer.apply(candidates.map { StravaImportChoice(it) }) { if (it == 20) cancel() } }; job.join()
        assertEquals(20, db.dao().workouts().size)
        importer.apply(candidates.map { StravaImportChoice(it) }); assertEquals(21, db.dao().workouts().size)
        val removed = db.dao().workouts().first(); WorkoutRepository(db).deleteWorkout(removed.id)
        val ignored = preview(headers + "\n" + csv(999, date = "2026-10-01T20:00:00Z")).candidates.single()
        HealthImportRepository(db).ignore(ignored)
        val backup = WorkoutRepository(db).backup(); assertEquals(backup, BackupCodec.decode(BackupCodec.encode(backup)))
        WorkoutRepository(db).restore(backup)
        importer.apply((candidates + ignored).map { StravaImportChoice(it) }); assertEquals(20, db.dao().workouts().size)
        assertEquals(STRAVA_EXPORT_PROVIDER, db.healthDao().session(ignored.session.key)!!.provider)
        val analysis = JSONObject(BackupCodec.analysis(backup)); assertEquals(10, analysis.getInt("backupSchemaVersion"))
        try { BackupCodec.encode(backup.copy(version = 8)); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun matchSuggestionsNeverUseDateOrTitleAloneAndDistanceMismatchPreventsSuggestion() {
        val s = preview().candidates.single().session
        val unknown = Workout(1, 0, title = s.title, sessionDate = healthSessionDate(s), finished = true, activityType = "running")
        assertTrue(likelySourceMatches(s, listOf(unknown), emptyList()).isEmpty())
        val timed = unknown.copy(startedAt = s.start, completedAt = s.end, distance = 20.0, distanceUnit = "km")
        assertTrue(likelySourceMatches(s, listOf(timed), emptyList()).isEmpty())
    }
    @Test fun healthConnectDeletionAndMetricFeedsCannotModifyOwnerExportSnapshots() = runBlocking {
        val candidate = preview().candidates.single()
        StravaExportImporter(db, lock).apply(listOf(StravaImportChoice(candidate)))
        val before = db.healthDao().sessions().single()
        val source = object : HealthSyncSource {
            override suspend fun access() = HealthAccess(true, true, false)
            override suspend fun token(feed: HealthFeed) = feed.name
            override suspend fun sessions(start: Instant, end: Instant) = emptyList<HealthCandidate>()
            override suspend fun changes(feed: HealthFeed, token: String) = if (feed == HealthFeed.EXERCISE) HealthDeltaPage(deletedIds = setOf("1"), next = token) else HealthDeltaPage(metricsChanged = true, next = token)
            override suspend fun enrich(candidate: HealthCandidate): HealthCandidate = error("Owner-export snapshots must not be re-read through Health Connect")
        }
        assertTrue(HealthSyncCoordinator(db, MemoryHealthSyncStore(), source, lock).sync(HealthSyncReason.MANUAL))
        assertEquals(before, db.healthDao().sessions().single())
    }
    @Test fun sourceIntervalCanSuggestADateOnlyAttachmentAndLargeHistoryDoesNotChangeTheMatch() {
        val candidate = preview().candidates.single().session
        val dated = Workout(42, 0, title = "Keep local date-only", sessionDate = "2026-10-23", finished = true, activityType = "running")
        val garmin = candidate.copy(key = healthKey(GARMIN_PACKAGE, "shared"), recordId = "shared", originPackage = GARMIN_PACKAGE,
            provider = HEALTH_CONNECT_PROVIDER, accountId = "", workoutId = 42, sourceCreated = false)
        val unrelated = (100L..5100L).map { id -> Workout(id, candidate.start + id * 3600000, candidate.start + id * 3600000 + 1800000, activityType = "running") }
        val matches = SourceMatchIndex(unrelated + dated, listOf(garmin)).matches(candidate)
        assertEquals(listOf(42L), matches.map { it.workoutId }); assertNull(dated.knownStart)
        assertEquals("2026-10-23", dated.sessionDate)
    }
    @Test fun versionTenMigrationPreservesHealthConnectRecordsAndSetsProviderDefaults() = runBlocking {
        val name = "synthetic-provider-migration.db"; context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/com.example.fitnotes.data.FitDatabase/10.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("$" + "{TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                for (j in 0 until (indices?.length() ?: 0)) old.execSQL(indices!!.getJSONObject(j).getString("createSql").replace("$" + "{TABLE_NAME}", table))
            }
            old.execSQL("INSERT INTO workouts(id, startedAt, title, notes) VALUES(1,1000,'Keep title','Keep note')")
            val source = HealthSession(healthKey(GARMIN_PACKAGE, "record"), 1, "record", GARMIN_PACKAGE, "Synthetic", null, 0, 0, 1000, 1000, 2000, null, null, "strength", 70, "", "", null, "", "", "")
            val json = HealthBackupJson.encode(source, 8); val keys = json.keys().asSequence().toList()
            old.execSQL("INSERT INTO health_sessions(" + keys.joinToString(",") { "`$it`" } + ") VALUES(" + keys.joinToString(",") { "?" } + ")", keys.map { if (json.isNull(it)) null else json.get(it) }.toTypedArray())
            old.version = 10
        }
        val migrated = Room.databaseBuilder(context, FitDatabase::class.java, name).addMigrations(ACTIVITY_PROVIDER_MIGRATION_10_11, WORKOUT_LIFECYCLE_MIGRATION_11_12).build()
        try { assertEquals("Keep note", migrated.dao().workout(1)!!.notes); assertEquals(HEALTH_CONNECT_PROVIDER, migrated.healthDao().sessions().single().provider) }
        finally { migrated.close(); context.deleteDatabase(name) }
    }
}
