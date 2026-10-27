package com.example.fitnotes.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class InstalledUpdateVersion(val name: String, val code: Long)
class GitHubUpdater(private val context: Context) {
    val installed: InstalledUpdateVersion get() = installedInfo().let { InstalledUpdateVersion(it.versionName ?: "", PackageInfoCompat.getLongVersionCode(it)) }
    private val directory get() = File(context.cacheDir, "updates").apply { mkdirs() }
    suspend fun check(): UpdateRelease? = withContext(Dispatchers.IO) {
        val raw = fetch(UPDATE_API_URL, 512 * 1024, noReleaseAllowed = true) ?: return@withContext null
        val listing = UpdateMetadata.listing(raw.toString(Charsets.UTF_8))
        val manifest = fetch(listing.manifest.url, 32768)!!
        require(manifest.size.toLong() == listing.manifest.bytes) { "The update manifest download is incomplete." }
        listing.manifest.digest?.let { require(sha256(manifest) == it) { "The update manifest checksum does not match." } }
        UpdateMetadata.release(manifest.toString(Charsets.UTF_8), listing, context.packageName)
    }
    suspend fun download(release: UpdateRelease, progress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        require(Build.VERSION.SDK_INT >= release.minSdk) { "This update needs Android API ${release.minSdk} or newer." }
        directory.listFiles()?.forEach { it.delete() }
        val partial = File(directory, "download.part")
        val target = File(directory, "fitnotes-${release.versionCode}.apk")
        try {
            connection(release.listing.apk.url).let { connection ->
                try {
                    require(connection.responseCode == HttpURLConnection.HTTP_OK) { "GitHub could not download the update. Try again later." }
                    val length = connection.contentLengthLong
                    require(length <= 0 || length == release.listing.apk.bytes) { "The update size changed. Check for updates again." }
                    connection.inputStream.use { input -> partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024); var bytes = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer); if (read < 0) break
                            bytes += read
                            require(bytes <= release.listing.apk.bytes && bytes <= MAX_UPDATE_BYTES) { "The update exceeds its advertised size." }
                            output.write(buffer, 0, read); progress(bytes, release.listing.apk.bytes)
                        }
                        require(bytes == release.listing.apk.bytes) { "The update download was interrupted. Download it again." }
                    } }
                } finally { connection.disconnect() }
            }
            verify(partial, release)
            check(partial.renameTo(target)) { "Could not save the downloaded update." }
            target
        } catch (error: Exception) { partial.delete(); target.delete(); throw error }
    }
    suspend fun installIntent(file: File, release: UpdateRelease): Intent = withContext(Dispatchers.IO) {
        verify(file, release)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.handoff-files", file)
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = android.content.ClipData.newRawUri("FitNotes update", uri) }
    }
    fun mayInstall(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()
    private fun verify(file: File, release: UpdateRelease) {
        require(file.isFile && file.length() == release.listing.apk.bytes) { "The downloaded update is missing or incomplete. Download it again." }
        require(file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            hex(digest.digest()) == release.sha256
        }) { "The update checksum does not match. Download it again." }
        val candidate = context.packageManager.getPackageArchiveInfo(file.absolutePath, signatureFlags())
        requireNotNull(candidate) { "The downloaded file is not a readable Android APK." }
        validateUpdatePackage(packageDetails(installedInfo()), packageDetails(candidate), release)
        require(candidate.applicationInfo!!.minSdkVersion <= Build.VERSION.SDK_INT) { "This APK needs a newer Android version." }
    }
    @Suppress("DEPRECATION")
    private fun signatureFlags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    private fun installedInfo() = context.packageManager.getPackageInfo(context.packageName, signatureFlags())
    @Suppress("DEPRECATION")
    private fun packageDetails(info: PackageInfo): UpdatePackage {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return UpdatePackage(info.packageName, PackageInfoCompat.getLongVersionCode(info), signatures.orEmpty().map { sha256(it.toByteArray()) }.toSet())
    }
    private suspend fun fetch(url: String, max: Int, noReleaseAllowed: Boolean = false): ByteArray? {
        val connection = connection(url)
        try {
            val status = connection.responseCode
            if (status == 404 && noReleaseAllowed) return null
            if (status == 403 || status == 429) throw IllegalStateException("GitHub is limiting update checks. Try again later.")
            require(status == 200) { "GitHub could not check updates. Try again later." }
            return connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val size = input.read(buffer); if (size < 0) break
                    require(output.size() + size <= max) { "The update metadata is too large." }
                    output.write(buffer, 0, size)
                }; output.toByteArray()
            }
        } finally { connection.disconnect() }
    }
    private fun connection(raw: String): HttpURLConnection {
        var url = URL(raw)
        repeat(6) {
            require(url.protocol == "https" && url.userInfo == null && (url.port == -1 || url.port == 443) &&
                url.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")) { "The update redirected outside GitHub." }
            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 15000; readTimeout = 30000
                setRequestProperty("User-Agent", "FitNotes-Android-Updater")
                setRequestProperty("Accept", if (url.host == "api.github.com") "application/vnd.github+json" else "application/octet-stream")
                if (url.host == "api.github.com") setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
            try {
                if (connection.responseCode !in setOf(301, 302, 303, 307, 308)) return connection
                val location = connection.getHeaderField("Location"); connection.disconnect()
                require(!location.isNullOrBlank()) { "GitHub returned an incomplete download redirect." }
                url = URL(url, location)
            } catch (error: Exception) { connection.disconnect(); throw error }
        }
        throw IOException("GitHub redirected the update too many times.")
    }
}
internal fun sha256(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
