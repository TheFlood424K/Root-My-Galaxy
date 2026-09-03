package dev.busung.s25uroot

import android.content.ContentValues
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.busung.s25uroot.ui.theme.RootMyGalaxyTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------
// Activity
// ---------------------------------------------------------------------------

class MainActivity : ComponentActivity() {

    private val viewModel: InstallViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)

        setContent {
            val accentColor by viewModel.accentColor.collectAsStateWithLifecycle()
            val themeMode  by viewModel.themeMode.collectAsStateWithLifecycle()

            RootMyGalaxyTheme(accentColor = accentColor, themeMode = themeMode) {
                MainScreen(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val profileId = intent?.getStringExtra(EXTRA_PROFILE_ID) ?: return
        viewModel.setPendingInstallRequest(profileId)
    }

    companion object {
        const val EXTRA_PROFILE_ID = "dev.busung.s25uroot.PROFILE_ID"
    }
}

// ---------------------------------------------------------------------------
// Root composable
// ---------------------------------------------------------------------------

@Composable
fun MainScreen(viewModel: InstallViewModel) {
    val navController = rememberNavController()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val installHistory by viewModel.installHistory.collectAsStateWithLifecycle()

    val pendingRequest = uiState.pendingInstallRequest
    if (pendingRequest != null) {
        val profile = uiState.selectedProfile
        InstallConfirmDialog(
            profileName = profile?.displayName ?: pendingRequest,
            source = profile?.exploit?.url ?: pendingRequest,
            onConfirm = {
                viewModel.consumePendingInstallRequest()
                val target = profile ?: return@InstallConfirmDialog
                viewModel.startRoot(target)
            },
            onDismiss = { viewModel.consumePendingInstallRequest() },
        )
    }

    Scaffold(
        bottomBar = { BottomNavBar(navController = navController) },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "overview",
            modifier = Modifier.padding(innerPadding),
        ) {
            composable("overview") {
                OverviewScreen(
                    uiState = uiState,
                    onStartRoot = { viewModel.startRoot() },
                    onStopSession = { viewModel.stopSession() },
                    onSelectProfile = { viewModel.selectProfile(it) },
                    onLoadCatalog = { viewModel.loadTargetCatalog() },
                    catalogState = viewModel.targetCatalog.collectAsStateWithLifecycle().value,
                )
            }
            composable("history") {
                HistoryScreen(
                    history = installHistory,
                    onDelete = { viewModel.deleteHistoryEntry(it.id) },
                    onDeleteAll = { viewModel.deleteAllHistoryEntries() },
                )
            }
            composable("settings") {
                val advancedMode  by viewModel.advancedMode.collectAsStateWithLifecycle()
                val shizukuMode   by viewModel.shizukuMode.collectAsStateWithLifecycle()
                val autoReroot    by viewModel.autoReroot.collectAsStateWithLifecycle()
                val localPayload  by viewModel.localPayloadMode.collectAsStateWithLifecycle()
                val accentColor   by viewModel.accentColor.collectAsStateWithLifecycle()
                val themeMode     by viewModel.themeMode.collectAsStateWithLifecycle()
                SettingsScreen(
                    advancedMode = advancedMode,
                    onAdvancedModeChange = { viewModel.setAdvancedMode(it) },
                    shizukuMode = shizukuMode,
                    onShizukuModeChange = { viewModel.setShizukuMode(it) },
                    autoReroot = autoReroot,
                    onAutoRerootChange = { viewModel.setAutoReroot(it) },
                    localPayloadMode = localPayload,
                    onLocalPayloadModeChange = { viewModel.setLocalPayloadMode(it) },
                    accentColor = accentColor,
                    onAccentColorChange = { viewModel.setAccentColor(it) },
                    themeMode = themeMode,
                    onThemeModeChange = { viewModel.setThemeMode(it) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Bottom navigation
// ---------------------------------------------------------------------------

@Composable
fun BottomNavBar(navController: NavHostController) {
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination?.route

    NavigationBar {
        NavigationBarItem(
            selected = current == "overview",
            onClick = { navController.navigate("overview") { launchSingleTop = true } },
            icon = { Icon(Icons.Rounded.Home, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_overview)) },
        )
        NavigationBarItem(
            selected = current == "history",
            onClick = { navController.navigate("history") { launchSingleTop = true } },
            icon = { Icon(Icons.Rounded.History, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_history)) },
        )
        NavigationBarItem(
            selected = current == "settings",
            onClick = { navController.navigate("settings") { launchSingleTop = true } },
            icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_settings)) },
        )
    }
}

// ---------------------------------------------------------------------------
// Log export helper
// ---------------------------------------------------------------------------

/**
 * Writes [log] to Downloads/RootMyGalaxy/ via MediaStore (API 29+), then
 * fires a share Intent so the user can send it anywhere.  A Toast confirms
 * the save path or reports failure.
 *
 * @param tag   Short label used in the filename, e.g. "home" or "history"
 */
fun exportLog(context: android.content.Context, log: String, tag: String = "log") {
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val fileName  = "rmg_${tag}_$timestamp.txt"

    runCatching {
        // Write to Downloads/RootMyGalaxy/<fileName> via MediaStore
        val resolver = context.contentResolver
        val values   = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE,    "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/RootMyGalaxy")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert returned null")

        resolver.openOutputStream(uri)?.use { it.write(log.toByteArray()) }
            ?: error("openOutputStream returned null")

        // Share intent so the user can also forward it immediately
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type        = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(shareIntent,
                context.getString(R.string.export_log_share_title))
        )

        Toast.makeText(
            context,
            context.getString(R.string.export_log_saved,
                "Downloads/RootMyGalaxy/$fileName"),
            Toast.LENGTH_LONG,
        ).show()
    }.onFailure {
        Toast.makeText(
            context,
            context.getString(R.string.export_log_failed),
            Toast.LENGTH_SHORT,
        ).show()
    }
}

/**
 * Compact icon button that calls [exportLog].  Placed inline in log panel
 * headers and history entry footers.
 */
@Composable
fun ExportLogButton(log: String, tag: String = "log", modifier: Modifier = Modifier) {
    val context = LocalContext.current
    IconButton(
        onClick = { exportLog(context, log, tag) },
        modifier = modifier,
    ) {
        Icon(
            imageVector  = Icons.Rounded.FileDownload,
            contentDescription = stringResource(R.string.export_log),
            modifier     = Modifier.size(18.dp),
            tint         = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// Overview screen
//
// Layout:
//   Column (fillMaxSize)
//   ├── scrollable top section (weight=0, intrinsic height)
//   │     device card, ksu card, profile picker, steps card
//   ├── sticky progress/action card (weight=0, always visible while busy)
//   └── collapsible log panel (weight=1f when expanded, else 0)
//
// This guarantees the progress bar is never pushed off-screen by log output.
// ---------------------------------------------------------------------------

@Composable
fun OverviewScreen(
    uiState: InstallUiState,
    onStartRoot: () -> Unit,
    onStopSession: () -> Unit,
    onSelectProfile: (TargetProfile) -> Unit,
    onLoadCatalog: () -> Unit,
    catalogState: TargetCatalogUiState,
) {
    var showProfileSheet by remember { mutableStateOf(false) }

    // Auto-expand log on failure so the user sees the error immediately.
    // Collapse again whenever a new session starts (busy goes true).
    var logExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.busy) {
        if (uiState.busy) logExpanded = false
    }
    LaunchedEffect(uiState.phase) {
        if (uiState.phase == InstallPhase.Failed) logExpanded = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {

        // ── Scrollable info cards (shrink-to-fit, never takes more than needed) ──
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            uiState.device?.let { device ->
                DeviceCard(
                    device = device,
                    androidVersion = uiState.androidVersion ?: "",
                    securityPatch = uiState.securityPatch ?: "",
                )
            }

            KernelSuCard(isRooted = uiState.isRooted, kernelSuVersion = uiState.kernelSuVersion)

            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onLoadCatalog(); showProfileSheet = true },
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PhoneAndroid,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.select_device_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = uiState.selectedProfile?.displayName
                                ?: stringResource(R.string.select_device_description),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        uiState.selectedProfile?.let {
                            Text(
                                text = it.supportedModels,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            StepsCard(phase = uiState.phase)

            Spacer(Modifier.height(4.dp))
        }

        // ── Sticky progress + action card (always in viewport) ────────────
        StickyProgressCard(
            uiState = uiState,
            onStartRoot = onStartRoot,
            onStopSession = onStopSession,
        )

        // ── Log panel — fills remaining vertical space, scrollable inside ─
        if (uiState.log.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            LogPanel(
                log = uiState.log,
                expanded = logExpanded,
                onToggle = { logExpanded = !logExpanded },
                showExport = uiState.phase == InstallPhase.Failed,
                modifier = if (logExpanded) Modifier.weight(1f) else Modifier,
            )
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showProfileSheet) {
        ProfileSelectionSheet(
            catalogState = catalogState,
            onSelect = { profile ->
                onSelectProfile(profile)
                showProfileSheet = false
            },
            onDismiss = { showProfileSheet = false },
        )
    }
}

// ---------------------------------------------------------------------------
// Sticky progress + action card
// ---------------------------------------------------------------------------

@Composable
fun StickyProgressCard(
    uiState: InstallUiState,
    onStartRoot: () -> Unit,
    onStopSession: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                uiState.phase == InstallPhase.Done   -> MaterialTheme.colorScheme.primaryContainer
                uiState.phase == InstallPhase.Failed -> MaterialTheme.colorScheme.errorContainer
                uiState.busy                         -> MaterialTheme.colorScheme.secondaryContainer
                else                                 -> MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Status row: message + percentage
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = uiState.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        uiState.phase == InstallPhase.Done   -> MaterialTheme.colorScheme.onPrimaryContainer
                        uiState.phase == InstallPhase.Failed -> MaterialTheme.colorScheme.onErrorContainer
                        else                                 -> MaterialTheme.colorScheme.onSecondaryContainer
                    },
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                uiState.progress?.let { p ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${(p * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            // Progress bar — always rendered while busy or done
            when {
                uiState.progress != null -> LinearProgressIndicator(
                    progress = { uiState.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.15f),
                )
                uiState.busy -> LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.15f),
                )
                else -> LinearProgressIndicator(
                    progress = { 0f },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                )
            }

            // Action button
            when {
                uiState.phase == InstallPhase.Done -> FilledTonalButton(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                    enabled = false,
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_done))
                }
                uiState.busy -> OutlinedButton(
                    onClick = onStopSession,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.action_cancel)) }
                else -> Button(
                    onClick = onStartRoot,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.install_tap_start)) }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Log panel — fixed-height, internally scrollable, collapsible
// ---------------------------------------------------------------------------

@Composable
fun LogPanel(
    log: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    showExport: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val lines = remember(log) { log.lines() }

    // Auto-scroll to the latest line when new content arrives and panel is expanded
    LaunchedEffect(lines.size, expanded) {
        if (expanded && lines.isNotEmpty()) {
            listState.animateScrollToItem(lines.lastIndex)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Column {
            // Header row — always visible, tap to toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.history_log),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "(${lines.size} lines)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Export button — only visible on exploit failure
                    if (showExport) {
                        ExportLogButton(log = log, tag = "home")
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (expanded) "Collapse log" else "Expand log",
                        modifier = Modifier.size(18.dp).padding(end = 8.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Log body — only rendered when expanded, scrolls internally
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    items(lines) { line ->
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                line.startsWith("[!]") || line.startsWith("error", ignoreCase = true) ->
                                    MaterialTheme.colorScheme.error
                                line.startsWith("[+]") || line.startsWith("[*]") ->
                                    MaterialTheme.colorScheme.primary
                                else ->
                                    MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------------

@Composable
fun DeviceCard(device: DeviceSnapshot, androidVersion: String, securityPatch: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Rounded.PhoneAndroid,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    stringResource(R.string.device),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            InfoRow(label = stringResource(R.string.firmware), value = device.model)
            InfoRow(label = stringResource(R.string.kernel), value = device.kernelVersion)
            if (androidVersion.isNotBlank()) {
                InfoRow(label = "Android", value = androidVersion)
            }
            if (securityPatch.isNotBlank()) {
                InfoRow(label = "Security Patch", value = securityPatch)
            }
            InfoRow(label = stringResource(R.string.system_abi), value = device.abi)
        }
    }
}

@Composable
fun KernelSuCard(isRooted: Boolean, kernelSuVersion: String?) {
    val containerColor by animateColorAsState(
        targetValue = if (isRooted)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.errorContainer,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "ksuCardColor",
    )
    val contentColor = if (isRooted)
        MaterialTheme.colorScheme.onPrimaryContainer
    else
        MaterialTheme.colorScheme.onErrorContainer

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = if (isRooted) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(28.dp),
            )
            Column {
                Text(
                    stringResource(R.string.kernelsu_card_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                )
                Text(
                    text = when {
                        isRooted && kernelSuVersion != null ->
                            stringResource(R.string.status_ksu_active_format, kernelSuVersion)
                        isRooted -> stringResource(R.string.phase_installed)
                        else -> stringResource(R.string.status_not_installed)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
fun StepsCard(phase: InstallPhase) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.how_it_works),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            StepRow(
                icon = Icons.Rounded.Search,
                title = stringResource(R.string.step_support_title),
                detail = stringResource(R.string.step_support_detail),
                active = phase == InstallPhase.Checking,
                done = phase.ordinal > InstallPhase.Checking.ordinal,
            )
            StepRow(
                icon = Icons.Rounded.CloudDownload,
                title = stringResource(R.string.step_download_title),
                detail = stringResource(R.string.step_download_detail),
                active = phase == InstallPhase.Downloading,
                done = phase.ordinal > InstallPhase.Downloading.ordinal,
            )
            StepRow(
                icon = Icons.Rounded.Security,
                title = stringResource(R.string.step_exploit_title),
                detail = stringResource(R.string.step_exploit_detail),
                active = phase == InstallPhase.Exploiting,
                done = phase.ordinal > InstallPhase.Exploiting.ordinal,
            )
            StepRow(
                icon = Icons.Rounded.Memory,
                title = stringResource(R.string.step_ksu_title),
                detail = stringResource(R.string.step_ksu_detail),
                active = phase == InstallPhase.LoadingKernelSu,
                done = phase == InstallPhase.Installed || phase == InstallPhase.Done,
            )
        }
    }
}

@Composable
fun StepRow(
    icon: ImageVector,
    title: String,
    detail: String,
    active: Boolean,
    done: Boolean,
) {
    val iconTint by animateColorAsState(
        targetValue = when {
            done   -> MaterialTheme.colorScheme.primary
            active -> MaterialTheme.colorScheme.tertiary
            else   -> MaterialTheme.colorScheme.outlineVariant
        },
        label = "stepIconTint",
    )
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = when {
                done   -> Icons.Rounded.CheckCircle
                active -> Icons.Rounded.RadioButtonChecked
                else   -> Icons.Rounded.RadioButtonUnchecked
            },
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(20.dp).padding(top = 2.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                color = if (active) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = if (done) 1f else 0.6f),
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 80.dp, max = 120.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------
// Profile selection sheet
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSelectionSheet(
    catalogState: TargetCatalogUiState,
    onSelect: (TargetProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 32.dp)) {
            Text(
                text = stringResource(R.string.select_device_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider()
            when {
                catalogState.loading -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }
                catalogState.error != null -> {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = catalogState.error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                catalogState.profiles.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.no_matching_devices),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    LazyColumn {
                        items(catalogState.profiles) { profile ->
                            ListItem(
                                modifier = Modifier.clickable { onSelect(profile) },
                                leadingContent = {
                                    Icon(
                                        Icons.Rounded.PhoneAndroid,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                headlineContent = { Text(profile.displayName) },
                                supportingContent = {
                                    Text(
                                        profile.supportedModels,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Install confirm dialog
// ---------------------------------------------------------------------------

@Composable
fun InstallConfirmDialog(
    profileName: String,
    source: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_install_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.dialog_install_message, profileName))
                Text(
                    text = source,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text(stringResource(R.string.dialog_install_confirm)) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

// ---------------------------------------------------------------------------
// History screen
// ---------------------------------------------------------------------------

@Composable
fun HistoryScreen(
    history: List<InstallHistoryEntry>,
    onDelete: (InstallHistoryEntry) -> Unit,
    onDeleteAll: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.nav_history),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (history.isNotEmpty()) {
                TextButton(onClick = onDeleteAll) {
                    Text(stringResource(R.string.history_delete_all))
                }
            }
        }

        if (history.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.history_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(history, key = { it.id }) { entry ->
                    HistoryEntryCard(entry = entry, onDelete = { onDelete(entry) })
                }
            }
        }
    }
}

@Composable
fun HistoryEntryCard(entry: InstallHistoryEntry, onDelete: () -> Unit) {
    var logExpanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.profileName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = entry.timestamp,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                text = if (entry.success)
                                    stringResource(R.string.history_success)
                                else
                                    stringResource(R.string.history_failed),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (entry.success)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.errorContainer,
                            labelColor = if (entry.success)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    )
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = stringResource(R.string.history_delete),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            if (entry.log.isNotBlank()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { logExpanded = !logExpanded }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Terminal,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.history_log),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ExportLogButton(log = entry.log, tag = "history_${entry.id}")
                        Icon(
                            imageVector = if (logExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                AnimatedVisibility(
                    visible = logExpanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    val lines = remember(entry.log) { entry.log.lines() }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        lines.forEach { line ->
                            Text(
                                text = line,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                color = when {
                                    line.startsWith("[!]") || line.startsWith("error", ignoreCase = true) ->
                                        MaterialTheme.colorScheme.error
                                    line.startsWith("[+]") || line.startsWith("[*]") ->
                                        MaterialTheme.colorScheme.primary
                                    else ->
                                        MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Settings screen
// ---------------------------------------------------------------------------

@Composable
fun SettingsScreen(
    advancedMode: Boolean,
    onAdvancedModeChange: (Boolean) -> Unit,
    shizukuMode: Boolean,
    onShizukuModeChange: (Boolean) -> Unit,
    autoReroot: Boolean,
    onAutoRerootChange: (Boolean) -> Unit,
    localPayloadMode: Boolean,
    onLocalPayloadModeChange: (Boolean) -> Unit,
    accentColor: AccentColor,
    onAccentColorChange: (AccentColor) -> Unit,
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            SectionHeader(title = stringResource(R.string.settings_general), icon = Icons.Rounded.Tune)
        }
        item {
            SwitchPreference(
                title = stringResource(R.string.advanced_mode),
                subtitle = stringResource(R.string.advanced_mode_description),
                checked = advancedMode,
                onCheckedChange = onAdvancedModeChange,
            )
        }
        item {
            SwitchPreference(
                title = stringResource(R.string.shizuku_mode),
                subtitle = stringResource(R.string.shizuku_mode_description),
                checked = shizukuMode,
                onCheckedChange = onShizukuModeChange,
            )
        }
        item {
            SwitchPreference(
                title = stringResource(R.string.settings_reboot_after_install),
                subtitle = stringResource(R.string.settings_reboot_after_install_description),
                checked = autoReroot,
                onCheckedChange = onAutoRerootChange,
            )
        }
        item {
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }
        item {
            SectionHeader(title = stringResource(R.string.local_payload_card_title), icon = Icons.Rounded.FolderOpen)
        }
        item {
            SwitchPreference(
                title = stringResource(R.string.local_payload_mode),
                subtitle = stringResource(R.string.local_payload_mode_description),
                checked = localPayloadMode,
                onCheckedChange = onLocalPayloadModeChange,
            )
        }
        item {
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }
        item {
            SectionHeader(title = stringResource(R.string.appearance), icon = Icons.Rounded.Palette)
        }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.material_color),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(R.string.material_color_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AccentColorPicker(
                    selected = accentColor,
                    onSelect = onAccentColorChange,
                )
            }
        }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.appearance),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                ThemePicker(
                    selected = themeMode,
                    onSelect = onThemeModeChange,
                )
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, icon: ImageVector) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun SwitchPreference(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

// ---------------------------------------------------------------------------
// AccentColorPicker — horizontal scrolling swatch row
// ---------------------------------------------------------------------------

@Composable
fun AccentColorPicker(
    selected: AccentColor,
    onSelect: (AccentColor) -> Unit,
    modifier: Modifier = Modifier,
) {
    val swatchColor: @Composable (AccentColor) -> Color = { accent ->
        when (accent) {
            AccentColor.Dynamic    -> MaterialTheme.colorScheme.primary
            AccentColor.Blue       -> Color(0xFF1976D2)
            AccentColor.Violet     -> Color(0xFF7E57C2)
            AccentColor.Green      -> Color(0xFF388E3C)
            AccentColor.Orange     -> Color(0xFFF57C00)
            AccentColor.Purple     -> Color(0xFF8E24AA)
            AccentColor.Red        -> Color(0xFFD32F2F)
            AccentColor.Pink       -> Color(0xFFE91E63)
            AccentColor.Teal       -> Color(0xFF00796B)
            AccentColor.Yellow     -> Color(0xFFF9A825)
            AccentColor.Monochrome -> MaterialTheme.colorScheme.onSurface
        }
    }

    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
    ) {
        items(AccentColor.entries) { accent ->
            val color = swatchColor(accent)
            val isSelected = accent == selected
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .then(
                        if (isSelected) Modifier.border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.onSurface,
                            shape = CircleShape,
                        ) else Modifier
                    )
                    .background(color, CircleShape)
                    .clickable { onSelect(accent) },
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = accent.name,
                        tint = if (accent == AccentColor.Monochrome)
                            MaterialTheme.colorScheme.surface
                        else
                            Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// ThemePicker — segmented button: System / Light / Dark
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemePicker(
    selected: AppThemeMode,
    onSelect: (AppThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = AppThemeMode.entries
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = mode == selected,
                onClick  = { onSelect(mode) },
                shape    = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = {
                    Text(
                        text = when (mode) {
                            AppThemeMode.System -> stringResource(R.string.theme_system)
                            AppThemeMode.Light  -> stringResource(R.string.theme_light)
                            AppThemeMode.Dark   -> stringResource(R.string.theme_dark)
                        },
                    )
                },
                icon = {
                    SegmentedButtonDefaults.Icon(active = mode == selected) {
                        Icon(
                            imageVector = when (mode) {
                                AppThemeMode.System -> Icons.Rounded.BrightnessAuto
                                AppThemeMode.Light  -> Icons.Rounded.LightMode
                                AppThemeMode.Dark   -> Icons.Rounded.DarkMode
                            },
                            contentDescription = null,
                            modifier = Modifier.size(SegmentedButtonDefaults.IconSize),
                        )
                    }
                },
            )
        }
    }
}
