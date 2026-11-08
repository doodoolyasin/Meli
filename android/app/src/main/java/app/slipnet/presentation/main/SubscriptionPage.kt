package app.slipnet.presentation.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material.icons.filled.Share
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import app.slipnet.util.SubMeta
import app.slipnet.util.UpdateFlags
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** One subscription row. */
private data class SubRow(val remark: String, val url: String, val count: Int, val meta: SubMeta)

/** Editor state. [oldRemark] == null means "adding a new subscription". */
private class SubEditor(val oldRemark: String?, val remark: String, val url: String, val meta: SubMeta)

/** What the "Update subscription" dialog will run: a single group, or all (null). */
private class UpdateTarget(val remark: String?)

private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

private fun isHttpUrl(url: String): Boolean {
    val u = url.trim()
    return u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true)
}

/**
 * PattNG-style Subscriptions page: top bar with [+] and [update], rows with remarks / url /
 * last-updated, share, edit, delete and an enable switch.
 */
@Composable
internal fun SubscriptionPage(
    urls: Map<String, String>,
    metas: Map<String, SubMeta>,
    groups: List<String>,
    counts: Map<String, Int>,
    status: String?,
    onDismiss: () -> Unit,
    onSave: (oldRemark: String?, remark: String, url: String, meta: SubMeta) -> Unit,
    onUpdate: (remark: String, flags: UpdateFlags) -> Unit,
    onUpdateAll: (flags: UpdateFlags) -> Unit,
    onDelete: (remark: String) -> Unit,
    onOpen: (remark: String) -> Unit,
    onToggle: (remark: String, enabled: Boolean) -> Unit
) {
    val rows = (urls.keys + groups).distinct().map {
        SubRow(it, urls[it].orEmpty(), counts[it] ?: 0, metas[it] ?: SubMeta())
    }
    var editor by remember { mutableStateOf<SubEditor?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var updateTarget by remember { mutableStateOf<UpdateTarget?>(null) }
    val clipboard = LocalClipboardManager.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    Text("Subscriptions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { editor = SubEditor(null, "", "", SubMeta()) }) {
                        Icon(Icons.Default.Add, contentDescription = "Add subscription")
                    }
                    IconButton(onClick = { updateTarget = UpdateTarget(null) }, enabled = rows.any { it.url.isNotBlank() }) {
                        Icon(Icons.Default.Sync, contentDescription = "Update all")
                    }
                }
                HorizontalDivider()
                if (!status.isNullOrBlank()) {
                    Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
                }
                if (rows.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Text("No subscriptions yet", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { editor = SubEditor(null, "", "", SubMeta()) }) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add subscription")
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(rows, key = { it.remark }) { row ->
                            SubscriptionCard(
                                row = row,
                                onOpen = { onOpen(row.remark) },
                                onShare = { clipboard.setText(AnnotatedString(row.url)) },
                                onEdit = { editor = SubEditor(row.remark, row.remark, row.url, row.meta) },
                                onDelete = { pendingDelete = row.remark },
                                onToggle = { onToggle(row.remark, it) }
                            )
                        }
                    }
                }
            }
        }
    }

    updateTarget?.let { t ->
        var autoTest by remember(t) { mutableStateOf(false) }
        var delInvalid by remember(t) { mutableStateOf(false) }
        var autoSort by remember(t) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { updateTarget = null },
            title = { Text("Update subscription") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SwitchRow("Auto test after updating", autoTest) { autoTest = it; if (!it) { delInvalid = false; autoSort = false } }
                    SwitchRow("Auto delete invalid after test", delInvalid, enabled = autoTest) { delInvalid = it }
                    SwitchRow("Auto sort after test", autoSort, enabled = autoTest) { autoSort = it }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val f = UpdateFlags(autoTest, delInvalid, autoSort)
                    val r = t.remark
                    updateTarget = null
                    if (r == null) onUpdateAll(f) else onUpdate(r, f)
                }) { Text("Update") }
            },
            dismissButton = { TextButton(onClick = { updateTarget = null }) { Text("Cancel") } }
        )
    }

    editor?.let { ed ->
        var remark by remember(ed) { mutableStateOf(ed.remark) }
        var url by remember(ed) { mutableStateOf(ed.url) }
        var ua by remember(ed) { mutableStateOf(ed.meta.userAgent) }
        var hdr by remember(ed) { mutableStateOf(ed.meta.headersJson) }
        var flt by remember(ed) { mutableStateOf(ed.meta.remarksFilter) }
        var enabled by remember(ed) { mutableStateOf(ed.meta.enabled) }
        var auto by remember(ed) { mutableStateOf(ed.meta.autoUpdate) }
        var interval by remember(ed) { mutableStateOf(ed.meta.intervalMinutes.toString()) }
        var http by remember(ed) { mutableStateOf(ed.meta.allowInsecureHttp) }
        val urlOk = isHttpUrl(url)
        val intervalVal = interval.toIntOrNull()
        val intervalOk = intervalVal != null && intervalVal >= SubMeta.MIN_INTERVAL
        val hdrOk = SubMeta(headersJson = hdr).parseHeaders() != null
        val fltOk = flt.isBlank() || try { Regex(flt); true } catch (_: Exception) { false }
        val httpBlocked = url.trim().startsWith("http://", ignoreCase = true) && !http
        AlertDialog(
            onDismissRequest = { editor = null },
            title = { Text(if (ed.oldRemark == null) "Add subscription" else "Edit subscription") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(remark, { remark = it }, label = { Text("Remarks") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        value = url, onValueChange = { url = it }, label = { Text("URL") },
                        placeholder = { Text("https://…") }, singleLine = true,
                        isError = url.isNotBlank() && (!urlOk || httpBlocked),
                        supportingText = {
                            if (url.isNotBlank() && !urlOk) Text("Link must start with http:// or https://")
                            else if (httpBlocked) Text("Enable \"Allow insecure HTTP\" for http:// links")
                        },
                        trailingIcon = {
                            IconButton(onClick = { clipboard.getText()?.text?.let { url = it.trim() } }) {
                                Icon(Icons.Default.ContentPaste, contentDescription = "Paste")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(ua, { ua = it }, label = { Text("User Agent") }, placeholder = { Text("v2rayNG/1.8.5") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(hdr, { hdr = it }, label = { Text("Request Headers (JSON)") }, isError = !hdrOk,
                        supportingText = { if (!hdrOk) Text("Must be a JSON object") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(flt, { flt = it }, label = { Text("Remarks filter (regex)") }, isError = !fltOk, singleLine = true,
                        supportingText = { if (!fltOk) Text("Invalid regex") }, modifier = Modifier.fillMaxWidth())
                    SwitchRow("Enable update", enabled) { enabled = it }
                    SwitchRow("Enable automatic update", auto) { auto = it }
                    if (auto) {
                        OutlinedTextField(interval, { interval = it.filter(Char::isDigit) }, label = { Text("Update interval (minutes)") },
                            isError = !intervalOk, singleLine = true,
                            supportingText = { Text(if (intervalOk) "Default 1440" else "Minimum ${SubMeta.MIN_INTERVAL}") },
                            modifier = Modifier.fillMaxWidth())
                    }
                    SwitchRow("Allow insecure HTTP", http) { http = it }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val m = ed.meta.copy(
                            userAgent = ua.trim(), headersJson = hdr.trim(), remarksFilter = flt.trim(),
                            enabled = enabled, autoUpdate = auto,
                            intervalMinutes = (intervalVal ?: 1440).coerceAtLeast(SubMeta.MIN_INTERVAL),
                            allowInsecureHttp = http
                        )
                        onSave(ed.oldRemark, remark, url, m)
                        editor = null
                    },
                    enabled = urlOk && hdrOk && fltOk && (!auto || intervalOk)
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editor = null }) { Text("Cancel") } }
        )
    }

    pendingDelete?.let { remark ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete subscription?") },
            text = { Text("\"$remark\" and its ${counts[remark] ?: 0} configs will be removed.") },
            confirmButton = {
                TextButton(onClick = { onDelete(remark); pendingDelete = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun SubscriptionCard(
    row: SubRow,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, bottom = 4.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(row.remark, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (row.url.isBlank()) "No link saved" else row.url.substringBefore('#').substringBefore('?').let { if (it.length < row.url.length) "$it?…" else it },
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${row.count} configs · " + if (row.meta.lastUpdated > 0) dateFmt.format(Date(row.meta.lastUpdated)) else "never updated",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.primary
                    )
                }
                Switch(checked = row.meta.enabled, onCheckedChange = onToggle, modifier = Modifier.graphicsLayer(scaleX = 0.7f, scaleY = 0.7f))
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onShare, enabled = row.url.isNotBlank()) { Icon(Icons.Default.Share, contentDescription = "Copy link") }
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
