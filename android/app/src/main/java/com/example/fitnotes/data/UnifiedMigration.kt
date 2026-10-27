package com.example.fitnotes.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Released and local builds assigned different changes to versions 10–12.
 * Inspect actual schema features, then converge without replacing user rows. */
val UNIFIED_MIGRATIONS_TO_16: Array<Migration> = (9..15).map { from ->
    object : Migration(from, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            fun column(table: String, name: String): Boolean = db.query("PRAGMA table_info(`$table`)").use { rows ->
                val index = rows.getColumnIndexOrThrow("name")
                var found = false
                while (rows.moveToNext()) if (rows.getString(index) == name) found = true
                found
            }
            fun table(name: String): Boolean = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)).use { it.moveToFirst() }
            if (!column("health_sessions", "sourceCreated")) ACTIVITY_SUMMARY_MIGRATION_9_10.migrate(db)
            if (!column("health_sessions", "provider")) ACTIVITY_PROVIDER_MIGRATION_10_11.migrate(db)
            if (!column("workouts", "lastActionAt")) WORKOUT_LIFECYCLE_MIGRATION_11_12.migrate(db)
            if (!table("training_programs")) PROGRAM_MIGRATION_12_13.migrate(db)
            if (!column("training_programs", "definitionId")) PROGRAM_IMPORT_MIGRATION_13_14.migrate(db)
            if (!table("load_profiles")) LOAD_ADVICE_MIGRATION_14_15.migrate(db)
            if (!column("body_measurements", "createdAt")) BODY_PROFILE_MIGRATION_10_11.migrate(db)
            if (!column("health_sessions", "runData")) RUNNING_MIGRATION_11_12.migrate(db)
            for ((table, owner) in listOf("workout_exercises" to "workoutId", "planned_exercises" to "planId")) {
                val index = "index_${table}_${owner}_exerciseId"
                db.execSQL("DROP INDEX IF EXISTS `$index`")
                db.execSQL("CREATE INDEX `$index` ON `$table`(`$owner`,exerciseId)")
            }
        }
    }
}.toTypedArray()
