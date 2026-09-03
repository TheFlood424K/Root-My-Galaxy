package dev.busung.s25uroot

import android.content.Context
import android.net.Uri
import android.system.Os
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

data class VerifiedPayloads(
    val profile: TargetProfile,
    val exploit: File,
    val kernelSu: File,
)

class PayloadRepository(private val context: Context) {
    fun loadTargets(): List<TargetProfile> {
        val commit = resolveMainCommit()
        val manifestBytes = downloadBytes(rawUrl(commit, "support/targets-v3.json"), MAX_MANIFEST_BYTES)
        return SupportManifest.parse(manifestBytes).targets.map { profile -> profile.copy(
            exploit = profile.exploit.copy(url = pinArtifactUrl(profile.exploit.url, commit)),
            kernelSu = profile.kernelSu.copy(url = pinArtifactUrl(profile.kernelSu.url, commit)),
        ) }
    }

    fun resolveTarget(snapshot: DeviceSnapshot): TargetProfile = loadTargets()
        .firstOrNull { it.matches(snapshot) }
        ?: error(context.getString(R.string.repo_no_profile))

    fun resolveTarget(profileId: String): TargetProfile = loadTargets()
        .firstOrNull { it.profileId == profileId }
        ?: error(context.getString(R.string.repo_profile_missing, profileId))

    /**
     * Downloads the exploit and KernelSU payloads for [profile].
     *
     * [onProgress] receives a human-readable message and an optional
     * 0.0–1.0 fraction representing the current file-level download
     * progress.  The fraction is `null` when the server does not advertise
     * a Content-Length (live upstream URL) or for non-download events.
     */
    fun download(
        profile: TargetProfile,
        onProgress: (String, Float?) -> Unit,
    ): VerifiedPayloads {
        val directory = File(context.filesDir, "payloads/${profile.profileId}").apply { mkdirs() }

        val ksudArtifact = KernelSuReleases.resolveKsud()
            ?.also { onProgress("[*] ksud resolved from tiann/KernelSU releases", null) }
            ?: profile.kernelSu.also {
                onProgress("[*] ksud: tiann/KernelSU API unavailable, using payload-repo pin", null)
            }

        val exploit = downloadArtifact(
            profile.exploit,
            File(directory, "cve-2026-43499-app.so"),
            context.getString(R.string.artifact_exploit),
            onProgress,
        )
        val kernelSu = downloadArtifact(
            ksudArtifact,
            File(directory, "ksud-s25u-kdp"),
            context.getString(R.string.artifact_kernelsu),
            onProgress,
        )
        Os.chmod(exploit.absolutePath, 0b100100100)
        Os.chmod(kernelSu.absolutePath, 0b100100100)
        return VerifiedPayloads(profile, exploit, kernelSu)
    }

    fun stageLocalExploit(
        profile: TargetProfile,
        uri: Uri,
        onProgress: (String, Float?) -> Unit,
    ): VerifiedPayloads {
        val directory = File(context.filesDir, "payloads/${profile.profileId}").apply { mkdirs() }
        val destination = File(directory, "cve-2026-43499-app.so")
        onProgress(context.getString(R.string.repo_importing, context.getString(R.string.artifact_exploit)), null)
        copyFromUri(uri, destination, context.getString(R.string.artifact_exploit))
        Os.chmod(destination.absolutePath, 0b100100100)
        onProgress(context.getString(R.string.repo_verified, context.getString(R.string.artifact_exploit)), 1f)
        return VerifiedPayloads(profile, destination, File(directory, "ksud-s25u-kdp"))
    }

    fun stageLocalKernelSu(
        payloads: VerifiedPayloads,
        uri: Uri,
        onProgress: (String, Float?) -> Unit,
    ): VerifiedPayloads {
        val destination = payloads.kernelSu
        onProgress(context.getString(R.string.repo_importing, context.getString(R.string.artifact_kernelsu)), null)
        copyFromUri(uri, destination, context.getString(R.string.artifact_kernelsu))
        Os.chmod(destination.absolutePath, 0b100100100)
        onProgress(context.getString(R.string.repo_verified, context.getString(R.string.artifact_kernelsu)), 1f)
        return VerifiedPayloads(payloads.profile, payloads.exploit, destination)
    }

    private fun copyFromUri(uri: Uri, destination: File, label: String) {
        val temporary = File(destination.parentFile, "${destination.name}.part")
        val input = context.contentResolver.openInputStream(uri)
            ?: error(context.getString(R.string.repo_local_open_failed, label))
        input.use { stream ->
            FileOutputStream(temporary).use { output ->
                stream.copyTo(output, DEFAULT_BUFFER_SIZE)
                output.fd.sync()
            }
        }
        if (destination.exists()) destination.delete()
        require(temporary.renameTo(destination)) {
            context.getString(R.string.repo_finalize_failed, label)
        }
    }

    /**
     * Downloads [artifact] to [destination], calling [onProgress] with
     * both a log string and a 0.0–1.0 progress fraction.
     *
     * Progress fractions are only emitted when Content-Length is known AND
     * matches [artifact.size].  Otherwise the fraction is `null`.
     */
    private fun downloadArtifact(
        artifact: RemoteArtifact,
        destination: File,
        label: String,
        onProgress: (String, Float?) -> Unit,
    ): File {
        onProgress(context.getString(R.string.repo_downloading, label), 0f)
        val temporary = File(destination.parentFile, "${destination.name}.part")
        val connection = open(artifact.url)
        val expectedSize = artifact.size
        if (expectedSize != -1L) {
            require(
                connection.contentLengthLong == -1L ||
                    connection.contentLengthLong == expectedSize,
            ) { context.getString(R.string.repo_size_mismatch, label) }
        }
        val contentLength = if (expectedSize != -1L) expectedSize else connection.contentLengthLong
        var total = 0L
        connection.inputStream.use { input ->
            FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (expectedSize != -1L) {
                        require(total <= expectedSize) {
                            context.getString(R.string.repo_size_exceeded, label)
                        }
                    }
                    output.write(buffer, 0, count)
                    // Emit byte-level progress when we know the total size
                    val fraction: Float? = if (contentLength > 0L) {
                        (total.toFloat() / contentLength.toFloat()).coerceIn(0f, 1f)
                    } else {
                        null
                    }
                    onProgress(context.getString(R.string.repo_downloading, label), fraction)
                }
                output.fd.sync()
            }
        }
        connection.disconnect()
        if (expectedSize != -1L) {
            require(total == expectedSize) { context.getString(R.string.repo_incomplete, label) }
        } else {
            require(total > 0L) { context.getString(R.string.repo_incomplete, label) }
        }
        if (destination.exists()) destination.delete()
        require(temporary.renameTo(destination)) {
            context.getString(R.string.repo_finalize_failed, label)
        }
        onProgress(context.getString(R.string.repo_verified, label), 1f)
        return destination
    }

    private fun resolveMainCommit(): String {
        val response = downloadBytes(COMMIT_API_URL, MAX_COMMIT_RESPONSE_BYTES)
        val commit = JSONObject(response.toString(Charsets.UTF_8))
            .getJSONObject("object")
            .getString("sha")
        require(commit.matches(Regex("[0-9a-f]{40}"))) { context.getString(R.string.repo_commit_invalid) }
        return commit
    }

    private fun rawUrl(commit: String, path: String) = "$RAW_REPOSITORY/$commit/$path"

    private fun pinArtifactUrl(url: String, commit: String): String {
        require(url.startsWith(MUTABLE_RAW_PREFIX)) { context.getString(R.string.repo_url_invalid) }
        return "$RAW_REPOSITORY/$commit/${url.removePrefix(MUTABLE_RAW_PREFIX)}"
    }

    private fun downloadBytes(url: String, maximum: Int): ByteArray {
        val connection = open(url)
        val bytes = connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= maximum) {
                    context.getString(R.string.repo_response_too_large)
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        connection.disconnect()
        return bytes
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "S25URoot/${BuildConfig.VERSION_NAME}")
            connect()
            require(responseCode == HttpURLConnection.HTTP_OK) { "HTTP $responseCode" }
        }

    companion object {
        private const val COMMIT_API_URL =
            "https://api.github.com/repos/asarr22/Root-My-Galaxy-Payloads/git/ref/heads/main"
        private const val RAW_REPOSITORY =
            "https://raw.githubusercontent.com/asarr22/Root-My-Galaxy-Payloads"
        private const val MUTABLE_RAW_PREFIX = "$RAW_REPOSITORY/main/"
        private const val MAX_COMMIT_RESPONSE_BYTES = 16 * 1024
        private const val MAX_MANIFEST_BYTES = 256 * 1024
    }
}
