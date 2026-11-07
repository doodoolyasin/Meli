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

/** One subscription: the user's Remark, the saved link and how many configs it produced. */
private data class SubRow(val remark: String, val url: String, val count: Int)

/** Editor state. [oldRemark] == null means "adding a new subscription". */
private class SubEditor(val oldRemark: String?, val remark: String, val url: String)

/** Hide query/fragment so tokens in a link are not shown on screen. */
private fun shortUrl(url: String): String {
    val bare = url.substringBefore('#').substringBefore('?')
    return if (bare.length < url.length) "$bare?…" else bare
}

private fun isHttpUrl(url: String): Boolean {
    val u = url.trim()
    return u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true)
}

/**
 * PattNG-style Subscription page: a full-screen list of "Remark + URL" cards with
 * update / edit / delete, "+" to add and "sync" to update everything.
 *
 * [status] is a one-line progress/result message (a Snackbar would sit behind this window).
 */
@Composable
internal fun SubscriptionPage(
    urls: Map<String, String>,
    groups: List<String>,
    counts: Map<String, Int>,
    status: String?,
    onDismiss: () -> Unit,
    onSave: (oldRemark: String?, remark: String, url: String) -> Unit,
    onUpdate: (remark: String) -> Unit,
    onUpdateAll: () -> Unit,
    onDelete: (remark: String) -> Unit,
    onOpen: (remark: String) -> Unit
) {
    val rows = (urls.keys + groups).distinct().map { SubRow(it, urls[it].orEmpty(), counts[it] ?: 0) }
    var editor by remember { mutableStateOf<SubEditor?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Text(
                        "Subscription",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = onUpdateAll,
                        enabled = rows.any { it.url.isNotBlank() }
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = "Update all")
                    }
                    IconButton(onClick = { editor = SubEditor(null, "", "") }) {
                        Icon(Icons.Default.Add, contentDescription = "Add subscription")
                    }
                }
                HorizontalDivider()

                if (!status.isNullOrBlank()) {
                    Text(
                        status,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                if (rows.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.Link,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("No subscriptions yet", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Add a Remark and a link. Its configs are fetched into that group.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { editor = SubEditor(null, "", "") }) {
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
                                onUpdate = { onUpdate(row.remark) },
                                onEdit = { editor = SubEditor(row.remark, row.remark, row.url) },
                                onDelete = { pendingDelete = row.remark }
                            )
                        }
                    }
                }
            }
        }
    }

    editor?.let { ed ->
        var remark by remember(ed) { mutableStateOf(ed.remark) }
        var url by remember(ed) { mutableStateOf(ed.url) }
        val clipboard = LocalClipboardManager.current
        val urlOk = isHttpUrl(url)
        AlertDialog(
            onDismissRequest = { editor = null },
            title = { Text(if (ed.oldRemark == null) "Add subscription" else "Edit subscription") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = remark,
                        onValueChange = { remark = it },
                        label = { Text("Remark") },
                        placeholder = { Text("e.g. VIP") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("URL") },
                        placeholder = { Text("https://…") },
                        singleLine = true,
                        isError = url.isNotBlank() && !urlOk,
                        supportingText = {
                            if (url.isNotBlank() && !urlOk) Text("Link must start with http:// or https://")
                        },
                        trailingIcon = {
                            IconButton(onClick = {
                                clipboard.getText()?.text?.let { url = it.trim() }
                            }) {
                                Icon(Icons.Default.ContentPaste, contentDescription = "Paste")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSave(ed.oldRemark, remark, url)
                        editor = null
                    },
                    enabled = urlOk
                ) { Text("Save & update") }
            },
            dismissButton = {
                TextButton(onClick = { editor = null }) { Text("Cancel") }
            }
        )
    }

    pendingDelete?.let { remark ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete subscription?") },
            text = { Text("\"$remark\" and its ${counts[remark] ?: 0} configs will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(remark)
                    pendingDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SubscriptionCard(
    row: SubRow,
    onOpen: () -> Unit,
    onUpdate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    row.remark,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (row.url.isBlank()) "No link saved" else shortUrl(row.url),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${row.count} configs",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onUpdate, enabled = row.url.isNotBlank()) {
                Icon(Icons.Default.Refresh, contentDescription = "Update")
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit")
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
