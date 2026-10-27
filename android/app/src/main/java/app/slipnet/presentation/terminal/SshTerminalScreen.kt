package app.slipnet.presentation.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshTerminalScreen(
    onNavigateBack: () -> Unit,
    viewModel: SshTerminalViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showActionBottomSheet by remember { mutableStateOf(false) }
    var showNewHostDialog by remember { mutableStateOf(false) }
    var showSnippetsSheet by remember { mutableStateOf(false) }
    var showNewTabDialog by remember { mutableStateOf(false) }

    var newHostName by remember { mutableStateOf("") }
    var newHostIp by remember { mutableStateOf("") }
    var newHostPort by remember { mutableStateOf("22") }
    var newHostUser by remember { mutableStateOf("root") }
    var newHostPass by remember { mutableStateOf("") }
    var newHostOs by remember { mutableStateOf("ubuntu") }

    val currentTheme = uiState.selectedTheme
    val termBg = Color(currentTheme.bgHex)
    val termText = Color(currentTheme.textHex)
    val termSurface = Color(currentTheme.surfaceHex)
    val termKeyBg = Color(currentTheme.keyBgHex)

    Scaffold(
        modifier = Modifier.imePadding(),
        bottomBar = {
            // Termius 3-Tab Bottom Navigation Bar (Vaults, Connections, Settings)
            NavigationBar(
                containerColor = termSurface,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = uiState.currentNavTab == TerminalNavTab.VAULTS,
                    onClick = { viewModel.setNavTab(TerminalNavTab.VAULTS) },
                    icon = { Icon(Icons.Default.VpnKey, contentDescription = "Vaults") },
                    label = { Text("Vaults", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF00E5FF),
                        selectedTextColor = Color(0xFF00E5FF),
                        indicatorColor = Color(0xFF1E293B)
                    )
                )
                NavigationBarItem(
                    selected = uiState.currentNavTab == TerminalNavTab.CONNECTIONS,
                    onClick = { viewModel.setNavTab(TerminalNavTab.CONNECTIONS) },
                    icon = {
                        BadgedBox(badge = {
                            if (uiState.connectionState is TerminalConnectionState.Connected) {
                                Badge(containerColor = Color(0xFF00FF66))
                            }
                        }) {
                            Icon(Icons.Default.Dns, contentDescription = "Connections")
                        }
                    },
                    label = { Text("Connections", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF00E5FF),
                        selectedTextColor = Color(0xFF00E5FF),
                        indicatorColor = Color(0xFF1E293B)
                    )
                )
                NavigationBarItem(
                    selected = uiState.currentNavTab == TerminalNavTab.SETTINGS,
                    onClick = { viewModel.setNavTab(TerminalNavTab.SETTINGS) },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF00E5FF),
                        selectedTextColor = Color(0xFF00E5FF),
                        indicatorColor = Color(0xFF1E293B)
                    )
                )
            }
        },
        floatingActionButton = {
            if (uiState.currentNavTab == TerminalNavTab.VAULTS) {
                FloatingActionButton(
                    onClick = { showActionBottomSheet = true },
                    containerColor = Color(0xFF2563EB),
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Host", modifier = Modifier.size(26.dp))
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(termBg)
        ) {
            when (uiState.currentNavTab) {
                TerminalNavTab.VAULTS -> {
                    TermiusHostsScreen(
                        uiState = uiState,
                        termBg = termBg,
                        termSurface = termSurface,
                        termKeyBg = termKeyBg,
                        onNavigateBack = onNavigateBack,
                        onSearchChange = { viewModel.updateSearchQuery(it) },
                        onHostClick = { host -> viewModel.selectHostAndConnect(host) },
                        onDeleteHost = { id -> viewModel.deleteHost(id) }
                    )
                }
                TerminalNavTab.CONNECTIONS -> {
                    TermiusSessionScreen(
                        viewModel = viewModel,
                        uiState = uiState,
                        termBg = termBg,
                        termText = termText,
                        termSurface = termSurface,
                        termKeyBg = termKeyBg,
                        onNavigateBack = { viewModel.setNavTab(TerminalNavTab.VAULTS) },
                        onNewTabClick = { showNewTabDialog = true },
                        onSnippetsClick = { showSnippetsSheet = true }
                    )
                }
                TerminalNavTab.SETTINGS -> {
                    TermiusSettingsScreen(
                        viewModel = viewModel,
                        uiState = uiState,
                        termSurface = termSurface,
                        termKeyBg = termKeyBg,
                        onNavigateBack = { viewModel.setNavTab(TerminalNavTab.VAULTS) }
                    )
                }
            }

            // Termius Bottom Sheet (FAB Action Menu: New Host, New Local, New Group, Quick Import)
            if (showActionBottomSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showActionBottomSheet = false },
                    containerColor = termSurface
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Add to Vault",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        HorizontalDivider(color = Color(0xFF334155))

                        TermiusSheetActionItem(
                            icon = Icons.Default.Computer,
                            title = "New host",
                            subtitle = "Add remote SSH VPS or server instance",
                            iconTint = Color(0xFF38BDF8)
                        ) {
                            showActionBottomSheet = false
                            showNewHostDialog = true
                        }

                        TermiusSheetActionItem(
                            icon = Icons.Default.Terminal,
                            title = "New local",
                            subtitle = "Local terminal session",
                            iconTint = Color(0xFF4ADE80)
                        ) {
                            showActionBottomSheet = false
                            viewModel.addTab("Local", "127.0.0.1", "local", "linux")
                            viewModel.setNavTab(TerminalNavTab.CONNECTIONS)
                        }

                        TermiusSheetActionItem(
                            icon = Icons.Default.Folder,
                            title = "New group",
                            subtitle = "Organize hosts into custom folders",
                            iconTint = Color(0xFFFACC15)
                        ) {
                            showActionBottomSheet = false
                        }

                        TermiusSheetActionItem(
                            icon = Icons.Default.CloudDownload,
                            title = "Quick Import",
                            subtitle = "Import SSH config, known_hosts or URI",
                            iconTint = Color(0xFFA78BFA)
                        ) {
                            showActionBottomSheet = false
                            showNewHostDialog = true
                        }

                        Spacer(Modifier.height(16.dp))
                    }
                }
            }

            // New Host Dialog
            if (showNewHostDialog) {
                AlertDialog(
                    onDismissRequest = { showNewHostDialog = false },
                    title = { Text("New SSH Host") },
                    text = {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.verticalScroll(rememberScrollState())
                        ) {
                            OutlinedTextField(
                                value = newHostName,
                                onValueChange = { newHostName = it },
                                label = { Text("Host Alias (e.g. Amazon-1, iran)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(modifier = Modifier.fillMaxWidth()) {
                                OutlinedTextField(
                                    value = newHostIp,
                                    onValueChange = { newHostIp = it },
                                    label = { Text("Hostname / IP") },
                                    singleLine = true,
                                    modifier = Modifier.weight(2f)
                                )
                                Spacer(Modifier.width(8.dp))
                                OutlinedTextField(
                                    value = newHostPort,
                                    onValueChange = { newHostPort = it },
                                    label = { Text("Port") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            OutlinedTextField(
                                value = newHostUser,
                                onValueChange = { newHostUser = it },
                                label = { Text("Username (root, ubuntu, yasin)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = newHostPass,
                                onValueChange = { newHostPass = it },
                                label = { Text("Password (optional)") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("OS:", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                listOf("ubuntu" to "Ubuntu", "amazon" to "Amazon", "linux" to "Linux").forEach { (type, name) ->
                                    FilterChip(
                                        selected = newHostOs == type,
                                        onClick = { newHostOs = type },
                                        label = { Text(name, fontSize = 11.sp) }
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            if (newHostIp.isNotBlank()) {
                                viewModel.saveCurrentHost(
                                    label = newHostName.ifBlank { newHostIp },
                                    osType = newHostOs
                                )
                                showNewHostDialog = false
                            }
                        }) {
                            Text("Save Host")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showNewHostDialog = false }) { Text("Cancel") }
                    }
                )
            }

            // New Tab Dialog
            if (showNewTabDialog) {
                var tabTitle by remember { mutableStateOf("") }
                var tabHost by remember { mutableStateOf("") }
                var tabUser by remember { mutableStateOf("root") }

                AlertDialog(
                    onDismissRequest = { showNewTabDialog = false },
                    title = { Text("Open Session Tab") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = tabTitle,
                                onValueChange = { tabTitle = it },
                                label = { Text("Tab Title (e.g. Amazon-1)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = tabHost,
                                onValueChange = { tabHost = it },
                                label = { Text("Host / IP") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = tabUser,
                                onValueChange = { tabUser = it },
                                label = { Text("Username") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            if (tabHost.isNotBlank()) {
                                viewModel.addTab(
                                    title = tabTitle.ifBlank { tabHost },
                                    host = tabHost,
                                    username = tabUser
                                )
                                showNewTabDialog = false
                                viewModel.connect()
                            }
                        }) {
                            Text("Connect")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showNewTabDialog = false }) { Text("Cancel") }
                    }
                )
            }

            // Snippets Bottom Sheet
            if (showSnippetsSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showSnippetsSheet = false },
                    containerColor = termSurface
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Termius Snippets",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00E5FF)
                        )
                        Spacer(Modifier.height(12.dp))
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.verticalScroll(rememberScrollState())
                        ) {
                            uiState.customSnippets.forEach { snippet ->
                                Surface(
                                    color = termKeyBg,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.sendCommand(snippet)
                                            showSnippetsSheet = false
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = snippet,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 13.sp,
                                            color = Color.White
                                        )
                                        Icon(
                                            Icons.AutoMirrored.Filled.Send,
                                            contentDescription = "Run",
                                            tint = Color(0xFF00FF66),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SCREEN 1: Termius Hosts Vault (Matching img_5ace9f11faa2.jpg & img_638561f9a1a0.jpg)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun TermiusHostsScreen(
    uiState: SshTerminalUiState,
    termBg: Color,
    termSurface: Color,
    termKeyBg: Color,
    onNavigateBack: () -> Unit,
    onSearchChange: (String) -> Unit,
    onHostClick: (SavedSshHost) -> Unit,
    onDeleteHost: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(termBg)
    ) {
        // Top Search Bar (Termius Style)
        Surface(
            color = termSurface,
            shadowElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color(0xFFE2E8F0)
                    )
                }

                Spacer(Modifier.width(8.dp))

                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = onSearchChange,
                    placeholder = { Text("Search", color = Color(0xFF94A3B8), fontSize = 14.sp) },
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = "Search", tint = Color(0xFF94A3B8))
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF475569),
                        unfocusedBorderColor = Color(0xFF334155),
                        focusedContainerColor = Color(0xFF0F172A),
                        unfocusedContainerColor = Color(0xFF0F172A),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )

                Spacer(Modifier.width(8.dp))

                IconButton(onClick = {}) {
                    Icon(Icons.Default.FilterList, contentDescription = "Sort/Filter", tint = Color(0xFF94A3B8))
                }
            }
        }

        // Hosts List (Matching Cards in img_5ace9f11faa2.jpg)
        val filteredHosts = remember(uiState.savedHosts, uiState.searchQuery) {
            if (uiState.searchQuery.isBlank()) uiState.savedHosts else {
                uiState.savedHosts.filter {
                    it.name.contains(uiState.searchQuery, ignoreCase = true) ||
                    it.host.contains(uiState.searchQuery, ignoreCase = true) ||
                    it.username.contains(uiState.searchQuery, ignoreCase = true)
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filteredHosts, key = { it.id }) { host ->
                TermiusHostCard(
                    host = host,
                    termKeyBg = termKeyBg,
                    onClick = { onHostClick(host) },
                    onDelete = { onDeleteHost(host.id) }
                )
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun TermiusHostCard(
    host: SavedSshHost,
    termKeyBg: Color,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var expandedMenu by remember { mutableStateOf(false) }

    Surface(
        color = Color(0xFF1E293B),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // OS Logo / Avatar Icon
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(
                        when (host.osType.lowercase()) {
                            "ubuntu" -> Color(0xFFE95420)
                            "amazon" -> Color(0xFFFF9900)
                            else -> Color(0xFF3B82F6)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when (host.osType.lowercase()) {
                        "ubuntu" -> "ubuntu"
                        "amazon" -> "aws"
                        else -> "linux"
                    },
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Spacer(Modifier.width(14.dp))

            // Title and Subtitle (Termius layout: Name + "ssh, root, ubuntu")
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = host.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "ssh, ${host.username}, ${if (host.osType.isNotBlank()) host.osType else host.host}",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8)
                )
            }

            // More actions
            Box {
                IconButton(onClick = { expandedMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color(0xFF64748B))
                }
                DropdownMenu(
                    expanded = expandedMenu,
                    onDismissRequest = { expandedMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Connect Now") },
                        onClick = {
                            expandedMenu = false
                            onClick()
                        },
                        leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFF00FF66)) }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete Host", color = Color.Red) },
                        onClick = {
                            expandedMenu = false
                            onDelete()
                        },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Color.Red) }
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SCREEN 2: Termius Active SSH Terminal (Matching img_d4a38e622fc0.jpg)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun TermiusSessionScreen(
    viewModel: SshTerminalViewModel,
    uiState: SshTerminalUiState,
    termBg: Color,
    termText: Color,
    termSurface: Color,
    termKeyBg: Color,
    onNavigateBack: () -> Unit,
    onNewTabClick: () -> Unit,
    onSnippetsClick: () -> Unit
) {
    val outputScrollState = rememberScrollState()

    LaunchedEffect(uiState.output) {
        if (uiState.isAutoScroll) {
            outputScrollState.scrollTo(outputScrollState.maxValue)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(termBg)
    ) {
        // Session Tabs Header (Termius Style)
        Surface(
            color = termSurface,
            shadowElevation = 3.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(termKeyBg)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color(0xFFE2E8F0),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(Modifier.width(8.dp))

                // Scrollable Session Tabs
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    uiState.tabs.forEach { tab ->
                        val isActive = tab.id == uiState.activeTabId
                        var tabMenuExpanded by remember { mutableStateOf(false) }

                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isActive) Color(0xFF2D3748) else Color(0xFF1A202C))
                                .border(
                                    width = 1.dp,
                                    color = if (isActive) Color(0xFF4A5568) else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable { viewModel.selectTab(tab) }
                                .padding(start = 8.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🌿", fontSize = 12.sp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = tab.title,
                                fontSize = 12.sp,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                color = if (isActive) Color.White else Color(0xFFA0AEC0),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.width(2.dp))
                            Box {
                                IconButton(
                                    onClick = { tabMenuExpanded = true },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        contentDescription = "Options",
                                        tint = Color(0xFFA0AEC0),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                DropdownMenu(
                                    expanded = tabMenuExpanded,
                                    onDismissRequest = { tabMenuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Reconnect") },
                                        onClick = {
                                            tabMenuExpanded = false
                                            viewModel.connect()
                                        },
                                        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Disconnect") },
                                        onClick = {
                                            tabMenuExpanded = false
                                            viewModel.disconnect()
                                        },
                                        leadingIcon = { Icon(Icons.Default.PowerSettingsNew, contentDescription = null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Clear Output") },
                                        onClick = {
                                            tabMenuExpanded = false
                                            viewModel.clearOutput()
                                        },
                                        leadingIcon = { Icon(Icons.Default.ClearAll, contentDescription = null) }
                                    )
                                    if (uiState.tabs.size > 1) {
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text("Close Tab", color = Color.Red) },
                                            onClick = {
                                                tabMenuExpanded = false
                                                viewModel.closeTab(tab.id)
                                            },
                                            leadingIcon = { Icon(Icons.Default.Close, contentDescription = null, tint = Color.Red) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.width(8.dp))

                IconButton(
                    onClick = onNewTabClick,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(termKeyBg)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "New Tab",
                        tint = Color(0xFFE2E8F0),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Terminal Console Output Window
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp)
                .background(termBg)
        ) {
            SelectionContainer {
                Text(
                    text = if (uiState.output.isEmpty()) {
                        "Termius SSH Engine: Connected to ${uiState.host}\nTap accessory bar below to execute shell commands.\n"
                    } else {
                        uiState.output
                    },
                    color = termText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = uiState.fontSizeSp.sp,
                    lineHeight = (uiState.fontSizeSp + 4).sp,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp)
                        .verticalScroll(outputScrollState)
                )
            }
        }

        // Termius Accessory Tool Row (Shown above keyboard - Mode A)
        Surface(
            color = termSurface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TermiusToolIcon(icon = Icons.Default.TouchApp, desc = "Pointer") {}

                TermiusToolIcon(icon = Icons.Default.AutoAwesome, desc = "AI Assistant") {
                    viewModel.sendCommand("curl -s wttr.in?0")
                }

                TermiusToolText(label = "***", desc = "Autofill Password") {
                    if (uiState.password.isNotBlank()) {
                        viewModel.sendCommand(uiState.password)
                    }
                }

                TermiusToolText(label = "{ }", desc = "Snippets") {
                    onSnippetsClick()
                }

                TermiusToolIcon(icon = Icons.Default.FolderOpen, desc = "SFTP") {
                    viewModel.sendCommand("ls -lah")
                }

                TermiusToolText(label = "⇧ Tab", desc = "Shift Tab") {
                    viewModel.sendTab()
                }

                TermiusToolText(label = "Ctrl", desc = "Ctrl", isAccent = true) {
                    viewModel.sendCtrlC()
                }

                Surface(
                    color = if (uiState.isExtendedKeyboardVisible) Color(0xFF4F46E5) else termKeyBg,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.clickable { viewModel.toggleExtendedKeyboard() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (uiState.isExtendedKeyboardVisible) Icons.Default.KeyboardHide else Icons.Default.Keyboard,
                            contentDescription = "Toggle Keys",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (uiState.isExtendedKeyboardVisible) "Hide Keys" else "Keys",
                            fontSize = 11.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Extended Terminal Keypad (7 Rows)
        AnimatedVisibility(
            visible = uiState.isExtendedKeyboardVisible,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(termSurface)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                TermiusKeyRow(listOf("Home", "Pg Up", "Pg Dn", "End", "|", "\\", "?", "-"), termKeyBg) { key ->
                    when (key) {
                        "Home" -> viewModel.sendHome()
                        "Pg Up" -> viewModel.sendPageUp()
                        "Pg Dn" -> viewModel.sendPageDown()
                        "End" -> viewModel.sendEnd()
                        else -> viewModel.sendChar(key)
                    }
                }

                TermiusKeyRow(listOf("|", ":", ";", "!", "~", "@", "$", "*"), termKeyBg) { key ->
                    viewModel.sendChar(key)
                }

                TermiusKeyRow(listOf("^", "%", "=", "`", "<", ">", "(", ")"), termKeyBg) { key ->
                    viewModel.sendChar(key)
                }

                TermiusKeyRow(listOf("{", "}", "[", "]", "F1", "F2", "F3", "F4"), termKeyBg) { key ->
                    when (key) {
                        "F1" -> viewModel.sendFn(1)
                        "F2" -> viewModel.sendFn(2)
                        "F3" -> viewModel.sendFn(3)
                        "F4" -> viewModel.sendFn(4)
                        else -> viewModel.sendChar(key)
                    }
                }

                TermiusKeyRow(listOf("F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12"), termKeyBg) { key ->
                    val fnNum = key.removePrefix("F").toIntOrNull() ?: 1
                    viewModel.sendFn(fnNum)
                }

                TermiusKeyRow(listOf("^_", "^W", "^R", "^XX", "^C", "^L", "^S", "^Z"), termKeyBg) { key ->
                    when (key) {
                        "^W" -> viewModel.sendCtrl('w')
                        "^R" -> viewModel.sendCtrl('r')
                        "^XX" -> { viewModel.sendCtrl('x'); viewModel.sendCtrl('x') }
                        "^C" -> viewModel.sendCtrlC()
                        "^L" -> viewModel.sendCtrl('l')
                        "^S" -> viewModel.sendCtrl('s')
                        "^Z" -> viewModel.sendCtrlZ()
                        else -> viewModel.sendChar(key)
                    }
                }

                TermiusKeyRow(listOf("^X", "^G", "^N", "^P", "ESC", "TAB", "▲", "▼"), termKeyBg) { key ->
                    when (key) {
                        "^X" -> viewModel.sendCtrl('x')
                        "^G" -> viewModel.sendCtrl('g')
                        "^N" -> viewModel.sendCtrl('n')
                        "^P" -> viewModel.sendCtrl('p')
                        "ESC" -> viewModel.sendEsc()
                        "TAB" -> viewModel.sendTab()
                        "▲" -> viewModel.sendArrowUp()
                        "▼" -> viewModel.sendArrowDown()
                        else -> viewModel.sendChar(key)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { viewModel.setExtendedKeyboardVisible(false) },
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(termKeyBg)
                    ) {
                        Icon(Icons.Default.KeyboardHide, contentDescription = "Dismiss", tint = Color(0xFFA0AEC0))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(
                            onClick = { viewModel.sendArrowLeft() },
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(termKeyBg)
                        ) {
                            Text("◀", color = Color(0xFF00E5FF), fontSize = 13.sp)
                        }
                        IconButton(
                            onClick = { viewModel.sendArrowUp() },
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(termKeyBg)
                        ) {
                            Text("▲", color = Color(0xFF00E5FF), fontSize = 13.sp)
                        }
                        IconButton(
                            onClick = { viewModel.sendArrowDown() },
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(termKeyBg)
                        ) {
                            Text("▼", color = Color(0xFF00E5FF), fontSize = 13.sp)
                        }
                        IconButton(
                            onClick = { viewModel.sendArrowRight() },
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(termKeyBg)
                        ) {
                            Text("▶", color = Color(0xFF00E5FF), fontSize = 13.sp)
                        }
                    }

                    IconButton(
                        onClick = { viewModel.sendEnter() },
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF238636))
                    ) {
                        Text("⏎", color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        }

        // Termius Direct Keystroke Input Capture (Pressing Enter sends \r to SSH stdin, no Send button needed)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(termSurface)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "❯ ",
                color = Color(0xFF00E5FF),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            OutlinedTextField(
                value = uiState.currentCommand,
                onValueChange = { viewModel.updateCommand(it) },
                placeholder = { Text("Type command & press Return (⏎)...", fontSize = 11.sp, color = Color(0xFF6E7681)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    viewModel.sendCommand()
                }),
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = Color.White
                ),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp)
            )
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = { viewModel.sendCommand() },
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF2563EB))
            ) {
                Text("⏎", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SCREEN 3: Termius Settings
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun TermiusSettingsScreen(
    viewModel: SshTerminalViewModel,
    uiState: SshTerminalUiState,
    termSurface: Color,
    termKeyBg: Color,
    onNavigateBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Terminal Settings",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = termSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Color Theme", fontWeight = FontWeight.SemiBold, color = Color.White)
                TerminalTheme.entries.forEach { theme ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setTheme(theme) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = uiState.selectedTheme == theme,
                            onClick = { viewModel.setTheme(theme) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(theme.displayName, color = Color.White)
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = termSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Font Size", fontWeight = FontWeight.SemiBold, color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10, 11, 12, 14, 16).forEach { size ->
                        FilterChip(
                            selected = uiState.fontSizeSp == size,
                            onClick = { viewModel.setFontSize(size) },
                            label = { Text("${size}sp") }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TermiusSheetActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    iconTint: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1E293B)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 15.sp)
            Text(subtitle, color = Color(0xFF94A3B8), fontSize = 12.sp)
        }
    }
}

@Composable
private fun TermiusToolIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(32.dp)
    ) {
        Icon(icon, contentDescription = desc, tint = Color(0xFFA0AEC0), modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun TermiusToolText(
    label: String,
    desc: String,
    isAccent: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        color = if (isAccent) Color(0xFF4F46E5) else Color(0xFF2D3748),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun TermiusKeyRow(
    keys: List<String>,
    keyBg: Color,
    onKeyClick: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        keys.forEach { key ->
            Surface(
                color = keyBg,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clickable { onKeyClick(key) }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = key,
                        fontSize = if (key.length > 2) 9.sp else 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFE2E8F0),
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                }
            }
        }
    }
}