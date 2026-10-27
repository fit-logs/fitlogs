package com.example.fitnotes

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.fitnotes.ui.AppUpdateContent
import com.example.fitnotes.ui.theme.FitNotesTheme
import com.example.fitnotes.update.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.security.MessageDigest

class AppUpdateInteractionTest {
    @get:Rule val ui = createComposeRule()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun previewOnly() { assumeTrue(context.packageName == "com.example.fitnotes.preview.photos") }
    private fun release(file: File): UpdateRelease {
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val asset = UpdateAsset(UPDATE_APK_NAME, "https://github.com/$UPDATE_REPOSITORY/releases/download/test/$UPDATE_APK_NAME", file.length(), digest)
        return UpdateRelease(3, "1.1", 24, digest, UpdateListing("Synthetic update", "A synthetic update for installer verification.", UPDATE_RELEASES_URL + "/tag/test", asset.copy(name = UPDATE_MANIFEST_NAME), asset))
    }
    private fun capture(name: String) {
        ui.waitForIdle(); val i = InstrumentationRegistry.getInstrumentation(); i.waitForIdleSync(); android.os.SystemClock.sleep(300)
        val image = i.uiAutomation.takeScreenshot()!!
        val suffix = InstrumentationRegistry.getArguments().getString("captureSuffix").orEmpty()
        File(context.filesDir, "$name$suffix.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
    @Test fun permissionAndInstallNeedExplicitTapsAndNoReleaseErrorsRemainReadable() {
        val file = File(context.cacheDir, "updates/candidate.apk")
        assumeTrue("Push the signed preview version-3 fixture first", file.isFile)
        var state by mutableStateOf(UpdateUiState("1.1", 2, UpdatePhase.AVAILABLE, release = release(file)))
        var downloads = 0; var permissions = 0; var installs = 0; var checks = 0
        ui.setContent { FitNotesTheme { AppUpdateContent(state, onClose = {}, onCheck = { checks++ },
            onDownload = { downloads++ }, onInstall = { installs++ }, onAllow = { permissions++ }, onReleases = {}) } }
        ui.onNodeWithText("Download update").assertIsEnabled(); capture("update-available")
        ui.onNodeWithText("Download update").performClick(); assertEquals(1, downloads); assertEquals(0, installs)
        ui.runOnIdle { state = state.copy(phase = UpdatePhase.READY, file = file, mayInstall = false) }
        ui.onNodeWithText("Allow updates").performClick(); assertEquals(1, permissions); assertEquals(0, installs)
        capture("update-permission")
        ui.runOnIdle { state = state.copy(mayInstall = true) }
        ui.onNodeWithText("Install update").performClick(); assertEquals(1, installs)
        ui.runOnIdle { state = state.copy(phase = UpdatePhase.NO_RELEASE, release = null, file = null) }
        ui.onNodeWithText("No stable Android update has been published yet.").assertExists()
        ui.onNodeWithText("Check for updates").performClick(); assertEquals(1, checks)
        ui.runOnIdle { state = state.copy(phase = UpdatePhase.ERROR, error = "Could not reach GitHub securely. Check your connection and try again.") }
        ui.onNodeWithText("Check for updates").assertIsEnabled(); capture("update-offline")
    }
    @Test fun realSignedNewerApkVerifiesAndCorruptionOrOldVersionCannotOpenInstaller() = runBlocking {
        val file = File(context.cacheDir, "updates/candidate.apk")
        assumeTrue("Push the signed preview version-3 fixture first", file.isFile)
        val updater = GitHubUpdater(context); assertEquals(2L, updater.installed.code)
        val release = release(file)
        val intent = updater.installIntent(file, release)
        assertEquals("content", intent.data!!.scheme)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertTrue(intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        try { updater.installIntent(file, release.copy(versionCode = 2)); fail("Expected advertised version mismatch") } catch (_: IllegalArgumentException) { }
        val corrupt = File(file.parentFile, "corrupt.apk"); corrupt.writeBytes(file.readBytes().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() })
        try { updater.installIntent(corrupt, release); fail("Expected checksum rejection") } catch (_: IllegalArgumentException) { } finally { corrupt.delete() }
        // With the preview source permission granted, hand off to the real Android
        // installer and cancel. No app update or training fixture is submitted.
        if (updater.mayInstall()) {
            ui.setContent { FitNotesTheme { androidx.compose.material3.Text("Synthetic updater verification") } }
            ui.runOnIdle { context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            android.os.SystemClock.sleep(1200)
            val i = InstrumentationRegistry.getInstrumentation()
            val image = i.uiAutomation.takeScreenshot()!!
            File(context.filesDir, "update-android-installer.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
            i.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            assertEquals(2L, updater.installed.code)
        }
    }
}
