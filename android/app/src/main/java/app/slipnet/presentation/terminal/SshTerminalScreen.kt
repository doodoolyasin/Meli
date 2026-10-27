package app.slipnet.presentation.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
    var showConfigSheet by remember { mutableStateOf(false) }
    var showThemesDialog by remember { mutableStateOf(false) }
    var showSnippetsSheet by remember { mutableStateOf(false) }
    var showNewTabDialog by remember { mutableStateOf(false) }
    var newTabTitle by remember { mutableStateOf("") }
    var newTabHost by remember { mutableStateOf("") }
    var newTabUser by remember { mutableStateOf("root") }

    val outputScrollState = rememberScrollState()

    val currentTheme = uiState.selectedTheme
    val termBg = Color(currentTheme.bgHex)
    val termText = Color(currentTheme.textHex)
    val termSurface = Color(currentTheme.surfaceHex)
    val termKeyBg = Color(currentTheme.keyBgHex)

    // Smooth auto-scroll when output updates
    LaunchedEffect(uiState.output) {
        if (uiState.isAutoScroll) {
            outputScrollState.scrollTo(outputScrollState.maxValue)
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            // Termius Header with Tabs
            Surface(
                color = termSurface,
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Back button in rounded square
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

                    // Scrollable Session Tabs (Termius style)
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
                                // Leaf / server icon
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
                                            contentDescription = "Tab options",
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

                    // Plus (+) button to add new tab
                    IconButton(
                        onClick = { showNewTabDialog = true },
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
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(termBg)
        ) {
            // New Tab Dialog
            if (showNewTabDialog) {
                AlertDialog(
                    onDismissRequest = { showNewTabDialog = false },
                    title = { Text("New SSH Session Tab") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = newTabTitle,
                                onValueChange = { newTabTitle = it },
                                label = { Text("Tab Title (e.g. Amazon 1)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = newTabHost,
                                onValueChange = { newTabHost = it },
                                label = { Text("Host / IP") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = newTabUser,
                                onValueChange = { newTabUser = it },
                                label = { Text("Username") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            if (newTabHost.isNotBlank()) {
                                viewModel.addTab(
                                    title = newTabTitle.ifBlank { newTabHost },
                                    host = newTabHost,
                                    username = newTabUser
                                )
                                showNewTabDialog = false
                                viewModel.connect()
                            }
                        }) {
                            Text("Open Tab")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showNewTabDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }

            // Theme & Font Size Dialog
            if (showThemesDialog) {
                AlertDialog(
                    onDismissRequest = { showThemesDialog = false },
                    title = { Text("Terminal Appearance & Theme") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Color Theme:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            TerminalTheme.entries.forEach { theme ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.setTheme(theme) }
                                ) {
                                    RadioButton(
                                        selected = uiState.selectedTheme == theme,
                                        onClick = { viewModel.setTheme(theme) }
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(theme.displayName, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            HorizontalDivider()
                            Text("Font Size:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
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
                    },
                    confirmButton = {
                        TextButton(onClick = { showThemesDialog = false }) { Text("Done") }
                    }
                )
            }

            // Expandable Server Config Card
            if (showConfigSheet) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    colors = CardDefaults.cardColors(containerColor = termSurface),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "SSH Host Settings (Termius)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (uiState.savedHosts.isNotEmpty()) {
                                var expandedHostDropdown by remember { mutableStateOf(false) }
                                Box {
                                    TextButton(onClick = { expandedHostDropdown = true }) {
                                        Text("Saved Hosts ▼", fontSize = 11.sp)
                                    }
                                    DropdownMenu(
                                        expanded = expandedHostDropdown,
                                        onDismissRequest = { expandedHostDropdown = false }
                                    ) {
                                        uiState.savedHosts.forEach { saved ->
                                            DropdownMenuItem(
                                                text = { Text("${saved.name} (${saved.host})") },
                                                onClick = {
                                                    viewModel.selectHost(saved)
                                                    expandedHostDropdown = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = uiState.host,
                                onValueChange = { viewModel.updateHost(it) },
                                label = { Text("Host / IP", fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(2f)
                            )
                            Spacer(Modifier.width(8.dp))
                            OutlinedTextField(
                                value = uiState.port,
                                onValueChange = { viewModel.updatePort(it) },
                                label = { Text("Port", fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = uiState.username,
                                onValueChange = { viewModel.updateUsername(it) },
                                label = { Text("Username", fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            OutlinedTextField(
                                value = uiState.password,
                                onValueChange = { viewModel.updatePassword(it) },
                                label = { Text("Password", fontSize = 11.sp) },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    viewModel.connect()
                                    showConfigSheet = false
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636))
                            ) {
                                Text("Connect")
                            }
                            OutlinedButton(
                                onClick = {
                                    viewModel.saveCurrentHost("${uiState.username}@${uiState.host}")
                                }
                            ) {
                                Text("Save Host", fontSize = 11.sp)
                            }
                        }
                    }
                }
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
                    // Touch / Pointer mode
                    TermiusToolIcon(icon = Icons.Default.TouchApp, desc = "Pointer") {}

                    // AI / Magic wand
                    TermiusToolIcon(icon = Icons.Default.AutoAwesome, desc = "AI Assistant") {
                        viewModel.sendCommand("curl -s wttr.in?0")
                    }

                    // *** Password autofill
                    TermiusToolText(label = "***", desc = "Autofill Password") {
                        if (uiState.password.isNotBlank()) {
                            viewModel.sendCommand(uiState.password)
                        }
                    }

                    // { } Snippets manager
                    TermiusToolText(label = "{ }", desc = "Snippets") {
                        showSnippetsSheet = true
                    }

                    // Folder / SFTP
                    TermiusToolIcon(icon = Icons.Default.FolderOpen, desc = "SFTP") {
                        viewModel.sendCommand("ls -lah")
                    }

                    // Shift / Tab
                    TermiusToolText(label = "⇧ Tab", desc = "Shift Tab") {
                        viewModel.sendTab()
                    }

                    // Ctrl key
                    TermiusToolText(label = "Ctrl", desc = "Ctrl", isAccent = true) {
                        viewModel.sendCtrlC()
                    }

                    // Gear / Settings
                    TermiusToolIcon(icon = Icons.Default.Settings, desc = "Settings") {
                        showConfigSheet = !showConfigSheet
                    }

                    // Appearance Palette
                    TermiusToolIcon(icon = Icons.Default.Palette, desc = "Theme") {
                        showThemesDialog = true
                    }

                    // Keyboard Switcher: Toggles the Extended Terminal Keypad (Mode B)
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
                                contentDescription = "Toggle Terminal Keypad",
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

            // Termius Extended Terminal Keypad (7 Rows from Screenshot 1 & 2)
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
                    // Row 1: Home | Pg Up | Pg Dn | End | | | \ | ? | -
                    TermiusKeyRow(listOf("Home", "Pg Up", "Pg Dn", "End", "|", "\\", "?", "-"), termKeyBg) { key ->
                        when (key) {
                            "Home" -> viewModel.sendHome()
                            "Pg Up" -> viewModel.sendPageUp()
                            "Pg Dn" -> viewModel.sendPageDown()
                            "End" -> viewModel.sendEnd()
                            else -> viewModel.sendChar(key)
                        }
                    }

                    // Row 2: | : ; ! ~ @ $ *
                    TermiusKeyRow(listOf("|", ":", ";", "!", "~", "@", "$", "*"), termKeyBg) { key ->
                        viewModel.sendChar(key)
                    }

                    // Row 3: ^ % = ` < > ( )
                    TermiusKeyRow(listOf("^", "%", "=", "`", "<", ">", "(", ")"), termKeyBg) { key ->
                        viewModel.sendChar(key)
                    }

                    // Row 4: { } [ ] F1 F2 F3 F4
                    TermiusKeyRow(listOf("{", "}", "[", "]", "F1", "F2", "F3", "F4"), termKeyBg) { key ->
                        when (key) {
                            "F1" -> viewModel.sendFn(1)
                            "F2" -> viewModel.sendFn(2)
                            "F3" -> viewModel.sendFn(3)
                            "F4" -> viewModel.sendFn(4)
                            else -> viewModel.sendChar(key)
                        }
                    }

                    // Row 5: F5 F6 F7 F8 F9 F10 F11 F12
                    TermiusKeyRow(listOf("F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12"), termKeyBg) { key ->
                        val fnNum = key.removePrefix("F").toIntOrNull() ?: 1
                        viewModel.sendFn(fnNum)
                    }

                    // Row 6: ^_ ^W ^R ^XX ^C ^L ^S ^Z
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

                    // Row 7: ^X ^G ^N ^P ESC TAB ▲ ▼
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

                    // Navigation bar with arrows and dismiss
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

            // Interactive Command Input Line
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(termSurface)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$ ",
                    color = Color(0xFF00FF66),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                OutlinedTextField(
                    value = uiState.currentCommand,
                    onValueChange = { viewModel.updateCommand(it) },
                    placeholder = { Text("Enter command...", fontSize = 12.sp, color = Color(0xFF6E7681)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        viewModel.sendCommand()
                    }),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = Color.White
                    ),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(6.dp))
                IconButton(
                    onClick = { viewModel.sendCommand() },
                    enabled = uiState.currentCommand.isNotBlank()
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (uiState.currentCommand.isNotBlank()) Color(0xFF00FF66) else Color.Gray
                    )
                }
            }
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