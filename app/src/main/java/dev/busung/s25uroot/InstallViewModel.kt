package dev.busung.s25uroot

import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// ---------------------------------------------------------------------------
// Phase enum
// ---------------------------------------------------------------------------

enum class InstallPhase {
    Checking,
    Ready,
    Downloading,
    Exploiting,
    LoadingKernelSu,
    Installed,
    Done,
    Failed,
    Running,
}

private enum class PayloadSource { Remote, Local }

// ---------------------------------------------------------------------------
// UI state
// ---------------------------------------------------------------------------

data class InstallUiState(
    val phase: InstallPhase = InstallPhase.Checking,
    val statusMessage: String = "",
    val progress: Float? = null,
    val progressLabel: String? = null,
    val probeOutput: String = "",
    val log: String = "",
    val device: DeviceSnapshot? = null,
    val isRooted: Boolean = false,
    val kernelSuVersion: String? = null,
    val androidVersion: String? = null,
    val securityPatch: String? = null,
    val selectedProfile: TargetProfile? = null,
    val pendingInstallRequest: String? = null,
) {
    val busy: Boolean
        get() = phase in setOf(
            InstallPhase.Checking,
            InstallPhase.Downloading,
            InstallPhase.Exploiting,
            InstallPhase.LoadingKernelSu,
            InstallPhase.Running,
        )

    val message: String get() = statusMessage
}

data class TargetCatalogUiState(
    val loading: Boolean = false,
    val profiles: List<TargetProfile> = emptyList(),
    val error: String? = null,
)

private data class CommandResult(val code: Int, val output: String)

// ---------------------------------------------------------------------------
// ProcessDiagnostics
// ---------------------------------------------------------------------------

/**
 * Full diagnostic bundle collected from a dead native process.
 *
 * @param exitCode   Raw waitpid exit code (0 = clean, non-zero = abnormal).
 * @param signal     Signal that killed the process, or 0 if not signalled.
 *                   Populated from /proc/<pid>/stat field 35 via JNI.
 * @param stderrTail Last [MAX_STDERR_LINES] non-blank lines from the merged
 *                   stdout/stderr stream captured during the run.
 * @param linkerErrors Lines from [stderrTail] that look like a dynamic-linker
 *                   error ("cannot locate symbol", "dlopen failed", etc.).
 * @param fingerprint Build.FINGERPRINT of the device at the time of failure.
 */
data class ProcessDiagnostics(
    val exitCode: Int,
    val signal: Int,
    val stderrTail: List<String>,
    val linkerErrors: List<String>,
    val fingerprint: String,
) {
    /** Human-readable signal name, e.g. "SIGSEGV (11)". */
    val signalName: String
        get() = when (signal) {
            0    -> "none"
            1    -> "SIGHUP (1)"
            2    -> "SIGINT (2)"
            3    -> "SIGQUIT (3)"
            4    -> "SIGILL (4)"
            5    -> "SIGTRAP (5)"
            6    -> "SIGABRT (6)"
            7    -> "SIGBUS (7)"
            8    -> "SIGFPE (8)"
            9    -> "SIGKILL (9)"
            10   -> "SIGUSR1 (10)"
            11   -> "SIGSEGV (11)"
            12   -> "SIGUSR2 (12)"
            13   -> "SIGPIPE (13)"
            14   -> "SIGALRM (14)"
            15   -> "SIGTERM (15)"
            16   -> "SIGSTKFLT (16)"
            17   -> "SIGCHLD (17)"
            18   -> "SIGCONT (18)"
            19   -> "SIGSTOP (19)"
            20   -> "SIGTSTP (20)"
            21   -> "SIGTTIN (21)"
            22   -> "SIGTTOU (22)"
            23   -> "SIGURG (23)"
            24   -> "SIGXCPU (24)"
            25   -> "SIGXFSZ (25)"
            26   -> "SIGVTALRM (26)"
            27   -> "SIGPROF (27)"
            28   -> "SIGWINCH (28)"
            29   -> "SIGIO (29)"
            30   -> "SIGPWR (30)"
            31   -> "SIGSYS (31)"
            else -> "signal $signal"
        }

    /**
     * Formats all fields into a multi-line diagnostic block suitable for
     * direct inclusion in the in-app error message and the install log.
     */
    fun format(): String = buildString {
        appendLine("── Process diagnostics ──────────────────")
        appendLine("Exit status : $exitCode")
        if (signal != 0) appendLine("Signal      : $signalName")
        appendLine("Fingerprint : $fingerprint")
        if (linkerErrors.isNotEmpty()) {
            appendLine("Linker errors:")
            linkerErrors.forEach { appendLine("  $it") }
        }
        if (stderrTail.isNotEmpty()) {
            appendLine("stderr tail:")
            stderrTail.forEach { appendLine("  $it") }
        }
        append("─────────────────────────────────────────")
    }

    companion object {
        /** Patterns that indicate a dynamic-linker failure in stderr output. */
        private val LINKER_PATTERNS = listOf(
            Regex("""cannot locate symbol""", RegexOption.IGNORE_CASE),
            Regex("""dlopen failed""", RegexOption.IGNORE_CASE),
            Regex("""undefined symbol""", RegexOption.IGNORE_CASE),
            Regex("""CANNOT LINK EXECUTABLE""", RegexOption.IGNORE_CASE),
            Regex("""could not load""", RegexOption.IGNORE_CASE),
            Regex("""soname""", RegexOption.IGNORE_CASE),
            Regex("""library.*not found""", RegexOption.IGNORE_CASE),
            Regex("""no such file or directory""", RegexOption.IGNORE_CASE),
        )

        private const val MAX_STDERR_LINES = 20

        /**
         * Collects diagnostics from a process that has already exited.
         *
         * @param process    The dead [Process] object.
         * @param capturedOutput  Everything already drained from the process
         *                   stdout/stderr stream into a [StringBuilder].
         * @param stripAnsi  Function to strip ANSI escape codes.
         */
        fun collect(
            process: Process,
            capturedOutput: StringBuilder,
            stripAnsi: (String) -> String,
        ): ProcessDiagnostics {
            // Drain any remaining bytes the caller may not have read yet.
            runCatching {
                val avail = process.inputStream.available()
                if (avail > 0) {
                    capturedOutput.append(
                        process.inputStream.readNBytes(minOf(avail, 65_536))
                            .toString(Charsets.UTF_8)
                    )
                }
            }

            val exitCode = runCatching { process.waitFor() }.getOrDefault(-1)

            // Read the exit signal from /proc/<pid>/stat via JNI.
            // Process.pid() is available from API 26; our minSdk is 33.
            val signal = runCatching {
                NativeProbe.getProcessSignal(process.pid())
            }.getOrDefault(-1).let { if (it < 0) 0 else it }

            val rawOutput = stripAnsi(capturedOutput.toString())
            val tailLines = rawOutput
                .lines()
                .filter { it.isNotBlank() }
                .takeLast(MAX_STDERR_LINES)

            val linkerErrors = tailLines.filter { line ->
                LINKER_PATTERNS.any { it.containsMatchIn(line) }
            }

            return ProcessDiagnostics(
                exitCode = exitCode,
                signal = signal,
                stderrTail = tailLines,
                linkerErrors = linkerErrors,
                fingerprint = Build.FINGERPRINT,
            )
        }
    }
}

internal fun stagedFileIsCurrent(staged: File, source: File): Boolean {
    if (!staged.exists()) return false
    val stagedDigest = sha256OrNull(staged) ?: return false
    return stagedDigest == sha256OrNull(source)
}

private fun sha256OrNull(file: File): String? = runCatching {
    file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}.getOrNull()

// ---------------------------------------------------------------------------
// Progress layout
// ---------------------------------------------------------------------------

private const val PROGRESS_CHECKING_END     = 0.05f
private const val PROGRESS_EXPLOIT_START    = 0.05f
private const val PROGRESS_EXPLOIT_END      = 0.50f
private const val PROGRESS_KSU_START        = 0.50f
private const val PROGRESS_KSU_END          = 0.85f
private const val PROGRESS_EXPLOITING_START = 0.85f
private const val PROGRESS_EXPLOITING_END   = 0.95f
private const val PROGRESS_LOADING_KSU      = 0.97f
private const val PROGRESS_DONE             = 1.00f

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

class InstallViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val repository = PayloadRepository(application)
    private val historyStore = InstallHistoryStore(application)

    private val mutableUiState = MutableStateFlow(InstallUiState())
    private val mutableHistory = MutableStateFlow(historyStore.closeInterruptedRuns())
    private val mutableTargetCatalog = MutableStateFlow(TargetCatalogUiState())

    private val mutableAccentColorEnum  = MutableStateFlow(AppPreferences.accentColor(app))
    private val mutableThemeModeEnum    = MutableStateFlow(AppPreferences.themeMode(app))
    private val mutableAdvancedMode     = MutableStateFlow(AppPreferences.advancedMode(app))
    private val mutableShizukuMode      = MutableStateFlow(AppPreferences.shizukuMode(app))
    private val mutableAutoReroot       = MutableStateFlow(AppPreferences.autoReroot(app))
    private val mutableLocalPayloadMode = MutableStateFlow(AppPreferences.localPayloadMode(app))
    private val mutableDebugLog         = MutableStateFlow(AppPreferences.debugLog(app))

    private var discoveryJob: Job? = null
    private var installJob: Job? = null
    private var activeHistoryEntry: InstallHistoryEntry? = null
    private var localPayloadUris: Map<String, Uri> = emptyMap()

    @Volatile private var activeRunShizuku: Boolean? = null
    @Volatile private var activeRunRebootUserspace: Boolean? = null

    val uiState: StateFlow<InstallUiState> = mutableUiState.asStateFlow()
    val installHistory: StateFlow<List<InstallHistoryEntry>> = mutableHistory.asStateFlow()

    val accentColor: StateFlow<String> = mutableAccentColorEnum
        .map { it.storedValue }
        .stateIn(viewModelScope, SharingStarted.Eagerly, mutableAccentColorEnum.value.storedValue)

    val themeMode: StateFlow<String> = mutableThemeModeEnum
        .map { it.storedValue }
        .stateIn(viewModelScope, SharingStarted.Eagerly, mutableThemeModeEnum.value.storedValue)

    val advancedMode: StateFlow<Boolean>     = mutableAdvancedMode.asStateFlow()
    val shizukuMode: StateFlow<Boolean>      = mutableShizukuMode.asStateFlow()
    val autoReroot: StateFlow<Boolean>       = mutableAutoReroot.asStateFlow()
    val localPayloadMode: StateFlow<Boolean> = mutableLocalPayloadMode.asStateFlow()
    val debugLog: StateFlow<Boolean>         = mutableDebugLog.asStateFlow()

    val state: StateFlow<InstallUiState>          = uiState
    val history: StateFlow<List<InstallHistoryEntry>> = installHistory
    val targetCatalog: StateFlow<TargetCatalogUiState> = mutableTargetCatalog.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val snap   = runCatching { DeviceSnapshot.current() }.getOrNull()
            val rooted = runCatching { NativeProbe.isKernelSuActive() }.getOrDefault(false)
            mutableUiState.value = mutableUiState.value.copy(
                device         = snap,
                isRooted       = rooted,
                kernelSuVersion = null,
                androidVersion = snap?.androidRelease,
                securityPatch  = Build.VERSION.SECURITY_PATCH,
                phase          = InstallPhase.Ready,
                statusMessage  = app.getString(R.string.install_preparing),
                progress       = null,
                progressLabel  = null,
            )
        }
    }

    // -----------------------------------------------------------------------
    // Public actions
    // -----------------------------------------------------------------------

    fun startRoot(profile: TargetProfile? = null) = install(profile)
    fun stopSession() = cancel()

    fun deleteHistoryEntry(id: String)  = deleteHistoryEntries(setOf(id))
    fun deleteAllHistoryEntries()       = deleteHistoryEntries(mutableHistory.value.map { it.id }.toSet())

    fun selectProfile(profile: TargetProfile?) {
        mutableUiState.value = mutableUiState.value.copy(selectedProfile = profile)
    }

    fun setPendingInstallRequest(profileId: String?) {
        mutableUiState.value = mutableUiState.value.copy(pendingInstallRequest = profileId)
    }

    fun consumePendingInstallRequest() {
        mutableUiState.value = mutableUiState.value.copy(pendingInstallRequest = null)
    }

    fun setAdvancedMode(enabled: Boolean)     { AppPreferences.setAdvancedMode(app, enabled);     mutableAdvancedMode.value = enabled }
    fun setShizukuMode(enabled: Boolean)      { AppPreferences.setShizukuMode(app, enabled);      mutableShizukuMode.value = enabled }
    fun setAutoReroot(enabled: Boolean)       { AppPreferences.setAutoReroot(app, enabled);       mutableAutoReroot.value = enabled }
    fun setLocalPayloadMode(enabled: Boolean) { AppPreferences.setLocalPayloadMode(app, enabled); mutableLocalPayloadMode.value = enabled }
    fun setDebugLog(enabled: Boolean)         { AppPreferences.setDebugLog(app, enabled);         mutableDebugLog.value = enabled }

    fun setAccentColor(color: String) {
        val enum = AccentColor.fromStoredValue(color)
        AppPreferences.setAccentColor(app, enum)
        mutableAccentColorEnum.value = enum
    }

    fun setThemeMode(mode: String) {
        val enum = AppThemeMode.fromStoredValue(mode)
        AppPreferences.setThemeMode(app, enum)
        mutableThemeModeEnum.value = enum
    }

    // -----------------------------------------------------------------------
    // Discovery / catalog
    // -----------------------------------------------------------------------

    fun startDiscovery() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = viewModelScope.launch(Dispatchers.IO) {
            mutableTargetCatalog.value = TargetCatalogUiState(loading = true)
            mutableTargetCatalog.value = try {
                TargetCatalogUiState(profiles = repository.loadTargets())
            } catch (e: Exception) {
                TargetCatalogUiState(error = e.message ?: "Unknown error")
            }
        }
    }

    fun cancelDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        mutableTargetCatalog.value = TargetCatalogUiState()
    }

    fun setLocalPayloadUris(uris: Map<String, Uri>) { localPayloadUris = uris }

    fun installByProfileId(profileId: String?, rebootUserspace: Boolean? = null) {
        if (installJob?.isActive == true) return
        installJob = viewModelScope.launch(Dispatchers.IO) {
            val profile: TargetProfile? = when {
                profileId == null || profileId == LOCAL_PROFILE_ID -> null
                else -> try {
                    repository.resolveTarget(profileId)
                } catch (e: Exception) {
                    val msg = buildString {
                        append(e.message ?: "Failed to resolve profile '$profileId'")
                        val cause = e.cause
                        if (cause != null) append(" (caused by: ${cause.message})")
                    }
                    mutableUiState.value = mutableUiState.value.copy(
                        phase = InstallPhase.Failed,
                        statusMessage = msg,
                        progress = null,
                        progressLabel = null,
                    )
                    appendLog("[!] Profile resolution failed: $msg")
                    return@launch
                }
            }
            install(profile, rebootUserspace)
        }
    }

    // -----------------------------------------------------------------------
    // Core install flow
    // -----------------------------------------------------------------------

    fun install(
        profile: TargetProfile? = null,
        rebootUserspace: Boolean? = null,
    ) {
        if (installJob?.isActive == true) return
        installJob = viewModelScope.launch(Dispatchers.IO) {
            startHistory()
            try {
                activeRunRebootUserspace = rebootUserspace
                activeRunShizuku = AppPreferences.shizukuMode(app)

                setPhase(InstallPhase.Checking, app.getString(R.string.install_preparing), PROGRESS_CHECKING_END * 0.5f)
                if (shizukuEnabled()) {
                    appendLog(app.getString(R.string.log_shizuku_prepare))
                    if (!ShizukuController.isRunning() && !ShizukuController.pingUntilRunning())
                        error(app.getString(R.string.error_shizuku_unavailable))
                    if (!ShizukuController.isGranted() && !ShizukuController.requestPermission())
                        error(app.getString(R.string.error_shizuku_permission))
                    appendLog(app.getString(R.string.log_shizuku_permission))
                }
                setPhase(InstallPhase.Checking, app.getString(R.string.install_preparing), PROGRESS_CHECKING_END)

                val verified = resolvePayloads(profile)

                setPhase(InstallPhase.Exploiting, app.getString(R.string.install_preparing), PROGRESS_EXPLOITING_START)
                executeExploitWithRetry(verified.exploit, verified.profile.requiresFreshP0Session)

                val kernelSuUri = localPayloadUris[PAYLOAD_KERNELSU]
                val finalPayloads = if (kernelSuUri != null) {
                    repository.stageLocalKernelSu(verified, kernelSuUri) { msg, fraction ->
                        appendLog("[*] $msg")
                        if (fraction != null) emitProgress(
                            PROGRESS_KSU_START + fraction * (PROGRESS_KSU_END - PROGRESS_KSU_START)
                        )
                    }
                } else {
                    verified
                }

                setPhase(InstallPhase.LoadingKernelSu, app.getString(R.string.install_preparing), PROGRESS_LOADING_KSU)
                installKernelSu(finalPayloads)

                if (shouldRebootUserspace()) {
                    appendLog(app.getString(R.string.log_reboot_userspace))
                    runHelper("--reboot-userspace")
                }

                val nowRooted = runCatching { NativeProbe.isKernelSuActive() }.getOrDefault(true)
                mutableUiState.value = mutableUiState.value.copy(isRooted = nowRooted, kernelSuVersion = null)

                setPhase(InstallPhase.Installed, app.getString(R.string.log_install_complete), PROGRESS_DONE)
                finishHistory(InstallRunResult.Succeeded)
            } catch (e: Exception) {
                val msg = buildString {
                    append(e.message ?: "Unknown error")
                    val cause = e.cause
                    if (cause != null) append("\nCaused by: ${cause.message}")
                }
                mutableUiState.value = mutableUiState.value.copy(
                    phase = InstallPhase.Failed,
                    statusMessage = msg,
                    progress = null,
                    progressLabel = null,
                )
                appendLog("[!] Installation failed: $msg")
                finishHistory(InstallRunResult.Failed)
            } finally {
                activeRunShizuku = null
                activeRunRebootUserspace = null
            }
        }
    }

    private suspend fun resolvePayloads(profile: TargetProfile?): VerifiedPayloads {
        val localExploitUri = localPayloadUris[PAYLOAD_EXPLOIT]

        return when {
            localExploitUri != null -> {
                setPhase(InstallPhase.Downloading, app.getString(R.string.install_preparing), PROGRESS_EXPLOIT_START)
                val snapshot = DeviceSnapshot.current()
                debugLog("[DBG] Local exploit mode – model=${snapshot.model}  kernel=${snapshot.kernelVersion}")
                val syntheticProfile = TargetProfile(
                    profileId              = LOCAL_PROFILE_ID,
                    displayName            = "",
                    models                 = setOf(snapshot.model),
                    kernelVersions         = setOf(snapshot.kernelVersion),
                    exploit                = RemoteArtifact("", -1L),
                    kernelSu               = RemoteArtifact("", -1L),
                    requiresFreshP0Session = false,
                )
                repository.stageLocalExploit(syntheticProfile, localExploitUri) { msg, fraction ->
                    appendLog("[*] $msg")
                    if (fraction != null) emitProgress(
                        PROGRESS_EXPLOIT_START + fraction * (PROGRESS_EXPLOIT_END - PROGRESS_EXPLOIT_START)
                    )
                }
            }

            profile != null -> {
                setPhase(InstallPhase.Downloading, app.getString(R.string.install_preparing), PROGRESS_EXPLOIT_START)
                debugLog("[DBG] Using explicit profile: id=${profile.profileId}  name=${profile.displayName}")
                debugLog("[DBG] Profile targets: models=${profile.models}  kernels=${profile.kernelVersions}")
                updateHistoryProfile(profile.profileId)
                try {
                    repository.download(profile) { msg, fraction ->
                        appendLog("[*] $msg")
                        if (fraction != null) emitDownloadProgress(fraction)
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    throw IllegalStateException(
                        "Download timed out for profile '${profile.displayName}'. " +
                        "Check your internet connection and try again.", e
                    )
                } catch (e: java.io.IOException) {
                    val detail = e.message ?: ""
                    val httpCode = Regex("""HTTP (\d+)""").find(detail)?.groupValues?.get(1)
                    throw IllegalStateException(
                        if (httpCode != null)
                            "Server returned HTTP $httpCode for profile '${profile.displayName}'. " +
                            "The payload may have been moved or deleted."
                        else
                            "Network error downloading '${profile.displayName}': $detail", e
                    )
                }
            }

            else -> {
                setPhase(InstallPhase.Checking, app.getString(R.string.install_preparing), PROGRESS_CHECKING_END)
                val snapshot = DeviceSnapshot.current()
                debugLog(
                    "[DBG] Auto-detect – model=${snapshot.model}  " +
                    "kernel=${snapshot.kernelVersion}  " +
                    "android=${snapshot.androidRelease}  " +
                    "patch=${Build.VERSION.SECURITY_PATCH}"
                )
                val resolved = try {
                    repository.resolveTarget(snapshot)
                } catch (e: Exception) {
                    val baseMsg    = e.message ?: "No matching profile found"
                    val deviceHint = "Device: ${snapshot.model}, kernel ${snapshot.kernelVersion}"
                    mutableUiState.value = mutableUiState.value.copy(
                        phase = InstallPhase.Ready, statusMessage = app.getString(R.string.install_preparing),
                        progress = null, progressLabel = null,
                    )
                    appendLog("[!] Profile lookup failed — $baseMsg ($deviceHint)")
                    throw IllegalStateException("$baseMsg\n$deviceHint", e)
                }
                debugLog(
                    "[DBG] Matched profile: id=${resolved.profileId}  " +
                    "name=${resolved.displayName}  " +
                    "requiresFreshP0=${resolved.requiresFreshP0Session}"
                )
                setPhase(InstallPhase.Downloading, app.getString(R.string.install_preparing), PROGRESS_EXPLOIT_START)
                updateHistoryProfile(resolved.profileId)
                try {
                    repository.download(resolved) { msg, fraction ->
                        appendLog("[*] $msg")
                        if (fraction != null) emitDownloadProgress(fraction)
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    throw IllegalStateException(
                        "Download timed out for profile '${resolved.displayName}'. " +
                        "Check your internet connection and try again.", e
                    )
                } catch (e: java.io.IOException) {
                    val detail = e.message ?: ""
                    val httpCode = Regex("""HTTP (\d+)""").find(detail)?.groupValues?.get(1)
                    throw IllegalStateException(
                        if (httpCode != null)
                            "Server returned HTTP $httpCode for profile '${resolved.displayName}'. " +
                            "The payload may have been moved or deleted."
                        else
                            "Network error downloading '${resolved.displayName}': $detail", e
                    )
                }
            }
        }
    }

    private var lastDownloadProgress: Float = PROGRESS_EXPLOIT_START

    private fun emitDownloadProgress(fileFraction: Float) {
        val totalRange = PROGRESS_KSU_END - PROGRESS_EXPLOIT_START
        val candidate  = PROGRESS_EXPLOIT_START + fileFraction * totalRange
        val next       = maxOf(lastDownloadProgress, candidate)
        lastDownloadProgress = next
        emitProgress(next)
    }

    private fun emitProgress(raw: Float) {
        val clamped = raw.coerceIn(0f, 1f)
        val pct     = (clamped * 100f).roundToInt()
        mutableUiState.value = mutableUiState.value.copy(
            progress      = clamped,
            progressLabel = "$pct %",
        )
    }

    // -----------------------------------------------------------------------
    // Exploit execution
    // -----------------------------------------------------------------------

    private suspend fun executeExploitWithRetry(
        payload: File,
        requiresFreshP0Session: Boolean,
    ) {
        val maxAttempts = EXPLOIT_ATTEMPTS.toIntOrNull() ?: 6
        val bootToken   = currentBootToken()
        var lastError: Throwable? = null

        for (attempt in 1..maxAttempts) {
            val attemptStart = SystemClock.elapsedRealtime()
            if (attempt > 1) appendLog("[*] Retrying exploit (attempt $attempt / $maxAttempts)…")
            try {
                executeExploit(payload, requiresFreshP0Session, bootToken, attempt)
                debugLog("[DBG] Exploit succeeded on attempt $attempt (${SystemClock.elapsedRealtime() - attemptStart} ms)")
                return
            } catch (e: ExploitStallException) {
                val elapsed = SystemClock.elapsedRealtime() - attemptStart
                appendLog("[!] Attempt $attempt stalled after ${elapsed} ms – ${e.message}; retrying")
                lastError = e
            } catch (e: Exception) {
                throw e
            }
        }

        throw lastError ?: IllegalStateException(app.getString(R.string.error_exploit_timeout))
    }

    private suspend fun executeExploit(
        payload: File,
        requiresFreshP0Session: Boolean,
        bootToken: String?,
        attempt: Int,
    ) {
        val shizuku = shizukuEnabled()
        val logFile = if (shizuku) File(SHIZUKU_LOG_PATH) else File(app.filesDir, "exploit.log")

        if (shizuku) ShizukuController.exec(arrayOf("rm", "-f", SHIZUKU_LOG_PATH)).waitFor()
        else logFile.delete()

        val helper = helperFile()
        if (!shizuku) require(helper.canExecute()) { app.getString(R.string.error_helper_unavailable) }

        val logPrefix = mutableUiState.value.log
        val env       = buildExploitEnvironment(bootToken, payload, helper, shizuku)

        debugLog(
            "[DBG] Exploit attempt $attempt — payload=${payload.absolutePath} " +
            "(${payload.length()} B)  logFile=${logFile.absolutePath}  shizuku=$shizuku"
        )
        debugLog("[DBG] Env: ${env.joinToString("  ")}")

        val process = launchExploitProcess(payload, helper, logFile, shizuku, env)

        var lastLogSize    = -1L
        var lastLogChangeAt = SystemClock.elapsedRealtime()
        val startedAt       = lastLogChangeAt
        val earlyOutput     = StringBuilder()

        var exploitPulse = PROGRESS_EXPLOITING_START
        val exploitBand  = PROGRESS_EXPLOITING_END - PROGRESS_EXPLOITING_START

        try {
            while (process.isAlive) {
                drainProcessOutput(process, earlyOutput)

                val logSize = logFile.length()
                val now     = SystemClock.elapsedRealtime()

                if (logSize != lastLogSize) { lastLogSize = logSize; lastLogChangeAt = now }

                publishExploitLog(logPrefix, logFile.readTextIfPresent())

                exploitPulse = PROGRESS_EXPLOITING_START +
                    ((exploitPulse - PROGRESS_EXPLOITING_START + EXPLOIT_PULSE_STEP) % exploitBand)
                emitProgress(exploitPulse)

                val stallMs = now - lastLogChangeAt
                val totalMs = now - startedAt

                if (stallMs >= EXPLOIT_STALL_MILLIS)
                    throw ExploitStallException("${app.getString(R.string.error_exploit_stalled)} (no output for ${stallMs} ms)")
                require(totalMs < EXPLOIT_TOTAL_MILLIS) { app.getString(R.string.error_exploit_timeout) }

                delay(LOG_POLL_INTERVAL)
            }

            drainProcessOutput(process, earlyOutput)
            val rawLog = if (shizuku) File(SHIZUKU_LOG_PATH).readTextIfPresent() else logFile.readTextIfPresent()
            publishExploitLog(logPrefix, rawLog)

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                // Collect full diagnostics from the dead process.
                val diag = ProcessDiagnostics.collect(process, earlyOutput, ::stripAnsi)
                val diagBlock = diag.format()

                // Also include the exploit log tail so the failure is fully
                // self-contained without needing adb logcat.
                val logTail = rawLog.lines().filter { it.isNotBlank() }.takeLast(5).joinToString("\n")
                val logDetail = logTail.takeIf(String::isNotBlank)?.let { "\nExploit log:\n$it" } ?: ""

                debugLog("[DBG] Exploit process died:\n$diagBlock")
                error(
                    app.getString(R.string.error_payload_exit, exitCode, "") +
                    "\n" + diagBlock + logDetail
                )
            }

            cacheP0Offset(bootToken, rawLog)
            appendLog(app.getString(R.string.log_bootstrap_root))
            require(detectInstalled()) { app.getString(R.string.error_success_marker) }
        } finally {
            killProcess(process)
        }
    }

    private fun buildExploitEnvironment(
        bootToken: String?,
        payload: File,
        helper: File,
        shizuku: Boolean,
    ): Array<String> {
        val payloadPath = if (shizuku) SHIZUKU_PAYLOAD_PATH else payload.absolutePath
        val helperPath  = if (shizuku) SHIZUKU_HELPER_PATH  else helper.absolutePath
        return buildList {
            add("EXPLOIT_ATTEMPTS=$EXPLOIT_ATTEMPTS")
            add("P0_ATTEMPT_TIMEOUT_SEC=$P0_ATTEMPT_TIMEOUT_SEC")
            add("EXPLOIT_ATTEMPT_TIMEOUT_SEC=$EXPLOIT_ATTEMPT_TIMEOUT_SEC")
            add("CVE43499_ROOT_HELPER=$helperPath")
            add("LD_PRELOAD=$payloadPath")
            if (bootToken != null) {
                cachedP0Offset(bootToken)?.let { add("$P0_OFFSET_ENV=$it") }
            }
        }.toTypedArray()
    }

    private fun launchExploitProcess(
        payload: File,
        helper: File,
        logFile: File,
        shizuku: Boolean,
        env: Array<String>,
    ): Process {
        return if (shizuku) {
            val stagedHelper  = shizukuStage(nativeHelperFile(), SHIZUKU_HELPER_PATH, "755")
            val stagedPayload = shizukuStage(payload, SHIZUKU_PAYLOAD_PATH, "755")
            debugLog("[DBG] Shizuku staged: helper=${stagedHelper.absolutePath}  payload=${stagedPayload.absolutePath}")
            ShizukuController.exec(
                arrayOf(
                    stagedHelper.absolutePath,
                    "--run-payload",
                    stagedPayload.absolutePath,
                    stagedHelper.absolutePath,
                    SHIZUKU_LOG_PATH,
                ),
                env,
            )
        } else {
            ProcessBuilder(
                listOf(
                    helper.absolutePath,
                    "--run-payload",
                    payload.absolutePath,
                    helper.absolutePath,
                    logFile.absolutePath,
                ),
            )
                .redirectErrorStream(true)
                .apply {
                    environment().putAll(
                        env.associate { entry ->
                            val eq = entry.indexOf('=')
                            if (eq >= 0) entry.substring(0, eq) to entry.substring(eq + 1) else entry to ""
                        },
                    )
                }
                .start()
        }
    }

    private suspend fun killProcess(process: Process) {
        if (!process.isAlive) return
        process.destroy()
        delay(500.milliseconds)
        if (process.isAlive) process.destroyForcibly()
    }

    private fun drainProcessOutput(process: Process, buffer: StringBuilder): String {
        drainStream(process.inputStream, buffer)
        return buffer.toString()
    }

    private fun drainStream(stream: InputStream, buffer: StringBuilder) {
        if (stream.available() > 0) {
            val bytes = stream.readNBytes(minOf(stream.available(), MAX_DRAIN_BYTES))
            buffer.append(bytes.toString(Charsets.UTF_8))
        }
    }

    private fun publishExploitLog(logPrefix: String, rawLog: String) {
        val clean = stripAnsi(rawLog)
        val lines = clean.lines().takeLast(MAX_LOG_LINES)
        mutableUiState.value = mutableUiState.value.copy(log = logPrefix + lines.joinToString("\n"))
    }

    // -----------------------------------------------------------------------
    // KernelSU installation
    // -----------------------------------------------------------------------

    private suspend fun installKernelSu(payloads: VerifiedPayloads) {
        val ksud = payloads.kernelSu
        appendLog(app.getString(R.string.log_kernelsu_source, ksud.absolutePath))
        debugLog("[DBG] ksud size=${ksud.length()} B  sha256=${sha256OrNull(ksud) ?: "n/a"}")

        val (stagedKsud, stagePath) = stageKsud(ksud)
        debugLog("[DBG] ksud staged to ${stagedKsud.absolutePath}  stagePath=$stagePath")

        val stageCommand = "${shellQuote(stagedKsud.absolutePath)} install --path ${shellQuote(stagePath)}"
        debugLog("[DBG] Running stage command: $stageCommand")
        val stage = runHelper("-c", stageCommand)
        if (stage.code != 0) {
            val out = stage.output.trim().takeIf(String::isNotBlank)
            debugLog("[DBG] ksud stage output: ${out ?: "(empty)"}")
            throw IllegalStateException(app.getString(R.string.error_ksu_stage, out ?: "exit code ${stage.code}"))
        }
        appendLog(app.getString(R.string.log_ksu_staged))

        debugLog("[DBG] Running --late-load")
        val lateLoad = runHelper("--late-load")
        if (lateLoad.code != 0) {
            val out = lateLoad.output.trim().takeIf(String::isNotBlank)
            debugLog("[DBG] late-load output: ${out ?: "(empty)"}")
            throw IllegalStateException(app.getString(R.string.error_ksu_verify, lateLoad.code, out ?: ""))
        }

        val libksudPath = app.applicationInfo.nativeLibraryDir + "/libksud.so"
        debugLog("[DBG] Verifying KSU control via libksud at $libksudPath")
        val verify = runHelper("-c", "\"${shellQuote(libksudPath)}\"")
        if (verify.code != 0) {
            val out = verify.output.trim().takeIf(String::isNotBlank)
            debugLog("[DBG] verify output: ${out ?: "(empty)"}")
            throw IllegalStateException(app.getString(R.string.error_ksu_verify, verify.code, out ?: ""))
        }
        appendLog(app.getString(R.string.log_ksu_control_verified))
    }

    private fun stageKsud(ksud: File): Pair<File, String> {
        return if (shizukuEnabled()) {
            val staged = shizukuStage(ksud, SHIZUKU_KSUD_PATH, "755")
            shizukuStage(nativeHelperFile(), SHIZUKU_HELPER_PATH, "755")
            staged to SHIZUKU_KSUD_STAGE_PATH
        } else {
            ksud to (app.filesDir.absolutePath + "/ksud-stage")
        }
    }

    // -----------------------------------------------------------------------
    // Receipt / offset helpers
    // -----------------------------------------------------------------------

    private fun detectInstalled(): Boolean {
        val receipt = File(app.filesDir, INSTALL_RECEIPT)
        if (!receipt.exists()) return false
        return try { receipt.readText(Charsets.UTF_8).contains(RECEIPT_VERIFIED) } catch (_: Exception) { false }
    }

    @Suppress("unused")
    private fun storeInstallReceipt() {
        val receipt = File(app.filesDir, INSTALL_RECEIPT)
        try {
            val bootToken = currentBootToken() ?: return
            receipt.writeText("$RECEIPT_BOOT_TOKEN=$bootToken\n$RECEIPT_VERIFIED=true", Charsets.UTF_8)
        } catch (_: Exception) {
            error(app.getString(R.string.error_receipt))
        }
    }

    private fun currentBootToken(): String? = runCatching {
        File("/proc/sys/kernel/random/boot_id").readText(Charsets.UTF_8).trim()
    }.getOrElse { null }

    private fun cachedP0Offset(bootToken: String?): String? {
        if (bootToken == null) return null
        val prefs = app.getSharedPreferences(P0_OFFSET_PREFS, android.content.Context.MODE_PRIVATE)
        val stored = prefs.getString(bootToken, null) ?: return null
        return stored.takeIf { it.matches(Regex("[0-9a-fx]+")) }
    }

    private fun cacheP0Offset(bootToken: String?, rawLog: String) {
        if (bootToken == null) return
        val match = P0_OFFSET_PATTERN.find(rawLog) ?: return
        val offset = match.groupValues[1]
        app.getSharedPreferences(P0_OFFSET_PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putString(bootToken, offset).apply()
    }

    // -----------------------------------------------------------------------
    // History helpers
    // -----------------------------------------------------------------------

    private fun startHistory() {
        val entry = historyStore.create()
        activeHistoryEntry = entry
        publishHistory(entry)
    }

    private fun updateHistory(transform: (InstallHistoryEntry) -> InstallHistoryEntry) {
        val entry   = activeHistoryEntry ?: return
        val updated = transform(entry)
        activeHistoryEntry = updated
        historyStore.save(updated)
        publishHistory(updated)
    }

    private fun updateHistoryLog()                     = updateHistory { it.copy(log = mutableUiState.value.log) }
    private fun updateHistoryProfile(profileId: String) = updateHistory { it.copy(profileId = profileId) }

    private fun finishHistory(result: InstallRunResult) = updateHistory {
        it.copy(completedAtMillis = System.currentTimeMillis(), result = result, log = mutableUiState.value.log)
    }

    private fun publishHistory(entry: InstallHistoryEntry) {
        mutableHistory.value = buildList {
            add(entry)
            addAll(mutableHistory.value.filter { it.id != entry.id })
        }
    }

    // -----------------------------------------------------------------------
    // State helpers
    // -----------------------------------------------------------------------

    private fun setPhase(phase: InstallPhase, message: String, progress: Float? = null) {
        val pct = progress?.let { (it.coerceIn(0f, 1f) * 100f).roundToInt() }
        mutableUiState.value = mutableUiState.value.copy(
            phase         = phase,
            statusMessage = message,
            progress      = progress?.coerceIn(0f, 1f),
            progressLabel = pct?.let { "$it %" },
        )
        updateHistoryLog()
    }

    private fun appendLog(line: String) {
        val current = mutableUiState.value.log
        val lines   = current.lines()
        val trimmed = if (lines.size >= MAX_LOG_LINES) lines.takeLast(MAX_LOG_LINES - 1).joinToString("\n") else current
        mutableUiState.value = mutableUiState.value.copy(
            log = if (trimmed.isEmpty()) line else "$trimmed\n$line",
        )
        updateHistoryLog()
    }

    private fun debugLog(line: String) {
        Log.d(TAG, line)
        if (mutableDebugLog.value) appendLog(line)
    }

    private fun error(message: String): Nothing = throw IllegalStateException(message)

    private fun shouldRebootUserspace(): Boolean =
        activeRunRebootUserspace == true ||
            (activeRunRebootUserspace == null && AppPreferences.rebootAfterInstall(app))

    // -----------------------------------------------------------------------
    // Native helpers
    // -----------------------------------------------------------------------

    private fun helperFile(): File =
        File(app.applicationInfo.nativeLibraryDir, "libcve43499root.so").also {
            require(it.exists()) { app.getString(R.string.error_helper_unavailable) }
        }

    private fun nativeHelperFile() = File(app.applicationInfo.nativeLibraryDir, "libcve43499root.so")

    private fun shizukuEnabled(): Boolean = activeRunShizuku ?: AppPreferences.shizukuMode(app)

    private fun shizukuStage(source: File, target: String, mode: String): File {
        val result = ShizukuController.exec(arrayOf("cp", source.absolutePath, target)).waitFor()
        require(result == 0) {
            app.getString(R.string.error_shizuku_stage, source.name, "cp → $target exited $result")
        }
        ShizukuController.exec(arrayOf("chmod", mode, target)).waitFor()
        return File(target)
    }

    private suspend fun runHelper(vararg arguments: String): CommandResult =
        withContext(Dispatchers.IO) {
            val helper = helperFile()
            debugLog("[DBG] runHelper: ${listOf(helper.name) + arguments.toList()}")
            val process = if (shizukuEnabled()) {
                ShizukuController.exec(arrayOf(helper.absolutePath) + arguments)
            } else {
                ProcessBuilder(listOf(helper.absolutePath) + arguments)
                    .redirectErrorStream(true)
                    .start()
            }
            val captured  = StringBuilder()
            val startedAt = SystemClock.elapsedRealtime()
            try {
                while (process.isAlive) {
                    drainProcessOutput(process, captured)
                    require(SystemClock.elapsedRealtime() - startedAt < HELPER_TIMEOUT_MILLIS) {
                        app.getString(
                            R.string.error_helper_timeout,
                            captured.toString().trim().takeIf(String::isNotBlank)?.let { ": $it" } ?: "",
                        )
                    }
                    delay(HELPER_POLL_INTERVAL)
                }
                drainProcessOutput(process, captured)
                val output = stripAnsi(captured.toString().trim())
                val code   = process.waitFor()
                if (code != 0) {
                    // Collect full diagnostics and attach to the debug log.
                    val diag = ProcessDiagnostics.collect(process, captured, ::stripAnsi)
                    debugLog("[DBG] runHelper exit=$code\n${diag.format()}")
                }
                CommandResult(code, output)
            } finally {
                killProcess(process)
            }
        }

    private fun shellQuote(value: String) = "'${value.replace("'", "'\\''")}'"

    // -----------------------------------------------------------------------
    // Public misc
    // -----------------------------------------------------------------------

    fun refresh()            { mutableHistory.value = historyStore.closeInterruptedRuns() }
    fun loadTargetCatalog()  = startDiscovery()

    fun deleteHistoryEntries(ids: Set<String>) {
        ids.forEach { historyStore.delete(it) }
        mutableHistory.value = historyStore.load()
    }

    fun cancel() {
        installJob?.cancel()
        installJob = null
        if (mutableUiState.value.busy) {
            mutableUiState.value = mutableUiState.value.copy(
                phase         = InstallPhase.Failed,
                statusMessage = "Installation cancelled",
                progress      = null,
                progressLabel = null,
            )
        }
    }

    fun clearError() {
        if (mutableUiState.value.phase == InstallPhase.Failed) {
            mutableUiState.value = mutableUiState.value.copy(
                phase         = InstallPhase.Ready,
                statusMessage = "",
                progress      = null,
                progressLabel = null,
            )
        }
    }

    private fun stripAnsi(text: String): String =
        text.replace(Regex("\u001B\\[[0-9;]*[mGKHF]"), "")

    private fun File.readTextIfPresent(): String =
        if (exists()) runCatching { readText(Charsets.UTF_8) }.getOrDefault("") else ""

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    companion object {
        private const val TAG = "InstallViewModel"

        internal const val PAYLOAD_EXPLOIT  = "exploit"
        internal const val PAYLOAD_KERNELSU = "kernelsu"
        internal const val LOCAL_PROFILE_ID = "__local__"

        private const val INSTALL_RECEIPT   = "install_receipt"
        private const val RECEIPT_BOOT_TOKEN = "boot_token"
        private const val RECEIPT_VERIFIED  = "verified"

        private const val P0_OFFSET_PREFS   = "p0_offset_cache"
        private const val P0_OFFSET_ENV     = "CVE43499_P0_OFFSET"
        private val P0_OFFSET_PATTERN       = Regex("""p0_offset=([0-9a-fx]+)""")

        private const val SHIZUKU_LOG_PATH      = "/data/local/tmp/ksu-exploit.log"
        private const val SHIZUKU_HELPER_PATH   = "/data/local/tmp/ksu-helper"
        private const val SHIZUKU_PAYLOAD_PATH  = "/data/local/tmp/ksu-payload"
        private const val SHIZUKU_KSUD_PATH     = "/data/local/tmp/ksud-s25u-kdp"
        private const val SHIZUKU_KSUD_STAGE_PATH = "/data/local/tmp/.ksud-stage"

        private const val EXPLOIT_ATTEMPTS          = "6"
        private const val P0_ATTEMPT_TIMEOUT_SEC    = "18"
        private const val EXPLOIT_ATTEMPT_TIMEOUT_SEC = "90"

        private val EXPLOIT_STALL_MILLIS  : Long = 30.seconds.inWholeMilliseconds
        private val EXPLOIT_TOTAL_MILLIS  : Long = 10.minutes.inWholeMilliseconds
        private val HELPER_TIMEOUT_MILLIS : Long = 60.seconds.inWholeMilliseconds
        private val LOG_POLL_INTERVAL     : Long = 500.milliseconds.inWholeMilliseconds
        private val HELPER_POLL_INTERVAL  : Long = 100.milliseconds.inWholeMilliseconds

        private const val MAX_LOG_LINES    = 200
        private const val MAX_DRAIN_BYTES  = 65_536
        private const val EXPLOIT_PULSE_STEP = 0.002f
    }
}

private class ExploitStallException(message: String) : Exception(message)
