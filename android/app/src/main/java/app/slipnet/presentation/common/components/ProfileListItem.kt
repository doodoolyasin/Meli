package app.slipnet.presentation.common.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.slipnet.domain.model.PingResult
import app.slipnet.domain.model.ServerProfile
import app.slipnet.domain.model.TunnelType
import app.slipnet.presentation.profiles.EditProfileViewModel
import app.slipnet.presentation.theme.ColorConfigType
import app.slipnet.presentation.theme.ColorPingGreen
import app.slipnet.presentation.theme.ColorPingOrange
import app.slipnet.presentation.theme.ColorPingRed
import app.slipnet.presentation.theme.ColorSelectionAccent
import app.slipnet.presentation.theme.ConnectedGreen
import app.slipnet.tunnel.DOH_SERVERS

/**
 * PattNG-styled Server Row Item:
 * Features a distinct left accent indicator bar for active/selected server,
 * bold remarks typography, inline action buttons (Share, Edit, Delete, More),
 * endpoint subtitle, protocol badge pill, and real-time latency ping badge.
 */
@Composable
fun ProfileListItem(
    profile: ServerProfile,
    isSelected: Boolean,
    isConnected: Boolean,
    pingResult: PingResult? = null,
    onClick: () -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onExportClick: () -> Unit,
    onShareQrClick: () -> Unit,
    onPinClick: () -> Unit,
    onPingClick: () -> Unit,
    isPingRunning: Boolean = false,
    modifier: Modifier = Modifier
) {
    var showExportMenu by remember { mutableStateOf(false) }

    val containerColor = when {
        isConnected -> ConnectedGreen.copy(alpha = 0.08f)
        isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
        else -> MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            // PattNG Left Indicator Accent Bar
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(
                        when {
                            isConnected -> ConnectedGreen
                            isSelected -> ColorSelectionAccent
                            else -> Color.Transparent
                        }
                    )
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)
            ) {
                // Top Row: Remarks & Inline Actions (Share, Edit, Delete, More)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    if (profile.isPinned) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = "Pinned",
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (profile.isLocked) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Locked",
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // PattNG Inline Action Buttons
                    IconButton(
                        onClick = onExportClick,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(
                        onClick = onEditClick,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(
                        onClick = onDeleteClick,
                        enabled = !isConnected,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete",
                            modifier = Modifier.size(18.dp),
                            tint = if (!isConnected) MaterialTheme.colorScheme.onSurfaceVariant else Color.Gray.copy(alpha = 0.4f)
                        )
                    }

                    Box {
                        IconButton(
                            onClick = { showExportMenu = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        DropdownMenu(
                            expanded = showExportMenu,
                            onDismissRequest = { showExportMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (profile.isPinned) "برداشتن سنجاق" else "سنجاق به بالا") },
                                onClick = {
                                    showExportMenu = false
                                    onPinClick()
                                },
                                leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("اشتراک QR Code") },
                                onClick = {
                                    showExportMenu = false
                                    onShareQrClick()
                                },
                                leadingIcon = { Icon(Icons.Default.QrCode2, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("تست پینگ زنده") },
                                onClick = {
                                    showExportMenu = false
                                    onPingClick()
                                },
                                enabled = !isPingRunning,
                                leadingIcon = { Icon(Icons.Default.NetworkPing, contentDescription = null) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Subtitle: Endpoint / Host / Port Details
                val subtitleText = if (profile.isLocked) {
                    "پیکربندی محافظت‌شده (رمزنگاری اختصاصی)"
                } else {
                    val hostPart = if (profile.cdnIp.isNotBlank()) profile.cdnIp else profile.domain
                    when (profile.tunnelType) {
                        TunnelType.DOH -> DOH_SERVERS.firstOrNull { it.url == profile.dohUrl }?.name ?: profile.dohUrl
                        TunnelType.SSH -> "$hostPart:${profile.sshPort}"
                        TunnelType.SNOWFLAKE -> "شبکه Tor / Snowflake"
                        TunnelType.VLESS, TunnelType.VLESS_REALITY -> "$hostPart:${profile.cdnPort} (${profile.vlessTransport.ifBlank { "tcp" }})"
                        TunnelType.VMESS, TunnelType.TROJAN -> "$hostPart:${profile.cdnPort}"
                        TunnelType.WIREGUARD -> "$hostPart:${profile.cdnPort} (UDP)"
                        else -> "$hostPart:${profile.cdnPort.takeIf { it > 0 } ?: 53}"
                    }
                }

                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Bottom Row: Protocol Badge Pill on Left & Latency Result on Right
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val tunnelTypeLabel = when {
                        profile.tunnelType == TunnelType.MELI -> "Meli Protocol (Rust)"
                        profile.tunnelType == TunnelType.MELI_SSH -> "Meli + SSH"
                        profile.tunnelType == TunnelType.MASTERDNS -> "MasterDNS (UDP)"
                        profile.tunnelType == TunnelType.MASTERDNS_SSH -> "MasterDNS + SSH"
                        profile.tunnelType == TunnelType.STORMDNS -> "StormDNS"
                        profile.tunnelType == TunnelType.VLESS_REALITY -> "VLESS / Reality"
                        profile.tunnelType == TunnelType.VLESS -> "VLESS"
                        profile.tunnelType == TunnelType.WIREGUARD -> "WireGuard"
                        profile.tunnelType == TunnelType.BALE -> "Bale Meet (WebRTC)"
                        profile.tunnelType == TunnelType.PAQET -> "Paqet (Raw TCP)"
                        profile.tunnelType == TunnelType.SNOWFLAKE -> EditProfileViewModel.detectBridgeType(profile.torBridgeLines).displayName
                        else -> profile.tunnelType.displayName
                    }

                    // Protocol badge pill (PattNG style)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(ColorConfigType.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = tunnelTypeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = ColorConfigType,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Ping Latency Badge
                    when (pingResult) {
                        is PingResult.Pending -> {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 1.5.dp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        is PingResult.Testing -> {
                            Text(
                                text = pingResult.phase,
                                style = MaterialTheme.typography.labelSmall,
                                color = ColorPingOrange,
                                maxLines = 1
                            )
                        }
                        is PingResult.Success -> {
                            val latencyColor = when {
                                pingResult.latencyMs < 350 -> ColorPingGreen
                                pingResult.latencyMs < 900 -> ColorPingOrange
                                else -> ColorPingRed
                            }
                            Text(
                                text = "${pingResult.latencyMs} ms",
                                style = MaterialTheme.typography.labelSmall,
                                color = latencyColor,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        is PingResult.Error -> {
                            Text(
                                text = pingResult.message,
                                style = MaterialTheme.typography.labelSmall,
                                color = ColorPingRed,
                                maxLines = 1
                            )
                        }
                        is PingResult.Skipped, null -> {
                            Text(
                                text = if (isConnected) "متصل" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isConnected) ColorPingGreen else Color.Transparent
                            )
                        }
                    }
                }
            }
        }
    }
}
