package com.example.fitnotes.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val COACH_MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE training_programs ADD COLUMN flexibleQueue INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE planned_workouts ADD COLUMN activityType TEXT NOT NULL DEFAULT 'strength'")
        db.execSQL("ALTER TABLE planned_workouts ADD COLUMN runningJson TEXT NOT NULL DEFAULT ''")
        // Rebuild only the scheduling table to add the prescription foreign key.
        db.execSQL("CREATE TABLE program_steps_new (id TEXT NOT NULL PRIMARY KEY, programId TEXT NOT NULL, dayIndex INTEGER NOT NULL, action TEXT NOT NULL, workoutId INTEGER, recordedAt INTEGER NOT NULL, queuePosition INTEGER NOT NULL DEFAULT -1, planId TEXT, scheduledDate TEXT NOT NULL DEFAULT '', FOREIGN KEY(programId) REFERENCES training_programs(id) ON DELETE CASCADE, FOREIGN KEY(workoutId) REFERENCES workouts(id) ON DELETE SET NULL, FOREIGN KEY(planId) REFERENCES planned_workouts(id) ON DELETE RESTRICT)")
        db.execSQL("INSERT INTO program_steps_new (id,programId,dayIndex,action,workoutId,recordedAt) SELECT id,programId,dayIndex,action,workoutId,recordedAt FROM program_steps")
        db.execSQL("DROP TABLE program_steps")
        db.execSQL("ALTER TABLE program_steps_new RENAME TO program_steps")
        db.execSQL("CREATE UNIQUE INDEX index_program_steps_programId_dayIndex ON program_steps(programId,dayIndex)")
        db.execSQL("CREATE UNIQUE INDEX index_program_steps_workoutId ON program_steps(workoutId)")
        db.execSQL("CREATE INDEX index_program_steps_planId ON program_steps(planId)")
    }
}
