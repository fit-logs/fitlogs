package com.example.fitnotes.update

import org.json.JSONObject
import java.net.URI

const val UPDATE_REPOSITORY = "fit-logs/fitlogs"
const val UPDATE_RELEASES_URL = "https://github.com/$UPDATE_REPOSITORY/releases"
const val UPDATE_API_URL = "https://api.github.com/repos/$UPDATE_REPOSITORY/releases/latest"
const val UPDATE_APK_NAME = "fitnotes.apk"
const val UPDATE_MANIFEST_NAME = "fitnotes-update.json"
const val MAX_UPDATE_BYTES = 100L * 1024 * 1024

data class UpdateAsset(val name: String, val url: String, val bytes: Long, val digest: String?)
data class UpdateListing(val title: String, val notes: String, val page: String, val manifest: UpdateAsset, val apk: UpdateAsset)
data class UpdateRelease(val versionCode: Long, val versionName: String, val minSdk: Int, val sha256: String,
    val listing: UpdateListing)

object UpdateMetadata {
    private val hash = Regex("[0-9a-f]{64}")
    private fun integer(json: JSONObject, key: String, max: Long): Long {
        val value = json.get(key)
        require(value is Int || value is Long) { "The update field $key must be an integer." }
        return (value as Number).toLong().also { require(it in 1..max) { "The update field $key is out of range." } }
    }
    fun listing(raw: String): UpdateListing {
        val json = JSONObject(raw)
        require(!json.getBoolean("draft") && !json.getBoolean("prerelease")) { "Only published stable updates are supported." }
        val page = json.getString("html_url")
        require(safeRepositoryUrl(page, "releases/tag/")) { "The release page is outside the update repository." }
        val rows = json.getJSONArray("assets")
        fun asset(name: String, max: Long): UpdateAsset {
            val matches = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.optString("name") == name }
            require(matches.size == 1) { "This release has no unique $name asset. Try again after it finishes publishing." }
            val item = matches.single()
            val url = item.getString("browser_download_url")
            require(safeRepositoryUrl(url, "releases/download/") && URI(url).path.endsWith("/$name")) { "The update download is outside the update repository." }
            val size = integer(item, "size", max)
            require(item.optString("state") == "uploaded") { "The update is still being uploaded. Try again shortly." }
            val digest = item.optString("digest").takeIf { it.isNotEmpty() && it != "null" }
            require(digest == null || (digest.startsWith("sha256:") && hash.matches(digest.removePrefix("sha256:")))) { "The release checksum is invalid." }
            return UpdateAsset(name, url, size, digest?.removePrefix("sha256:"))
        }
        return UpdateListing(json.optString("name").ifBlank { json.getString("tag_name") }.take(200),
            json.optString("body").take(8000), page, asset(UPDATE_MANIFEST_NAME, 32768), asset(UPDATE_APK_NAME, MAX_UPDATE_BYTES))
    }
    fun release(raw: String, listing: UpdateListing, packageName: String): UpdateRelease {
        val json = JSONObject(raw)
        require(integer(json, "schemaVersion", 100) == 1L) { "This update format requires a newer updater." }
        require(json.getString("packageName") == packageName) { "This release is for a different app." }
        val code = integer(json, "versionCode", 2100000000L)
        val name = json.getString("versionName")
        require(name.isNotBlank() && name.length <= 80) { "The update version name is invalid." }
        val sdk = integer(json, "minSdk", 100).toInt()
        require(sdk >= 24) { "The update Android requirement is invalid." }
        require(json.getString("apkName") == UPDATE_APK_NAME && integer(json, "size", MAX_UPDATE_BYTES) == listing.apk.bytes) { "The update file does not match its manifest." }
        val digest = json.getString("sha256")
        require(hash.matches(digest) && (listing.apk.digest == null || digest == listing.apk.digest)) { "The update checksums do not match." }
        return UpdateRelease(code, name, sdk, digest, listing)
    }
    fun safeRepositoryUrl(value: String, pathPrefix: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null &&
            uri.port == -1 && uri.query == null && uri.fragment == null &&
            uri.normalize().rawPath.startsWith("/$UPDATE_REPOSITORY/$pathPrefix")
    }.getOrDefault(false)
}

data class UpdatePackage(val name: String, val code: Long, val certificates: Set<String>)
fun validateUpdatePackage(installed: UpdatePackage, candidate: UpdatePackage, release: UpdateRelease) {
    require(candidate.name == installed.name) { "The downloaded APK is for a different app." }
    require(candidate.code == release.versionCode && candidate.code > installed.code) { "The APK is not the advertised newer version." }
    require(installed.certificates.isNotEmpty() && candidate.certificates == installed.certificates) {
        "The APK uses a different signing key. The installed app and its data were left unchanged."
    }
}
