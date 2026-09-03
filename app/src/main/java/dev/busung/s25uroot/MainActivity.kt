package dev.busung.s25uroot

import android.content.Intent
import android.os.Bundle
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.busung.s25uroot.ui.theme.RootMyGalaxyTheme

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
// Overview screen
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── Device info ───────────────────────────────────────────────────
        uiState.device?.let { device ->
            DeviceCard(
                device = device,
                androidVersion = uiState.androidVersion ?: "",
                securityPatch = uiState.securityPatch ?: "",
            )
        }

        // ── KernelSU status ───────────────────────────────────────────────
        KernelSuCard(isRooted = uiState.isRooted, kernelSuVersion = uiState.kernelSuVersion)

        // ── Profile picker ────────────────────────────────────────────────
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

        // ── Steps ─────────────────────────────────────────────────────────
        StepsCard(phase = uiState.phase)

        // ── Progress card (only shown while busy) ─────────────────────────
        AnimatedVisibility(
            visible = uiState.busy,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = uiState.statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        uiState.progress?.let { p ->
                            Text(
                                text = "${(p * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                    if (uiState.progress != null) {
                        LinearProgressIndicator(
                            progress = { uiState.progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f),
                        )
                    }
                }
            }
        }

        // ── Action button ─────────────────────────────────────────────────
        when {
            uiState.phase == InstallPhase.Done -> {
                FilledTonalButton(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                    enabled = false,
                ) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_done))
                }
            }
            uiState.busy -> {
                OutlinedButton(
                    onClick = onStopSession,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.action_cancel)) }
            }
            else -> {
                Button(
                    onClick = onStartRoot,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.install_tap_start)) }
            }
        }

        // ── Log output ────────────────────────────────────────────────────
        if (uiState.log.isNotBlank()) {
            LogCard(log = uiState.log)
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
                InfoRow(label = stringResource(R.string.security_patch), value = securityPatch)
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
                            stringResource(R.string.version_format, kernelSuVersion)
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
fun LogCard(log: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.history_log),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = log,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
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
                                headlineContent = {
                                    Text(
                                        profile.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        profile.supportedModels,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        Icons.Rounded.PhoneAndroid,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                modifier = Modifier.clickable { onSelect(profile) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Install confirmation dialog
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
        icon = { Icon(Icons.Rounded.Security, contentDescription = null) },
        title = { Text(stringResource(R.string.install_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.install_confirm_body))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.install_confirm_source, source),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
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
        // Header row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.history_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (history.isNotEmpty()) {
                TextButton(onClick = onDeleteAll) {
                    Icon(
                        Icons.Rounded.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.history_delete_selected))
                }
            }
        }
        HorizontalDivider()

        if (history.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Rounded.History,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Text(
                        stringResource(R.string.history_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        stringResource(R.string.history_empty_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(history) { entry ->
                    HistoryEntryItem(entry = entry, onDelete = { onDelete(entry) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun HistoryEntryItem(entry: InstallHistoryEntry, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val resultColor = when (entry.result) {
                InstallRunResult.Succeeded -> MaterialTheme.colorScheme.primary
                InstallRunResult.Failed -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Status dot
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(resultColor)
                )
                Column {
                    Text(
                        text = entry.profileId ?: stringResource(R.string.history_payload),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val resultLabel = when (entry.result) {
                        InstallRunResult.Succeeded -> stringResource(R.string.history_succeeded)
                        InstallRunResult.Failed    -> stringResource(R.string.history_failed)
                        InstallRunResult.Running   -> stringResource(R.string.history_running)
                        null -> if (entry.completedAtMillis == null)
                            stringResource(R.string.history_running)
                        else
                            stringResource(R.string.history_completed)
                    }
                    Text(
                        text = resultLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = resultColor,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.history_delete),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = expanded && entry.log.isNotBlank(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.history_log),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = entry.log,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // Title
        Text(
            stringResource(R.string.settings),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
        )
        HorizontalDivider()

        // Advanced section
        SectionHeader(title = stringResource(R.string.advanced), icon = Icons.Rounded.Tune)
        SwitchPreference(
            title = stringResource(R.string.advanced_mode),
            subtitle = stringResource(R.string.advanced_mode_description),
            checked = advancedMode,
            onCheckedChange = onAdvancedModeChange,
        )
        SwitchPreference(
            title = stringResource(R.string.shizuku_mode),
            subtitle = stringResource(R.string.shizuku_mode_description),
            checked = shizukuMode,
            onCheckedChange = onShizukuModeChange,
        )
        SwitchPreference(
            title = stringResource(R.string.settings_reboot_after_install),
            subtitle = stringResource(R.string.settings_reboot_after_install_description),
            checked = autoReroot,
            onCheckedChange = onAutoRerootChange,
        )

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        // Local payload section
        SectionHeader(title = stringResource(R.string.local_payload_card_title), icon = Icons.Rounded.FolderOpen)
        SwitchPreference(
            title = stringResource(R.string.local_payload_mode),
            subtitle = stringResource(R.string.local_payload_mode_description),
            checked = localPayloadMode,
            onCheckedChange = onLocalPayloadModeChange,
        )

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        // Appearance section
        SectionHeader(title = stringResource(R.string.appearance), icon = Icons.Rounded.Palette)

        // Color picker
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

        // Theme picker
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.theme_title),
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
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
fun AccentColorPicker(selected: AccentColor, onSelect: (AccentColor) -> Unit) {
    val colorMap: List<Pair<AccentColor, Color?>> = listOf(
        AccentColor.Dynamic    to null,
        AccentColor.Blue       to Color(0xFF415F91),
        AccentColor.Violet     to Color(0xFF6750A4),
        AccentColor.Green      to Color(0xFF356A35),
        AccentColor.Orange     to Color(0xFF8B4F23),
        AccentColor.Purple     to Color(0xFF7B3FA0),
        AccentColor.Red        to Color(0xFFB3261E),
        AccentColor.Pink       to Color(0xFF9C27B0),
        AccentColor.Teal       to Color(0xFF00695C),
        AccentColor.Yellow     to Color(0xFFF9A825),
        AccentColor.Monochrome to Color(0xFF757575),
    )
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 2.dp),
    ) {
        items(colorMap) { (accent, swatch) ->
            val isSelected = selected == accent
            val borderColor = if (isSelected)
                MaterialTheme.colorScheme.primary
            else
                Color.Transparent
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .border(2.dp, borderColor, CircleShape)
                    .background(
                        if (swatch != null) swatch
                        else MaterialTheme.colorScheme.primaryContainer
                    )
                    .clickable { onSelect(accent) },
                contentAlignment = Alignment.Center,
            ) {
                if (swatch == null) {
                    // "Dynamic" chip — show small auto icon
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = accent.name,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp),
                    )
                } else if (isSelected) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun ThemePicker(selected: AppThemeMode, onSelect: (AppThemeMode) -> Unit) {
    val options = listOf(
        AppThemeMode.System to stringResource(R.string.theme_system),
        AppThemeMode.Light  to stringResource(R.string.theme_light),
        AppThemeMode.Dark   to stringResource(R.string.theme_dark),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (mode, label) ->
            FilterChip(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                label = { Text(label) },
            )
        }
    }
}
