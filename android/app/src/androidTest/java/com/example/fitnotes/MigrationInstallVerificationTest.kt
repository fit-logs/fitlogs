package com.example.fitnotes

import androidx.test.platform.app.InstrumentationRegistry
import com.example.fitnotes.data.FitDatabase
import com.example.fitnotes.update.GitHubUpdater
import com.example.fitnotes.update.UPDATE_REPOSITORY
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Runs only against the isolated, explicitly seeded migration emulator. */
class MigrationInstallVerificationTest {
    @Test fun signedUpgradeKeepsLocalWorkoutProfileAndRunningLink() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("migrationFixture") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = FitDatabase.open(context)
        try {
            assertEquals(16, db.openHelper.readableDatabase.version)
            assertEquals("Migration synthetic workout", db.dao().workout(9001)!!.title)
            assertEquals("Keep migration notes", db.dao().workout(9001)!!.notes)
            assertEquals(42.5, db.dao().set(9001)!!.weightKg, 0.0)
            assertEquals(8, db.dao().set(9001)!!.reps)
            assertEquals(180.0, db.bodyProfileDao().get()!!.heightCm!!, 0.0)
            assertTrue(db.runDao().all().any { it.sourceKey == "migration-source" && it.canonicalKey == "local:9002" })
            assertEquals("preserve-migration-files", File(context.filesDir, "migration-sentinel.txt").readText())
            assertEquals(4L, GitHubUpdater(context).installed.code)
            assertEquals("fit-logs/fitlogs", UPDATE_REPOSITORY)
        } finally {
            db.close()
        }
    }

    @Test fun migratedUpdaterFindsThePublishedStableRelease() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("publishedUpdateFixture") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val release = requireNotNull(GitHubUpdater(context).check())
        assertEquals(4L, release.versionCode)
        assertEquals("1.1.1", release.versionName)
        assertTrue(release.listing.page.startsWith("https://github.com/fit-logs/fitlogs/releases/tag/"))
    }
}
