package dev.busung.s25uroot

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionName: String,
    val apkUrl: String?,
    val releaseUrl: String,
)

const val ROOT_MY_GALAXY_URL = "https://github.com/TheFlood424K/Root-My-Galaxy"

object AppUpdater {

    private const val GITHUB_API = "https://api.github.com/repos/TheFlood424K/Root-My-Galaxy"
    private const val RELEASES_PAGE = "$ROOT_MY_GALAXY_URL/releases/latest"

    /** Fetches the latest *stable* release (for production builds). */
    suspend fun fetchLatestRelease(): UpdateInfo? = withContext(Dispatchers.IO) {
        fetchReleaseByTag("latest")
    }

    /**
     * Fetches the rolling [ci-latest] pre-release.
     *
     * The quick-build workflow publishes a pre-release tagged [ci-latest] after
     * every successful CI build and attaches a stable-named APK asset
     * (root-my-galaxy-ci.apk).  Because the CI variant uses
     * applicationId = dev.busung.s25uroot.ci and is always signed with the
     * same debug keystore, the system installer will accept an upgrade
     * in-place without an uninstall.
     *
     * Version comparison is done on the run number embedded in the release
     * title ("CI build #<N>") against [BuildConfig.CI_RUN_NUMBER].
     */
    suspend fun fetchLatestCiBuild(): UpdateInfo? = withContext(Dispatchers.IO) {
        fetchReleaseByTag("ci-latest")
    }

    /** Returns true when the installed CI build is older than [latest]. */
    fun isCiUpdateAvailable(latest: UpdateInfo): Boolean {
        val latestRun = runNumberFromVersionName(latest.versionName) ?: return false
        val currentRun = runNumberFromVersionName(BuildConfig.VERSION_NAME) ?: return false
        return latestRun > currentRun
    }

    /** Extracts the numeric run number from a version string like "0.2.67-ci+42" or "CI build #42". */
    private fun runNumberFromVersionName(name: String): Int? {
        // Matches "-ci+42" or "#42" at the end of the string.
        val match = Regex("(?:ci\\+(\\d+)|#(\\d+))").find(name) ?: return null
        return (match.groupValues[1].takeIf { it.isNotEmpty() }
            ?: match.groupValues[2]).toIntOrNull()
    }

    private fun fetchReleaseByTag(tag: String): UpdateInfo? {
        val url = if (tag == "latest") "$GITHUB_API/releases/latest"
                  else "$GITHUB_API/releases/tags/$tag"
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "RootMyGalaxy/${BuildConfig.VERSION_NAME}")
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
                val body = connection.inputStream.bufferedReader().use { it.readText() }

                val json = JSONObject(body)
                val rawTag = json.optString("tag_name").trim()
                // For ci-latest the "version" is the release title (e.g. "CI build #42").
                val versionName = if (rawTag == "ci-latest")
                    json.optString("name").trim()
                else
                    rawTag.removePrefix("v")
                if (versionName.isBlank()) return null

                var apkUrl: String? = null
                json.optJSONArray("assets")?.let { assets ->
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        if (asset.optString("name").endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url").ifEmpty { null }
                            break
                        }
                    }
                }
                UpdateInfo(
                    versionName = versionName,
                    apkUrl = apkUrl,
                    releaseUrl = json.optString("html_url").ifEmpty { RELEASES_PAGE },
                )
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun isUpdateAvailable(latestVersion: String, currentVersion: String): Boolean =
        latestVersion.isNotEmpty() && latestVersion != currentVersion

    suspend fun downloadApk(
        context: Context,
        url: String,
        onProgress: (Float) -> Unit = {},
    ): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "update.apk")
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "RootMyGalaxy/${BuildConfig.VERSION_NAME}")
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
                val total = connection.contentLength
                val buffer = ByteArray(64 * 1024)
                var downloaded = 0L
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                onProgress((downloaded.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                if (target.length() == 0L) return@withContext null
                target
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            target.delete()
            null
        }
    }

    fun installApk(context: Context, apk: File): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    fun openReleasesPage(context: Context) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE)))
    }
}

/** Fetches and installs the latest KernelSU Manager APK from tiann/KernelSU. */
object KernelSuManagerInstaller {

    /** Returns the browser_download_url of the first .apk in the latest KSU release, or null. */
    suspend fun fetchManagerApkUrl(): String? = withContext(Dispatchers.IO) {
        KernelSuReleases.fetchManagerApkUrl()
    }

    /** Downloads the manager APK to cache and triggers the system installer. */
    suspend fun downloadAndInstall(
        context: Context,
        onProgress: (Float) -> Unit = {},
    ): Boolean {
        val url = fetchManagerApkUrl() ?: return false
        val apk = AppUpdater.downloadApk(context, url, onProgress) ?: return false
        return AppUpdater.installApk(context, apk)
    }

    fun openReleasesPage(context: Context) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(KernelSuReleases.KSU_RELEASES_PAGE)),
        )
    }
}
