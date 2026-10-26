package app.slipnet.presentation.terminal

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    val outputScrollState = rememberScrollState()

    // Smooth auto-scroll only if isAutoScroll is enabled
    LaunchedEffect(uiState.output) {
        if (uiState.isAutoScroll) {
            outputScrollState.scrollTo(outputScrollState.maxValue)
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Aryana Core Terminal",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val (dotColor, statusText) = when (uiState.connectionState) {
                                is TerminalConnectionState.Connected -> Color(0xFF00E676) to "${uiState.username}@${uiState.host}:${uiState.port}"
                                is TerminalConnectionState.Connecting -> Color(0xFFFFD600) to "Connecting..."
                                is TerminalConnectionState.Disconnected -> Color(0xFF9E9E9E) to "Disconnected"
                                is TerminalConnectionState.Error -> Color(0xFFFF5252) to "Error"
                            }
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(dotColor, RoundedCornerShape(4.dp))
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showConfigSheet = !showConfigSheet }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Server Settings",
                            tint = if (showConfigSheet) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (uiState.connectionState is TerminalConnectionState.Connected) {
                        IconButton(onClick = { viewModel.disconnect() }) {
                            Icon(Icons.Default.Stop, contentDescription = "Disconnect", tint = Color(0xFFFF5252))
                        }
                    } else {
                        IconButton(onClick = { viewModel.connect() }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Connect", tint = Color(0xFF00E676))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF161B22)
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF0D1117))
        ) {
            // Expandable Server Config Card
            if (showConfigSheet) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1F242C)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Aryana Core SSH Connection",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
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
                        Button(
                            onClick = {
                                viewModel.connect()
                                showConfigSheet = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636))
                        ) {
                            Text("Save & Connect")
                        }
                    }
                }
            }

            // Quick Command Chips & Auto-Scroll Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161B22))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Auto Scroll toggle chip
                InputChip(
                    selected = uiState.isAutoScroll,
                    onClick = { viewModel.toggleAutoScroll() },
                    label = {
                        Text(
                            if (uiState.isAutoScroll) "Scroll: Auto" else "Scroll: Manual",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    },
                    colors = InputChipDefaults.inputChipColors(
                        selectedContainerColor = Color(0xFF238636),
                        selectedLabelColor = Color.White
                    )
                )

                QuickCommandChip(label = "sing-box status", cmd = "systemctl status sing-box") { viewModel.sendCommand(it) }
                QuickCommandChip(label = "sing-box restart", cmd = "systemctl restart sing-box") { viewModel.sendCommand(it) }
                QuickCommandChip(label = "sing-box logs", cmd = "journalctl -u sing-box -n 30 --no-pager") { viewModel.sendCommand(it) }
                QuickCommandChip(label = "meli logs", cmd = "journalctl -u meli-rust-server -n 30 --no-pager") { viewModel.sendCommand(it) }
                QuickCommandChip(label = "top", cmd = "top -b -n 1 | head -n 15") { viewModel.sendCommand(it) }
                QuickCommandChip(label = "ip a", cmd = "ip a") { viewModel.sendCommand(it) }
                QuickCommandChip(label = "free -m", cmd = "free -m") { viewModel.sendCommand(it) }
                QuickCommandChip(label = "df -h", cmd = "df -h") { viewModel.sendCommand(it) }
                QuickCommandChip(label = "ping 8.8.8.8", cmd = "ping -c 4 8.8.8.8") { viewModel.sendCommand(it) }
            }

            // Terminal Console Output Window
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(6.dp)
                    .background(Color(0xFF0D1117), RoundedCornerShape(6.dp))
            ) {
                SelectionContainer {
                    Text(
                        text = uiState.output,
                        color = Color(0xFF39FF14),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                            .verticalScroll(outputScrollState)
                    )
                }
            }

            // Terminal Key Bar: History Up/Down, Ctrl+C, Ctrl+Z, Tab, Clear, Enter
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161B22))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { viewModel.historyUp() }) {
                    Text("▲", color = Color(0xFF40C4FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = { viewModel.historyDown() }) {
                    Text("▼", color = Color(0xFF40C4FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = { viewModel.sendCtrlC() }) {
                    Text("Ctrl+C", color = Color(0xFFFF5252), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = { viewModel.sendCtrlZ() }) {
                    Text("Ctrl+Z", color = Color(0xFFFFAB40), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = { viewModel.sendTab() }) {
                    Text("Tab", color = Color(0xFF80D8FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = { viewModel.clearOutput() }) {
                    Text("Clear", color = Color(0xFFB0BEC5), fontSize = 11.sp)
                }
                TextButton(onClick = { viewModel.sendEnter() }) {
                    Text("Enter ⏎", color = Color(0xFF00E676), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Interactive Command Input Line
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161B22))
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
                    placeholder = { Text("type command...", fontSize = 12.sp, color = Color(0xFF6E7681)) },
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
private fun QuickCommandChip(
    label: String,
    cmd: String,
    onExecute: (String) -> Unit
) {
    SuggestionChip(
        onClick = { onExecute(cmd) },
        label = { Text(label, fontSize = 10.sp, fontFamily = FontFamily.Monospace) },
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = Color(0xFF21262D),
            labelColor = Color(0xFFE6EDF3)
        ),
        border = SuggestionChipDefaults.suggestionChipBorder(
            enabled = true,
            borderColor = Color(0xFF30363D)
        )
    )
}
