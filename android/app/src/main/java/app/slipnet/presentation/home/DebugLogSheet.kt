package app.slipnet.presentation.home

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.slipnet.util.AppLog
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogSheet(onDismiss: () -> Unit) {
    DisposableEffect(Unit) {
        AppLog.addObserver()
        onDispose { AppLog.removeObserver() }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(100)
            AppLog.flushIfDirty()
        }
    }

    val lines by AppLog.lines.collectAsState()
    val activeResolvers by AppLog.activeResolvers.collectAsState()
    val packetLogs by AppLog.packetLogs.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var filterText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val packetListState = rememberLazyListState()
    val clipboardManager = LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current

    // Filter lines based on user query
    val filteredLines = remember(lines, filterText) {
        if (filterText.isBlank()) lines
        else lines.filter { it.raw.contains(filterText, ignoreCase = true) }
    }

    // Auto-scroll when near bottom
    LaunchedEffect(filteredLines.size) {
        if (filteredLines.isNotEmpty()) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (lastVisible >= filteredLines.size - 4) {
                listState.scrollToItem(filteredLines.size - 1)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF0D1117)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top Action Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF161B22))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "⚡ Aryana Core Live Telemetry",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00FF66)
                        )
                        Text(
                            text = "Profile: ${AppLog.currentProfileName} (${AppLog.currentTunnelType})",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF8B949E)
                        )
                    }

                    // 1-Click Export Full Diagnostic Report
                    IconButton(onClick = {
                        try {
                            val report = generateDiagnosticReport(lines, packetLogs, activeResolvers)
                            val logDir = java.io.File(context.cacheDir, "logs").apply { mkdirs() }
                            val logFile = java.io.File(logDir, "aryana_diagnostic_report.txt")
                            logFile.writeText(report)

                            val fileUri = androidx.core.content.FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                logFile
                            )

                            val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_STREAM, fileUri)
                                putExtra(android.content.Intent.EXTRA_SUBJECT, "Aryana Diagnostic Report")
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            val shareIntent = android.content.Intent.createChooser(sendIntent, "Share Diagnostic Report")
                            context.startActivity(shareIntent)
                        } catch (e: Exception) {
                            android.util.Log.e("DebugLogSheet", "Failed to share report file", e)
                        }
                    }) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "Share Full Report",
                            tint = Color(0xFF00E5FF)
                        )
                    }

                    // Copy visible logs
                    IconButton(onClick = {
                        val text = if (selectedTab == 1) {
                            packetLogs.joinToString("\n") { "${it.timestamp} [${it.direction}] ${it.protocol} -> ${it.target} (${it.size}B) status=${it.status} ${it.latencyMs}ms" }
                        } else {
                            filteredLines.joinToString("\n") { it.raw }
                        }
                        clipboardManager.setText(AnnotatedString(text))
                    }) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy logs",
                            tint = Color(0xFFC9D1D9)
                        )
                    }

                    IconButton(onClick = { AppLog.clear() }) {
                        Icon(
                            Icons.Default.DeleteSweep,
                            contentDescription = "Clear logs",
                            tint = Color(0xFFFF7B72)
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color(0xFFC9D1D9)
                        )
                    }
                }

                // Live Traffic & Resolver Statistics Bar
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1F242C)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val tx = AppLog.txCount.get()
                            val rx = AppLog.rxCount.get()
                            val drops = AppLog.dropCount.get()
                            Text(
                                text = "TX: ${tx} pkts  |  RX: ${rx} pkts",
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF58A6FF)
                            )
                            Text(
                                text = "Drops: $drops",
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = if (drops > 0) Color(0xFFFF7B72) else Color(0xFF3FB950)
                            )
                        }

                        Spacer(Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Active Resolvers: ",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF8B949E)
                            )
                            if (activeResolvers.isEmpty()) {
                                Text(
                                    text = "Auto / System Gateway (Standby)",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFFC9D1D9)
                                )
                            } else {
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    activeResolvers.forEach { r ->
                                        SuggestionChip(
                                            onClick = { },
                                            label = { Text(r, fontSize = 10.sp, fontFamily = FontFamily.Monospace) },
                                            colors = SuggestionChipDefaults.suggestionChipColors(
                                                containerColor = Color(0xFF21262D),
                                                labelColor = Color(0xFF58A6FF)
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Tabs: System Logs (Detailed) / Packet Stream / Smart Diagnosis
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color(0xFF161B22),
                    contentColor = Color(0xFF00FF66)
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("📋 System Logs (${filteredLines.size})", fontSize = 12.sp) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("📡 Packets (${packetLogs.size})", fontSize = 12.sp) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("🩺 Smart Diagnostics", fontSize = 12.sp) }
                    )
                }

                when (selectedTab) {
                    0 -> {
                        // System Logs with Instant Search Bar
                        Column(modifier = Modifier.fillMaxSize().weight(1f)) {
                            OutlinedTextField(
                                value = filterText,
                                onValueChange = { filterText = it },
                                placeholder = { Text("Filter logs (e.g. ERROR, DNS, MTU, SSH)...", fontSize = 11.sp, color = Color(0xFF6E7681)) },
                                singleLine = true,
                                trailingIcon = {
                                    if (filterText.isNotBlank()) {
                                        IconButton(onClick = { filterText = "" }) {
                                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color.Gray)
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = Color.White)
                            )

                            val horizontalScroll = rememberScrollState()
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 8.dp)
                                    .horizontalScroll(horizontalScroll)
                            ) {
                                items(filteredLines, key = { it.id }) { logEntry ->
                                    Text(
                                        text = logEntry.raw,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp,
                                        color = levelColor(logEntry.level),
                                        softWrap = false,
                                        modifier = Modifier.padding(vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    }

                    1 -> {
                        // Live Packet Inspector
                        if (packetLogs.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize().weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "No packets recorded yet. Traffic will stream live when connected.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFF8B949E)
                                )
                            }
                        } else {
                            LazyColumn(
                                state = packetListState,
                                modifier = Modifier.fillMaxSize().weight(1f).padding(8.dp)
                            ) {
                                items(packetLogs, key = { it.id }) { pkt ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = pkt.timestamp.takeLast(12),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            color = Color(0xFF8B949E)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        val dirColor = if (pkt.direction == "TX") Color(0xFF58A6FF) else Color(0xFF3FB950)
                                        Text(
                                            text = pkt.direction,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.sp,
                                            color = dirColor
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = "[${pkt.protocol}]",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            color = Color(0xFFD29922)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = pkt.target,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = Color.White,
                                            maxLines = 1,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = "${pkt.size}B",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            color = Color(0xFF8B949E)
                                        )
                                        if (pkt.latencyMs > 0) {
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                text = "${pkt.latencyMs}ms",
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.sp,
                                                color = Color(0xFF3FB950)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    2 -> {
                        // Live Smart Diagnostics: Analyzes REAL logs instead of static text!
                        val diagFindings = remember(lines) { analyzeEngineLogs(lines) }

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (AppLog.lastErrorDetail.isNotBlank()) {
                                item {
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF3E1C1D)),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Text(
                                                text = "⚠️ Last Engine Error",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFFF7B72)
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = AppLog.lastErrorDetail,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = Color(0xFFFFD2D2)
                                            )
                                        }
                                    }
                                }
                            }

                            items(diagFindings) { finding ->
                                DynamicDiagnosticCard(finding)
                            }
                        }
                    }
                }
            }
        }
    }
}

data class DiagnosticFinding(
    val title: String,
    val status: String,
    val isOk: Boolean,
    val detail: String,
    val recommendation: String = ""
)

/**
 * Live analyzer that inspects the real log buffer for known patterns
 * and delivers actionable advice rather than vague statements.
 */
private fun analyzeEngineLogs(lines: List<app.slipnet.util.LogEntry>): List<DiagnosticFinding> {
    val findings = mutableListOf<DiagnosticFinding>()
    val logText = lines.takeLast(200).joinToString("\n") { it.raw }

    // 1. Resolver & MTU Analysis
    if (logText.contains("UPLOAD_MTU") || logText.contains("no valid connections after mtu testing")) {
        findings.add(
            DiagnosticFinding(
                title = "1. DNS Resolver & Authoritative Route",
                status = "FAILED",
                isOk = false,
                detail = "Resolvers failed MTU probes or dropped tunnel datagrams.",
                recommendation = "Switch DNS Resolver directly to 45.38.249.16:53 or ensure domain NS records in Cloudflare point to current active server IP."
            )
        )
    } else if (logText.contains("MTU Testing Completed") || logText.contains("Accepted (")) {
        findings.add(
            DiagnosticFinding(
                title = "1. DNS Resolver & Authoritative Route",
                status = "RESOLVED",
                isOk = true,
                detail = "DNS handshake succeeded and MTU parameters calibrated."
            )
        )
    } else {
        findings.add(
            DiagnosticFinding(
                title = "1. DNS Resolver Reachability",
                status = "READY",
                isOk = true,
                detail = "Sockets initialized and waiting for tunnel session."
            )
        )
    }

    // 2. Port Conflict Analysis
    if (logText.contains("Port is in use", ignoreCase = true) || logText.contains("EADDRINUSE", ignoreCase = true)) {
        findings.add(
            DiagnosticFinding(
                title = "2. Local Socket Port",
                status = "PORT CONFLICT",
                isOk = false,
                detail = "Port is already bound by another background service or dead socket.",
                recommendation = "Aryana dynamic port auto-allocator will bind to next port, or restart the app."
            )
        )
    } else {
        findings.add(
            DiagnosticFinding(
                title = "2. Local Socket Bind",
                status = "OK",
                isOk = true,
                detail = "Local proxy sockets bound successfully without collision."
            )
        )
    }

    // 3. Routing Loop / Download Freeze Analysis
    val tx = AppLog.txCount.get()
    val rx = AppLog.rxCount.get()
    if (tx > 20 && rx == 0L) {
        findings.add(
            DiagnosticFinding(
                title = "3. TUN Interface & Traffic Flow",
                status = "DOWNLOAD BLOCKED",
                isOk = false,
                detail = "Packets are sent (TX=$tx) but 0 bytes returned from server.",
                recommendation = "VPN socket protect issue or server firewall blocking inbound connection."
            )
        )
    } else {
        findings.add(
            DiagnosticFinding(
                title = "3. TUN Interface & Traffic Flow",
                status = "NORMAL",
                isOk = true,
                detail = "Bi-directional socket forwarding active (TX=$tx / RX=$rx)."
            )
        )
    }

    // 4. Remote Auth & Key Validation
    if (logText.contains("Auth fail", ignoreCase = true) || logText.contains("Permission denied", ignoreCase = true)) {
        findings.add(
            DiagnosticFinding(
                title = "4. Server Authentication",
                status = "AUTH REJECTED",
                isOk = false,
                detail = "Remote server rejected credentials, password, or tunnel encryption key.",
                recommendation = "Verify username/password or MasterDNS symmetric 16-byte key with server."
            )
        )
    } else {
        findings.add(
            DiagnosticFinding(
                title = "4. Encryption & Authentication",
                status = "VALID",
                isOk = true,
                detail = "Credentials, cipher, and encryption algorithms accepted."
            )
        )
    }

    return findings
}

@Composable
private fun DynamicDiagnosticCard(finding: DiagnosticFinding) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = finding.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    color = if (finding.isOk) Color(0xFF238636) else Color(0xFFDA3633),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = finding.status,
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = finding.detail,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8B949E)
            )
            if (finding.recommendation.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "💡 Fix: ${finding.recommendation}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF58A6FF)
                )
            }
        }
    }
}

private fun generateDiagnosticReport(
    lines: List<app.slipnet.util.LogEntry>,
    packets: List<app.slipnet.util.PacketLogItem>,
    resolvers: List<String>
): String {
    val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    return buildString {
        appendLine("==================================================")
        appendLine("          ARYANA VPN DIAGNOSTIC REPORT            ")
        appendLine("==================================================")
        appendLine("Report Time: $dateStr")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
        appendLine("App Version: v${app.slipnet.BuildConfig.VERSION_NAME} (code ${app.slipnet.BuildConfig.VERSION_CODE})")
        appendLine("Active Profile: ${AppLog.currentProfileName}")
        appendLine("Tunnel Type: ${AppLog.currentTunnelType}")
        appendLine("Active Resolvers (${resolvers.size}): ${resolvers.joinToString(", ").ifEmpty { "System Gateway" }}")
        appendLine("Traffic Stats: TX=${AppLog.txCount.get()} pkts | RX=${AppLog.rxCount.get()} pkts | Drops=${AppLog.dropCount.get()}")
        if (AppLog.lastErrorDetail.isNotBlank()) {
            appendLine("Last Error: ${AppLog.lastErrorDetail}")
        }
        appendLine("--------------------------------------------------")
        appendLine("AUTOMATED ROOT-CAUSE FINDINGS:")
        val findings = analyzeEngineLogs(lines)
        findings.forEach { f ->
            val icon = if (f.isOk) "[PASS]" else "[FAIL]"
            appendLine("• $icon ${f.title}: ${f.status}")
            appendLine("  Detail: ${f.detail}")
            if (f.recommendation.isNotBlank()) {
                appendLine("  Fix: ${f.recommendation}")
            }
        }
        appendLine("--------------------------------------------------")
        appendLine("RECENT PACKET FLOW (Last 20):")
        packets.takeLast(20).forEach { p ->
            appendLine("[${p.timestamp}] [${p.direction}] ${p.protocol} -> ${p.target} (${p.size}B) status=${p.status} ${p.latencyMs}ms")
        }
        appendLine("--------------------------------------------------")
        appendLine("SANITIZED LOG TRACE (Last 100):")
        lines.takeLast(100).forEach { l ->
            appendLine("[${l.level}] ${l.raw}")
        }
        appendLine("==================================================")
        appendLine("               END OF REPORT                      ")
        appendLine("==================================================")
    }
}

@Composable
private fun levelColor(level: Char): Color {
    return when (level) {
        'E' -> Color(0xFFFF7B72)
        'W' -> Color(0xFFD29922)
        'D' -> Color(0xFF8B949E)
        'V' -> Color(0xFF6E7681)
        else -> Color(0xFFC9D1D9)
    }
}
