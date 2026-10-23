package com.example.fitnotes.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ActivityType(val key: String, val label: String, val symbol: String) {
    STRENGTH("strength", "Strength training", "◆"), RUNNING("running", "Running", "↗"),
    WALKING("walking", "Walking / hiking", "↟"), CLIMBING("climbing", "Climbing / bouldering", "△"),
    SOCCER("soccer", "Soccer", "◉"), MARTIAL_ARTS("martial_arts", "Martial arts", "✦"), OTHER("other", "Other", "●");
    companion object { fun from(key: String) = entries.firstOrNull { it.key == key } ?: OTHER }
}
fun activityName(w: Workout) = if (w.activityType == "other") w.activityLabel.ifBlank { "Other" } else ActivityType.from(w.activityType).label
fun durationText(seconds: Double?): String = seconds?.let {
    val total = it.toLong(); val h = total / 3600; val m = total % 3600 / 60; val s = total % 60
    if (h > 0) "${h}h ${m}m ${s}s" else "${m}m ${s}s"
} ?: "Duration unknown"
fun timestampText(millis: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss XXX", Locale.getDefault()).format(Date(millis))
fun localDuration(w: Workout): Double? = if (w.durationUnknown) null else w.durationSeconds ?: w.completedAt?.let { end -> w.knownStart?.let { (end - it) / 1000.0 } }

@Entity(tableName = "health_sessions", foreignKeys = [ForeignKey(entity = Workout::class,
    parentColumns = ["id"], childColumns = ["workoutId"], onDelete = ForeignKey.SET_NULL),
    ForeignKey(entity = ImportedWorkout::class, parentColumns = ["id"], childColumns = ["importedWorkoutId"], onDelete = ForeignKey.SET_NULL)], indices = [Index("workoutId"), Index("importedWorkoutId")])
data class HealthSession(
    @PrimaryKey val key: String,
    val workoutId: Long?,
    val recordId: String,
    val originPackage: String,
    val sourceName: String,
    val clientRecordId: String?,
    val clientRecordVersion: Long,
    val modifiedAt: Long,
    val importedAt: Long,
    val start: Long,
    val end: Long,
    val startOffsetSeconds: Int?,
    val endOffsetSeconds: Int?,
    val activityType: String,
    val exerciseType: Int,
    val title: String,
    val notes: String,
    val distanceMeters: Double?,
    val distanceOrigins: String,
    val hrStatus: String,
    val distanceStatus: String,
    val sourceStatus: String = "available",
    val device: String = "",
    val importedWorkoutId: String? = null,
    @ColumnInfo(defaultValue = "0") val sourceCreated: Boolean = false,
    val elevationMeters: Double? = null,
    @ColumnInfo(defaultValue = "''") val elevationOrigins: String = "",
    @ColumnInfo(defaultValue = "''") val elevationStatus: String = "",
    val movingSeconds: Double? = null,
    @ColumnInfo(defaultValue = "''") val movingOrigin: String = "",
    @ColumnInfo(defaultValue = "'health_connect'") val provider: String = HEALTH_CONNECT_PROVIDER,
    @ColumnInfo(defaultValue = "''") val accountId: String = "",
    val averageBpm: Double? = null,
    val maximumBpm: Double? = null,
    @ColumnInfo(defaultValue = "''") val heartOrigin: String = "",
    @ColumnInfo(defaultValue = "''") val sportLabel: String = "",
)
@Entity(tableName = "health_samples", foreignKeys = [ForeignKey(entity = HealthSession::class,
    parentColumns = ["key"], childColumns = ["sessionKey"], onDelete = ForeignKey.CASCADE)], indices = [Index("sessionKey")])
data class HealthSample(@PrimaryKey val key: String, val sessionKey: String, val recordId: String,
    val originPackage: String, val time: Long, val bpm: Long)
data class HealthDetail(@Embedded val session: HealthSession,
    @Relation(parentColumn = "key", entityColumn = "sessionKey") val samples: List<HealthSample>)
@Dao interface HealthDao {
    @Query("SELECT * FROM health_sessions") suspend fun sessions(): List<HealthSession>
    @Query("SELECT * FROM health_samples") suspend fun samples(): List<HealthSample>
    @Query("SELECT * FROM health_sessions WHERE `key` = :key") suspend fun session(key: String): HealthSession?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSession(session: HealthSession)
    @Update suspend fun updateSession(session: HealthSession)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSamples(samples: List<HealthSample>)
    @Query("DELETE FROM health_samples WHERE sessionKey = :key") suspend fun clearSamples(key: String)
    @Query("DELETE FROM health_sessions") suspend fun clear()
}

val ACTIVITY_MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE workouts ADD COLUMN activityType TEXT NOT NULL DEFAULT 'strength'")
        db.execSQL("ALTER TABLE workouts ADD COLUMN activityLabel TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE workouts ADD COLUMN sessionDate TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE workouts ADD COLUMN finished INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE workouts ADD COLUMN durationSeconds REAL")
        db.execSQL("ALTER TABLE workouts ADD COLUMN effort TEXT")
        db.execSQL("ALTER TABLE workouts ADD COLUMN distance REAL")
        db.execSQL("ALTER TABLE workouts ADD COLUMN distanceUnit TEXT")
        db.execSQL("""CREATE TABLE IF NOT EXISTS health_sessions (`key` TEXT NOT NULL PRIMARY KEY, workoutId INTEGER,
            recordId TEXT NOT NULL, originPackage TEXT NOT NULL, sourceName TEXT NOT NULL, clientRecordId TEXT,
            clientRecordVersion INTEGER NOT NULL, modifiedAt INTEGER NOT NULL, importedAt INTEGER NOT NULL,
            start INTEGER NOT NULL, end INTEGER NOT NULL, startOffsetSeconds INTEGER, endOffsetSeconds INTEGER,
            activityType TEXT NOT NULL, exerciseType INTEGER NOT NULL, title TEXT NOT NULL, notes TEXT NOT NULL,
            distanceMeters REAL, distanceOrigins TEXT NOT NULL, hrStatus TEXT NOT NULL, distanceStatus TEXT NOT NULL,
            sourceStatus TEXT NOT NULL, device TEXT NOT NULL, importedWorkoutId TEXT,
            FOREIGN KEY(workoutId) REFERENCES workouts(id) ON UPDATE NO ACTION ON DELETE SET NULL,
            FOREIGN KEY(importedWorkoutId) REFERENCES imported_workouts(id) ON UPDATE NO ACTION ON DELETE SET NULL)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_health_sessions_workoutId ON health_sessions(workoutId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_health_sessions_importedWorkoutId ON health_sessions(importedWorkoutId)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS health_samples (`key` TEXT NOT NULL PRIMARY KEY, sessionKey TEXT NOT NULL,
            recordId TEXT NOT NULL, originPackage TEXT NOT NULL, time INTEGER NOT NULL, bpm INTEGER NOT NULL,
            FOREIGN KEY(sessionKey) REFERENCES health_sessions(`key`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_health_samples_sessionKey ON health_samples(sessionKey)")
    }
}

val ACTIVITY_MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // An earlier installable v4 had local attachments only. Later v4 builds
        // also had importedWorkoutId. Support both without touching existing rows.
        val hasImportedLink = db.query("PRAGMA table_info(health_sessions)").use { cursor ->
            var found = false
            while (cursor.moveToNext()) if (cursor.getString(1) == "importedWorkoutId") found = true
            found
        }
        if (!hasImportedLink) db.execSQL("ALTER TABLE health_sessions ADD COLUMN importedWorkoutId TEXT REFERENCES imported_workouts(id) ON UPDATE NO ACTION ON DELETE SET NULL")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_health_sessions_importedWorkoutId ON health_sessions(importedWorkoutId)")
    }
}

fun heartRateText(detail: HealthDetail): String = buildString {
    val s = detail.samples.sortedBy { it.time }
    if (s.isEmpty()) append("No heart-rate samples found for this session")
    else {
        append("Imported samples: ${s.size} · ${timestampText(s.first().time)} to ${timestampText(s.last().time)}\n")
        append("Sample summary: min ${s.minOf { it.bpm }}, max ${s.maxOf { it.bpm }}, average ${weightText(kotlin.math.round(s.map { it.bpm }.average() * 10) / 10)} bpm\n")
        append("Samples may be sparse; this is not an official Garmin workout statistic or continuous coverage.\n")
        append("Heart-rate sources: " + s.map { it.originPackage }.distinct().joinToString())
    }
    append("\n" + detail.session.hrStatus)
}

fun healthContext(details: List<HealthDetail>): String = details.sortedBy { it.session.key }.joinToString("\n\n") { health ->
    val source = health.session
    buildString {
        appendLine("${sourceProviderName(source)} source: ${source.sourceName} (${source.originPackage})")
        appendLine("Source timing: ${sourceTime(source.start, source.startOffsetSeconds)} to ${sourceTime(source.end, source.endOffsetSeconds)} · interval " + durationText((source.end - source.start) / 1000.0))
        source.distanceMeters?.let { appendLine("Imported distance: " + weightText(it / 1000) + " km · " + source.distanceOrigins) }
        source.elevationMeters?.let { appendLine("Imported elevation gain: " + weightText(it) + " m · " + source.elevationOrigins) }
        source.movingSeconds?.let { appendLine("Source-reported moving time: " + durationText(it) + " · " + source.movingOrigin) }
        appendLine("Source sport: " + sourceSport(source))
        if (source.sportLabel.isNotBlank()) appendLine("Original sport: " + source.sportLabel)
        appendLine("Distance context: " + source.distanceStatus)
        if (source.title.isNotBlank()) appendLine("Source title: " + source.title)
        if (source.notes.isNotBlank()) appendLine("Source notes: " + source.notes)
        if (source.averageBpm != null || source.maximumBpm != null) appendLine("Source-reported heart rate: " +
            listOfNotNull(source.averageBpm?.let { "average ${weightText(it)} bpm" }, source.maximumBpm?.let { "maximum ${weightText(it)} bpm" }).joinToString(", ") + " · ${source.heartOrigin}")
        appendLine(heartRateText(health))
        appendLine("Last import: " + timestampText(source.importedAt) + " · " + source.sourceStatus)
    }.trim()
}

/** Only the explicit marker from the earlier importer identifies ownership. */
val ACTIVITY_SUMMARY_MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE health_sessions ADD COLUMN sourceCreated INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE health_sessions ADD COLUMN elevationMeters REAL")
        db.execSQL("ALTER TABLE health_sessions ADD COLUMN elevationOrigins TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE health_sessions ADD COLUMN elevationStatus TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE health_sessions ADD COLUMN movingSeconds REAL")
        db.execSQL("ALTER TABLE health_sessions ADD COLUMN movingOrigin TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE health_sessions SET sourceCreated = 1 WHERE substr(sourceStatus, 1, ?) = ?", arrayOf<Any>(SOURCE_CREATED_STATUS_PREFIX.length, SOURCE_CREATED_STATUS_PREFIX))
    }
}

const val HEALTH_CONNECT_PROVIDER = "health_connect"
const val STRAVA_EXPORT_PROVIDER = "strava_owner_export"
fun activitySourceKey(provider: String, account: String, origin: String, record: String): String =
    if (provider == HEALTH_CONNECT_PROVIDER) healthKey(origin, record) else "$provider:$account:$record"
fun sourceProviderName(s: HealthSession) = if (s.provider == STRAVA_EXPORT_PROVIDER) "Strava owner export" else "Health Connect"
val ACTIVITY_PROVIDER_MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE health_sessions ADD COLUMN provider TEXT NOT NULL DEFAULT 'health_connect'")
        db.execSQL("ALTER TABLE health_sessions ADD COLUMN accountId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE health_sessions ADD COLUMN averageBpm REAL")
        db.execSQL("ALTER TABLE health_sessions ADD COLUMN maximumBpm REAL")
        db.execSQL("ALTER TABLE health_sessions ADD COLUMN heartOrigin TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE health_sessions ADD COLUMN sportLabel TEXT NOT NULL DEFAULT ''")
    }
}
