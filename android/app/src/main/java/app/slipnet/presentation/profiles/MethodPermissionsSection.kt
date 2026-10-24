package app.slipnet.presentation.profiles

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.slipnet.domain.model.TunnelType

/**
 * Permissions + in-app setup guide for the selected tunnel method.
 *
 * Shown inside the New/Edit Profile screen for every method. Statuses are
 * recomputed after each grant callback so the row flips to a green check the
 * moment the system dialog is accepted — no stale "not granted" state.
 */
@Composable
fun MethodPermissionsSection(
    tunnelType: TunnelType,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Plain mutableStateOf holders (no `by` delegation) — captured by the
    // local refresh function below without any capture edge cases.
    val vpnOk = remember { mutableStateOf(VpnService.prepare(context) == null) }
    val notifOk = remember { mutableStateOf(hasNotificationPermission(context)) }
    val batteryOk = remember { mutableStateOf(isBatteryExempt(context)) }
    val showGuide = remember { mutableStateOf(false) }
    val showPermissions = remember { mutableStateOf(false) }

    fun refreshAll() {
        vpnOk.value = VpnService.prepare(context) == null
        notifOk.value = hasNotificationPermission(context)
        batteryOk.value = isBatteryExempt(context)
    }

    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshAll() }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshAll() }

    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshAll() }

    val isNotificationRowShown = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val rows = buildList {
        add(
            PermissionRowModel(
                icon = Icons.Default.Shield,
                titleEn = "VPN connection",
                titleFa = "اجازه اتصال VPN",
                detailEn = "Android must allow SlipNet to create the VPN tunnel. This dialog appears only the first time.",
                detailFa = "اندروید باید اجازه ساخت تونل VPN را بدهد. این درخواست فقط بار اول نشان داده می‌شود.",
                granted = vpnOk.value,
                actionLabel = "Grant"
            )
        )
        if (isNotificationRowShown) {
            add(
                PermissionRowModel(
                    icon = Icons.Default.Notifications,
                    titleEn = "Notifications",
                    titleFa = "اجازه اعلان‌ها",
                    detailEn = "Shows the connection status notification. Without it you cannot see that the VPN is running.",
                    detailFa = "اعلان وضعیت اتصال را نشان می‌دهد. بدون آن نمی‌بینید که VPN روشن است.",
                    granted = notifOk.value,
                    actionLabel = "Grant"
                )
            )
        }
        add(
            PermissionRowModel(
                icon = Icons.Default.BatteryAlert,
                titleEn = "Battery exemption",
                titleFa = "بدون محدودیت باتری",
                detailEn = "Stops Android from killing the tunnel when the screen is off (Doze mode).",
                detailFa = "کاری می‌کند اندروید تونل را وقتی صفحه خاموش است نکشد.",
                granted = batteryOk.value,
                actionLabel = "Allow"
            )
        )
    }

    val grantedCount = rows.count { it.granted }
    val allGranted = grantedCount == rows.size

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showPermissions.value = !showPermissions.value },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Permissions ($grantedCount/${rows.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (isNotificationRowShown) "3 permissions · method: ${tunnelType.displayName}"
                        else "2 permissions · method: ${tunnelType.displayName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (allGranted) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "All granted",
                        tint = Color(0xFF2E7D32),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Icon(
                    imageVector = if (showPermissions.value) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
            }

            AnimatedVisibility(
                visible = showPermissions.value,
                enter = expandVertically(animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)),
                exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(200))
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    rows.forEachIndexed { index, row ->
                        PermissionRow(row = row, onAction = {
                            when (index) {
                                0 -> {
                                    val intent = VpnService.prepare(context)
                                    if (intent == null) refreshAll()
                                    else runCatching { vpnLauncher.launch(intent) }.onFailure { refreshAll() }
                                }
                                1 -> {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        runCatching {
                                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                    }
                                }
                                else -> {
                                    val intent = Intent(
                                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    runCatching { batteryLauncher.launch(intent) }.onFailure {
                                        runCatching {
                                            context.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                        }
                                    }
                                }
                            }
                        })
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    TextButton(
                        onClick = { showGuide.value = !showGuide.value },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
                    ) {
                        Text(
                            text = if (showGuide.value) "Hide setup guide" else "Exact setup guide",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = if (showGuide.value) Icons.Default.KeyboardArrowUp
                            else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    AnimatedVisibility(
                        visible = showGuide.value,
                        enter = expandVertically(animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)),
                        exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(200))
                    ) {
                        SetupGuide(tunnelType = tunnelType)
                    }
                }
            }
        }
    }
}

private data class PermissionRowModel(
    val icon: ImageVector,
    val titleEn: String,
    val titleFa: String,
    val detailEn: String,
    val detailFa: String,
    val granted: Boolean,
    val actionLabel: String
)

@Composable
private fun PermissionRow(row: PermissionRowModel, onAction: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    color = if (row.granted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline,
                    shape = CircleShape
                )
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.titleEn,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = row.titleFa,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = row.detailEn,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            Text(
                text = row.detailFa,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(10.dp))
        if (row.granted) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Granted",
                tint = Color(0xFF2E7D32),
                modifier = Modifier.size(22.dp)
            )
        } else {
            Button(
                onClick = onAction,
                shape = RoundedCornerShape(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Text(text = row.actionLabel, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

private fun isBatteryExempt(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}
