package com.example.fitnotes

import com.example.fitnotes.update.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppUpdateTest {
    private val hash = "a".repeat(64)
    private fun asset(name: String, size: Long = 1024) = JSONObject().put("name", name).put("size", size).put("state", "uploaded")
        .put("browser_download_url", "https://github.com/$UPDATE_REPOSITORY/releases/download/android-v1.1-2/$name").put("digest", "sha256:$hash")
    private fun listing() = JSONObject().put("draft", false).put("prerelease", false).put("tag_name", "android-v1.1-2").put("name", "FitNotes 1.1")
        .put("body", "Improved workout logging.").put("html_url", "$UPDATE_RELEASES_URL/tag/android-v1.1-2")
        .put("assets", JSONArray().put(asset(UPDATE_APK_NAME)).put(asset(UPDATE_MANIFEST_NAME)))
    private fun manifest() = JSONObject().put("schemaVersion", 1).put("packageName", "com.example.fitnotes").put("versionCode", 2)
        .put("versionName", "1.1").put("minSdk", 24).put("apkName", UPDATE_APK_NAME).put("size", 1024).put("sha256", hash)
    private fun release() = UpdateMetadata.release(manifest().toString(), UpdateMetadata.listing(listing().toString()), "com.example.fitnotes")
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected unsafe update to be rejected") } catch (_: IllegalArgumentException) { } }

    @Test fun stableReleaseParsesAndVersionCodeControlsUpdateAvailability() {
        val release = release(); assertEquals(2L, release.versionCode); assertEquals("1.1", release.versionName)
        assertEquals(24, release.minSdk); assertEquals(hash, release.sha256)
        val installed = UpdatePackage("com.example.fitnotes", 1, setOf("signing-key"))
        validateUpdatePackage(installed, installed.copy(code = 2), release)
        rejects { validateUpdatePackage(installed.copy(code = 2), installed.copy(code = 2), release) }
        rejects { validateUpdatePackage(installed.copy(code = 3), installed.copy(code = 2), release) }
    }
    @Test fun foreignPackageSigningKeyAndMismatchedAdvertisedVersionAreRejected() {
        val installed = UpdatePackage("com.example.fitnotes", 1, setOf("signing-key")); val candidate = installed.copy(code = 2)
        rejects { validateUpdatePackage(installed, candidate.copy(name = "other.app"), release()) }
        rejects { validateUpdatePackage(installed, candidate.copy(certificates = setOf("different-key")), release()) }
        rejects { validateUpdatePackage(installed, candidate.copy(certificates = emptySet()), release()) }
        rejects { validateUpdatePackage(installed, candidate.copy(code = 3), release()) }
    }
    @Test fun incompleteDraftPrereleaseDuplicateAndOversizedAssetsAreRejected() {
        rejects { UpdateMetadata.listing(listing().put("draft", true).toString()) }
        rejects { UpdateMetadata.listing(listing().put("prerelease", true).toString()) }
        rejects { UpdateMetadata.listing(listing().put("assets", JSONArray().put(asset(UPDATE_APK_NAME))).toString()) }
        rejects { UpdateMetadata.listing(listing().put("assets", listing().getJSONArray("assets").put(asset(UPDATE_APK_NAME))).toString()) }
        rejects { UpdateMetadata.listing(listing().put("assets", JSONArray().put(asset(UPDATE_APK_NAME, MAX_UPDATE_BYTES + 1)).put(asset(UPDATE_MANIFEST_NAME))).toString()) }
        val pending = asset(UPDATE_APK_NAME).put("state", "new")
        rejects { UpdateMetadata.listing(listing().put("assets", JSONArray().put(pending).put(asset(UPDATE_MANIFEST_NAME))).toString()) }
    }
    @Test fun releaseManifestMustMatchPackageHashSizeVersionAndSchema() {
        val listing = UpdateMetadata.listing(listing().toString())
        for ((key, value) in listOf("schemaVersion" to 2, "packageName" to "another.app", "versionCode" to -1,
            "versionCode" to 2.5, "minSdk" to 23, "size" to 1025, "sha256" to "b".repeat(64), "apkName" to "other.apk")) {
            rejects { UpdateMetadata.release(manifest().put(key, value).toString(), listing, "com.example.fitnotes") }
        }
    }
    @Test fun releaseLinksCannotSubstituteAnotherHostRepositoryOrProtocol() {
        for (url in listOf("http://github.com/$UPDATE_REPOSITORY/releases/tag/v1", "https://github.com.evil.test/$UPDATE_REPOSITORY/releases/tag/v1",
            "https://github.com/another/repo/releases/tag/v1", "https://github.com@$UPDATE_REPOSITORY/releases/tag/v1",
            "https://github.com/$UPDATE_REPOSITORY/releases/tag/v1?redirect=elsewhere", "https://github.com/$UPDATE_REPOSITORY/releases/tag/../../../outside")) {
            assertFalse(url, UpdateMetadata.safeRepositoryUrl(url, "releases/tag/"))
        }
    }
    @Test fun githubMayOmitItsOptionalDigestButTheManifestChecksumRemainsRequired() {
        val rows = listing().getJSONArray("assets"); rows.getJSONObject(0).remove("digest")
        val listing = UpdateMetadata.listing(listing().put("assets", rows).toString())
        assertNull(listing.apk.digest)
        assertEquals(hash, UpdateMetadata.release(manifest().toString(), listing, "com.example.fitnotes").sha256)
        rejects { UpdateMetadata.release(manifest().put("sha256", "bad").toString(), listing, "com.example.fitnotes") }
    }
}
