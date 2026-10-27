package app.slipnet.presentation.profiles

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import app.slipnet.domain.model.CongestionControl
import app.slipnet.domain.model.DnsTransport
import app.slipnet.domain.model.TunnelType
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(
    profileId: Long?,
    onNavigateBack: () -> Unit,
    onNavigateToScanner: ((Long?) -> Unit)? = null,
    selectedResolvers: String? = null,
    selectedResolversTransportHint: String? = null,
    viewModel: EditProfileViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollState = rememberScrollState()

    var showAdvanced by remember { mutableStateOf(false) }

    LaunchedEffect(selectedResolvers) {
        selectedResolvers?.let { resolvers ->
            if (resolvers.isNotBlank()) {
                viewModel.updateResolvers(resolvers)
            }
        }
    }

    LaunchedEffect(uiState.saveSuccess) {
        if (uiState.saveSuccess) {
            Toast.makeText(context, "Profile saved successfully", Toast.LENGTH_SHORT).show()
            onNavigateBack()
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { err ->
            snackbarHostState.showSnackbar(err)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (profileId != null) "Edit Profile" else "New Profile",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(16.dp).size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(onClick = { viewModel.save() }) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Save",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section 1: Protocol Selector
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Protocol Type",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    val protocols = listOf(
                        TunnelType.VLESS to "VLESS",
                        TunnelType.VLESS_REALITY to "VLESS-Reality",
                        TunnelType.MASTERDNS to "MasterDNS",
                        TunnelType.MELI to "Meli Protocol",
                        TunnelType.STORMDNS to "StormDNS",
                        TunnelType.SLIPSTREAM to "Slipstream",
                        TunnelType.DNSTT to "DNSTT",
                        TunnelType.WIREGUARD to "WireGuard",
                        TunnelType.TROJAN to "Trojan",
                        TunnelType.VMESS to "VMess",
                        TunnelType.HYSTERIA2 to "Hysteria 2",
                        TunnelType.SSH to "SSH Direct",
                        TunnelType.AETHER to "Aether (MASQUE)",
                        TunnelType.WARP to "WARP"
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        protocols.forEach { (type, label) ->
                            FilterChip(
                                selected = uiState.tunnelType == type,
                                onClick = { viewModel.updateTunnelType(type) },
                                label = { Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }
                }
            }

            // Section 2: Basic Configuration
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Server Details",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    OutlinedTextField(
                        value = uiState.name,
                        onValueChange = { viewModel.updateName(it) },
                        label = { Text("Profile Name / Remarks") },
                        placeholder = { Text("e.g. My Fast Server") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    val isDns = uiState.isDnsttOrNoizOrVaydnsBased || uiState.isSlipstreamBased
                    OutlinedTextField(
                        value = if (uiState.isVless && uiState.cdnIp.isNotBlank()) uiState.cdnIp else uiState.domain,
                        onValueChange = {
                            if (uiState.isVless) {
                                viewModel.updateCdnIp(it)
                                viewModel.updateDomain(it)
                            } else {
                                viewModel.updateDomain(it)
                            }
                        },
                        label = { Text(if (isDns) "Tunnel Domain / NS" else "Server Address / IP / Host") },
                        placeholder = { Text(if (isDns) "t.example.com" else "104.252.175.11 or server.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (!isDns) {
                        OutlinedTextField(
                            value = if (uiState.isVless) uiState.cdnPort else uiState.sshPort,
                            onValueChange = {
                                if (uiState.isVless) viewModel.updateCdnPort(it)
                                else viewModel.updateSshPort(it)
                            },
                            label = { Text("Port") },
                            placeholder = { Text("443") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Section 3: Protocol Credentials & Transport
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Authentication & Transport",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    // For VLESS / VMess
                    if (uiState.isVless || uiState.isVmess) {
                        OutlinedTextField(
                            value = uiState.vlessUuid,
                            onValueChange = { viewModel.updateVlessUuid(it) },
                            label = { Text("User ID (UUID)") },
                            trailingIcon = {
                                IconButton(onClick = { viewModel.updateVlessUuid(UUID.randomUUID().toString()) }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Generate UUID")
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = uiState.vlessTransport,
                                onValueChange = { viewModel.updateVlessTransport(it) },
                                label = { Text("Transport (ws, tcp, grpc)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = uiState.vlessWsPath,
                                onValueChange = { viewModel.updateVlessWsPath(it) },
                                label = { Text("WS Path") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        OutlinedTextField(
                            value = uiState.vlessSni,
                            onValueChange = { viewModel.updateVlessSni(it) },
                            label = { Text("SNI (Server Name Indication)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // For DNS Tunnels (MasterDNS, StormDNS, Meli, DNSTT, Slipstream)
                    if (uiState.isDnsttOrNoizOrVaydnsBased || uiState.isSlipstreamBased) {
                        OutlinedTextField(
                            value = uiState.dnsttPublicKey,
                            onValueChange = { viewModel.updateDnsttPublicKey(it) },
                            label = { Text("Public / Encryption Key (Hex)") },
                            placeholder = { Text("0123456789abcdef0123456789abcdef") },
                            trailingIcon = {
                                IconButton(onClick = {
                                    val is32 = uiState.tunnelType in listOf(
                                        TunnelType.MASTERDNS, TunnelType.MASTERDNS_SSH,
                                        TunnelType.STORMDNS, TunnelType.STORMDNS_SSH,
                                        TunnelType.COTTENDNS, TunnelType.COTTENDNS_SSH
                                    )
                                    viewModel.updateDnsttPublicKey(
                                        if (is32) "0123456789abcdef0123456789abcdef"
                                        else "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                                    )
                                }) {
                                    Icon(Icons.Default.AutoFixHigh, contentDescription = "Default Key")
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = uiState.resolvers,
                            onValueChange = { viewModel.updateResolvers(it) },
                            label = { Text("Upstream DNS Resolvers") },
                            placeholder = { Text("8.8.8.8, 1.1.1.1, 94.140.14.14") },
                            trailingIcon = {
                                IconButton(onClick = { viewModel.updateResolvers("8.8.8.8, 1.1.1.1, 94.140.14.14") }) {
                                    Icon(Icons.Default.Dns, contentDescription = "Clean Resolvers")
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("DNS Transport:", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            FilterChip(
                                selected = uiState.dnsTransport == DnsTransport.UDP,
                                onClick = { viewModel.updateDnsTransport(DnsTransport.UDP) },
                                label = { Text("UDP (Port 53)") }
                            )
                            Spacer(Modifier.width(8.dp))
                            FilterChip(
                                selected = uiState.dnsTransport == DnsTransport.DOH,
                                onClick = { viewModel.updateDnsTransport(DnsTransport.DOH) },
                                label = { Text("DoH (HTTPS)") }
                            )
                        }
                    }

                    // For Password-based tunnels (Trojan, Shadowsocks, SSH)
                    if (uiState.tunnelType in listOf(TunnelType.TROJAN, TunnelType.SHADOWSOCKS, TunnelType.SSH, TunnelType.SOCKS5)) {
                        OutlinedTextField(
                            value = uiState.sshUsername,
                            onValueChange = { viewModel.updateSshUsername(it) },
                            label = { Text("Username") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = uiState.sshPassword,
                            onValueChange = { viewModel.updateSshPassword(it) },
                            label = { Text("Password") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Section 4: Advanced Tuning (Collapsible)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.clickable { showAdvanced = !showAdvanced }
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Advanced Engine Options",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Icon(
                            if (showAdvanced) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null
                        )
                    }

                    AnimatedVisibility(visible = showAdvanced) {
                        Column(
                            modifier = Modifier.padding(top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = uiState.keepAliveInterval,
                                onValueChange = { viewModel.updateKeepAliveInterval(it) },
                                label = { Text("Keep-Alive Interval (ms)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("SNI Fragmentation (Bypass Censorship)", fontSize = 12.sp)
                                Switch(
                                    checked = uiState.sniFragmentEnabled,
                                    onCheckedChange = { viewModel.updateSniFragmentEnabled(it) }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Save Action Button at Bottom
            Button(
                onClick = { viewModel.save() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Save Configuration", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
