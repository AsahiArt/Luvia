@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package tech.asahiart.luvia.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.asahiart.luvia.HostRole
import tech.asahiart.luvia.isTailnetAddress
import tech.asahiart.luvia.TerminalKey
import tech.asahiart.luvia.ui.theme.LuviaTheme
import tech.asahiart.luvia.HostSection
import tech.asahiart.luvia.PushRegistrar

@Composable
fun HostListPane(
    hosts: List<HostUiModel>,
    selectedHostId: String?,
    onSelect: (String) -> Unit,
    onAddHost: () -> Unit,
    onConnect: (String) -> Unit = {},
    onDisconnect: (String) -> Unit = {},
    onRefreshAll: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var nowEpochMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            nowEpochMs = System.currentTimeMillis()
        }
    }
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddHost,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add host")
            }
        },
        topBar = {
            LargeTopAppBar(
                title = { Text("Luvia", style = MaterialTheme.typography.headlineMedium) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        if (hosts.isEmpty()) {
            EmptyHostsPane(
                onAddHost = onAddHost,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        } else {
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    scope.launch {
                        refreshing = true
                        onRefreshAll()
                        delay(400)
                        refreshing = false
                    }
                },
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(hosts, key = { it.id }) { host ->
                        HostRow(
                            host = host,
                            selected = selectedHostId == host.id,
                            nowEpochMs = nowEpochMs,
                            onSelect = onSelect,
                            onConnect = onConnect,
                            onDisconnect = onDisconnect,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HostRow(
    host: HostUiModel,
    selected: Boolean,
    nowEpochMs: Long,
    onSelect: (String) -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: (String) -> Unit,
) {
    val action = when {
        host.connection == ConnectionBadge.Connecting -> "Cancel"
        host.connected -> "Disconnect"
        else -> "Connect"
    }
    val onAction = {
        if (host.connected || host.connection == ConnectionBadge.Connecting) {
            onDisconnect(host.id)
        } else {
            onConnect(host.id)
        }
    }
    val freshness = freshnessLabel(host.lastUpdatedEpochMs, nowEpochMs)
    val statusLine = buildString {
        append(host.connection.label())
        if (freshness != null) {
            append(" · ")
            append(freshness)
        }
    }
    Card(
        onClick = { onSelect(host.id) },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        ListItem(
            headlineContent = {
                Text(host.name, style = MaterialTheme.typography.titleMedium)
            },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        statusLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            host.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (isTailnetAddress(host.address)) {
                            HostBadge("Tailnet", LuviaTheme.extended.connecting)
                        }
                        if (host.backend.equals("herdr", ignoreCase = true)) {
                            HostBadge("Herdr", LuviaTheme.extended.live)
                        }
                    }
                    host.errorMessage?.let { error ->
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            leadingContent = {
                HostAvatar(name = host.name, connection = host.connection)
            },
            trailingContent = {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (host.blockedAgents > 0) {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ) {
                            Text("${host.blockedAgents}")
                        }
                    }
                    TextButton(
                        onClick = onAction,
                        modifier = Modifier.semantics(mergeDescendants = true) {
                            contentDescription = action
                            role = Role.Button
                        },
                    ) { Text(action) }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
private fun HostAvatar(name: String, connection: ConnectionBadge) {
    Box(contentAlignment = Alignment.BottomEnd) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(99.dp),
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    name.trim().take(1).ifEmpty { "H" }.uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        Surface(
            color = connection.color(),
            shape = RoundedCornerShape(99.dp),
            modifier = Modifier.size(12.dp).border(2.dp, MaterialTheme.colorScheme.surface, RoundedCornerShape(99.dp)),
        ) {}
    }
}

@Composable
internal fun HostBadge(label: String, color: Color) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
fun HostDetailPane(
    host: HostUiModel,
    section: HostSection,
    onSection: (HostSection) -> Unit,
    onConnect: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onUnpair: () -> Unit = {},
    onUpdateConnection: (alias: String, hosts: String, port: String, username: String) -> Unit = { _, _, _, _ -> },
    sections: List<HostSection> = HostSection.entries,
    pushCapable: Boolean = false,
    pushEnabled: Boolean = false,
    hasPushDistributor: Boolean = true,
    onSetPushEnabled: (Boolean) -> Unit = {},
    agentsContent: @Composable (Modifier) -> Unit = { EmptyPane("Agents", "Connect to this host", modifier = it) },
    filesContent: @Composable (Modifier) -> Unit = { EmptyPane("Files", "Connect to this host", modifier = it) },
    searchContent: @Composable (Modifier) -> Unit = { EmptyPane("Search", "Connect to this host", modifier = it) },
    reviewContent: @Composable (Modifier) -> Unit = { EmptyPane("Review", "Connect to this host", modifier = it) },
    worktreesContent: @Composable (Modifier) -> Unit = { EmptyPane("Worktrees", "Connect to this host", modifier = it) },
    automationsContent: @Composable (Modifier) -> Unit = { EmptyPane("Automations", "Connect to this host", modifier = it) },
    tasksContent: @Composable (Modifier) -> Unit = { EmptyPane("Tasks", "Connect to this host", modifier = it) },
    layoutContent: @Composable (Modifier) -> Unit = { EmptyPane("Layout", "Connect to this host", modifier = it) },
    modifier: Modifier = Modifier,
) {
    var confirmUnpair by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    var editingConnection by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val visible = sections.ifEmpty { HostSection.entries }
    val tabs = visible.filter { it.isPrimaryTab }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(host.name, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                            if (host.backend.equals("herdr", ignoreCase = true)) {
                                HostBadge("Herdr", LuviaTheme.extended.live)
                            }
                        }
                        Text(host.sessionName ?: host.address, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
                actions = {
                    if (host.connected) {
                        TextButton(onClick = onDisconnect) {
                            Text(if (host.connection == ConnectionBadge.Connecting) "Cancel" else "Disconnect")
                        }
                    } else {
                        Button(onClick = onConnect) { Text("Connect") }
                    }
                    Box {
                        IconButton(
                            onClick = { overflowOpen = true },
                            modifier = Modifier.semantics { contentDescription = "More" },
                        ) {
                            Icon(Icons.Filled.MoreVert, contentDescription = null)
                        }
                        DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                            visible.filter { !it.isPrimaryTab }.forEach { item ->
                                DropdownMenuItem(
                                    text = { Text(item.name) },
                                    onClick = {
                                        overflowOpen = false
                                        onSection(item)
                                    },
                                    modifier = Modifier.semantics { contentDescription = item.name },
                                )
                            }
                            if (visible.any { !it.isPrimaryTab }) {
                                HorizontalDivider()
                            }
                            DropdownMenuItem(
                                text = { Text("Refresh") },
                                onClick = {
                                    overflowOpen = false
                                    onRefresh()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Edit connection") },
                                onClick = {
                                    overflowOpen = false
                                    editingConnection = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                onClick = {
                                    overflowOpen = false
                                    showSettings = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Unpair") },
                                onClick = {
                                    overflowOpen = false
                                    confirmUnpair = true
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                tabs.forEach { item ->
                    NavigationBarItem(
                        selected = item == section,
                        onClick = { onSection(item) },
                        icon = { Icon(item.barIcon(), contentDescription = null) },
                        label = { Text(item.name) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            host.errorMessage?.let { error ->
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            when (section) {
                HostSection.Agents -> agentsContent(Modifier.weight(1f))
                HostSection.Files -> filesContent(Modifier.weight(1f))
                HostSection.Search -> searchContent(Modifier.weight(1f))
                HostSection.Review -> reviewContent(Modifier.weight(1f))
                HostSection.Worktrees -> worktreesContent(Modifier.weight(1f))
                HostSection.Automations -> automationsContent(Modifier.weight(1f))
                HostSection.Tasks -> tasksContent(Modifier.weight(1f))
                HostSection.Layout -> layoutContent(Modifier.weight(1f))
            }
        }
    }
    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("Unpair ${host.name}?") },
            text = { Text("This device will no longer connect to this host. The host grant stays until you remove it on the machine.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmUnpair = false
                        onUnpair()
                    },
                ) { Text("Unpair") }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnpair = false }) { Text("Cancel") }
            },
        )
    }
    if (editingConnection) {
        EditConnectionDialog(
            host = host,
            onDismiss = { editingConnection = false },
            onSave = { alias, hosts, port, username ->
                onUpdateConnection(alias, hosts, port, username)
                editingConnection = false
            },
        )
    }
    if (showSettings) {
        HostSettingsSheet(
            host = host,
            pushCapable = pushCapable,
            pushEnabled = pushEnabled,
            hasPushDistributor = hasPushDistributor,
            onSetPushEnabled = onSetPushEnabled,
            onDismiss = { showSettings = false },
        )
    }
 }

private fun HostSection.barIcon(): ImageVector = when (this) {
    HostSection.Agents -> Icons.Filled.Person
    HostSection.Review -> Icons.Filled.Edit
    HostSection.Tasks -> Icons.Filled.CheckCircle
    HostSection.Automations -> Icons.Filled.DateRange
    else -> Icons.Filled.MoreVert
}

@Composable
private fun EditConnectionDialog(
    host: HostUiModel,
    onDismiss: () -> Unit,
    onSave: (alias: String, hosts: String, port: String, username: String) -> Unit,
) {
    var alias by remember { mutableStateOf(host.name) }
    var hosts by remember { mutableStateOf(host.addresses.joinToString(", ")) }
    var port by remember { mutableStateOf(host.sshPort.toString()) }
    var username by remember { mutableStateOf(host.username) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit connection") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = hosts,
                    onValueChange = { hosts = it },
                    label = { Text("Host") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Comma-separated hosts. SSH host keys stay pinned from pairing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(alias, hosts, port, username) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun HostSettingsSheet(
    host: HostUiModel,
    pushCapable: Boolean,
    pushEnabled: Boolean,
    hasPushDistributor: Boolean,
    onSetPushEnabled: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val uriHandler = LocalUriHandler.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
            Text(
                host.address,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (host.backend.equals("herdr", ignoreCase = true)) {
                HostBadge("Herdr", LuviaTheme.extended.live)
            }
            if (pushCapable) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Wake me for approvals", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "A content-free push when an agent is blocked or asks for permission.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = pushEnabled,
                        onCheckedChange = onSetPushEnabled,
                        enabled = hasPushDistributor || pushEnabled,
                    )
                }
                if (!hasPushDistributor) {
                    Text(
                        "No UnifiedPush distributor is installed, so this phone cannot receive wakes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { uriHandler.openUri(PushRegistrar.UNIFIEDPUSH_DOCS) }) {
                        Text("Learn more at unifiedpush.org")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}



@Composable
fun TerminalPane(
    terminal: TerminalUiModel,
    onRequestControl: () -> Unit,
    onSendText: (String) -> Unit,
    onSelectPane: (String) -> Unit = {},
    onSendKey: (TerminalKey) -> Unit = {},
    modifier: Modifier = Modifier,
    boundToPane: Boolean = false,
) {
    var input by remember { mutableStateOf("") }
    var wrap by remember { mutableStateOf(true) }
    val defaultFg = LuviaTheme.extended.terminalFg
    val defaultBg = LuviaTheme.extended.terminalBg
    val displayed = remember(terminal.text) {
        ansiAnnotatedString(terminal.text, defaultFg, defaultBg)
    }
    val terminalVertical = rememberScrollState()
    val terminalHorizontal = rememberScrollState()
    var pinToBottom by remember { mutableStateOf(true) }
    val live = terminal.errorText == null
    LaunchedEffect(terminalVertical.isScrollInProgress, terminalVertical.value, terminalVertical.maxValue) {
        if (!terminalVertical.isScrollInProgress) {
            pinToBottom = terminalVertical.maxValue == 0 ||
                terminalVertical.value >= terminalVertical.maxValue - 80
        }
    }
    LaunchedEffect(terminal.text) {
        if (pinToBottom) {
            terminalVertical.scrollTo(terminalVertical.maxValue)
        }
    }
    val chrome = when {
        !live -> Color(0xFFFFC66D)
        terminal.control == TerminalControl.Controlling -> Color(0xFF75D69C)
        terminal.control == TerminalControl.Conflict -> Color(0xFFFFC66D)
        else -> Color(0xFF9AA4B2)
    }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = defaultFg,
        unfocusedTextColor = defaultFg,
        disabledTextColor = defaultFg.copy(alpha = 0.5f),
        focusedLabelColor = Color(0xFF9AA4B2),
        unfocusedLabelColor = Color(0xFF9AA4B2),
        cursorColor = defaultFg,
        focusedBorderColor = Color(0xFF75D69C),
        unfocusedBorderColor = Color.White.copy(alpha = 0.24f),
        disabledBorderColor = Color.White.copy(alpha = 0.12f),
    )
    Column(
        modifier
            .background(defaultBg)
            .border(2.dp, chrome.copy(alpha = 0.85f))
            .imePadding()
            .systemBottomPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(terminal.title, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            FilterChip(
                selected = wrap,
                onClick = { wrap = !wrap },
                label = { Text("Wrap") },
            )
            Spacer(Modifier.width(8.dp))
            if (!live) {
                Text("Unavailable", color = Color(0xFFFFC66D), style = MaterialTheme.typography.labelLarge)
            } else if (!terminal.canControl) {
                Text("Observing", color = Color(0xFF9AA4B2), style = MaterialTheme.typography.labelLarge)
            } else if (terminal.control == TerminalControl.Controlling) {
                Text("Controlling", color = Color(0xFF75D69C), style = MaterialTheme.typography.labelLarge)
            } else {
                FilledTonalButton(onClick = onRequestControl, enabled = terminal.control != TerminalControl.Requesting) {
                    Text(if (terminal.control == TerminalControl.Conflict) "Request control" else "Control")
                }
            }
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
        if (live && terminal.conflictMessage != null) {
            Text(
                terminal.conflictMessage,
                color = Color(0xFFFFC66D),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!live) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Text(
                        terminal.errorText.orEmpty(),
                        color = defaultFg,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val others = terminal.panes.filter { it.paneId != terminal.paneId }
                    if (!boundToPane && others.isNotEmpty()) {
                        Text(
                            "Live panes",
                            color = Color(0xFF9AA4B2),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        others.forEach { pane ->
                            FilledTonalButton(onClick = { onSelectPane(pane.paneId) }) {
                                Text(
                                    buildString {
                                        append(pane.title)
                                        pane.cwd?.takeIf { it.isNotBlank() }?.let {
                                            append(" · ")
                                            append(it.substringAfterLast('/'))
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            } else {
                SelectionContainer {
                    Text(
                        displayed,
                        color = defaultFg,
                        fontFamily = LuviaTheme.mono,
                        style = MaterialTheme.typography.bodySmall,
                        softWrap = wrap,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(terminalVertical)
                            .then(if (wrap) Modifier else Modifier.horizontalScroll(terminalHorizontal))
                            .padding(16.dp),
                    )
                }
                JumpToLatestPill(
                    visible = !pinToBottom && terminalVertical.maxValue > 0,
                    onClick = {
                        pinToBottom = true
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                )
            }
        }
        if (live && terminal.isTruncated) {
            Text("Output truncated", color = Color(0xFFFFC66D), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (live && terminal.canControl && terminal.control == TerminalControl.Controlling) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TerminalKeyChip("Esc") { onSendKey(TerminalKey.Escape) }
                TerminalKeyChip("Tab") { onSendKey(TerminalKey.Tab) }
                TerminalKeyChip("Ctrl-C") { onSendKey(TerminalKey.CtrlC) }
                TerminalKeyChip("Ctrl-D") { onSendKey(TerminalKey.CtrlD) }
                TerminalKeyChip("↑") { onSendKey(TerminalKey.Up) }
                TerminalKeyChip("↓") { onSendKey(TerminalKey.Down) }
                TerminalKeyChip("←") { onSendKey(TerminalKey.Left) }
                TerminalKeyChip("→") { onSendKey(TerminalKey.Right) }
                TerminalKeyChip("Enter") { onSendKey(TerminalKey.Enter) }
            }
        }
        if (live && terminal.canControl) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Exact input") },
                    enabled = terminal.control == TerminalControl.Controlling,
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = terminal.control == TerminalControl.Controlling && input.isNotEmpty(),
                    onClick = {
                        onSendText(input)
                        input = ""
                    },
                ) { Text("Send") }
            }
        }
    }
    LaunchedEffect(pinToBottom, terminalVertical.maxValue) {
        if (pinToBottom) {
            terminalVertical.scrollTo(terminalVertical.maxValue)
        }
    }
}

@Composable
private fun TerminalKeyChip(label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick) { Text(label) }
}

@Composable
internal fun JumpToLatestPill(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    FilledTonalButton(onClick = onClick, modifier = modifier) {
        Text("Jump to latest")
    }
}

@Composable
fun PairHostPane(
    command: String?,
    authorizedKeysLine: String?,
    fingerprint: String?,
    errorMessage: String?,
    completing: Boolean,
    pairedHostId: String? = null,
    onBegin: (String, HostRole) -> Unit,
    onCopyCommand: (String) -> Unit,
    onComplete: (raw: String, host: String, port: String, user: String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showScan by remember { mutableStateOf(false) }
    var reachHost by remember { mutableStateOf("") }
    var reachPort by remember { mutableStateOf("22") }
    var reachUser by remember { mutableStateOf("") }
    val step = when {
        pairedHostId != null -> 3
        command == null -> 1
        !showScan -> 2
        else -> 3
    }
    val title = if (pairedHostId != null) "Paired. Connecting…" else when (step) {
        1 -> "Name this Device"
        2 -> "Run on the Host"
        else -> "Scan pairing code"
    }
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (pairedHostId == null) {
                PairingStepRail(step = step, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            }
            when {
                pairedHostId != null ->
                    PairSuccessStep(
                        onShowList = onCancel,
                        modifier = Modifier.fillMaxSize(),
                    )
                command == null ->
                    PairLabelStep(
                        errorMessage = errorMessage,
                        host = reachHost,
                        port = reachPort,
                        user = reachUser,
                        onHostChange = { reachHost = it },
                        onPortChange = { reachPort = it },
                        onUserChange = { reachUser = it },
                        onBegin = { label, role ->
                            showScan = false
                            onBegin(label, role)
                        },
                        onCancel = onCancel,
                        modifier = Modifier.fillMaxSize(),
                    )
                !showScan ->
                    PairCommandStep(
                        command = command,
                        fingerprint = fingerprint.orEmpty(),
                        errorMessage = errorMessage,
                        onCopyCommand = onCopyCommand,
                        onScan = { showScan = true },
                        onBack = onCancel,
                        modifier = Modifier.fillMaxSize(),
                    )
                else ->
                    PairScanStep(
                        errorMessage = errorMessage,
                        completing = completing,
                        onComplete = { raw -> onComplete(raw, reachHost, reachPort, reachUser) },
                        onBack = { showScan = false },
                        modifier = Modifier.fillMaxSize(),
                    )
            }
        }
    }
}

@Composable
private fun PairingStepRail(step: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(3) { index ->
            Box(
                Modifier
                    .height(4.dp)
                    .weight(1f)
                    .clip(RoundedCornerShape(99.dp))
                    .background(
                        if (index < step) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
            )
        }
    }
}

@Composable
private fun PairLabelStep(
    errorMessage: String?,
    host: String,
    port: String,
    user: String,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onUserChange: (String) -> Unit,
    onBegin: (String, HostRole) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var label by remember { mutableStateOf(defaultDeviceLabel(context)) }
    var role by remember { mutableStateOf(HostRole.Controller) }
    Column(
        modifier.imePadding().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Name this device, then pick whether it may control terminals or only observe.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("Device label") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Role", style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            HostRole.entries.forEachIndexed { index, item ->
                SegmentedButton(
                    selected = role == item,
                    onClick = { role = item },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = HostRole.entries.size),
                ) {
                    Text(item.name)
                }
            }
        }
        Text(
            "Observer can watch sessions. Controller can prompt agents, review, tasks, and type in terminals.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = host,
            onValueChange = onHostChange,
            label = { Text("Host") },
            placeholder = { Text("IP or hostname, optional") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = port,
            onValueChange = onPortChange,
            label = { Text("Port") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = user,
            onValueChange = onUserChange,
            label = { Text("Username") },
            placeholder = { Text("Optional override") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            FilledTonalButton(onClick = onCancel) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onBegin(label, role) }, enabled = label.isNotBlank()) { Text("Continue") }
        }
    }
}

@Composable
private fun PairCommandStep(
    command: String,
    fingerprint: String,
    errorMessage: String?,
    onCopyCommand: (String) -> Unit,
    onScan: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(
        modifier.imePadding().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Run the pairing command on the Host, then scan the QR it prints.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (fingerprint.isNotBlank()) {
            Text("Device key fingerprint", style = MaterialTheme.typography.labelLarge)
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
            ) {
                SelectionContainer {
                    Text(
                        fingerprint,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    )
                }
            }
        }
        Button(
            onClick = {
                onCopyCommand(command)
                copied = true
                scope.launch {
                    delay(2_000)
                    copied = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (copied) "Copied. Paste it in a terminal on the host." else "Copy full command") }
        errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            FilledTonalButton(onClick = onBack) { Text("Back") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onScan) { Text("Scan QR code") }
        }
    }
}

@Composable
private fun PairSuccessStep(onShowList: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            CircularProgressIndicator()
            Text("Paired. Connecting…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Opening this Host once the first snapshot arrives.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onShowList) { Text("Show Host list") }
        }
    }
}

@Composable
private fun PairScanStep(
    errorMessage: String?,
    completing: Boolean,
    onComplete: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var cameraDenied by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(cameraGranted) }
    var pasted by remember { mutableStateOf("") }
    var lastCode by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraGranted = granted
        cameraDenied = !granted
        showScanner = granted
    }
    val previewVisible = showScanner && cameraGranted && !cameraDenied
    Column(
        modifier
            .imePadding()
            .padding(20.dp)
            .fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Scan the QR printed by luvia-host, or paste the pairing code. The app will verify it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!previewVisible) {
                Button(
                    onClick = {
                        lastCode = null
                        if (cameraGranted) {
                            showScanner = true
                            cameraDenied = false
                        } else {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    enabled = !completing,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Scan QR code") }
            }
            if (cameraDenied) {
                Text(
                    "Camera permission denied. Paste the luvia1: code instead.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (previewVisible) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clipToBounds()
                        .background(Color.Black),
                ) {
                    QrScanner(
                        onQrCode = { code ->
                            if (!completing && code != lastCode) {
                                lastCode = code
                                onComplete(code)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    QrViewfinderOverlay(Modifier.fillMaxSize())
                }
            }
            TextButton(
                onClick = {
                    val clip = context.getSystemService(ClipboardManager::class.java)
                        ?.primaryClip
                        ?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)
                        ?.coerceToText(context)
                        ?.toString()
                        .orEmpty()
                    if (clip.isNotBlank()) pasted = clip
                },
                enabled = !completing,
            ) { Text("Paste code instead") }
            OutlinedTextField(
                value = pasted,
                onValueChange = { pasted = it },
                label = { Text("luvia1: pairing code") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !completing,
            )
        }
        errorMessage?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            FilledTonalButton(onClick = onBack, enabled = !completing) { Text("Back") }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { onComplete(pasted) },
                enabled = !completing && pasted.isNotBlank(),
            ) { Text("Pair") }
        }
    }
}

@Composable
private fun QrViewfinderOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val color = Color.White.copy(alpha = 0.85f)
        val stroke = 3.dp.toPx()
        val length = 28.dp.toPx()
        val inset = 20.dp.toPx()
        val left = inset
        val top = inset
        val right = size.width - inset
        val bottom = size.height - inset
        drawLine(color, Offset(left, top), Offset(left + length, top), stroke, StrokeCap.Round)
        drawLine(color, Offset(left, top), Offset(left, top + length), stroke, StrokeCap.Round)
        drawLine(color, Offset(right, top), Offset(right - length, top), stroke, StrokeCap.Round)
        drawLine(color, Offset(right, top), Offset(right, top + length), stroke, StrokeCap.Round)
        drawLine(color, Offset(left, bottom), Offset(left + length, bottom), stroke, StrokeCap.Round)
        drawLine(color, Offset(left, bottom), Offset(left, bottom - length), stroke, StrokeCap.Round)
        drawLine(color, Offset(right, bottom), Offset(right - length, bottom), stroke, StrokeCap.Round)
        drawLine(color, Offset(right, bottom), Offset(right, bottom - length), stroke, StrokeCap.Round)
    }
}

@Composable
private fun EmptyPane(
    title: String,
    message: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null && onAction != null) Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun EmptyHostsPane(
    onAddHost: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("No Hosts", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Install luvia-host on your computer, then pair this phone.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EmptyStep(1, "Install luvia-host on the machine that runs Luvus.")
            EmptyStep(2, "Pair this Device from the app.")
            EmptyStep(3, "Scan the pairing code the Host prints.")
        }
        Button(onClick = onAddHost, modifier = Modifier.fillMaxWidth()) { Text("Add Host") }
    }
}

@Composable
private fun EmptyStep(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(99.dp),
            modifier = Modifier.size(28.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    number.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

internal fun freshnessLabel(epochMs: Long, nowEpochMs: Long): String? {
    if (epochMs <= 0L) return null
    val seconds = ((nowEpochMs - epochMs) / 1000L).coerceAtLeast(0L)
    return when {
        seconds < 60L -> "synced ${seconds}s ago"
        seconds < 3600L -> "synced ${seconds / 60L}m ago"
        seconds < 86_400L -> "synced ${seconds / 3600L}h ago"
        else -> "synced ${seconds / 86_400L}d ago"
    }
}

private fun ConnectionBadge.label() = when (this) {
    ConnectionBadge.Live -> "Live"
    ConnectionBadge.Connecting -> "Connecting"
    ConnectionBadge.Stale -> "Reconnect"
    ConnectionBadge.Offline -> "Offline"
}

private fun defaultDeviceLabel(context: Context): String {
    val deviceName = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        ?.trim()
        .orEmpty()
    if (deviceName.isNotEmpty()) return deviceName
    val bluetoothName = Settings.Secure.getString(context.contentResolver, "bluetooth_name")
        ?.trim()
        .orEmpty()
    if (bluetoothName.isNotEmpty()) return bluetoothName
    return Build.MODEL.orEmpty()
}

@Composable
private fun ConnectionBadge.color() = when (this) {
    ConnectionBadge.Live -> LuviaTheme.extended.live
    ConnectionBadge.Connecting -> LuviaTheme.extended.connecting
    ConnectionBadge.Stale -> LuviaTheme.extended.stale
    ConnectionBadge.Offline -> LuviaTheme.extended.offline
}
