package app.slipnet.presentation.settings

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.CircularProgressIndicator
import android.provider.Settings
import app.slipnet.BuildConfig
import app.slipnet.presentation.common.components.AboutDialogContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.slipnet.data.local.datastore.DarkMode
import app.slipnet.data.local.datastore.DnsWorkerMode
import app.slipnet.data.local.datastore.DomainRoutingMode
import app.slipnet.data.local.datastore.SplitTunnelingMode
import app.slipnet.data.local.datastore.SshCipher
import app.slipnet.tunnel.GeoBypassCountry
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToScanner: (() -> Unit)? = null,
    onNavigateToAppSelector: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    var showDarkModeDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showRemoteDnsDialog by remember { mutableStateOf(false) }
    var showGlobalResolverDialog by remember { mutableStateOf(false) }
    var showDnsPoolDialog by remember { mutableStateOf(false) }
    var showMtuDialog by remember { mutableStateOf(false) }
    var showDnsWorkerDialog by remember { mutableStateOf(false) }
    var showResetSettingsDialog by remember { mutableStateOf(false) }

    // Proxy settings - local state for text fields to avoid cursor jumps from async DataStore round-trip
    var proxyPort by remember { mutableStateOf(uiState.proxyListenPort.toString()) }
    var httpProxyPort by remember { mutableStateOf(uiState.httpProxyPort.toString()) }
    var proxyAuthUsername by remember { mutableStateOf(uiState.proxyAuthUsername) }
    var proxyAuthPassword by remember { mutableStateOf(uiState.proxyAuthPassword) }

    // Sync local state when DataStore values load (initial default → actual saved value)
    LaunchedEffect(uiState.proxyListenPort) {
        proxyPort = uiState.proxyListenPort.toString()
    }
    LaunchedEffect(uiState.httpProxyPort) {
        httpProxyPort = uiState.httpProxyPort.toString()
    }
    LaunchedEffect(uiState.proxyAuthUsername) {
        if (uiState.proxyAuthUsername != proxyAuthUsername) proxyAuthUsername = uiState.proxyAuthUsername
    }
    LaunchedEffect(uiState.proxyAuthPassword) {
        if (uiState.proxyAuthPassword != proxyAuthPassword) proxyAuthPassword = uiState.proxyAuthPassword
    }

    val addressOptions = getAddressOptions()

    // Battery optimization state
    val context = LocalContext.current
    var isBatteryOptimized by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
                isBatteryOptimized = !pm.isIgnoringBatteryOptimizations(context.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showAboutDialog = true }) {
                        Icon(Icons.Default.Help, contentDescription = "About")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = 12.dp + navBarPadding.calculateBottomPadding()
                ),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ── 1. VPN Settings ───────────────────────────────────────────
            SettingsSection(
                title = "VPN Settings",
                subtitle = "Tunnel & network parameters"
            ) {
                // VPN MTU
                ClickableSettingItem(
                    icon = Icons.Default.Speed,
                    title = "VPN MTU",
                    description = "Packet size: ${uiState.vpnMtu} bytes (1280–1500)",
                    onClick = { showMtuDialog = true }
                )

                SettingsDivider()

                // Block IPv6
                SwitchSettingItem(
                    icon = Icons.Default.Block,
                    title = "Block IPv6",
                    description = "Prevent IPv6 traffic and DNS leakage across tunnel",
                    checked = uiState.blockIpv6,
                    onCheckedChange = { viewModel.setBlockIpv6(it) }
                )

                SettingsDivider()

                // Custom Remote DNS
                ClickableSettingItem(
                    icon = Icons.Default.Dns,
                    title = "Custom Remote DNS",
                    description = if (uiState.remoteDnsMode == "custom") {
                        val primary = uiState.customRemoteDns.ifBlank { "8.8.8.8" }
                        val fallback = uiState.customRemoteDnsFallback.ifBlank { "1.1.1.1" }
                        "Custom ($primary, $fallback)"
                    } else {
                        "Default (8.8.8.8, 1.1.1.1)"
                    },
                    onClick = { showRemoteDnsDialog = true }
                )

                SettingsDivider()

                // Client DNS Tunnel Options (Payload Size & Tuning)
                ClickableSettingItem(
                    icon = Icons.Default.SettingsEthernet,
                    title = "DNS Tunnel Tuning & Resolvers",
                    description = if (uiState.globalResolverList.isNotBlank()) {
                        "${uiState.globalResolverList.split(",", "\n").filter { it.isNotBlank() }.size} custom resolvers · ${uiState.dnsWorkerMode.displayName} Workers"
                    } else {
                        "Auto-tune (dynamic payload) · ${uiState.dnsWorkerMode.displayName} Workers"
                    },
                    onClick = { showGlobalResolverDialog = true }
                )

                SettingsDivider()

                // System VPN settings
                ClickableSettingItem(
                    icon = Icons.Default.SettingsEthernet,
                    title = "System VPN Settings",
                    description = "Open Android VPN settings to enable Always-on VPN",
                    onClick = {
                        try {
                            val intent = Intent(Settings.ACTION_VPN_SETTINGS)
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            // Fallback
                        }
                    }
                )
            }

            // ── 2. Core & Inbound Proxy Settings ───────────────────────────
            SettingsSection(
                title = "Inbound & Local Proxy",
                subtitle = "SOCKS5 & HTTP inbound servers"
            ) {
                val portsConflict = proxyPort.toIntOrNull() == httpProxyPort.toIntOrNull() && proxyPort.isNotBlank()

                // Listen Address (Proxy sharing on LAN)
                AddressSettingItem(
                    value = uiState.proxyListenAddress,
                    options = addressOptions,
                    onValueChange = { viewModel.setProxyListenAddress(it) }
                )

                SettingsDivider()

                // SOCKS5 Listen Port
                TextFieldSettingItem(
                    icon = Icons.Default.Numbers,
                    title = "SOCKS5 Port",
                    value = proxyPort,
                    placeholder = "10808",
                    supportingText = if (portsConflict) "Must differ from HTTP proxy port" else "Local SOCKS5 proxy inbound port",
                    isError = portsConflict,
                    keyboardType = KeyboardType.Number,
                    onValueChange = { text ->
                        proxyPort = text
                        text.toIntOrNull()?.let { viewModel.setProxyListenPort(it) }
                    }
                )

                SettingsDivider()

                // SOCKS5 Authentication
                SwitchSettingItem(
                    icon = Icons.Default.Lock,
                    title = "SOCKS5 Authentication",
                    description = if (uiState.proxyAuthEnabled) "Credentials required to access proxy"
                                  else "Allow local connections without credentials",
                    checked = uiState.proxyAuthEnabled,
                    onCheckedChange = { viewModel.setProxyAuthEnabled(it) }
                )

                if (uiState.proxyAuthEnabled) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = proxyAuthUsername,
                            onValueChange = { text ->
                                proxyAuthUsername = text
                                viewModel.setProxyAuthUsername(text)
                            },
                            label = { Text("Username") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = proxyAuthPassword,
                            onValueChange = { text ->
                                proxyAuthPassword = text
                                viewModel.setProxyAuthPassword(text)
                            },
                            label = { Text("Password") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                SettingsDivider()

                // HTTP Inbound Proxy
                SwitchSettingItem(
                    icon = Icons.Default.Lan,
                    title = "Enable HTTP Proxy",
                    description = "Enable HTTP proxy inbound for non-SOCKS5 apps and LAN devices",
                    checked = uiState.httpProxyEnabled,
                    onCheckedChange = { viewModel.setHttpProxyEnabled(it) }
                )

                if (uiState.httpProxyEnabled) {
                    SettingsDivider()
                    TextFieldSettingItem(
                        icon = Icons.Default.Numbers,
                        title = "HTTP Proxy Port",
                        value = httpProxyPort,
                        placeholder = "8080",
                        supportingText = if (portsConflict) "Must differ from SOCKS5 port" else "Local HTTP proxy inbound port",
                        isError = portsConflict,
                        keyboardType = KeyboardType.Number,
                        onValueChange = { text ->
                            httpProxyPort = text
                            text.toIntOrNull()?.let { viewModel.setHttpProxyPort(it) }
                        }
                    )
                }

                SettingsDivider()

                // Disable QUIC
                SwitchSettingItem(
                    icon = Icons.Default.Block,
                    title = "Disable QUIC",
                    description = "Block QUIC/UDP 443 to force TCP and avoid carrier packet drops",
                    checked = uiState.disableQuic,
                    onCheckedChange = { viewModel.setDisableQuic(it) }
                )

                SettingsDivider()

                // Core Log Level / Debug Logging
                SwitchSettingItem(
                    icon = Icons.Default.BugReport,
                    title = "Verbose Logging",
                    description = "Log detailed connection handshakes in Connection LOG",
                    checked = uiState.debugLogging,
                    onCheckedChange = { viewModel.setDebugLogging(it) }
                )
            }

            // ── 3. Mode & Connection Settings ──────────────────────────────
            SettingsSection(
                title = "Mode & Connection",
                subtitle = "App lifecycle and network behavior"
            ) {
                // Proxy-only mode vs VPN Service
                SwitchSettingItem(
                    icon = Icons.Default.SettingsEthernet,
                    title = "Proxy-only Mode",
                    description = "Expose local SOCKS5 proxy without establishing VPN interface",
                    checked = uiState.proxyOnlyMode,
                    onCheckedChange = { viewModel.setProxyOnlyMode(it) }
                )

                SettingsDivider()

                // Auto-connect on boot
                SwitchSettingItem(
                    icon = Icons.Default.PowerSettingsNew,
                    title = "Auto-connect on Boot",
                    description = "Automatically connect to active profile on system startup",
                    checked = uiState.autoConnectOnBoot,
                    onCheckedChange = { viewModel.setAutoConnectOnBoot(it) }
                )

                SettingsDivider()

                // Auto-reconnect
                SwitchSettingItem(
                    icon = Icons.Default.Sync,
                    title = "Auto-reconnect",
                    description = "Automatically reconnect when network connection changes or drops",
                    checked = uiState.autoReconnect,
                    onCheckedChange = { viewModel.setAutoReconnect(it) }
                )

                SettingsDivider()

                // Kill switch
                SwitchSettingItem(
                    icon = Icons.Default.Shield,
                    title = "Kill Switch",
                    description = "Block all internet traffic if VPN disconnects unexpectedly",
                    checked = uiState.killSwitch,
                    onCheckedChange = { viewModel.setKillSwitch(it) }
                )

                SettingsDivider()

                // Battery optimization
                ClickableSettingItem(
                    icon = Icons.Default.BatteryAlert,
                    title = "Battery Optimization",
                    description = if (isBatteryOptimized) "Not exempted — system may terminate VPN in background"
                                  else "Exempted — background tunnel runs without sleep interruptions",
                    onClick = {
                        try {
                            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            // Fallback
                        }
                    }
                )
            }

            // ── 4. UI & Display Settings ───────────────────────────────────
            SettingsSection(
                title = "UI & Display",
                subtitle = "Theme and notification counters"
            ) {
                // Real-time speed display in notification
                SwitchSettingItem(
                    icon = Icons.Default.Notifications,
                    title = "Speed in Notification",
                    description = "Show real-time upload and download speeds in status bar notification",
                    checked = uiState.showNotificationTraffic,
                    onCheckedChange = { viewModel.setShowNotificationTraffic(it) }
                )

                SettingsDivider()

                // Dark mode
                ClickableSettingItem(
                    icon = Icons.Default.DarkMode,
                    title = "Theme Mode",
                    description = when (uiState.darkMode) {
                        DarkMode.LIGHT -> "Light"
                        DarkMode.DARK -> "Dark"
                        DarkMode.AMOLED -> "AMOLED Dark"
                        DarkMode.SYSTEM -> "Follow system"
                    },
                    onClick = { showDarkModeDialog = true }
                )
            }

            // ── 5. Reset Settings ──────────────────────────────────────────
            SettingsSection(title = "Reset") {
                ClickableSettingItem(
                    icon = Icons.Default.RestartAlt,
                    title = "Reset All Settings",
                    description = "Restore all VPN and proxy settings to factory defaults",
                    onClick = { showResetSettingsDialog = true }
                )
            }

            // App Version Footer
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "AryanaNG v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // Dark Mode Dialog
    if (showMtuDialog) {
        val mtuPresets = listOf(
            1500 to "Best throughput on clean networks",
            1400 to "Recommended for most mobile networks",
            1350 to "Conservative, for double-NAT or PPPoE",
            1280 to "Maximum compatibility"
        )
        val isCustom = mtuPresets.none { it.first == uiState.vpnMtu }
        var customMtuText by remember { mutableStateOf(if (isCustom) uiState.vpnMtu.toString() else "") }
        var useCustom by remember { mutableStateOf(isCustom) }
        AlertDialog(
            onDismissRequest = { showMtuDialog = false },
            title = { Text("VPN MTU") },
            text = {
                Column {
                    mtuPresets.forEach { (mtu, desc) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    useCustom = false
                                    viewModel.setVpnMtu(mtu)
                                    showMtuDialog = false
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = !useCustom && uiState.vpnMtu == mtu,
                                onClick = {
                                    useCustom = false
                                    viewModel.setVpnMtu(mtu)
                                    showMtuDialog = false
                                }
                            )
                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                Text(text = "$mtu")
                                Text(
                                    text = desc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { useCustom = true }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = useCustom,
                            onClick = { useCustom = true }
                        )
                        OutlinedTextField(
                            value = customMtuText,
                            onValueChange = { customMtuText = it.filter { c -> c.isDigit() }.take(5) },
                            enabled = useCustom,
                            label = { Text("Custom") },
                            placeholder = { Text("512–1500") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                if (useCustom) {
                    TextButton(
                        onClick = {
                            val value = customMtuText.toIntOrNull()
                            if (value != null && value in 512..1500) {
                                viewModel.setVpnMtu(value)
                                showMtuDialog = false
                            }
                        }
                    ) {
                        Text("Apply")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showMtuDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDnsWorkerDialog) {
        AlertDialog(
            onDismissRequest = { showDnsWorkerDialog = false },
            title = { Text("DNS Workers") },
            text = {
                Column {
                    Text(
                        text = "Controls how DNS is resolved through the tunnel. Fewer workers = more stable on restricted networks. Per-query creates a fresh connection for each DNS lookup.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    DnsWorkerMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    viewModel.setDnsWorkerMode(mode)
                                    showDnsWorkerDialog = false
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = uiState.dnsWorkerMode == mode,
                                onClick = {
                                    viewModel.setDnsWorkerMode(mode)
                                    showDnsWorkerDialog = false
                                }
                            )
                            Text(
                                text = mode.displayName,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                    if (uiState.dnsWorkerMode.poolSize >= 3) {
                        Text(
                            text = "Higher worker counts increase background data usage due to keepalive traffic on each connection. Use 2 or per-query if data usage is a concern.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDnsWorkerDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDarkModeDialog) {
        AlertDialog(
            onDismissRequest = { showDarkModeDialog = false },
            title = { Text("Dark Mode") },
            text = {
                Column {
                    DarkMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    viewModel.setDarkMode(mode)
                                    showDarkModeDialog = false
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = uiState.darkMode == mode,
                                onClick = {
                                    viewModel.setDarkMode(mode)
                                    showDarkModeDialog = false
                                }
                            )
                            Text(
                                text = when (mode) {
                                    DarkMode.LIGHT -> "Light"
                                    DarkMode.DARK -> "Dark"
                                    DarkMode.AMOLED -> "AMOLED Dark"
                                    DarkMode.SYSTEM -> "Follow system"
                                },
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDarkModeDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Global Resolver Override Dialog
    if (showGlobalResolverDialog) {
        var resolverText by remember { mutableStateOf(uiState.globalResolverList) }
        val ipPattern = remember { Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}(:\d+)?$""") }
        val entries = resolverText.split(",", "\n").map { it.trim() }.filter { it.isNotBlank() }
        val resolverCount = entries.size
        val tooMany = resolverCount > 10
        val invalidEntries = entries.filter { !ipPattern.matches(it) }
        val hasInvalid = invalidEntries.isNotEmpty()
        AlertDialog(
            onDismissRequest = { showGlobalResolverDialog = false },
            title = { Text("Global DNS Resolvers") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Enter DNS resolver IPs, one per line or comma-separated (max 10). These override the resolvers in all DNS tunnel profiles and are used to resolve SSH hostnames.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = resolverText,
                        onValueChange = { resolverText = it },
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        placeholder = { Text("e.g. 8.8.8.8, 1.1.1.1") },
                        singleLine = false,
                        maxLines = 8,
                        isError = tooMany || hasInvalid
                    )
                    if (tooMany) {
                        Text(
                            "Maximum 10 resolvers ($resolverCount entered)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else if (hasInvalid) {
                        Text(
                            "Invalid IP: ${invalidEntries.first()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else if (resolverCount > 0) {
                        Text(
                            "$resolverCount resolver${if (resolverCount > 1) "s" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Normalize: strip ports, clean up separators
                        val cleaned = entries.joinToString(", ") { it.split(":").first() }
                        viewModel.setGlobalResolverList(cleaned)
                        showGlobalResolverDialog = false
                    },
                    enabled = !tooMany && !hasInvalid && resolverCount > 0
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showGlobalResolverDialog = false }) { Text("Cancel") }
            }
        )
    }

    // DNS Pool Dialog
    if (showDnsPoolDialog) {
        DnsPoolDialog(
            initialText = uiState.dnsPoolText,
            onSave = { text ->
                viewModel.setDnsPoolText(text)
                showDnsPoolDialog = false
            },
            onDismiss = { showDnsPoolDialog = false }
        )
    }

    // Remote DNS Dialog
    if (showRemoteDnsDialog) {
        RemoteDnsDialog(
            currentMode = uiState.remoteDnsMode,
            currentCustomDns = uiState.customRemoteDns,
            currentCustomDnsFallback = uiState.customRemoteDnsFallback,
            onSelectDefault = {
                viewModel.setRemoteDnsMode("default")
                showRemoteDnsDialog = false
            },
            onSelectCustom = { dns, fallback ->
                viewModel.setRemoteDnsMode("custom")
                viewModel.setCustomRemoteDns(dns)
                viewModel.setCustomRemoteDnsFallback(fallback)
                showRemoteDnsDialog = false
            },
            onDismiss = { showRemoteDnsDialog = false }
        )
    }

    // Reset Settings Confirmation Dialog
    if (showResetSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showResetSettingsDialog = false },
            title = { Text("Reset all settings?") },
            text = {
                Text("This will restore all settings to their default values. Your profiles and connection stats will not be affected.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetAllSettings()
                    showResetSettingsDialog = false
                }) {
                    Text("Reset", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetSettingsDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // About Dialog
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("About Aryana") },
            text = { AboutDialogContent() },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // Update available dialog
    if (uiState.updateCheckResult == UpdateCheckResult.UPDATE_AVAILABLE) {
        val update = viewModel.availableUpdate
        if (update != null) {
            val context = LocalContext.current
            AlertDialog(
                onDismissRequest = { viewModel.clearUpdateCheck() },
                title = { Text("Update Available") },
                text = {
                    Text("Version ${update.versionName} is available. You are on ${BuildConfig.VERSION_NAME}.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.clearUpdateCheck()
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
                    TextButton(onClick = { viewModel.clearUpdateCheck() }) {
                        Text("Later")
                    }
                }
            )
        }
    }
}

@Composable
private fun RemoteDnsDialog(
    currentMode: String,
    currentCustomDns: String,
    currentCustomDnsFallback: String,
    onSelectDefault: () -> Unit,
    onSelectCustom: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedMode by remember { mutableStateOf(currentMode) }
    var customDns by remember { mutableStateOf(currentCustomDns) }
    var customDnsFallback by remember { mutableStateOf(currentCustomDnsFallback) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remote DNS Server") },
        text = {
            Column {
                Text(
                    text = "DNS servers used on the remote side of the tunnel for resolving domain names.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Default option
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { selectedMode = "default" }
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedMode == "default",
                        onClick = { selectedMode = "default" }
                    )
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text("Default (8.8.8.8, 1.1.1.1)")
                        Text(
                            text = "Google primary, Cloudflare fallback",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Custom option
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { selectedMode = "custom" }
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedMode == "custom",
                        onClick = { selectedMode = "custom" }
                    )
                    Text(
                        text = "Custom",
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                if (selectedMode == "custom") {
                    OutlinedTextField(
                        value = customDns,
                        onValueChange = { customDns = it },
                        placeholder = { Text("e.g., 9.9.9.9") },
                        supportingText = { Text("Primary DNS server") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 40.dp, top = 4.dp)
                    )

                    OutlinedTextField(
                        value = customDnsFallback,
                        onValueChange = { customDnsFallback = it },
                        placeholder = { Text("e.g., 8.8.8.8") },
                        supportingText = { Text("Fallback DNS server") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 40.dp, top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (selectedMode == "default") {
                        onSelectDefault()
                    } else {
                        onSelectCustom(customDns.trim(), customDnsFallback.trim())
                    }
                }
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun SettingsSection(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit
) {
    Column {
        Row(
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            if (subtitle != null) {
                Text(
                    text = " · $subtitle",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 12.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}

@Composable
private fun SwitchSettingItem(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    inactive: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (inactive) 0.5f else 1f)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun ClickableSettingItem(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun IndentedSettingItem(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    inactive: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 40.dp)
            .alpha(if (inactive) 0.5f else 1f)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(vertical = 6.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun InfoNoticeRow(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 40.dp, top = 4.dp, bottom = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
private fun SliderSettingItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    subtitleColor: Color? = null,
    value: Int,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueFormatter: (Float) -> String,
    onValueChange: (Float) -> Unit,
    onReset: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = subtitleColor ?: MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (onReset != null) {
                TextButton(onClick = onReset) {
                    Text("Auto", style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(
                text = valueFormatter(value.toFloat()),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 40.dp, top = 4.dp)
        )
    }
}

@Composable
private fun StepperSettingItem(
    icon: ImageVector,
    title: String,
    description: String,
    value: Int,
    step: Int,
    range: IntRange,
    valueFormatter: (Int) -> String,
    onValueChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { onValueChange((value - step).coerceIn(range)) },
                enabled = value > range.first,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Default.Remove,
                    contentDescription = "Decrease",
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                text = valueFormatter(value),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 48.dp)
            )
            IconButton(
                onClick = { onValueChange((value + step).coerceIn(range)) },
                enabled = value < range.last,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Increase",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
/**
 * Detect available network addresses for the listen address picker.
 * Returns list of (label, ip) pairs.
 */
private fun getAddressOptions(): List<Pair<String, String>> {
    return listOf(
        "Localhost" to "127.0.0.1",
        "All interfaces" to "0.0.0.0"
    )
}

/**
 * Detect the device's shareable IP address.
 * Priority: hotspot interface > Wi-Fi > any non-loopback IPv4 interface.
 * Returns a pair of (ip, isHotspot) or null if no suitable interface is found.
 */
private fun detectShareableIp(): Pair<String, Boolean>? {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
        val hotspotPrefixes = listOf("wlan1", "ap0", "swlan0", "softap", "wlan-ap", "rndis")

        // First try hotspot interfaces
        for (iface in interfaces) {
            if (!iface.isUp) continue
            if (hotspotPrefixes.any { iface.name.startsWith(it) }) {
                val ip = iface.inetAddresses.toList()
                    .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                    ?.hostAddress
                if (ip != null) return ip to true
            }
        }

        // Fall back to Wi-Fi (wlan0)
        for (iface in interfaces) {
            if (!iface.isUp) continue
            if (iface.name.startsWith("wlan0") || iface.name.startsWith("wlan")) {
                val ip = iface.inetAddresses.toList()
                    .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                    ?.hostAddress
                if (ip != null) return ip to false
            }
        }

        // Fall back to any non-loopback IPv4 interface (mobile data, USB, ethernet, etc.)
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback) continue
            val ip = iface.inetAddresses.toList()
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress
            if (ip != null) return ip to false
        }
    } catch (_: Exception) { }
    return null
}

@Composable
private fun HotspotInfoCard(
    socksPort: Int,
    httpProxyEnabled: Boolean = false,
    httpProxyPort: Int = 8080
) {
    val shareableIp = remember { detectShareableIp() }
    if (shareableIp == null) return

    val (ip, isHotspot) = shareableIp
    val socksAddress = "$ip:$socksPort"
    val httpAddress = "$ip:$httpProxyPort"
    val copyText = if (httpProxyEnabled) "SOCKS5: $socksAddress | HTTP: $httpAddress" else socksAddress
    val clipboardManager = LocalClipboardManager.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isHotspot)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Wifi,
                contentDescription = null,
                tint = if (isHotspot)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    text = if (isHotspot) "Hotspot proxy address" else "Device IP",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isHotspot)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "SOCKS5: $socksAddress",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isHotspot)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurface
                )
                if (httpProxyEnabled) {
                    Text(
                        text = "HTTP: $httpAddress",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isHotspot)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onSurface
                    )
                }
                if (isHotspot) {
                    Text(
                        text = "Use as proxy on connected devices",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                } else {
                    Text(
                        text = "Enable hotspot to share with other devices",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(
                onClick = { clipboardManager.setText(AnnotatedString(copyText)) },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy address",
                    tint = if (isHotspot)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun LocalProxyInfoCard(
    listenAddress: String,
    socksPort: Int,
    httpProxyEnabled: Boolean = false,
    httpProxyPort: Int = 8080
) {
    val socksAddress = "$listenAddress:$socksPort"
    val httpAddress = "$listenAddress:$httpProxyPort"
    val copyText = if (httpProxyEnabled) "SOCKS5: $socksAddress | HTTP: $httpAddress" else socksAddress
    val clipboardManager = LocalClipboardManager.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.SettingsEthernet,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    text = "Proxy address",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "SOCKS5: $socksAddress",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (httpProxyEnabled) {
                    Text(
                        text = "HTTP: $httpAddress",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    text = "Configure this in your app's proxy settings",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = { clipboardManager.setText(AnnotatedString(copyText)) },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy address",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddressSettingItem(
    value: String,
    options: List<Pair<String, String>>,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    // Find the label for the current value, if it matches a known option
    val displayText = options.find { it.second == value }?.let { (label, ip) ->
        if (label == "All interfaces" || label == "Localhost") "$label ($ip)" else "$label: $ip"
    } ?: value

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Lan,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = "Listen Address",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 16.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.padding(start = 40.dp)
        ) {
            OutlinedTextField(
                value = displayText,
                onValueChange = { },
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor()
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { (label, ip) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "$label ($ip)",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        onClick = {
                            onValueChange(ip)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TextFieldSettingItem(
    icon: ImageVector,
    title: String,
    value: String,
    placeholder: String,
    supportingText: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    isError: Boolean = false,
    onValueChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 16.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder) },
            supportingText = { Text(supportingText) },
            isError = isError,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 40.dp)
        )
    }
}


@Composable
private fun BandwidthLimitDialog(
    currentUploadKbps: Int,
    currentDownloadKbps: Int,
    onDismiss: () -> Unit,
    onApply: (uploadKbps: Int, downloadKbps: Int) -> Unit
) {
    var uploadText by remember { mutableStateOf(if (currentUploadKbps > 0) currentUploadKbps.toString() else "") }
    var downloadText by remember { mutableStateOf(if (currentDownloadKbps > 0) currentDownloadKbps.toString() else "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bandwidth Limit") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Set speed limits in KB/s. Leave empty or 0 for unlimited. Applies on next connection.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = uploadText,
                    onValueChange = { uploadText = it.filter { c -> c.isDigit() }.take(6) },
                    label = { Text("Upload (KB/s)") },
                    placeholder = { Text("Unlimited") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = downloadText,
                    onValueChange = { downloadText = it.filter { c -> c.isDigit() }.take(6) },
                    label = { Text("Download (KB/s)") },
                    placeholder = { Text("Unlimited") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val up = uploadText.toIntOrNull() ?: 0
                    val down = downloadText.toIntOrNull() ?: 0
                    onApply(up.coerceAtLeast(0), down.coerceAtLeast(0))
                }
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun DnsPoolDialog(
    initialText: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val max = app.slipnet.tunnel.DnsPoolScanner.MAX_POOL_SIZE
    var text by remember { mutableStateOf(initialText) }
    val parsedCount = remember(text) {
        app.slipnet.tunnel.DnsPoolScanner.parsePool(text).size
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val content = context.contentResolver.openInputStream(it)
                    ?.bufferedReader()?.readText() ?: ""
                text = capPoolText(content, max)
            } catch (_: Exception) {}
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "DNS Resolver Pool",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                CountBadge(count = parsedCount, max = max)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Candidates scanned on each connect — top 10 lowest-latency win.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PoolActionButton(
                        icon = Icons.Default.UploadFile,
                        label = "Import",
                        onClick = { importLauncher.launch(arrayOf("text/plain", "*/*")) },
                        modifier = Modifier.weight(1f)
                    )
                    PoolActionButton(
                        icon = Icons.Default.Shuffle,
                        label = "Random",
                        onClick = {
                            val sampled = sampleRandomResolversFromBuiltIn(context, count = max)
                            if (sampled.isNotBlank()) text = sampled
                        },
                        modifier = Modifier.weight(1f)
                    )
                    PoolActionButton(
                        icon = Icons.Default.Delete,
                        label = "Clear",
                        onClick = { text = "" },
                        modifier = Modifier.weight(1f),
                        enabled = text.isNotEmpty()
                    )
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = capPoolText(it, max) },
                    placeholder = { Text("One IP or host[:port] per line or comma-separated.\nLines starting with # are comments.") },
                    minLines = 8,
                    maxLines = 12,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun CountBadge(count: Int, max: Int) {
    val nearLimit = count >= max
    val containerColor = if (nearLimit)
        MaterialTheme.colorScheme.errorContainer
    else
        MaterialTheme.colorScheme.secondaryContainer
    val textColor = if (nearLimit)
        MaterialTheme.colorScheme.onErrorContainer
    else
        MaterialTheme.colorScheme.onSecondaryContainer
    Text(
        text = "$count / $max",
        style = MaterialTheme.typography.labelMedium,
        color = textColor,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun PoolActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        modifier = modifier
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

private fun capPoolText(text: String, max: Int): String {
    val lines = text.lines()
    var validCount = 0
    val out = mutableListOf<String>()
    for (line in lines) {
        val trimmed = line.trim()
        val isEntry = trimmed.isNotEmpty() && !trimmed.startsWith("#")
        if (isEntry) {
            if (validCount >= max) continue
            validCount++
        }
        out.add(line)
    }
    return out.joinToString("\n")
}

private val POOL_PINNED_DNS = listOf(
    "208.67.222.222", "208.67.220.220",
    "8.8.8.8", "8.8.4.4",
    "77.88.8.2", "77.88.8.88",
    "1.1.1.2", "1.0.0.2",
    "223.5.5.5", "223.6.6.6",
    "80.80.80.80", "80.80.81.81",
    "9.9.9.9", "149.112.112.112"
)

private fun sampleRandomResolversFromBuiltIn(
    context: android.content.Context,
    count: Int,
): String = try {
    // Collect tier 1 + tier 2 only (stop at the second "# SHUFFLE_BELOW" marker).
    // Tier 3 has 55k+ IPs that are not useful for random pool picks.
    val pool = ArrayList<String>(count * 2)
    var shuffleBelowCount = 0
    context.resources.openRawResource(app.slipnet.R.raw.resolvers)
        .bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line == "# SHUFFLE_BELOW") {
                    shuffleBelowCount++
                    if (shuffleBelowCount >= 2) return@useLines  // stop after tier 2
                    continue
                }
                if (line.isEmpty() || line.startsWith("#")) continue
                pool.add(line)
            }
        }
    val sampled = pool.shuffled().take(count).toMutableList()
    val sampledSet = sampled.toHashSet()
    val missing = POOL_PINNED_DNS.filter { it !in sampledSet }
    // Prepend pinned IPs, trim tail to stay within cap
    val result = (missing + sampled).take(count)
    result.joinToString("\n")
} catch (_: Exception) {
    ""
}
