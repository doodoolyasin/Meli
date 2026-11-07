package app.slipnet.presentation.profiles

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.slipnet.domain.model.DnsTransport
import app.slipnet.util.ResolverListParser
import app.slipnet.util.ResolverMerge

/** Entries drawn per pool card; the rest stay in storage and are only counted. */
private const val POOL_PREVIEW = 8

/**
 * Resolver editor for the profile screen: Active (this profile), Standby (shared candidates) and
 * Harvested (scanner output) as three vertical lists. Owns the file picker, the clipboard and the
 * refresh-on-resume so the profile screen only has to place it.
 */
@Composable
fun ResolverEditorHost(
    viewModel: EditProfileViewModel,
    uiState: EditProfileUiState,
    canScan: Boolean,
    snackbarHostState: SnackbarHostState
) {
    val pools by viewModel.pools.collectAsState()
    val clipboard = LocalClipboardManager.current
    var importTarget by remember { mutableStateOf(ResolverPool.ACTIVE) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) viewModel.importResolverFile(uri, importTarget)
    }

    // The scanner saves into Harvested while this screen is paused; pick it up when we come back.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPools()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Results of imports and moves are shown once, wherever on the screen the user acted.
    LaunchedEffect(pools.message) {
        val msg = pools.message
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearPoolMessage()
        }
    }

    val active = remember(uiState.resolvers) { ResolverListParser.splitStored(uiState.resolvers) }
    val isDoT = uiState.isDnsttOrNoizOrVaydnsBased && uiState.dnsTransport == DnsTransport.DOT

    ResolverEditor(
        active = active,
        maxActive = EditProfileViewModel.MAX_RESOLVERS,
        error = uiState.resolversError,
        isDoT = isDoT,
        isAutoDetecting = uiState.isAutoDetecting,
        canScan = canScan,
        pools = pools,
        onAdd = viewModel::addResolver,
        onPickFile = { target ->
            importTarget = target
            picker.launch(arrayOf("text/plain", "*/*"))
        },
        onPaste = { target ->
            val text = clipboard.getText()?.text
            if (text.isNullOrBlank()) viewModel.postPoolMessage("Clipboard is empty")
            else viewModel.importResolverText(text, target)
        },
        onRemove = viewModel::removePoolEntry,
        onPromote = viewModel::promoteResolver,
        onDemote = viewModel::demoteResolver,
        onClear = viewModel::clearPool,
        onUseHarvested = viewModel::useHarvested,
        onHarvestedToStandby = viewModel::moveHarvestedToStandby,
        onAutoPick = viewModel::setAutoPick,
        onAddFast = viewModel::addFastPublicResolvers,
        onAutoDetect = viewModel::autoDetectResolver,
        onScan = viewModel::saveForScanner
    )
}

@Composable
private fun ResolverEditor(
    active: List<String>,
    maxActive: Int,
    error: String?,
    isDoT: Boolean,
    isAutoDetecting: Boolean,
    canScan: Boolean,
    pools: ResolverPoolsState,
    onAdd: (String) -> Boolean,
    onPickFile: (ResolverPool) -> Unit,
    onPaste: (ResolverPool) -> Unit,
    onRemove: (ResolverPool, String) -> Unit,
    onPromote: (String) -> Unit,
    onDemote: (String) -> Unit,
    onClear: (ResolverPool) -> Unit,
    onUseHarvested: () -> Unit,
    onHarvestedToStandby: () -> Unit,
    onAutoPick: (Boolean) -> Unit,
    onAddFast: () -> Unit,
    onAutoDetect: () -> Unit,
    onScan: () -> Unit
) {
    val port = if (isDoT) 853 else ResolverListParser.DEFAULT_PORT
    val activeKeys = remember(active, port) { active.map { ResolverMerge.key(it, port) }.toSet() }
    var standbyOpen by rememberSaveable { mutableStateOf(false) }
    var harvestedOpen by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }

    // Results from the scanner arrive after the first composition: open the card when the pool goes from
    // empty to filled, but never re-open it for a pool the user already saw and collapsed.
    val hasHarvested = pools.harvested.isNotEmpty()
    var hadHarvested by remember { mutableStateOf(hasHarvested) }
    LaunchedEffect(hasHarvested) {
        if (hasHarvested && !hadHarvested) harvestedOpen = true
        hadHarvested = hasHarvested
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        // ── Active ────────────────────────────────────────────────────────────────
        ResolverPoolCard(
            title = ResolverPool.ACTIVE.title,
            subtitle = "Used by this profile's tunnel",
            countText = "${active.size} / $maxActive",
            expanded = null,
            onToggle = null
        ) {
            if (active.isEmpty()) {
                Text(
                    text = "No resolver yet. Add one, import a .txt file or run the scanner.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            active.forEach { entry ->
                ResolverEntryRow(entry) {
                    ResolverRowButton(Icons.Default.KeyboardArrowDown, "Move to Standby") { onDemote(entry) }
                    ResolverRowButton(Icons.Default.Close, "Remove") { onRemove(ResolverPool.ACTIVE, entry) }
                }
            }
            if (error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("Add resolver") },
                placeholder = { Text(if (isDoT) "dns.google:853" else "8.8.8.8:53") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (onAdd(draft)) draft = "" }),
                trailingIcon = {
                    IconButton(
                        onClick = { if (onAdd(draft)) draft = "" },
                        enabled = draft.isNotBlank()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ResolverSmallAction(Icons.Default.FileUpload, "Import .txt", { onPickFile(ResolverPool.ACTIVE) }, Modifier.weight(1f))
                ResolverSmallAction(Icons.Default.ContentPaste, "Paste", { onPaste(ResolverPool.ACTIVE) }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ResolverSmallAction(Icons.Default.Add, "Fast", onAddFast, Modifier.weight(1f))
                ResolverSmallAction(null, "Local DNS", onAutoDetect, Modifier.weight(1f), busy = isAutoDetecting)
                if (canScan) {
                    ResolverSmallAction(Icons.Default.Search, "Scanner", onScan, Modifier.weight(1f))
                }
            }
        }

        // ── Standby ───────────────────────────────────────────────────────────────
        ResolverPoolCard(
            title = ResolverPool.STANDBY.title,
            subtitle = "Candidates shared by all profiles",
            countText = "${pools.standby.size}",
            expanded = standbyOpen,
            onToggle = { standbyOpen = !standbyOpen }
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-pick fastest at connect", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "All profiles: tests this pool on every connect (adds a few seconds) and replaces " +
                            "Active with the fastest responders. If none respond, the connect fails. " +
                            "UDP/TCP profiles only; DoT and DoH skip it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = pools.autoPick, onCheckedChange = onAutoPick)
            }
            Spacer(Modifier.height(6.dp))
            ResolverPoolEntries(
                entries = pools.standby,
                emptyHint = "Empty. Import a .txt file, paste a list, or move entries down from Active.",
                activeKeys = activeKeys,
                port = port,
                onPromote = onPromote,
                onRemove = { onRemove(ResolverPool.STANDBY, it) }
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ResolverSmallAction(Icons.Default.FileUpload, "Import .txt", { onPickFile(ResolverPool.STANDBY) }, Modifier.weight(1f))
                ResolverSmallAction(Icons.Default.ContentPaste, "Paste", { onPaste(ResolverPool.STANDBY) }, Modifier.weight(1f))
                ResolverSmallAction(
                    Icons.Default.Close, "Clear", { onClear(ResolverPool.STANDBY) }, Modifier.weight(1f),
                    enabled = pools.standby.isNotEmpty()
                )
            }
        }

        // ── Harvested ─────────────────────────────────────────────────────────────
        ResolverPoolCard(
            title = ResolverPool.HARVESTED.title,
            subtitle = pools.harvestedLabel ?: "Saved from the scanner",
            countText = "${pools.harvested.size}",
            expanded = harvestedOpen,
            onToggle = { harvestedOpen = !harvestedOpen }
        ) {
            ResolverPoolEntries(
                entries = pools.harvested,
                emptyHint = "Nothing yet. In the scanner results open the menu and choose \u201CSave as Harvested pool\u201D.",
                activeKeys = activeKeys,
                port = port,
                onPromote = onPromote,
                onRemove = { onRemove(ResolverPool.HARVESTED, it) }
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pools.harvested.isEmpty()) {
                    if (canScan) {
                        ResolverSmallAction(Icons.Default.Search, "Open scanner", onScan, Modifier.weight(1f))
                    }
                } else {
                    ResolverSmallAction(Icons.Default.KeyboardArrowUp, "Use best", onUseHarvested, Modifier.weight(1f))
                    ResolverSmallAction(Icons.Default.KeyboardArrowDown, "To Standby", onHarvestedToStandby, Modifier.weight(1f))
                    ResolverSmallAction(Icons.Default.Close, "Clear", { onClear(ResolverPool.HARVESTED) }, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ResolverPoolCard(
    title: String,
    subtitle: String,
    countText: String,
    expanded: Boolean?,
    onToggle: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                ResolverCountPill(countText)
                if (expanded != null) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (expanded != false) {
                Spacer(Modifier.height(8.dp))
                content()
            }
        }
    }
}

@Composable
private fun ResolverCountPill(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

@Composable
private fun ResolverEntryRow(entry: String, actions: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = entry,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        actions()
    }
}

@Composable
private fun ResolverRowButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ResolverPoolEntries(
    entries: List<String>,
    emptyHint: String,
    activeKeys: Set<String>,
    port: Int,
    onPromote: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    if (entries.isEmpty()) {
        Text(
            text = emptyHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        entries.take(POOL_PREVIEW).forEach { entry ->
            val inActive = ResolverMerge.key(entry, port) in activeKeys
            ResolverEntryRow(entry) {
                if (inActive) {
                    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Already in Active",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else {
                    ResolverRowButton(Icons.Default.KeyboardArrowUp, "Add to Active") { onPromote(entry) }
                }
                ResolverRowButton(Icons.Default.Close, "Remove") { onRemove(entry) }
            }
        }
        if (entries.size > POOL_PREVIEW) {
            Text(
                text = "+ ${entries.size - POOL_PREVIEW} more",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun ResolverSmallAction(
    icon: ImageVector?,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(4.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
