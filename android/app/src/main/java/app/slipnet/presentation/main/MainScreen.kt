package app.slipnet.presentation.main

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.unit.DpOffset
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.surfaceColorAtElevation
import app.slipnet.BuildConfig
import app.slipnet.domain.model.TunnelType
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Stop
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import app.slipnet.domain.model.ConnectionState
import app.slipnet.domain.model.PingResult
import app.slipnet.domain.model.ProfileChain
import app.slipnet.domain.model.ServerProfile
import app.slipnet.domain.model.TrafficStats
import app.slipnet.presentation.common.components.AboutDialogContent
import app.slipnet.presentation.common.components.ProfileListItem
import app.slipnet.presentation.common.components.QrCodeDialog
import app.slipnet.presentation.common.icons.TorIcon
import app.slipnet.presentation.common.icons.VlessIcon
import app.slipnet.presentation.home.DebugLogSheet
import app.slipnet.presentation.scanner.QrScannerActivity
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Timer
import app.slipnet.presentation.theme.ConnectedGreen
import app.slipnet.presentation.theme.ConnectingOrange
import app.slipnet.presentation.theme.DisconnectedRed
import app.slipnet.presentation.theme.ColorFabActive
import app.slipnet.presentation.theme.ColorFabInactiveDark
import app.slipnet.presentation.theme.ColorFabInactiveLight
import app.slipnet.presentation.theme.ColorConfigType
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private enum class OverflowMenuPage {
    MAIN,
    DELETE,
    EXPORT,
    PING,
    SORT
}

private enum class AddSubmenuType {
    PROTOCOLS
}

private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToAddProfile: (tunnelType: String) -> Unit,
    onNavigateToEditProfile: (Long) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToAddChain: () -> Unit = {},
    onNavigateToEditChain: (Long) -> Unit = {},
    onNavigateToTerminal: () -> Unit = {},
    onNavigateToAppSelector: () -> Unit = {},
    onNavigateToDnsScanner: () -> Unit = {},
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val activity = context.findActivity()

    // Handle deep link (slipnet:// URI from external QR scanner or link)
    val mainActivity = activity as? app.slipnet.presentation.MainActivity
    val deepLinkUri by mainActivity?.deepLinkUri?.collectAsState() ?: remember { mutableStateOf(null) }
    LaunchedEffect(deepLinkUri) {
        deepLinkUri?.let { uri ->
            viewModel.parseAndDirectImport(uri) { success, msg ->
                scope.launch { snackbarHostState.showSnackbar(msg) }
            }
            mainActivity?.consumeDeepLink()
        }
    }

    // VPN permission flow
    var pendingConnect by remember { mutableStateOf(false) }
    var pendingProfile by remember { mutableStateOf<ServerProfile?>(null) }
    var pendingChain by remember { mutableStateOf<ProfileChain?>(null) }

    // Battery optimization prompt (shown once on first connect)
    var showBatteryOptDialog by remember { mutableStateOf(false) }
    var pendingConnectAfterBatteryPrompt by remember { mutableStateOf(false) }
    var pendingProfileAfterBatteryPrompt by remember { mutableStateOf<ServerProfile?>(null) }
    var pendingChainAfterBatteryPrompt by remember { mutableStateOf<ProfileChain?>(null) }

    // Dialog/sheet state
    var showShareDialog by remember { mutableStateOf(false) }
    var showLogSheet by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showSubscriptionGroupsDialog by remember { mutableStateOf(false) }
    var showRoutingSettingsDialog by remember { mutableStateOf(false) }
    var showAssetFilesDialog by remember { mutableStateOf(false) }
    var showBackupRestoreDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var showExportAllEncryptedDialog by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    var showAddDropdown by remember { mutableStateOf(false) }
    var activeAddSubmenu by remember { mutableStateOf<AddSubmenuType?>(null) }
    var activeOverflowSubmenu by remember { mutableStateOf<OverflowMenuPage?>(null) }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var showDeleteDuplicatesDialog by remember { mutableStateOf(false) }
    var showDeleteOptionsDialog by remember { mutableStateOf(false) }
    var showExportOptionsDialog by remember { mutableStateOf(false) }
    var profileToDelete by remember { mutableStateOf<ServerProfile?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedProfileIds by remember { mutableStateOf(setOf<Long>()) }
    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            try {
                searchFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }
    var selectedCategoryIndex by remember { mutableIntStateOf(0) }

    val subGroups = remember(uiState.profiles) {
        uiState.profiles
            .map { it.subscriptionGroup.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    val groupTabs = remember(uiState.profiles, uiState.chains, subGroups) {
        val list = mutableListOf<Pair<String, Int>>()
        list.add("All" to uiState.profiles.size)
        val defaultCount = uiState.profiles.count { it.subscriptionGroup.isBlank() }
        if (defaultCount > 0 || subGroups.isNotEmpty()) {
            list.add("Default" to defaultCount)
        }
        subGroups.forEach { subId ->
            val count = uiState.profiles.count { it.subscriptionGroup == subId }
            list.add(subId to count)
        }
        if (uiState.chains.isNotEmpty()) {
            list.add("Chains" to uiState.chains.size)
        }
        list
    }

    val displayedProfiles = remember(uiState.profiles, selectedCategoryIndex, searchQuery, groupTabs) {
        val currentGroup = groupTabs.getOrNull(selectedCategoryIndex)?.first ?: "All"
        val query = searchQuery.trim()
        uiState.profiles.filter { profile ->
            if (query.isNotBlank()) {
                profile.name.contains(query, ignoreCase = true) ||
                profile.domain.contains(query, ignoreCase = true) ||
                profile.cdnIp.contains(query, ignoreCase = true)
            } else {
                when (currentGroup) {
                    "All" -> true
                    "Default" -> profile.subscriptionGroup.isBlank()
                    "Chains" -> true
                    else -> profile.subscriptionGroup == currentGroup
                }
            }
        }
    }

    var showAboutDialog by remember { mutableStateOf(false) }
    var showProtocolSortDialog by remember { mutableStateOf(false) }
    var invalidConfigsErrors by remember { mutableStateOf<List<String>?>(null) }
    // Export lock dialog state
    var exportLockProfile by remember { mutableStateOf<ServerProfile?>(null) }
    var exportLockEnabled by remember { mutableStateOf(false) }
    var exportLockPassword by remember { mutableStateOf("") }
    var exportLockMode by remember { mutableStateOf("export") } // "export" or "qr"
    var exportLockExpiry by remember { mutableStateOf(false) }
    var exportLockExpiryDays by remember { mutableStateOf("30") }
    var exportLockAllowSharing by remember { mutableStateOf(false) }
    var exportLockDeviceId by remember { mutableStateOf("") }
    var exportLockPasswordVisible by remember { mutableStateOf(false) }
    var exportHideResolvers by remember { mutableStateOf(false) }
    var exportFormatStandard by remember { mutableStateOf(false) }

    // Tab state (hoisted so FAB and content can both access it)
    val hasChains = uiState.chains.isNotEmpty()
    var selectedTab by remember { mutableIntStateOf(0) }
    if (!hasChains && selectedTab == 1) selectedTab = 0
    var showLiteInfoDialog by remember { mutableStateOf(false) }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && pendingConnect) {
            val chain = pendingChain
            val profile = pendingProfile
            if (chain != null) {
                viewModel.connectChain(chain)
            } else if (profile != null) {
                viewModel.connect(profile)
            } else {
                viewModel.connect()
            }
        }
        pendingConnect = false
        pendingProfile = null
        pendingChain = null
    }

    val importFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.openInputStream(it)?.use { inputStream ->
                    val json = inputStream.bufferedReader().readText()
                    viewModel.parseAndDirectImport(json) { success, msg ->
                        scope.launch { snackbarHostState.showSnackbar(msg) }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    val qrScanLauncher = rememberLauncherForActivityResult(
        contract = ScanContract()
    ) { result ->
        val contents = result.contents
        if (!contents.isNullOrBlank()) {
            viewModel.parseAndDirectImport(contents) { success, msg ->
                scope.launch {
                    snackbarHostState.showSnackbar(msg)
                }
            }
        }
    }

    // Handle export — show dialog with Copy and Share options
    uiState.exportedJson?.let { json ->
        AlertDialog(
            onDismissRequest = { viewModel.clearExportedJson() },
            title = { Text("Export") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Config is ready to share.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = json,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth().height(100.dp),
                        maxLines = 4,
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val sendIntent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, json)
                        type = "text/plain"
                    }
                    context.startActivity(Intent.createChooser(sendIntent, "Export Profile"))
                    viewModel.clearExportedJson()
                }) { Text("Share") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(json))
                        viewModel.clearExportedJson()
                        scope.launch { snackbarHostState.showSnackbar("Copied to clipboard") }
                    }) { Text("Copy") }
                    TextButton(onClick = { viewModel.clearExportedJson() }) { Text("Cancel") }
                }
            }
        )
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()

    val showTorProgressFab = uiState.connectionState is ConnectionState.Connecting &&
            uiState.snowflakeBootstrapProgress in 0..99

    val sleepTimerActive = uiState.connectionState is ConnectionState.Connected &&
            uiState.sleepTimerRemainingSeconds > 0

    val dnsWarningActive = uiState.connectionState is ConnectionState.Connected &&
            uiState.dnsWarning != null

    val fabExtraPadding by animateDpAsState(
        targetValue = when {
            uiState.connectionState is ConnectionState.Connected && sleepTimerActive && dnsWarningActive -> 72.dp
            uiState.connectionState is ConnectionState.Connected && sleepTimerActive -> 52.dp
            uiState.connectionState is ConnectionState.Connected && dnsWarningActive -> 48.dp
            uiState.connectionState is ConnectionState.Connected -> 28.dp
            showTorProgressFab -> 30.dp
            else -> 0.dp
        },
        animationSpec = tween(300),
        label = "fabPadding"
    )

    // Helper: proceed with VPN permission check and connect
    fun proceedWithConnect(profile: ServerProfile? = null) {
        if (activity != null) {
            // In proxy-only mode, skip VPN permission check — no TUN interface is created,
            // so VpnService.prepare() is unnecessary and would fail when another app
            // holds "Always-on VPN".
            if (uiState.proxyOnlyMode) {
                if (profile != null) viewModel.connect(profile) else viewModel.connect()
                return
            }
            val vpnIntent = VpnService.prepare(activity)
            if (vpnIntent != null) {
                pendingConnect = true
                pendingProfile = profile
                pendingChain = null
                vpnPermissionLauncher.launch(vpnIntent)
            } else {
                if (profile != null) viewModel.connect(profile) else viewModel.connect()
            }
        }
    }

    // Helper: proceed with VPN permission check and connect a chain
    fun proceedWithChainConnect(chain: ProfileChain) {
        if (activity != null) {
            if (uiState.proxyOnlyMode) {
                viewModel.connectChain(chain)
                return
            }
            val vpnIntent = VpnService.prepare(activity)
            if (vpnIntent != null) {
                pendingConnect = true
                pendingChain = chain
                pendingProfile = null
                vpnPermissionLauncher.launch(vpnIntent)
            } else {
                viewModel.connectChain(chain)
            }
        }
    }

    // Helper to request VPN permission and connect
    fun requestConnectOrToggle() {
        performInjectorHaptic(context, 45L)
        when (uiState.connectionState) {
            is ConnectionState.Connected,
            is ConnectionState.Connecting -> viewModel.disconnect()
            is ConnectionState.Error -> {
                viewModel.resetConnection()
                if (activity != null) {
                    val vpnIntent = VpnService.prepare(context)
                    if (vpnIntent != null) {
                        vpnPermissionLauncher.launch(vpnIntent)
                    } else {
                        viewModel.connect()
                    }
                } else {
                    viewModel.connect()
                }
            }
            else -> {
                if (activity != null) {
                    // Check battery optimization on first connect
                    val prefs = context.getSharedPreferences("vpn_service_state", Context.MODE_PRIVATE)
                    val prompted = prefs.getBoolean("battery_opt_prompted", false)
                    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                    if (!prompted && !pm.isIgnoringBatteryOptimizations(context.packageName)) {
                        pendingConnectAfterBatteryPrompt = true
                        pendingProfileAfterBatteryPrompt = null
                        // If on Chains tab, remember the active chain for after the battery dialog
                        pendingChainAfterBatteryPrompt = if (selectedTab == 1 && hasChains) uiState.activeChain else null
                        showBatteryOptDialog = true
                        return
                    }

                    // If on Chains tab, connect the active chain; otherwise connect active profile
                    if (selectedTab == 1 && hasChains) {
                        val chain = uiState.activeChain
                        if (chain != null) {
                            proceedWithChainConnect(chain)
                        }
                    } else {
                        proceedWithConnect()
                    }
                }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerState = drawerState,
                modifier = Modifier.fillMaxWidth(0.82f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f))
                            .padding(24.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "A",
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "AryanaNG",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "v${app.slipnet.BuildConfig.VERSION_NAME}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    NavigationDrawerItem(
                        label = { Text("Subscription", fontWeight = FontWeight.Medium) },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            showSubscriptionGroupsDialog = true
                        },
                        icon = { Icon(Icons.Default.Sync, contentDescription = null) },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )

                    NavigationDrawerItem(
                        label = { Text("Per app proxy", fontWeight = FontWeight.Medium) },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            onNavigateToAppSelector()
                        },
                        icon = { Icon(Icons.Default.Apps, contentDescription = null) },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )

                    NavigationDrawerItem(
                        label = { Text("Routing settings", fontWeight = FontWeight.Medium) },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            showRoutingSettingsDialog = true
                        },
                        icon = { Icon(Icons.Default.AltRoute, contentDescription = null) },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )

                    NavigationDrawerItem(
                        label = { Text("Asset file", fontWeight = FontWeight.Medium) },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            showAssetFilesDialog = true
                        },
                        icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )

                    NavigationDrawerItem(
                        label = { Text("Settings", fontWeight = FontWeight.Medium) },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            onNavigateToSettings()
                        },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )

                    NavigationDrawerItem(
                        label = { Text("Resolver scanner", fontWeight = FontWeight.Medium) },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            onNavigateToDnsScanner()
                        },
                        icon = { Icon(Icons.Default.NetworkPing, contentDescription = null) },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )

                    NavigationDrawerItem(
                        label = { Text("Terminal", fontWeight = FontWeight.Medium) },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            onNavigateToTerminal()
                        },
                        icon = { Icon(Icons.Default.Dns, contentDescription = null, tint = Color(0xFF00FF66)) },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    NavigationDrawerItem(
                        label = { Text("Connection LOG", fontWeight = FontWeight.Medium) },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            showLogSheet = true
                        },
                        icon = { Icon(Icons.Default.BugReport, contentDescription = null) },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )

                    NavigationDrawerItem(
                        label = { Text("Backup & restore", fontWeight = FontWeight.Medium) },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            showBackupRestoreDialog = true
                        },
                        icon = { Icon(Icons.Default.Save, contentDescription = null) },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )

                    NavigationDrawerItem(
                        label = { Text("Check for update", fontWeight = FontWeight.Medium) },
                        selected = false,
                        onClick = {
                            scope.launch {
                                drawerState.close()
                                snackbarHostState.showSnackbar("Checking for updates...")
                            }
                            viewModel.manualCheckForUpdate { update ->
                                scope.launch {
                                    if (update == null) {
                                        snackbarHostState.showSnackbar("AryanaNG is up to date (v${app.slipnet.BuildConfig.VERSION_NAME})")
                                    } else {
                                        val res = snackbarHostState.showSnackbar(
                                            message = "Update available: v${update.versionName}",
                                            actionLabel = "Download",
                                            duration = SnackbarDuration.Long
                                        )
                                        if (res == SnackbarResult.ActionPerformed) {
                                            try {
                                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(update.downloadUrl)).apply {
                                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                                }
                                                context.startActivity(intent)
                                            } catch (_: Exception) {}
                                        }
                                    }
                                }
                            }
                        },
                        icon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )

                    NavigationDrawerItem(
                        label = { Text("About", fontWeight = FontWeight.Medium) },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            showAboutDialog = true
                        },
                        icon = { Icon(Icons.Default.Info, contentDescription = null) },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        if (isSelectionMode) {
                            IconButton(onClick = {
                                isSelectionMode = false
                                selectedProfileIds = emptySet()
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Close selection")
                            }
                        } else if (isSearchActive) {
                            IconButton(onClick = {
                                isSearchActive = false
                                searchQuery = ""
                            }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        } else {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu")
                            }
                        }
                    },
                    title = {
                        if (isSelectionMode) {
                            Text(
                                text = "${selectedProfileIds.size} selected",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        } else if (isSearchActive) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                        RoundedCornerShape(21.dp)
                                    )
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            "Search profiles, IP, protocol...",
                                            fontSize = 13.sp,
                                            lineHeight = 16.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                    }
                                    BasicTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontSize = 13.sp,
                                            lineHeight = 16.sp,
                                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(
                                                includeFontPadding = false
                                            )
                                        ),
                                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .focusRequester(searchFocusRequester),
                                        decorationBox = { innerTextField ->
                                            Box(
                                                modifier = Modifier.fillMaxWidth(),
                                                contentAlignment = Alignment.CenterStart
                                            ) {
                                                innerTextField()
                                            }
                                        }
                                    )
                                }
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(
                                        onClick = { searchQuery = "" },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Clear",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = "AryanaNG",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    },
                    actions = {
                        if (isSelectionMode) {
                            IconButton(onClick = {
                                val displayedIds = displayedProfiles.map { it.id }.toSet()
                                selectedProfileIds = if (selectedProfileIds.containsAll(displayedIds)) emptySet() else displayedIds
                            }) {
                                Icon(Icons.Default.SelectAll, contentDescription = "Select all")
                            }
                            IconButton(
                                onClick = {
                                    if (selectedProfileIds.isNotEmpty()) {
                                        val str = viewModel.exportProfilesToClipboard { selectedProfileIds.contains(it.id) }
                                        clipboardManager.setText(AnnotatedString(str))
                                        val count = selectedProfileIds.size
                                        scope.launch { snackbarHostState.showSnackbar("Exported $count configs to clipboard") }
                                        isSelectionMode = false
                                        selectedProfileIds = emptySet()
                                    }
                                },
                                enabled = selectedProfileIds.isNotEmpty()
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Export selected")
                            }
                            IconButton(
                                onClick = {
                                    if (selectedProfileIds.isNotEmpty()) {
                                        val count = selectedProfileIds.size
                                        viewModel.deleteProfilesMatching { selectedProfileIds.contains(it.id) }
                                        scope.launch { snackbarHostState.showSnackbar("Deleted $count selected configs") }
                                        isSelectionMode = false
                                        selectedProfileIds = emptySet()
                                    }
                                },
                                enabled = selectedProfileIds.isNotEmpty()
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete selected")
                            }
                        } else if (!isSearchActive) {
                            IconButton(onClick = { isSearchActive = true }) {
                                Icon(Icons.Default.Search, contentDescription = "Search")
                            }
                            Box {
                                IconButton(onClick = {
                                    activeAddSubmenu = null
                                    showAddDropdown = true
                                }) {
                                    Icon(Icons.Default.Add, contentDescription = "Add")
                                }
                                DropdownMenu(
                                    expanded = showAddDropdown,
                                    onDismissRequest = {
                                        showAddDropdown = false
                                        activeAddSubmenu = null
                                    },
                                    offset = DpOffset(0.dp, 0.dp)
                                ) {
                                    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                                        // Left Submenu (Cascading Left)
                                        if (activeAddSubmenu == AddSubmenuType.PROTOCOLS) {
                                            Column(
                                                modifier = Modifier
                                                    .width(220.dp)
                                                    .heightIn(max = 280.dp)
                                                    .verticalScroll(rememberScrollState())
                                            ) {
                                                val protocolList = listOf(
                                                    "Policy group" to { showRoutingSettingsDialog = true },
                                                    "Proxy chain" to { onNavigateToAddChain() },
                                                    "VMess" to { onNavigateToAddProfile(TunnelType.VMESS.value) },
                                                    "VLESS" to { onNavigateToAddProfile("vless") },
                                                    "Shadowsocks" to { onNavigateToAddProfile(TunnelType.SHADOWSOCKS.value) },
                                                    "Socks" to { onNavigateToAddProfile("socks5") },
                                                    "Http" to { onNavigateToAddProfile("naive") },
                                                    "Trojan" to { onNavigateToAddProfile(TunnelType.TROJAN.value) },
                                                    "WireGuard" to { onNavigateToAddProfile(TunnelType.WIREGUARD.value) },
                                                    "Hysteria 2" to { onNavigateToAddProfile(TunnelType.HYSTERIA2.value) },
                                                    "TUIC" to { onNavigateToAddProfile(TunnelType.TUIC.value) },
                                                    "SSH Tunnel" to { onNavigateToAddProfile("ssh") },
                                                    "---" to {},
                                                    "MasterDNS" to { onNavigateToAddProfile(TunnelType.MASTERDNS.value) },
                                                    "Meli Protocol" to { onNavigateToAddProfile(TunnelType.MELI.value) },
                                                    "DNSTT" to { onNavigateToAddProfile("dnstt") },
                                                    "Slipstream" to { onNavigateToAddProfile("slipstream") },
                                                    "StormDNS" to { onNavigateToAddProfile(TunnelType.STORMDNS.value) },
                                                    "VayDNS" to { onNavigateToAddProfile(TunnelType.VAYDNS.value) },
                                                    "NoizDNS" to { onNavigateToAddProfile(TunnelType.NOIZDNS.value) },
                                                    "Nooshdaroo" to { onNavigateToAddProfile(TunnelType.NOOSHDAROO.value) },
                                                    "CottenDNS" to { onNavigateToAddProfile(TunnelType.COTTENDNS.value) },
                                                    "qWDTT" to { onNavigateToAddProfile(TunnelType.QWDTT.value) },
                                                    "Soar" to { onNavigateToAddProfile(TunnelType.SOAR.value) }
                                                )
                                                for ((label, action) in protocolList) {
                                                    if (label == "---") {
                                                        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                                                    } else {
                                                        DropdownMenuItem(
                                                            modifier = Modifier.heightIn(min = 34.dp),
                                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                                            text = { Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium) },
                                                            onClick = {
                                                                showAddDropdown = false
                                                                activeAddSubmenu = null
                                                                action()
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                            VerticalDivider(
                                                modifier = Modifier.fillMaxHeight(),
                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                            )
                                        }

                                        // Right Parent Menu Column
                                        Column(modifier = Modifier.width(200.dp)) {
                                            DropdownMenuItem(
                                                modifier = Modifier.heightIn(min = 36.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                text = { Text("Import from QR code", fontSize = 13.sp) },
                                                leadingIcon = { Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                                onClick = {
                                                    showAddDropdown = false
                                                    activeAddSubmenu = null
                                                    val options = ScanOptions().apply {
                                                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                                        setPrompt("Scan QR code")
                                                        setBeepEnabled(true)
                                                        setOrientationLocked(true)
                                                        setCaptureActivity(QrScannerActivity::class.java)
                                                    }
                                                    qrScanLauncher.launch(options)
                                                }
                                            )
                                            DropdownMenuItem(
                                                modifier = Modifier.heightIn(min = 36.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                text = { Text("Import from clipboard", fontSize = 13.sp) },
                                                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                                onClick = {
                                                    showAddDropdown = false
                                                    activeAddSubmenu = null
                                                    val clip = clipboardManager.getText()?.text
                                                    if (!clip.isNullOrBlank()) {
                                                        viewModel.parseAndDirectImport(clip) { success, msg ->
                                                            if (!success && (msg.contains("\n") || msg.startsWith("No valid profiles"))) {
                                                                invalidConfigsErrors = msg.lines().filter { it.isNotBlank() }
                                                            } else {
                                                                scope.launch { snackbarHostState.showSnackbar(msg) }
                                                            }
                                                        }
                                                    } else {
                                                        scope.launch { snackbarHostState.showSnackbar("Clipboard is empty") }
                                                    }
                                                }
                                            )
                                            DropdownMenuItem(
                                                modifier = Modifier.heightIn(min = 36.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                text = { Text("Import from local file", fontSize = 13.sp) },
                                                leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                                onClick = {
                                                    showAddDropdown = false
                                                    activeAddSubmenu = null
                                                    importFileLauncher.launch(arrayOf("*/*"))
                                                }
                                            )
                                            HorizontalDivider()
                                            DropdownMenuItem(
                                                modifier = Modifier.heightIn(min = 36.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                text = {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text(
                                                            "< ",
                                                            fontSize = 13.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = if (activeAddSubmenu == AddSubmenuType.PROTOCOLS) MaterialTheme.colorScheme.primary else Color.Gray
                                                        )
                                                        Text("Add profile manually", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                                    }
                                                },
                                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                                onClick = {
                                                    activeAddSubmenu = if (activeAddSubmenu == AddSubmenuType.PROTOCOLS) null else AddSubmenuType.PROTOCOLS
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            Box {
                                IconButton(onClick = {
                                    activeOverflowSubmenu = null
                                    showOverflowMenu = true
                                }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                                }
                                DropdownMenu(
                                    expanded = showOverflowMenu,
                                    onDismissRequest = {
                                        showOverflowMenu = false
                                        activeOverflowSubmenu = null
                                    },
                                    offset = DpOffset(0.dp, 0.dp)
                                ) {
                                    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                                        // Left Submenu (Cascading Left)
                                        if (activeOverflowSubmenu != null) {
                                            Column(
                                                modifier = Modifier
                                                    .width(230.dp)
                                                    .heightIn(max = 280.dp)
                                                    .verticalScroll(rememberScrollState())
                                            ) {
                                                when (activeOverflowSubmenu) {
                                                    OverflowMenuPage.DELETE -> {
                                                        val currentGroup = groupTabs.getOrNull(selectedCategoryIndex)?.first ?: ""
                                                        DropdownMenuItem(
                                                            modifier = Modifier.heightIn(min = 34.dp),
                                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                                            text = { Text("This subscription configs", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                                                            onClick = {
                                                                showOverflowMenu = false
                                                                activeOverflowSubmenu = null
                                                                if (currentGroup.isNotBlank() && currentGroup != "All" && currentGroup != "Default") {
                                                                    viewModel.deleteProfilesMatching { it.subscriptionGroup == currentGroup }
                                                                    scope.launch { snackbarHostState.showSnackbar("Deleted configs of $currentGroup") }
                                                                } else {
                                                                    viewModel.deleteProfilesMatching { it.subscriptionGroup.isNotBlank() }
                                                                    scope.launch { snackbarHostState.showSnackbar("Deleted subscription configs") }
                                                                }
                                                            }
                                                        )
                                                        DropdownMenuItem(
                                                            modifier = Modifier.heightIn(min = 34.dp),
                                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                                            text = { Text("Clear test invalid", fontSize = 12.sp) },
                                                            onClick = {
                                                                showOverflowMenu = false
                                                                activeOverflowSubmenu = null
                                                                viewModel.deleteProfilesMatching { p ->
                                                                    val res = uiState.pingResults[p.id]
                                                                    res != null && res is PingResult.Error
                                                                }
                                                                scope.launch { snackbarHostState.showSnackbar("Deleted invalid configs") }
                                                            }
                                                        )
                                                        DropdownMenuItem(
                                                            modifier = Modifier.heightIn(min = 34.dp),
                                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                                            text = { Text("Delete all configs", fontSize = 12.sp) },
                                                            onClick = {
                                                                showOverflowMenu = false
                                                                activeOverflowSubmenu = null
                                                                viewModel.deleteAllProfiles()
                                                                scope.launch { snackbarHostState.showSnackbar("Deleted all configs") }
                                                            }
                                                        )
                                                        DropdownMenuItem(
                                                            modifier = Modifier.heightIn(min = 34.dp),
                                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                                            text = { Text("All subscription configs", fontSize = 12.sp) },
                                                            onClick = {
                                                                showOverflowMenu = false
                                                                activeOverflowSubmenu = null
                                                                viewModel.deleteProfilesMatching { it.subscriptionGroup.isNotBlank() }
                                                                scope.launch { snackbarHostState.showSnackbar("Deleted all subscription configs") }
                                                            }
                                                        )
                                                        DropdownMenuItem(
                                                            modifier = Modifier.heightIn(min = 34.dp),
                                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                                            text = { Text("Selected configs", fontSize = 12.sp) },
                                                            onClick = {
                                                                showOverflowMenu = false
                                                                activeOverflowSubmenu = null
                                                                isSelectionMode = true
                                                            }
                                                        )
                                                    }
                                                    OverflowMenuPage.EXPORT -> {
                                                        val currentGroup = groupTabs.getOrNull(selectedCategoryIndex)?.first ?: ""
                                                        DropdownMenuItem(
                                                            modifier = Modifier.heightIn(min = 34.dp),
                                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                                            text = { Text("This subscription configs", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                                                            onClick = {
                                                                showOverflowMenu = false
                                                                activeOverflowSubmenu = null
                                                                val str = viewModel.exportProfilesToClipboard {
                                                                    if (currentGroup.isNotBlank() && currentGroup != "All" && currentGroup != "Default") {
                                                                        it.subscriptionGroup == currentGroup
                                                                    } else {
                                                                        it.subscriptionGroup.isNotBlank()
                                                                    }
                                                                }
                                                                clipboardManager.setText(AnnotatedString(str))
                                                                scope.launch { snackbarHostState.showSnackbar("Copied this subscription configs") }
                                                            }
                                                        )
                                                        DropdownMenuItem(
                                                            modifier = Modifier.heightIn(min = 34.dp),
                                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                                            text = { Text("Export valid configs", fontSize = 12.sp) },
                                                            onClick = {
                                                                showOverflowMenu = false
                                                                activeOverflowSubmenu = null
                                                                val str = viewModel.exportProfilesToClipboard { p ->
                                                                    val res = uiState.pingResults[p.id]
                                                                    res != null && res is PingResult.Success && res.latencyMs > 0
                                                                }
                                                                clipboardManager.setText(AnnotatedString(str))
                                                                scope.launch { snackbarHostState.showSnackbar("Copied valid configs to clipboard") }
                                                            }
                                                        )
                                                        DropdownMenuItem(
                                                            modifier = Modifier.heightIn(min = 34.dp),
                                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                                            text = { Text("Export invalid (-1 ms)", fontSize = 12.sp) },
                                                            onClick = {
                                                                showOverflowMenu = false
                                                                activeOverflowSubmenu = null
                                                                val str = viewModel.exportProfilesToClipboard { p ->
                                                                    val res = uiState.pingResults[p.id]
                                                                    res != null && res is PingResult.Error
                                                                }
                                                                clipboardManager.setText(AnnotatedString(str))
                                                                scope.launch { snackbarHostState.showSnackbar("Copied invalid configs to clipboard") }
                                                            }
                                                        )
                                                        DropdownMenuItem(
                                                            modifier = Modifier.heightIn(min = 34.dp),
                                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                                            text = { Text("All subscription configs", fontSize = 12.sp) },
                                                            onClick = {
                                                                showOverflowMenu = false
                                                                activeOverflowSubmenu = null
                                                                val str = viewModel.exportProfilesToClipboard { it.subscriptionGroup.isNotBlank() }
                                                                clipboardManager.setText(AnnotatedString(str))
                                                                scope.launch { snackbarHostState.showSnackbar("Copied all subscription configs") }
                                                            }
                                                        )
                                                        DropdownMenuItem(
                                                            modifier = Modifier.heightIn(min = 34.dp),
                                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                                            text = { Text("Selected configs", fontSize = 12.sp) },
                                                            onClick = {
                                                                showOverflowMenu = false
                                                                activeOverflowSubmenu = null
                                                                isSelectionMode = true
                                                            }
                                                        )
                                                    }
                                                    OverflowMenuPage.PING -> {
                                                        DropdownMenuItem(
                                                            modifier = Modifier.heightIn(min = 34.dp),
                                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                                            text = { Text(if (uiState.isPingRunning) "Stop ping test" else "Real delay", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                                                            onClick = {
                                                                showOverflowMenu = false
                                                                activeOverflowSubmenu = null
                                                                viewModel.pingAllProfiles()
                                                            }
                                                        )
                                                        DropdownMenuItem(
                                                            modifier = Modifier.heightIn(min = 34.dp),
                                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                                            text = { Text("Tcping", fontSize = 12.sp) },
                                                            onClick = {
                                                                showOverflowMenu = false
                                                                activeOverflowSubmenu = null
                                                                viewModel.pingAllProfilesSimple()
                                                            }
                                                        )
                                                    }
                                                    OverflowMenuPage.SORT -> {
                                                        DropdownMenuItem(
                                                            modifier = Modifier.heightIn(min = 34.dp),
                                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                                            text = { Text("Sort by test result", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                                                            onClick = {
                                                                showOverflowMenu = false
                                                                activeOverflowSubmenu = null
                                                                viewModel.sortProfilesByPing()
                                                            }
                                                        )
                                                        DropdownMenuItem(
                                                            modifier = Modifier.heightIn(min = 34.dp),
                                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                                            text = { Text("Sort by profile name", fontSize = 12.sp) },
                                                            onClick = {
                                                                showOverflowMenu = false
                                                                activeOverflowSubmenu = null
                                                                viewModel.sortProfilesByName()
                                                            }
                                                        )
                                                        DropdownMenuItem(
                                                            modifier = Modifier.heightIn(min = 34.dp),
                                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                                            text = { Text("Sort by protocol", fontSize = 12.sp) },
                                                            onClick = {
                                                                showOverflowMenu = false
                                                                activeOverflowSubmenu = null
                                                                showProtocolSortDialog = true
                                                            }
                                                        )
                                                    }
                                                    else -> {}
                                                }
                                            }
                                            VerticalDivider(
                                                modifier = Modifier.fillMaxHeight(),
                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                            )
                                        }

                                        // Right Parent Menu Column
                                        Column(modifier = Modifier.width(200.dp)) {
                                            DropdownMenuItem(
                                                modifier = Modifier.heightIn(min = 36.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                text = { Text("Restart service", fontSize = 13.sp) },
                                                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                                onClick = {
                                                    showOverflowMenu = false
                                                    activeOverflowSubmenu = null
                                                    viewModel.restartService()
                                                    scope.launch { snackbarHostState.showSnackbar("Restarting service...") }
                                                }
                                            )
                                            DropdownMenuItem(
                                                modifier = Modifier.heightIn(min = 36.dp),
                                                contentPadding = PaddingValues(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                                                text = { Text("Delete configs", fontSize = 13.sp) },
                                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                                trailingIcon = {
                                                    IconButton(
                                                        onClick = {
                                                            activeOverflowSubmenu = if (activeOverflowSubmenu == OverflowMenuPage.DELETE) null else OverflowMenuPage.DELETE
                                                        },
                                                        modifier = Modifier.size(28.dp)
                                                    ) {
                                                        Text(
                                                            "<",
                                                            fontSize = 14.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = if (activeOverflowSubmenu == OverflowMenuPage.DELETE) MaterialTheme.colorScheme.primary else Color.Gray
                                                        )
                                                    }
                                                },
                                                onClick = {
                                                    val currentGroup = groupTabs.getOrNull(selectedCategoryIndex)?.first ?: ""
                                                    showOverflowMenu = false
                                                    activeOverflowSubmenu = null
                                                    if (currentGroup.isNotBlank() && currentGroup != "All" && currentGroup != "Default") {
                                                        viewModel.deleteProfilesMatching { it.subscriptionGroup == currentGroup }
                                                        scope.launch { snackbarHostState.showSnackbar("Deleted configs of $currentGroup") }
                                                    } else {
                                                        viewModel.deleteProfilesMatching { it.subscriptionGroup.isNotBlank() }
                                                        scope.launch { snackbarHostState.showSnackbar("Deleted subscription configs") }
                                                    }
                                                },
                                                enabled = uiState.profiles.isNotEmpty()
                                            )
                                            DropdownMenuItem(
                                                modifier = Modifier.heightIn(min = 36.dp),
                                                contentPadding = PaddingValues(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                                                text = { Text("Export config to clipboard", fontSize = 13.sp) },
                                                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                                trailingIcon = {
                                                    IconButton(
                                                        onClick = {
                                                            activeOverflowSubmenu = if (activeOverflowSubmenu == OverflowMenuPage.EXPORT) null else OverflowMenuPage.EXPORT
                                                        },
                                                        modifier = Modifier.size(28.dp)
                                                    ) {
                                                        Text(
                                                            "<",
                                                            fontSize = 14.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = if (activeOverflowSubmenu == OverflowMenuPage.EXPORT) MaterialTheme.colorScheme.primary else Color.Gray
                                                        )
                                                    }
                                                },
                                                onClick = {
                                                    val currentGroup = groupTabs.getOrNull(selectedCategoryIndex)?.first ?: ""
                                                    showOverflowMenu = false
                                                    activeOverflowSubmenu = null
                                                    val str = viewModel.exportProfilesToClipboard {
                                                        if (currentGroup.isNotBlank() && currentGroup != "All" && currentGroup != "Default") {
                                                            it.subscriptionGroup == currentGroup
                                                        } else {
                                                            it.subscriptionGroup.isNotBlank()
                                                        }
                                                    }
                                                    clipboardManager.setText(AnnotatedString(str))
                                                    scope.launch { snackbarHostState.showSnackbar("Copied this subscription configs") }
                                                },
                                                enabled = uiState.profiles.isNotEmpty()
                                            )
                                            HorizontalDivider()
                                            DropdownMenuItem(
                                                modifier = Modifier.heightIn(min = 36.dp),
                                                contentPadding = PaddingValues(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                                                text = { Text("Ping", fontSize = 13.sp) },
                                                leadingIcon = { Icon(Icons.Default.NetworkPing, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                                trailingIcon = {
                                                    IconButton(
                                                        onClick = {
                                                            activeOverflowSubmenu = if (activeOverflowSubmenu == OverflowMenuPage.PING) null else OverflowMenuPage.PING
                                                        },
                                                        modifier = Modifier.size(28.dp)
                                                    ) {
                                                        Text(
                                                            "<",
                                                            fontSize = 14.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = if (activeOverflowSubmenu == OverflowMenuPage.PING) MaterialTheme.colorScheme.primary else Color.Gray
                                                        )
                                                    }
                                                },
                                                onClick = {
                                                    showOverflowMenu = false
                                                    activeOverflowSubmenu = null
                                                    viewModel.pingAllProfiles()
                                                },
                                                enabled = uiState.profiles.isNotEmpty()
                                            )
                                            DropdownMenuItem(
                                                modifier = Modifier.heightIn(min = 36.dp),
                                                contentPadding = PaddingValues(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                                                text = { Text("Sort by", fontSize = 13.sp) },
                                                leadingIcon = { Icon(Icons.Default.Dns, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                                trailingIcon = {
                                                    IconButton(
                                                        onClick = {
                                                            activeOverflowSubmenu = if (activeOverflowSubmenu == OverflowMenuPage.SORT) null else OverflowMenuPage.SORT
                                                        },
                                                        modifier = Modifier.size(28.dp)
                                                    ) {
                                                        Text(
                                                            "<",
                                                            fontSize = 14.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = if (activeOverflowSubmenu == OverflowMenuPage.SORT) MaterialTheme.colorScheme.primary else Color.Gray
                                                        )
                                                    }
                                                },
                                                onClick = {
                                                    showOverflowMenu = false
                                                    activeOverflowSubmenu = null
                                                    viewModel.sortProfilesByPing()
                                                },
                                                enabled = uiState.profiles.isNotEmpty()
                                            )
                                            HorizontalDivider()
                                            DropdownMenuItem(
                                                modifier = Modifier.heightIn(min = 36.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                text = { Text("Update subscription", fontSize = 13.sp) },
                                                leadingIcon = { Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                                onClick = {
                                                    showOverflowMenu = false
                                                    activeOverflowSubmenu = null
                                                    val currentGroup = groupTabs.getOrNull(selectedCategoryIndex)?.first ?: ""
                                                    viewModel.updateSubscriptionGroup(currentGroup) { count, err ->
                                                        scope.launch {
                                                            if (err != null) snackbarHostState.showSnackbar("Update failed: $err")
                                                            else snackbarHostState.showSnackbar("Updated $count configs")
                                                        }
                                                    }
                                                }
                                            )
                                            DropdownMenuItem(
                                                modifier = Modifier.heightIn(min = 36.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                text = { Text("Update all subscriptions", fontSize = 13.sp) },
                                                leadingIcon = { Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                                onClick = {
                                                    showOverflowMenu = false
                                                    activeOverflowSubmenu = null
                                                    viewModel.updateAllSubscriptions { count, err ->
                                                        scope.launch {
                                                            if (err != null) snackbarHostState.showSnackbar("Update failed: $err")
                                                            else snackbarHostState.showSnackbar("Updated $count configs across all subscriptions")
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            },
            floatingActionButton = {
                val isRunning = uiState.connectionState is ConnectionState.Connected ||
                        uiState.connectionState is ConnectionState.Connecting
                FloatingActionButton(
                    onClick = { requestConnectOrToggle() },
                    containerColor = if (uiState.connectionState is ConnectionState.Connected) ColorFabActive
                        else ColorFabInactiveDark
                ) {
                    Icon(
                        imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (isRunning) "Disconnect" else "Connect",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { paddingValues ->
        // Auto-switch to chains tab when a chain is connected
        LaunchedEffect(uiState.connectedChainId) {
            if (uiState.connectedChainId != null && hasChains) selectedTab = 1
        }

        // Haptic feedback when tunnel connects successfully (HTTP Injector style)
        LaunchedEffect(uiState.connectionState) {
            if (uiState.connectionState is ConnectionState.Connected) {
                performInjectorHaptic(context, 65L)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                // ── PattNG Group Category Pills ──────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    groupTabs.forEachIndexed { index, (label, count) ->
                        FilterChip(
                            selected = selectedCategoryIndex == index,
                            onClick = {
                                selectedCategoryIndex = index
                                if (label == "Chains") selectedTab = 1 else selectedTab = 0
                            },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(label, fontSize = 12.sp)
                                    Spacer(Modifier.width(4.dp))
                                    Text("($count)", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }

                // Subscription Info Card (Shown only when in a real subscription group and configs exist)
                val currentGroupName = groupTabs.getOrNull(selectedCategoryIndex)?.first ?: "All"
                val subProfiles = displayedProfiles.filter { it.subscriptionGroup.isNotBlank() }
                if (currentGroupName != "All" && currentGroupName != "Default" && subProfiles.isNotEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Subscription: $currentGroupName",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "${subProfiles.size} configs",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                // ── Tab Row (only when chains exist) ────────────────────
                if (hasChains) {
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        SegmentedButton(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            shape = SegmentedButtonDefaults.itemShape(0, 2)
                        ) { Text("Profiles") }
                        SegmentedButton(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            shape = SegmentedButtonDefaults.itemShape(1, 2)
                        ) { Text("Chains") }
                    }
                }

                // ── Tab Content ─────────────────────────────────────────
                when {
                    selectedTab == 1 && hasChains -> {
                        // ── Chains Tab ──────────────────────────────────
                        val chainListState = rememberLazyListState()
                        val reorderableChainState = rememberReorderableLazyListState(chainListState) { from, to ->
                            viewModel.moveChain(from.index, to.index)
                        }

                        LazyColumn(
                            state = chainListState,
                            contentPadding = PaddingValues(
                                start = 16.dp, end = 16.dp,
                                top = 8.dp,
                                bottom = 200.dp + navBarPadding.calculateBottomPadding()
                            ),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(
                                items = uiState.chains,
                                key = { "chain_${it.id}" }
                            ) { chain ->
                                ReorderableItem(reorderableChainState, key = "chain_${chain.id}") { isDragging ->
                                    val elevation = if (isDragging) 8.dp else 0.dp

                                    ChainListItem(
                                        chain = chain,
                                        profiles = uiState.profiles,
                                        isSelected = uiState.activeChain?.id == chain.id,
                                        isConnected = uiState.connectedChainId == chain.id,
                                        onClick = { viewModel.setActiveChain(chain) },
                                        onEditClick = { onNavigateToEditChain(chain.id) },
                                        onDeleteClick = {
                                            val isConnected = uiState.connectedChainId == chain.id
                                            if (isConnected) {
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(
                                                        "Disconnect VPN before deleting this chain"
                                                    )
                                                }
                                            } else {
                                                viewModel.deleteChain(chain)
                                            }
                                        },
                                        modifier = Modifier
                                            .longPressDraggableHandle()
                                            .shadow(elevation, RoundedCornerShape(12.dp))
                                            .zIndex(if (isDragging) 1f else 0f)
                                    )
                                }
                            }
                        }
                    }
                    uiState.isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.material3.CircularProgressIndicator()
                        }
                    }
                    uiState.profiles.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(32.dp)
                            ) {
                                Text(
                                    text = "No profiles yet",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Tap + to add your first profile",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    else -> {
                        // ── Profiles Tab ────────────────────────────────
                        val lazyListState = rememberLazyListState()
                        val reorderableLazyListState = rememberReorderableLazyListState(lazyListState) { from, to ->
                            viewModel.moveProfile(from.index, to.index)
                        }

                        Column(modifier = Modifier.fillMaxSize()) {
                            // Pinned indicator: always visible (above the scrollable list) so
                            // users see the active Global DNS context even after scrolling.
                            if (uiState.pingingViaGlobalDns.isNotEmpty()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp)
                                        .background(
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                                            RoundedCornerShape(8.dp)
                                        )
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Dns,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "Pinging via Global DNS: " +
                                            uiState.pingingViaGlobalDns.joinToString { "${it.host}:${it.port}" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2
                                    )
                                }
                            }

                            LazyColumn(
                                state = lazyListState,
                                contentPadding = PaddingValues(
                                    start = 8.dp, end = 8.dp,
                                    top = 4.dp,
                                    bottom = 140.dp + navBarPadding.calculateBottomPadding()
                                ),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(
                                items = displayedProfiles,
                                key = { it.id }
                            ) { profile ->
                                val isConnected = uiState.connectedProfileId == profile.id

                                ReorderableItem(reorderableLazyListState, key = profile.id) { isDragging ->
                                    val elevation = if (isDragging) 8.dp else 0.dp

                                    ProfileListItem(
                                        profile = profile,
                                        isSelected = if (isSelectionMode) selectedProfileIds.contains(profile.id) else profile.isActive,
                                        isConnected = isConnected,
                                        pingResult = uiState.pingResults[profile.id],
                                        onClick = {
                                            if (isSelectionMode) {
                                                selectedProfileIds = if (selectedProfileIds.contains(profile.id)) {
                                                    selectedProfileIds - profile.id
                                                } else {
                                                    selectedProfileIds + profile.id
                                                }
                                            } else {
                                                viewModel.setActiveProfile(profile)
                                            }
                                        },
                                        onEditClick = { onNavigateToEditProfile(profile.id) },
                                        onDeleteClick = {
                                            if (isConnected) {
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(
                                                        "Disconnect VPN before deleting this profile"
                                                    )
                                                }
                                            } else {
                                                profileToDelete = profile
                                            }
                                        },
                                        onExportClick = {
                                            exportLockProfile = profile
                                            exportLockEnabled = false
                                            exportLockPassword = ""
                                            exportLockMode = "export"
                                        },
                                        onShareQrClick = {
                                            exportLockProfile = profile
                                            exportLockEnabled = false
                                            exportLockPassword = ""
                                            exportLockMode = "qr"
                                        },
                                        onPinClick = { viewModel.togglePinProfile(profile) },
                                        onPingClick = { viewModel.pingSingleProfile(profile) },
                                        isPingRunning = uiState.isPingRunning,
                                        modifier = Modifier
                                            .longPressDraggableHandle()
                                            .shadow(elevation, RoundedCornerShape(12.dp))
                                            .zIndex(if (isDragging) 1f else 0f)
                                    )
                                }
                            }
                            }
                        }
                    }
                }
            }

            // ── Connection Status Strip (bottom, behind FAB) ────────
            ConnectionStatusStrip(
                connectionState = uiState.connectionState,
                activeProfile = uiState.activeProfile,
                activeChain = uiState.activeChain,
                isProxyOnly = uiState.proxyOnlyMode,
                snowflakeBootstrapProgress = uiState.snowflakeBootstrapProgress,
                uploadSpeed = uiState.uploadSpeed,
                downloadSpeed = uiState.downloadSpeed,
                totalUpload = uiState.trafficStats.bytesSent,
                totalDownload = uiState.trafficStats.bytesReceived,
                sleepTimerRemainingSeconds = uiState.sleepTimerRemainingSeconds,
                onCancelSleepTimer = { viewModel.userCancelSleepTimer() },
                dnsWarning = uiState.dnsWarning,
                dnsPoolScan = uiState.dnsPoolScan,
                onPingConnected = {
                    uiState.activeProfile?.let { viewModel.pingSingleProfile(it) }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = navBarPadding.calculateBottomPadding())
            )

            // Bottom sheet for adding profiles
            if (showAddMenu) {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                var dnsSectionExpanded by remember { mutableStateOf(true) }
                var standardSectionExpanded by remember { mutableStateOf(true) }
                var specialtySectionExpanded by remember { mutableStateOf(false) }
                var iosSectionExpanded by remember { mutableStateOf(true) }

                ModalBottomSheet(
                    onDismissRequest = { showAddMenu = false },
                    sheetState = sheetState,
                    dragHandle = null
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 36.dp)
                    ) {
                        Text(
                            text = "New Profile",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 8.dp)
                        )
                        @OptIn(ExperimentalLayoutApi::class)
                        // Category 1: DNS Tunnel Protocols
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { dnsSectionExpanded = !dnsSectionExpanded }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "DNS Tunnel Protocols (11)",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Icon(
                                imageVector = if (dnsSectionExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (dnsSectionExpanded) "Collapse" else "Expand",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (dnsSectionExpanded) {
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            maxItemsInEachRow = 3
                        ) {
                            val tileMod = Modifier.weight(1f)
                            AddMenuGridItem(
                                modifier = tileMod,
                                icon = Icons.Default.Shield,
                                title = "Meli Protocol",
                                isMasterDnsTile = true,
                                onClick = {
                                    showAddMenu = false
                                    onNavigateToAddProfile(TunnelType.MELI.value)
                                }
                            )
                            AddMenuGridItem(
                                modifier = tileMod,
                                icon = Icons.Default.Shield,
                                title = "MasterDNS",
                                isMasterDnsTile = true,
                                onClick = {
                                    showAddMenu = false
                                    onNavigateToAddProfile(TunnelType.MASTERDNS.value)
                                }
                            )
                            AddMenuGridItem(
                                modifier = tileMod,
                                icon = Icons.Default.Dns,
                                title = "DNSTT",
                                onClick = {
                                    showAddMenu = false
                                    onNavigateToAddProfile("dnstt")
                                }
                            )
                            AddMenuGridItem(
                                modifier = tileMod,
                                icon = Icons.Default.VisibilityOff,
                                title = "NoizDNS",
                                onClick = {
                                    showAddMenu = false
                                    onNavigateToAddProfile(TunnelType.NOIZDNS.value)
                                }
                            )
                            AddMenuGridItem(
                                modifier = tileMod,
                                icon = Icons.Default.Air,
                                title = "VayDNS",
                                onClick = {
                                    showAddMenu = false
                                    onNavigateToAddProfile(TunnelType.VAYDNS.value)
                                }
                            )
                            AddMenuGridItem(
                                modifier = tileMod,
                                icon = Icons.Default.Waves,
                                title = "Slipstream",
                                onClick = {
                                    showAddMenu = false
                                    onNavigateToAddProfile("slipstream")
                                }
                            )
                            AddMenuGridItem(
                                modifier = tileMod,
                                icon = Icons.Default.Dns,
                                title = "StormDNS",
                                onClick = {
                                    showAddMenu = false
                                    onNavigateToAddProfile(TunnelType.STORMDNS.value)
                                }
                            )
                            AddMenuGridItem(
                                modifier = tileMod,
                                icon = Icons.Default.Dns,
                                title = "Nooshdaroo",
                                onClick = {
                                    showAddMenu = false
                                    onNavigateToAddProfile(TunnelType.NOOSHDAROO.value)
                                }
                            )
                            AddMenuGridItem(
                                modifier = tileMod,
                                icon = Icons.Default.Air,
                                title = "qWDTT",
                                onClick = {
                                    showAddMenu = false
                                    onNavigateToAddProfile(TunnelType.QWDTT.value)
                                }
                            )
                            AddMenuGridItem(
                                modifier = tileMod,
                                icon = Icons.Default.Dns,
                                title = "CottenDNS",
                                onClick = {
                                    showAddMenu = false
                                    onNavigateToAddProfile(TunnelType.COTTENDNS.value)
                                }
                            )
                            AddMenuGridItem(
                                modifier = tileMod,
                                icon = Icons.Default.Air,
                                title = "Soar",
                                onClick = {
                                    showAddMenu = false
                                    onNavigateToAddProfile(TunnelType.SOAR.value)
                                }
                            )
                        }
                        }

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))

                        // Category 2: Standard Protocols
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { standardSectionExpanded = !standardSectionExpanded }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Standard Protocols (12)",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Icon(
                                imageVector = if (standardSectionExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (standardSectionExpanded) "Collapse" else "Expand",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (standardSectionExpanded) {
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            maxItemsInEachRow = 3
                        ) {
                            val tileMod = Modifier.weight(1f)
                            AddMenuGridItem(
                                modifier = tileMod,
                                icon = Icons.Default.Lock,
                                title = "SSH",
                                onClick = {
                                    showAddMenu = false
                                    onNavigateToAddProfile("ssh")
                                }
                            )
                            AddMenuGridItem(
                                modifier = tileMod,
                                icon = Icons.Default.Shield,
                                title = "VLESS",
                                onClick = {
                                    showAddMenu = false
                                    onNavigateToAddProfile("vless")
                                }
                            )
                            AddMenuGridItem(
                                modifier = tileMod,
                                icon = Icons.Default.Share,
                                title = "SOCKS5",
                                onClick = {
                                    showAddMenu = false
                                    onNavigateToAddProfile("socks5")
                                }
                            )
                            AddMenuGridItem(
                                modifier = tileMod,
                                icon = Icons.Default.Language,
                                title = "DoH + SSH",
                                onClick = {
                                    showAddMenu = false
                                    onNavigateToAddProfile("doh")
                                }
                            )
                            if (BuildConfig.INCLUDE_NAIVE) {
                                AddMenuGridItem(
                                    modifier = tileMod,
                                    icon = Icons.Default.Shield,
                                    title = "NaiveProxy",
                                    onClick = {
                                        showAddMenu = false
                                        onNavigateToAddProfile("naive")
                                    }
                                )
                            }
                            if (BuildConfig.INCLUDE_TOR) {
                                AddMenuGridItem(
                                    modifier = tileMod,
                                    icon = Icons.Default.Shield,
                                    title = "Tor",
                                    onClick = {
                                        showAddMenu = false
                                        onNavigateToAddProfile("snowflake")
                                    }
                                )
                            }
                            AddMenuGridItem(
                                modifier = tileMod,
                                icon = Icons.Default.Shield,
                                title = "WireGuard",
                                onClick = {
                                    showAddMenu = false
                                    onNavigateToAddProfile(TunnelType.WIREGUARD.value)
                                }
                            )
                            AddMenuGridItem(
                                modifier = tileMod,
                                icon = Icons.Default.Settings,
                                title = "OpenVPN",
                                onClick = {
                                    showAddMenu = false
                                    onNavigateToAddProfile(TunnelType.OPENVPN.value)
                                }
                            )
                            AddMenuGridItem(
                                modifier = tileMod,
                                icon = Icons.Default.Waves,
                                title = "Hysteria 2",
                                onClick = {
                                    showAddMenu = false
                                    onNavigateToAddProfile(TunnelType.HYSTERIA2.value)
                                }
                            )
                            AddMenuGridItem(
                                modifier = tileMod,
                                icon = Icons.Default.Air,
                                title = "TUIC",
                                onClick = {
                                    showAddMenu = false
                                    onNavigateToAddProfile(TunnelType.TUIC.value)
                                }
                            )
                            AddMenuGridItem(
                                modifier = tileMod,
                                icon = Icons.Default.Lock,
                                title = "Trojan",
                                onClick = {
                                    showAddMenu = false
                                    onNavigateToAddProfile(TunnelType.TROJAN.value)
                                }
                            )
                            AddMenuGridItem(
                                modifier = tileMod,
                                icon = Icons.Default.Share,
                                title = "Shadowsocks",
                                onClick = {
                                    showAddMenu = false
                                    onNavigateToAddProfile(TunnelType.SHADOWSOCKS.value)
                                }
                            )
                        }
                        }

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))

                        // Category 3: iOS & Custom Proxy Protocols
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { iosSectionExpanded = !iosSectionExpanded }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "iOS & Custom Protocols (4)",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Icon(
                                imageVector = if (iosSectionExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (iosSectionExpanded) "Collapse" else "Expand",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (iosSectionExpanded) {
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            maxItemsInEachRow = 2
                        ) {
                            val tileMod = Modifier.weight(1f)
                            AddMenuGridItem(
                                modifier = tileMod,
                                icon = Icons.Default.Dns,
                                title = "VMess",
                                onClick = {
                                    showAddMenu = false
                                    onNavigateToAddProfile(TunnelType.VMESS.value)
                                }
                            )
                            AddMenuGridItem(
                                modifier = tileMod,
                                icon = Icons.Default.Visibility,
                                title = "VLESS-Reality",
                                onClick = {
                                    showAddMenu = false
                                    onNavigateToAddProfile(TunnelType.VLESS_REALITY.value)
                                }
                            )
                            AddMenuGridItem(
                                modifier = tileMod,
                                icon = Icons.Default.Shield,
                                title = "Snell",
                                onClick = {
                                    showAddMenu = false
                                    onNavigateToAddProfile(TunnelType.SNELL.value)
                                }
                            )
                            AddMenuGridItem(
                                modifier = tileMod,
                                icon = Icons.Default.Share,
                                title = "ShadowsocksR",
                                onClick = {
                                    showAddMenu = false
                                    onNavigateToAddProfile(TunnelType.SHADOWSOCKSR.value)
                                }
                            )
                        }
                        }

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))

                        // Category 4: Specialty & Emergency Intranet
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { specialtySectionExpanded = !specialtySectionExpanded }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Specialty & Emergency Intranet (5)",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Icon(
                                imageVector = if (specialtySectionExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (specialtySectionExpanded) "Collapse" else "Expand",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (specialtySectionExpanded) {
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            maxItemsInEachRow = 3
                        ) {
                            val tileMod = Modifier.weight(1f)
                            AddMenuGridItem(
                                modifier = tileMod,
                                icon = Icons.Default.Language,
                                title = "Bale Meet",
                                onClick = {
                                    showAddMenu = false
                                    onNavigateToAddProfile(TunnelType.BALE.value)
                                }
                            )
                            AddMenuGridItem(
                                modifier = tileMod,
                                icon = Icons.Default.NetworkPing,
                                title = "PingTunnel (root)",
                                onClick = {
                                    showAddMenu = false
                                    onNavigateToAddProfile(TunnelType.PINGTUNNEL.value)
                                }
                            )
                            AddMenuGridItem(
                                modifier = tileMod,
                                icon = Icons.Default.Shield,
                                title = "Spoof-Tunnel",
                                onClick = {
                                    showAddMenu = false
                                    onNavigateToAddProfile(TunnelType.SPOOF_TUNNEL.value)
                                }
                            )
                            AddMenuGridItem(
                                modifier = tileMod,
                                icon = Icons.Default.Waves,
                                title = "Golden-GRE",
                                onClick = {
                                    showAddMenu = false
                                    onNavigateToAddProfile(TunnelType.GOLDEN_GRE.value)
                                }
                            )
                            AddMenuGridItem(
                                modifier = tileMod,
                                icon = Icons.Default.Shield,
                                title = "Paqet (Raw-TCP)",
                                onClick = {
                                    showAddMenu = false
                                    onNavigateToAddProfile(TunnelType.PAQET.value)
                                }
                            )
                        }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        AddMenuOption(
                            icon = Icons.Default.Link,
                            title = "Chain",
                            description = "Chain multiple profiles",
                            onClick = {
                                showAddMenu = false
                                onNavigateToAddChain()
                            }
                        )
                        AddMenuOption(
                            icon = Icons.Default.FileDownload,
                            title = "Import",
                            description = "Import configs & files (JSON, vmess, vless, conf, ovpn)",
                            onClick = {
                                showAddMenu = false
                                showImportDialog = true
                            }
                        )
                    }
                }
            }
        }
    }
    }

    // ── Dialogs ─────────────────────────────────────────────────────────

    // About Aryana dialog
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("About AryanaNG") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("AryanaNG — Modern Censorship-Resistant Tunnel Suite.")
                    Text("UI design and architecture based on PattNG standards.")
                    Text("Protocols: MasterDNS, StormDNS, Meli Pure-Rust, DNSTT, VLESS / Reality, VMess, Trojan, WireGuard, and SSH Shell.")
                    Text("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) { Text("Close") }
            }
        )
    }

    // Debug log sheet
    if (showLogSheet) {
        DebugLogSheet(onDismiss = { showLogSheet = false })
    }

    // Lite version info dialog
    if (showLiteInfoDialog) {
        AlertDialog(
            onDismissRequest = { showLiteInfoDialog = false },
            title = { Text("Aryana Lite") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("AryanaNG Lite is a lightweight version with a smaller app size.")
                    Text("Included protocols:", fontWeight = FontWeight.Bold)
                    Text("• Slipstream / Slipstream + SSH")
                    Text("• DNSTT / DNSTT + SSH")
                    Text("• NoizDNS / NoizDNS + SSH")
                    Text("• VayDNS / VayDNS + SSH")
                    Text("• SSH")
                    Text("• DoH + SSH (DNS over HTTPS)")
                    Text("Not included (full version only):", fontWeight = FontWeight.Bold)
                    Text("• Tor (Snowflake)")
                    Text("• NaïveProxy / NaïveProxy + SSH")
                }
            },
            confirmButton = {
                TextButton(onClick = { showLiteInfoDialog = false }) {
                    Text("OK")
                }
            }
        )
    }

    // Share dialog
    if (showShareDialog) {
        AlertDialog(
            onDismissRequest = { showShareDialog = false },
            title = { Text("Share Aryana") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "How would you like to share the app?",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    TextButton(
                        onClick = { showShareDialog = false; shareApk(context) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("APK File") }
                    TextButton(
                        onClick = { showShareDialog = false; shareGithubLink(context) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("GitHub Link") }
                    TextButton(
                        onClick = { showShareDialog = false; shareTelegramLink(context) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Telegram Channel") }
                }
            },
            confirmButton = {}
        )
    }

    // Delete confirmation dialog
    profileToDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { profileToDelete = null },
            title = { Text("Delete Profile") },
            text = { Text("Are you sure you want to delete \"${profile.name}\"?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteProfile(profile)
                        profileToDelete = null
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { profileToDelete = null }) { Text("Cancel") }
            }
        )
    }

    // Delete all confirmation dialog
    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text("Delete All Profiles") },
            text = {
                Text("Are you sure you want to delete all profiles? This cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteAllProfiles()
                        showDeleteAllDialog = false
                    }
                ) { Text("Delete All") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Delete duplicate profiles confirmation dialog
    if (showDeleteDuplicatesDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDuplicatesDialog = false },
            title = { Text("Delete Duplicate Profiles") },
            text = {
                Text("This will remove profiles with identical connection settings, keeping one copy of each.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteDuplicateProfiles()
                        showDeleteDuplicatesDialog = false
                    }
                ) { Text("Delete Duplicates") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDuplicatesDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Advanced Delete Options Dialog (Matching PattNG)
    if (showDeleteOptionsDialog) {
        val currentGroup = groupTabs.getOrNull(selectedCategoryIndex)?.first ?: "All"
        AlertDialog(
            onDismissRequest = { showDeleteOptionsDialog = false },
            title = { Text("Delete Configs") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            showDeleteOptionsDialog = false
                            viewModel.deleteProfilesMatching {
                                val res = uiState.pingResults[it.id]
                                res is PingResult.Error
                            }
                            scope.launch { snackbarHostState.showSnackbar("Deleted failed (-1 ms) configs") }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete Failed (-1 ms) Configs")
                    }

                    Button(
                        onClick = {
                            showDeleteOptionsDialog = false
                            viewModel.deleteProfilesMatching {
                                uiState.pingResults[it.id] is PingResult.Success
                            }
                            scope.launch { snackbarHostState.showSnackbar("Deleted alive (green) configs") }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Delete Alive (Green) Configs")
                    }

                    if (currentGroup != "All") {
                        OutlinedButton(
                            onClick = {
                                showDeleteOptionsDialog = false
                                viewModel.deleteProfilesMatching { it.subscriptionGroup == currentGroup }
                                scope.launch { snackbarHostState.showSnackbar("Deleted configs in $currentGroup") }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Delete Entire Subscription ($currentGroup)")
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            showDeleteOptionsDialog = false
                            viewModel.deleteDuplicateProfiles()
                            scope.launch { snackbarHostState.showSnackbar("Deleted duplicate configs") }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Delete Duplicate Configs")
                    }

                    OutlinedButton(
                        onClick = {
                            showDeleteOptionsDialog = false
                            showDeleteAllDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Delete All Configs")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDeleteOptionsDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Advanced Export Options Dialog (Matching PattNG)
    if (showExportOptionsDialog) {
        val currentGroup = groupTabs.getOrNull(selectedCategoryIndex)?.first ?: "All"
        AlertDialog(
            onDismissRequest = { showExportOptionsDialog = false },
            title = { Text("Export Configs to Clipboard") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            showExportOptionsDialog = false
                            val text = viewModel.exportProfilesToClipboard {
                                uiState.pingResults[it.id] is PingResult.Success
                            }
                            if (text.isNotBlank()) {
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(text))
                                scope.launch { snackbarHostState.showSnackbar("Copied alive configs to clipboard") }
                            } else {
                                scope.launch { snackbarHostState.showSnackbar("No alive configs found") }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Export Alive (Positive Ping) Configs")
                    }

                    Button(
                        onClick = {
                            showExportOptionsDialog = false
                            val text = viewModel.exportProfilesToClipboard {
                                val res = uiState.pingResults[it.id]
                                res is PingResult.Error
                            }
                            if (text.isNotBlank()) {
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(text))
                                scope.launch { snackbarHostState.showSnackbar("Copied failed (-1 ms) configs to clipboard") }
                            } else {
                                scope.launch { snackbarHostState.showSnackbar("No failed configs found") }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Export Failed (-1 ms) Configs")
                    }

                    if (currentGroup != "All") {
                        OutlinedButton(
                            onClick = {
                                showExportOptionsDialog = false
                                val text = viewModel.exportProfilesToClipboard { it.subscriptionGroup == currentGroup }
                                if (text.isNotBlank()) {
                                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(text))
                                    scope.launch { snackbarHostState.showSnackbar("Copied subscription configs to clipboard") }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Export Entire Subscription ($currentGroup)")
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            showExportOptionsDialog = false
                            val text = viewModel.exportProfilesToClipboard { true }
                            if (text.isNotBlank()) {
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(text))
                                scope.launch { snackbarHostState.showSnackbar("Copied all configs to clipboard") }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Export All Configs (All Subscriptions)")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showExportOptionsDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Battery optimization dialog (shown once on first connect)
    if (showBatteryOptDialog) {
        AlertDialog(
            onDismissRequest = {
                showBatteryOptDialog = false
                // Mark as prompted so we don't ask again
                context.getSharedPreferences("vpn_service_state", Context.MODE_PRIVATE)
                    .edit().putBoolean("battery_opt_prompted", true).apply()
                // Proceed with connect regardless
                if (pendingConnectAfterBatteryPrompt) {
                    pendingConnectAfterBatteryPrompt = false
                    val chain = pendingChainAfterBatteryPrompt
                    if (chain != null) {
                        pendingChainAfterBatteryPrompt = null
                        proceedWithChainConnect(chain)
                    } else {
                        proceedWithConnect(pendingProfileAfterBatteryPrompt)
                        pendingProfileAfterBatteryPrompt = null
                    }
                }
            },
            title = { Text("Disable Battery Optimization") },
            text = {
                Text("For reliable VPN operation, disable battery optimization for Aryana. " +
                     "Without this, Android may suspend the VPN when the screen is off.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBatteryOptDialog = false
                        context.getSharedPreferences("vpn_service_state", Context.MODE_PRIVATE)
                            .edit().putBoolean("battery_opt_prompted", true).apply()
                        // Open battery optimization settings
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            try {
                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            } catch (_: Exception) { }
                        }
                        // Proceed with connect
                        if (pendingConnectAfterBatteryPrompt) {
                            pendingConnectAfterBatteryPrompt = false
                            val chain = pendingChainAfterBatteryPrompt
                            if (chain != null) {
                                pendingChainAfterBatteryPrompt = null
                                proceedWithChainConnect(chain)
                            } else {
                                proceedWithConnect(pendingProfileAfterBatteryPrompt)
                                pendingProfileAfterBatteryPrompt = null
                            }
                        }
                    }
                ) { Text("Disable") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showBatteryOptDialog = false
                        context.getSharedPreferences("vpn_service_state", Context.MODE_PRIVATE)
                            .edit().putBoolean("battery_opt_prompted", true).apply()
                        // Proceed with connect without disabling
                        if (pendingConnectAfterBatteryPrompt) {
                            pendingConnectAfterBatteryPrompt = false
                            val chain = pendingChainAfterBatteryPrompt
                            if (chain != null) {
                                pendingChainAfterBatteryPrompt = null
                                proceedWithChainConnect(chain)
                            } else {
                                proceedWithConnect(pendingProfileAfterBatteryPrompt)
                                pendingProfileAfterBatteryPrompt = null
                            }
                        }
                    }
                ) { Text("Skip") }
            }
        )
    }

    // Import preview dialog
    uiState.importPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelImport() },
            title = { Text("Import Profiles") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${preview.profiles.size} profile(s) found:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    preview.profiles.forEach { profile ->
                        Text(
                            text = if (profile.isLocked) "\u2022 ${profile.name} (Locked)" else "\u2022 ${profile.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (preview.warnings.isNotEmpty()) {
                        Text(
                            text = "Warnings:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        preview.warnings.forEach { warning ->
                            Text(
                                text = "\u2022 $warning",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmImport() }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelImport() }) { Text("Cancel") }
            }
        )
    }

    // QR code share dialog
    uiState.qrCodeData?.let { qrData ->
        QrCodeDialog(
            profileName = qrData.profileName,
            configUri = qrData.configUri,
            onDismiss = { viewModel.clearQrCode() }
        )
    }

    // Export lock dialog
    exportLockProfile?.let { profile ->
        // If profile is already locked+sharable, skip dialog and re-export directly
        if (profile.isLocked && profile.allowSharing) {
            LaunchedEffect(profile) {
                if (exportLockMode == "qr") {
                    viewModel.showQrCodeLockedReExport(profile)
                } else {
                    viewModel.reExportLockedProfile(profile)
                }
                exportLockProfile = null
            }
        } else if (!profile.isLocked) {
            AlertDialog(
                onDismissRequest = { exportLockProfile = null },
                title = { Text(if (exportLockMode == "qr") "Share QR Code" else "Export Profile") },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // SSH security warning when sharing with root credentials
                        if (profile.sshUsername.equals("root", ignoreCase = true) &&
                            profile.tunnelType in listOf(
                                TunnelType.SSH, TunnelType.DNSTT_SSH,
                                TunnelType.SLIPSTREAM_SSH, TunnelType.NOIZDNS_SSH,
                                TunnelType.VAYDNS_SSH, TunnelType.NAIVE_SSH
                            )
                        ) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "This profile contains SSH credentials. Avoid using a root account or password auth — anyone with this profile can access your server. Use a restricted user with key-based authentication instead.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Lock for distribution",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Switch(
                                checked = exportLockEnabled,
                                onCheckedChange = {
                                    exportLockEnabled = it
                                    if (!it) exportHideResolvers = false
                                }
                            )
                        }
                        if (!exportLockEnabled) {
                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text(
                                    text = "Export Format:",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = !exportFormatStandard,
                                        onClick = { exportFormatStandard = false }
                                    )
                                    Text("Aryana Format", style = MaterialTheme.typography.bodySmall)
                                    Spacer(Modifier.width(12.dp))
                                    RadioButton(
                                        selected = exportFormatStandard,
                                        onClick = { exportFormatStandard = true }
                                    )
                                    Text("Standard (vless://, ss://, ...)", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if (exportLockEnabled) {
                            OutlinedTextField(
                                value = exportLockPassword,
                                onValueChange = { exportLockPassword = it },
                                label = { Text("Lock Password") },
                                visualTransformation = if (exportLockPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { exportLockPasswordVisible = !exportLockPasswordVisible }) {
                                        Icon(
                                            imageVector = if (exportLockPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = if (exportLockPasswordVisible) "Hide password" else "Show password"
                                        )
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Hide DNS resolver",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Switch(
                                    checked = exportHideResolvers,
                                    onCheckedChange = { exportHideResolvers = it }
                                )
                            }
                            if (exportHideResolvers) {
                                Text(
                                    text = "Resolver addresses will be hidden. Old app versions won't be able to import this profile.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // Expiration toggle + days
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Set expiration",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Switch(
                                    checked = exportLockExpiry,
                                    onCheckedChange = { exportLockExpiry = it }
                                )
                            }
                            if (exportLockExpiry) {
                                OutlinedTextField(
                                    value = exportLockExpiryDays,
                                    onValueChange = { exportLockExpiryDays = it.filter { c -> c.isDigit() } },
                                    label = { Text("Expires in (days)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            // Allow re-sharing
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Allow re-sharing",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Switch(
                                    checked = exportLockAllowSharing,
                                    onCheckedChange = { exportLockAllowSharing = it }
                                )
                            }

                            // Target device ID
                            OutlinedTextField(
                                value = exportLockDeviceId,
                                onValueChange = { exportLockDeviceId = it },
                                label = { Text("Target Device ID (optional)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "Device ID can be found in Settings on the target device",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val p = exportLockProfile ?: return@TextButton
                            if (exportLockEnabled) {
                                val expiryMs = if (exportLockExpiry) {
                                    val days = exportLockExpiryDays.toLongOrNull() ?: 30
                                    System.currentTimeMillis() + days * 24 * 60 * 60 * 1000L
                                } else 0L
                                if (exportLockMode == "qr") {
                                    viewModel.showQrCodeLocked(p, exportLockPassword, expiryMs, exportLockAllowSharing, exportLockDeviceId, exportHideResolvers)
                                } else {
                                    viewModel.exportProfileLocked(p, exportLockPassword, expiryMs, exportLockAllowSharing, exportLockDeviceId, exportHideResolvers)
                                }
                            } else {
                                if (exportLockMode == "qr") {
                                    viewModel.showQrCode(p, exportHideResolvers)
                                } else {
                                    if (exportFormatStandard) {
                                        viewModel.exportStandardUri(p)
                                    } else {
                                        viewModel.exportProfile(p, exportHideResolvers)
                                    }
                                }
                            }
                            exportHideResolvers = false
                            exportLockProfile = null
                        },
                        enabled = !exportLockEnabled || exportLockPassword.isNotBlank()
                    ) { Text(if (exportLockMode == "qr") "Share" else "Export") }
                },
                dismissButton = {
                    TextButton(onClick = { exportLockProfile = null }) { Text("Cancel") }
                }
            )
        }
    }

    // 1. Subscription Groups Dialog
    if (showSubscriptionGroupsDialog) {
        var newSubUrl by remember { mutableStateOf("") }
        var newSubName by remember { mutableStateOf("") }
        var showAddSubField by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showSubscriptionGroupsDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Subscription Groups", style = MaterialTheme.typography.titleLarge)
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Manage subscription links, update all configs, or add new provider groups.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (subGroups.isEmpty()) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "No subscription groups configured yet. Add a subscription URL below to auto-fetch server nodes.",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    } else {
                        subGroups.forEach { groupName ->
                            val count = uiState.profiles.count { it.subscriptionGroup == groupName }
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(groupName, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "$count configs",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(onClick = {
                                        viewModel.deleteProfilesMatching { it.subscriptionGroup == groupName }
                                        scope.launch { snackbarHostState.showSnackbar("Deleted subscription group: $groupName") }
                                    }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }

                    if (showAddSubField) {
                        OutlinedTextField(
                            value = newSubName,
                            onValueChange = { newSubName = it },
                            label = { Text("Group Name") },
                            placeholder = { Text("e.g. VIP Subscription") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = newSubUrl,
                            onValueChange = { newSubUrl = it },
                            label = { Text("Subscription URL") },
                            placeholder = { Text("https://... or vless/vmess/sub") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (!showAddSubField) {
                                    showAddSubField = true
                                } else if (newSubUrl.isNotBlank()) {
                                    val group = newSubName.ifBlank { "Subscription" }
                                    viewModel.saveSubscription(group, newSubUrl) { count, err ->
                                        scope.launch {
                                            if (err == null) {
                                                snackbarHostState.showSnackbar("Saved and fetched $count configs into $group")
                                            } else {
                                                snackbarHostState.showSnackbar("Error: $err")
                                            }
                                        }
                                    }
                                    showAddSubField = false
                                    newSubUrl = ""
                                    newSubName = ""
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (showAddSubField) "Save & Import" else "Add Group")
                        }

                        Button(
                            onClick = {
                                viewModel.updateAllSubscriptions { count, err ->
                                    scope.launch {
                                        if (err != null) {
                                            snackbarHostState.showSnackbar("Update failed: $err")
                                        } else {
                                            snackbarHostState.showSnackbar("Updated $count configs across all subscriptions")
                                        }
                                    }
                                }
                                showSubscriptionGroupsDialog = false
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Update All")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSubscriptionGroupsDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // 2. Routing Settings Dialog
    if (showRoutingSettingsDialog) {
        var routingMode by remember { mutableStateOf("Rule") }
        var bypassLan by remember { mutableStateOf(true) }
        var bypassIran by remember { mutableStateOf(true) }
        var blockAds by remember { mutableStateOf(true) }
        var domainStrategy by remember { mutableStateOf("IPIfNonMatch") }

        AlertDialog(
            onDismissRequest = { showRoutingSettingsDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Routing Settings", style = MaterialTheme.typography.titleLarge)
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Routing Mode", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                    listOf("Rule (Bypass LAN and Iran)", "Global (Proxy All)", "Direct (Bypass All)").forEach { mode ->
                        val isSelected = routingMode == mode.split(" ").first()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { routingMode = mode.split(" ").first() }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { routingMode = mode.split(" ").first() }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(mode, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Text("Preset Rules", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Bypass LAN", fontWeight = FontWeight.Medium)
                            Text("Directly connect private IP ranges", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = bypassLan, onCheckedChange = { bypassLan = it })
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Bypass Iran Sites & IPs", fontWeight = FontWeight.Medium)
                            Text("Direct connect .ir domains & national CIDRs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = bypassIran, onCheckedChange = { bypassIran = it })
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Block Ads & Malware", fontWeight = FontWeight.Medium)
                            Text("Drop connections to ad/tracker domains", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = blockAds, onCheckedChange = { blockAds = it })
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Text("Domain Resolution Strategy", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                    listOf("IPIfNonMatch", "IPOnDemand", "AsIs").forEach { strat ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { domainStrategy = strat }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = domainStrategy == strat,
                                onClick = { domainStrategy = strat }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(strat, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.applyRoutingSettings(routingMode, bypassLan, bypassIran, blockAds)
                    showRoutingSettingsDialog = false
                    scope.launch { snackbarHostState.showSnackbar("Routing settings applied") }
                }) {
                    Text("Apply & Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRoutingSettingsDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // 3. Asset Files Dialog
    if (showAssetFilesDialog) {
        var isUpdatingAssets by remember { mutableStateOf(false) }
        var assetUpdateMsg by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showAssetFilesDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Asset Files", style = MaterialTheme.typography.titleLarge)
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        "Routing rule database files for GeoIP and GeoSite domain/IP resolution.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("geoip.dat", fontWeight = FontWeight.Bold)
                                Text("4.32 MB", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            }
                            Text("IP geographical database • 8,420 Iran & Global CIDRs", style = MaterialTheme.typography.bodySmall)
                            Text("Status: Up to date", color = Color(0xFF00FF66), style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("geosite.dat", fontWeight = FontWeight.Bold)
                                Text("8.15 MB", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            }
                            Text("Domain routing categories • 12,850 sites & ad lists", style = MaterialTheme.typography.bodySmall)
                            Text("Status: Up to date", color = Color(0xFF00FF66), style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    if (assetUpdateMsg != null) {
                        Text(assetUpdateMsg!!, color = if (assetUpdateMsg!!.startsWith("Successfully")) Color(0xFF00FF66) else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }

                    Button(
                        onClick = {
                            isUpdatingAssets = true
                            assetUpdateMsg = null
                            viewModel.downloadGeoAssetFiles(context.filesDir) { success, msg ->
                                isUpdatingAssets = false
                                assetUpdateMsg = msg
                            }
                        },
                        enabled = !isUpdatingAssets,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isUpdatingAssets) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Downloading Latest Assets...")
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Update Asset Files")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAssetFilesDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // 4. Backup & Restore Dialog
    if (showBackupRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showBackupRestoreDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Backup & Restore", style = MaterialTheme.typography.titleLarge)
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Export your server profiles and configurations, or restore backups from clipboard and files.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedButton(
                        onClick = {
                            val str = viewModel.exportProfilesToClipboard { true }
                            clipboardManager.setText(AnnotatedString(str))
                            scope.launch {
                                snackbarHostState.showSnackbar("All ${uiState.profiles.size} configs copied to clipboard")
                            }
                            showBackupRestoreDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Export All Configs to Clipboard")
                    }

                    OutlinedButton(
                        onClick = {
                            val str = viewModel.exportProfilesToClipboard { true }
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, str)
                                putExtra(Intent.EXTRA_SUBJECT, "AryanaNG Backup (${uiState.profiles.size} configs)")
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Export Configs Backup"))
                            showBackupRestoreDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Export Backup to File / Share")
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Button(
                        onClick = {
                            showBackupRestoreDialog = false
                            val clipText = clipboardManager.getText()?.text ?: ""
                            if (clipText.isNotBlank()) {
                                viewModel.parseImportConfig(clipText)
                            } else {
                                scope.launch { snackbarHostState.showSnackbar("Clipboard is empty") }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Import Backup from Clipboard")
                    }

                    Button(
                        onClick = {
                            showBackupRestoreDialog = false
                            importFileLauncher.launch(arrayOf("text/plain", "*/*"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Dns, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Import Backup from File")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBackupRestoreDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Sort by Protocol Dialog
    if (showProtocolSortDialog) {
        val allProtocols = remember {
            listOf(
                app.slipnet.domain.model.TunnelType.VLESS_REALITY,
                app.slipnet.domain.model.TunnelType.VLESS,
                app.slipnet.domain.model.TunnelType.MASTERDNS,
                app.slipnet.domain.model.TunnelType.STORMDNS,
                app.slipnet.domain.model.TunnelType.MELI,
                app.slipnet.domain.model.TunnelType.SHADOWSOCKS,
                app.slipnet.domain.model.TunnelType.SSH,
                app.slipnet.domain.model.TunnelType.WIREGUARD,
                app.slipnet.domain.model.TunnelType.DNSTT,
                app.slipnet.domain.model.TunnelType.VMESS,
                app.slipnet.domain.model.TunnelType.TROJAN
            )
        }
        var selectedProtocolOrder by remember { mutableStateOf(allProtocols) }

        AlertDialog(
            onDismissRequest = { showProtocolSortDialog = false },
            title = {
                Text("Protocol Sort Priority", style = MaterialTheme.typography.titleLarge)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 350.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "Tap a protocol to move it to the top priority:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    selectedProtocolOrder.forEachIndexed { index, type ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val reordered = mutableListOf(type)
                                    reordered.addAll(selectedProtocolOrder.filter { it != type })
                                    selectedProtocolOrder = reordered
                                },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${index + 1}. ${type.displayName}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal,
                                    color = if (index == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                Icon(
                                    Icons.Default.KeyboardArrowUp,
                                    contentDescription = "Move to top",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showProtocolSortDialog = false
                    viewModel.sortProfilesByProtocolOrder(selectedProtocolOrder)
                    scope.launch { snackbarHostState.showSnackbar("Sorted by protocol priority") }
                }) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { showProtocolSortDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Invalid Configurations Glassmorphic Dialog
    invalidConfigsErrors?.let { errors ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { invalidConfigsErrors = null }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xEE1E142B),
                border = BorderStroke(1.dp, Color(0x55BB86FC)),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color(0xFFCF6679),
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            "Invalid Configurations",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE8DEF8)
                        )
                    }

                    Text(
                        "The following invalid configurations could not be imported:",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFD0BCFF)
                    )

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(errors) { err ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text("• ", color = Color(0xFFBB86FC), fontSize = 12.sp)
                                Text(
                                    err,
                                    color = Color(0xFFE6E1E5),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    Button(
                        onClick = { invalidConfigsErrors = null },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6B4C7D)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Dismiss", color = Color.White)
                    }
                }
            }
        }
    }

    // Password prompt for encrypted import (the user pasted a slipnet-bundle-enc:// URI)
    uiState.pendingEncryptedImport?.let { encryptedInput ->
        var pw by remember { mutableStateOf("") }
        var pwVisible by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { viewModel.cancelEncryptedImport() },
            title = { Text("Encrypted Bundle") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "This bundle is password-protected. Enter the password to decrypt.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = pw,
                        onValueChange = { pw = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = if (pwVisible) {
                            androidx.compose.ui.text.input.VisualTransformation.None
                        } else {
                            androidx.compose.ui.text.input.PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { pwVisible = !pwVisible }) {
                                Icon(
                                    if (pwVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (pwVisible) "Hide password" else "Show password"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.parseImportConfig(encryptedInput, pw) },
                    enabled = pw.isNotEmpty()
                ) { Text("Decrypt") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelEncryptedImport() }) { Text("Cancel") }
            }
        )
    }

    // Import input dialog
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = {
                showImportDialog = false
                importText = ""
            },
            title = { Text("Import Configs / V2Ray / JSON") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Paste V2Ray (vmess://, vless://, trojan://, ss://, hy2://), JSON config, or slipnet:// link:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp),
                        placeholder = { Text("vmess://... or vless://... or { \"outbounds\": [...] }") },
                        singleLine = false,
                        maxLines = 6
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        TextButton(
                            onClick = {
                                clipboardManager.getText()?.text?.let { clip ->
                                    if (clip.isNotBlank()) importText = clip
                                }
                            }
                        ) { Text("Paste") }
                        TextButton(
                            onClick = {
                                importFileLauncher.launch(arrayOf("text/plain", "*/*"))
                                showImportDialog = false
                                importText = ""
                            }
                        ) { Text("File") }
                        TextButton(
                            onClick = {
                                showImportDialog = false
                                importText = ""
                                qrScanLauncher.launch(ScanOptions().apply {
                                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                    setPrompt("")
                                    setBeepEnabled(false)
                                    setCaptureActivity(QrScannerActivity::class.java)
                                })
                            }
                        ) { Text("QR Code") }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (importText.isNotBlank()) {
                            viewModel.parseImportConfig(importText)
                            showImportDialog = false
                            importText = ""
                        }
                    },
                    enabled = importText.isNotBlank()
                ) { Text("Import") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showImportDialog = false
                        importText = ""
                    }
                ) { Text("Cancel") }
            }
        )
    }

    // First launch About dialog
    if (uiState.showFirstLaunchAbout) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissFirstLaunchAbout() },
            title = { Text("Welcome to Aryana") },
            text = { AboutDialogContent() },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissFirstLaunchAbout() }) {
                    Text("Get Started")
                }
            }
        )
    }

    // Update available dialog
    uiState.availableUpdate?.let { update ->
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { viewModel.dismissUpdate() },
            title = { Text("Update Available") },
            text = {
                Text("Version ${update.versionName} is available. You are on ${BuildConfig.VERSION_NAME}.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissUpdate()
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(update.downloadUrl)
                    )
                    context.startActivity(intent)
                }) {
                    Text("Download")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.skipUpdate() }) {
                        Text("Skip")
                    }
                    TextButton(onClick = { viewModel.dismissUpdate() }) {
                        Text("Later")
                    }
                }
            }
        )
    }
}

// ── ChainListItem ───────────────────────────────────────────────────────

@Composable
private fun ChainListItem(
    chain: ProfileChain,
    profiles: List<ServerProfile>,
    isSelected: Boolean = false,
    isConnected: Boolean = false,
    onClick: () -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val layerNames = chain.profileIds.mapNotNull { id ->
        profiles.find { it.id == id }?.let { "${it.name} (${it.tunnelType.displayName})" }
    }

    val borderColor = when {
        isConnected -> ConnectedGreen
        isSelected -> MaterialTheme.colorScheme.primary
        else -> null
    }
    val cardShape = RoundedCornerShape(12.dp)

    val containerColor = when {
        isConnected -> ConnectedGreen.copy(alpha = 0.08f)
        isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else -> MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (borderColor != null) Modifier.border(2.dp, borderColor, cardShape)
                else Modifier
            )
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = cardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = chain.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isConnected) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Connected",
                            style = MaterialTheme.typography.labelSmall,
                            color = ConnectedGreen,
                            modifier = Modifier
                                .background(
                                    ConnectedGreen.copy(alpha = 0.15f),
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    } else if (isSelected) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Selected",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = layerNames.joinToString(" \u2192 "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${chain.profileIds.size}-layer chain",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onEditClick,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit chain",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onDeleteClick,
                    enabled = !isConnected,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete chain",
                        modifier = Modifier.size(18.dp),
                        tint = if (!isConnected)
                            MaterialTheme.colorScheme.onSurfaceVariant
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    )
                }
            }
        }
    }
}

// ── ConnectionStatusStrip ───────────────────────────────────────────────

/**
 * Short, non-reversible identifier for a tunnel key.
 *
 * The server prints the same 8 hex chars at startup (security.KeyFingerprint),
 * so an operator can confirm both ends loaded the same key without the key ever
 * appearing in a screenshot, a bug report or journald.
 *
 * Returns null for a blank key (non-DNSTT profiles, or not configured yet).
 */
private fun tunnelKeyFingerprint(key: String): String? {
    if (key.isBlank()) return null
    return try {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray())
        digest.joinToString("") { "%02x".format(it) }.substring(0, 8)
    } catch (e: Exception) {
        null
    }
}

@Composable
private fun ConnectionStatusStrip(
    connectionState: ConnectionState,
    activeProfile: ServerProfile?,
    activeChain: ProfileChain? = null,
    isProxyOnly: Boolean,
    snowflakeBootstrapProgress: Int,
    uploadSpeed: Long = 0,
    downloadSpeed: Long = 0,
    totalUpload: Long = 0,
    totalDownload: Long = 0,
    sleepTimerRemainingSeconds: Int = 0,
    onCancelSleepTimer: () -> Unit = {},
    dnsWarning: String? = null,
    dnsPoolScan: app.slipnet.tunnel.DnsPoolScanState = app.slipnet.tunnel.DnsPoolScanState(),
    onPingConnected: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isConnected = connectionState is ConnectionState.Connected
    val isConnecting = connectionState is ConnectionState.Connecting ||
            connectionState is ConnectionState.Disconnecting
    val isError = connectionState is ConnectionState.Error
    val showTorProgress = connectionState is ConnectionState.Connecting &&
            snowflakeBootstrapProgress in 0..99
    val showDnsPoolProgress = dnsPoolScan.isRunning && dnsPoolScan.total > 0

    val statusColor by animateColorAsState(
        targetValue = when {
            isConnected -> ConnectedGreen
            isConnecting -> ConnectingOrange
            isError -> DisconnectedRed
            else -> MaterialTheme.colorScheme.outline
        },
        animationSpec = tween(300),
        label = "statusColor"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = isConnected) { onPingConnected() },
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 64.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status indicator dot
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Status text + profile name
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            isConnected && isProxyOnly -> "Proxy Active"
                            isConnected -> "Connected"
                            connectionState is ConnectionState.Connecting -> "Connecting..."
                            connectionState is ConnectionState.Disconnecting -> "Disconnecting..."
                            isError -> "Connection Failed"
                            else -> "Not Connected"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isConnected || isConnecting) statusColor
                        else MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = when {
                            isConnected && connectionState is ConnectionState.Connected ->
                                connectionState.chainName ?: connectionState.profile.name
                            isError && connectionState is ConnectionState.Error ->
                                connectionState.message
                            activeChain != null -> activeChain.name
                            activeProfile != null -> activeProfile.name
                            else -> "No profile selected"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isError) DisconnectedRed
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Traffic stats to the RIGHT side with clear English labels
                if (isConnected) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(
                            text = "Speed: ${TrafficStats.formatBytes(downloadSpeed + uploadSpeed)}/s",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Total: ${TrafficStats.formatBytes(totalDownload + totalUpload)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // DNS warning
            AnimatedVisibility(
                visible = isConnected && dnsWarning != null,
                enter = expandVertically(animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)),
                exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(200))
            ) {
                Text(
                    text = dnsWarning ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFF9800),
                    modifier = Modifier.padding(start = 22.dp, top = 6.dp)
                )
            }

            // Sleep timer countdown
            AnimatedVisibility(
                visible = isConnected && sleepTimerRemainingSeconds > 0,
                enter = expandVertically(animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)),
                exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(200))
            ) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 22.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Timer,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Sleep: ${formatCountdown(sleepTimerRemainingSeconds)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = onCancelSleepTimer,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(
                                text = "Cancel",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }

            // DNS pool scan progress (per-profile auto-pick).
            // Bar always starts at 0 each scan and animates up to the real
            // tested/total fraction — the Animatable is keyed to the scan's
            // running flag so it resets to 0 every time a scan begins, no
            // matter how many results have already landed in the state flow.
            AnimatedVisibility(
                visible = showDnsPoolProgress,
                enter = expandVertically(animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)),
                exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(200))
            ) {
                val targetProgress = if (dnsPoolScan.total > 0)
                    dnsPoolScan.probed.toFloat() / dnsPoolScan.total
                else 0f
                val progressAnim = remember(dnsPoolScan.isRunning) { Animatable(0f) }
                LaunchedEffect(dnsPoolScan.isRunning, targetProgress) {
                    if (dnsPoolScan.isRunning) {
                        progressAnim.animateTo(targetProgress, tween(durationMillis = 400))
                    }
                }
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LinearProgressIndicator(
                            progress = { progressAnim.value },
                            modifier = Modifier
                                .weight(1f)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = ConnectingOrange,
                            trackColor = ConnectingOrange.copy(alpha = 0.2f),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "DNS ${dnsPoolScan.probed}/${dnsPoolScan.total} · ${dnsPoolScan.alive} alive · ${dnsPoolScan.verified}✓",
                            style = MaterialTheme.typography.labelSmall,
                            color = ConnectingOrange
                        )
                    }
                }
            }

            // Tor bootstrap progress
            AnimatedVisibility(
                visible = showTorProgress,
                enter = expandVertically(animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)),
                exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(200))
            ) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LinearProgressIndicator(
                            progress = { snowflakeBootstrapProgress / 100f },
                            modifier = Modifier
                                .weight(1f)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = ConnectingOrange,
                            trackColor = ConnectingOrange.copy(alpha = 0.2f),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Tor: $snowflakeBootstrapProgress%",
                            style = MaterialTheme.typography.labelSmall,
                            color = ConnectingOrange
                        )
                    }
                }
            }
        }
    }
}

private fun formatCountdown(totalSeconds: Int): String {
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

// ── Connect FAB ─────────────────────────────────────────────────────────

@Composable
private fun ConnectFab(
    connectionState: ConnectionState,
    hasProfile: Boolean,
    snowflakeBootstrapProgress: Int,
    onToggleConnection: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isConnected = connectionState is ConnectionState.Connected
    val isConnecting = connectionState is ConnectionState.Connecting ||
            connectionState is ConnectionState.Disconnecting

    val statusColor by animateColorAsState(
        targetValue = when {
            isConnected -> ConnectedGreen
            isConnecting -> ConnectingOrange
            connectionState is ConnectionState.Error -> DisconnectedRed
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(300),
        label = "connectFabColor"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        FloatingActionButton(
            onClick = onToggleConnection,
            containerColor = statusColor,
            modifier = Modifier
                .size(56.dp)
                .then(if (isConnecting) Modifier.scale(pulseScale) else Modifier),
        ) {
            Icon(
                imageVector = Icons.Default.PowerSettingsNew,
                contentDescription = if (isConnected) "Disconnect" else "Connect",
                tint = when {
                    isConnected || isConnecting -> Color.White
                    hasProfile -> MaterialTheme.colorScheme.onPrimary
                    else -> MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f)
                }
            )
        }
    }
}

// ── FAB menu option ─────────────────────────────────────────────────────

@Composable
private fun AddMenuOption(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall
            )
            if (description.isNotEmpty()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AddMenuGridItem(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isMasterDnsTile: Boolean = false
) {
    val masterDnsColor = Color(0xFF00E5FF)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .then(if (isMasterDnsTile) Modifier.background(Color(0xFF00384D).copy(alpha = 0.5f)).border(1.5.dp, masterDnsColor, RoundedCornerShape(12.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isMasterDnsTile) masterDnsColor else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = if (isMasterDnsTile) masterDnsColor else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (isMasterDnsTile) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

// ── Share helpers ────────────────────────────────────────────────────────

private fun shareGithubLink(context: Context) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "AryanaNG")
        putExtra(Intent.EXTRA_TEXT, "Download AryanaNG:\nhttps://github.com/doodoolyasin/Meli/releases/latest")
    }
    context.startActivity(Intent.createChooser(intent, "Share AryanaNG"))
}

private fun shareTelegramLink(context: Context) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "AryanaNG")
        putExtra(Intent.EXTRA_TEXT, "Join AryanaNG on Telegram:\nhttps://t.me/AryanaNG")
    }
    context.startActivity(Intent.createChooser(intent, "Share AryanaNG"))
}

private fun shareApk(context: Context) {
    try {
        val appInfo = context.applicationInfo
        val sharedDir = java.io.File(context.cacheDir, "shared")
        sharedDir.mkdirs()

        val splits = appInfo.splitSourceDirs
        if (splits.isNullOrEmpty()) {
            val sourceApk = java.io.File(appInfo.sourceDir)
            val sharedApk = java.io.File(sharedDir, "AryanaNG-v${app.slipnet.BuildConfig.VERSION_NAME}.apk")
            sourceApk.copyTo(sharedApk, overwrite = true)

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", sharedApk)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share AryanaNG"))
        } else {
            val apksFile = java.io.File(sharedDir, "AryanaNG-v${app.slipnet.BuildConfig.VERSION_NAME}.apks")
            java.util.zip.ZipOutputStream(apksFile.outputStream().buffered()).use { zip ->
                val allApks = listOf(appInfo.sourceDir) + splits
                for (path in allApks) {
                    val file = java.io.File(path)
                    zip.putNextEntry(java.util.zip.ZipEntry(file.name))
                    file.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apksFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share AryanaNG"))
        }
    } catch (_: Exception) { }
}

private fun performInjectorHaptic(context: Context, durationMs: Long = 40L) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator?.vibrate(
                VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(durationMs)
            }
        }
    } catch (_: Exception) { }
}
