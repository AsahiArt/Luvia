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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.BadgedBox
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import tech.asahiart.luvia.HostRole
import tech.asahiart.luvia.PairingCodes
import tech.asahiart.luvia.isTailnetAddress
import tech.asahiart.luvia.TerminalKey
import tech.asahiart.luvia.ui.theme.LuviaTheme
import tech.asahiart.luvia.HostSection
import tech.asahiart.luvia.HostUhpState
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
    val compactHeight = LocalConfiguration.current.screenHeightDp < 600
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
                Icon(Icons.Filled.Add, contentDescription = "Add Host")
            }
        },
        topBar = {
            val title = @Composable { Text("Luvia", style = MaterialTheme.typography.headlineMedium) }
            val colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            )
            if (compactHeight) {
                TopAppBar(title = title, colors = colors)
            } else {
                LargeTopAppBar(title = title, colors = colors)
            }
        },
    ) { padding ->
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
            if (hosts.isEmpty()) {
                EmptyHostsPane(
                    onAddHost = onAddHost,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
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
) {
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
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (isTailnetAddress(host.address)) {
                            TypeBadge("Tailnet", LuviaTheme.extended.connecting)
                        }
                        if (host.backend.equals("herdr", ignoreCase = true)) {
                            TypeBadge("Herdr", LuviaTheme.extended.live)
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
                if (host.blockedAgents > 0) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Text("${host.blockedAgents}")
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
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
    state: HostUhpState? = null,
    attentionCount: Int = 0,
    onSelectWorkspace: (String) -> Unit = {},
    agentsContent: @Composable (Modifier) -> Unit = { EmptyPane("Agents", "This Host has not connected yet.", modifier = it) },
    filesContent: @Composable (Modifier) -> Unit = { EmptyPane("Files", "This Host has not connected yet.", modifier = it) },
    searchContent: @Composable (Modifier) -> Unit = { EmptyPane("Search", "This Host has not connected yet.", modifier = it) },
    reviewContent: @Composable (Modifier) -> Unit = { EmptyPane("Review", "This Host has not connected yet.", modifier = it) },
    worktreesContent: @Composable (Modifier) -> Unit = { EmptyPane("Worktrees", "This Host has not connected yet.", modifier = it) },
    automationsContent: @Composable (Modifier) -> Unit = { EmptyPane("Automations", "This Host has not connected yet.", modifier = it) },
    tasksContent: @Composable (Modifier) -> Unit = { EmptyPane("Tasks", "This Host has not connected yet.", modifier = it) },
    layoutContent: @Composable (Modifier) -> Unit = { EmptyPane("Layout", "This Host has not connected yet.", modifier = it) },
    modifier: Modifier = Modifier,
) {
    var confirmUnpair by remember { mutableStateOf(false) }
    var editingConnection by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var tool by remember { mutableStateOf(section.takeIf { it.isMoreSurface }) }
    var menuOpen by remember { mutableStateOf(false) }
    val visible = sections.ifEmpty { HostSection.entries }
    val tools = visible.filter { it.isMoreSurface }
    val segment = when (section) {
        HostSection.Review, HostSection.Tasks -> section
        else -> HostSection.Agents
    }
    LaunchedEffect(section) {
        if (!section.isMoreSurface) tool = null
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state?.projectLabel() ?: host.name, maxLines = 1)
                        Text(
                            host.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
                actions = {
                    ConnectionStatusPill(host.connection)
                    if (host.backend.equals("herdr", ignoreCase = true)) {
                        TypeBadge("Herdr", LuviaTheme.extended.live)
                    }
                    Box {
                        IconButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier.semantics { contentDescription = "Project tools" },
                        ) {
                            Icon(Icons.Filled.MoreVert, contentDescription = null)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            tools.forEach { item ->
                                DropdownMenuItem(
                                    text = { Text(item.moreTitle) },
                                    leadingIcon = { Icon(item.moreIcon(), contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        tool = item
                                        onSection(item)
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Host settings") },
                                leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    showSettings = true
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            val pages = listOf(HostSection.Agents, HostSection.Review, HostSection.Tasks)
                .filter { it == HostSection.Agents || it in visible }
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                pages.forEach { item ->
                    NavigationBarItem(
                        selected = tool == null && segment == item,
                        onClick = {
                            tool = null
                            onSection(item)
                        },
                        icon = {
                            val icon = when (item) {
                                HostSection.Agents -> Icons.Filled.Person
                                HostSection.Review -> Icons.Filled.Edit
                                else -> Icons.Filled.CheckCircle
                            }
                            if (item == HostSection.Agents && attentionCount > 0) {
                                BadgedBox(badge = { Badge { Text("$attentionCount") } }) {
                                    Icon(icon, contentDescription = null)
                                }
                            } else {
                                Icon(icon, contentDescription = null)
                            }
                        },
                        label = {
                            Text(
                                when (item) {
                                    HostSection.Agents -> "Threads"
                                    HostSection.Review -> "Changes"
                                    else -> "Tasks"
                                },
                            )
                        },
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
            val open = tool
            if (open != null) {
                TextButton(
                    onClick = {
                        tool = null
                        onSection(segment)
                    },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) { Text("‹ Back to project") }
                when (open) {
                    HostSection.Files -> filesContent(Modifier.weight(1f))
                    HostSection.Search -> searchContent(Modifier.weight(1f))
                    HostSection.Worktrees -> worktreesContent(Modifier.weight(1f))
                    HostSection.Automations -> automationsContent(Modifier.weight(1f))
                    else -> layoutContent(Modifier.weight(1f))
                }
            } else {
                if (state != null && state.needsProjectPick()) {
                    ProjectPicker(state = state, onSelect = onSelectWorkspace, modifier = Modifier.weight(1f))
                } else {
                    when (segment) {
                        HostSection.Review -> reviewContent(Modifier.weight(1f))
                        HostSection.Tasks -> tasksContent(Modifier.weight(1f))
                        else -> agentsContent(Modifier.weight(1f))
                    }
                }
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
            onDisconnect = onDisconnect,
            onEditConnection = { editingConnection = true },
            onUnpair = { confirmUnpair = true },
            onDismiss = { showSettings = false },
        )
    }
}

private val HostSection.moreTitle: String
    get() = name

private val HostSection.moreSubtitle: String
    get() = when (this) {
        HostSection.Files -> "Tree of the current workspace"
        HostSection.Search -> "Search the current workspace"
        HostSection.Worktrees -> "Git worktrees for this repo"
        HostSection.Automations -> "Host ledger of scheduled jobs"
        HostSection.Layout -> "Panes without an Agent"
        else -> ""
    }

private fun HostSection.moreIcon(): ImageVector = when (this) {
    HostSection.Files -> Icons.Filled.Person
    HostSection.Search -> Icons.Filled.PlayArrow
    HostSection.Worktrees -> Icons.Filled.Edit
    HostSection.Automations -> Icons.Filled.DateRange
    HostSection.Layout -> Icons.Filled.MoreVert
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
    onDisconnect: () -> Unit = {},
    onEditConnection: () -> Unit = {},
    onUnpair: () -> Unit = {},
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
            Text(
                if (host.isObserver) "Role: Observer" else "Role: Controller",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (host.backend.equals("herdr", ignoreCase = true)) {
                TypeBadge("Herdr", LuviaTheme.extended.live)
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
            FilledTonalButton(
                onClick = {
                    onEditConnection()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Edit connection") }
            if (host.connected || host.connection == ConnectionBadge.Connecting) {
                FilledTonalButton(
                    onClick = {
                        onDisconnect()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (host.connection == ConnectionBadge.Connecting) "Cancel connecting" else "Disconnect")
                }
            }
            TextButton(
                onClick = {
                    onUnpair()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Unpair") }
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
    var wrap by remember { mutableStateOf(false) }
    var userZoom by remember { mutableFloatStateOf(1f) }
    var liveScale by remember { mutableFloatStateOf(1f) }
    var fit by remember { mutableFloatStateOf(1f) }
    var referenceBufferWidth by remember { mutableFloatStateOf(0f) }
    val textMeasurer = rememberTextMeasurer()
    val defaultFg = LuviaTheme.extended.terminalFg
    val defaultBg = LuviaTheme.extended.terminalBg
    val lines = remember(terminal.text, terminal.isAnsi, defaultFg, defaultBg) {
        terminalDisplayLines(terminal.text, terminal.isAnsi, defaultFg, defaultBg)
    }
    val maxLineChars = lines.maxOfOrNull { it.length } ?: 0
    val monoAdvancePx = remember(textMeasurer) {
        measureTerminalCharAdvancePx(textMeasurer, LuviaTheme.mono)
    }
    val listState = rememberLazyListState()
    val terminalHorizontal = rememberScrollState()
    var pinToBottom by remember { mutableStateOf(true) }
    val live = terminal.errorText == null
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()
            val total = info.totalItemsCount
            val atBottom = when {
                total == 0 -> true
                lastVisible == null -> false
                else -> {
                    lastVisible.index >= total - 1 &&
                        lastVisible.offset + lastVisible.size <= info.viewportEndOffset + 80
                }
            }
            listState.isScrollInProgress to atBottom
        }.collect { (inProgress, atBottom) ->
            if (!inProgress) {
                pinToBottom = atBottom
            }
        }
    }
    LaunchedEffect(terminal.text, pinToBottom) {
        if (pinToBottom && !listState.isScrollInProgress) {
            listState.scrollToBottom(lines.size)
        }
    }
    val ext = LuviaTheme.extended
    val chrome = when {
        !live -> ext.stale
        terminal.control == TerminalControl.Controlling -> ext.live
        terminal.control == TerminalControl.Conflict -> ext.stale
        else -> ext.offline
    }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = defaultFg,
        unfocusedTextColor = defaultFg,
        disabledTextColor = defaultFg.copy(alpha = 0.5f),
        focusedLabelColor = ext.offline,
        unfocusedLabelColor = ext.offline,
        cursorColor = defaultFg,
        focusedBorderColor = ext.live,
        unfocusedBorderColor = defaultFg.copy(alpha = 0.24f),
        disabledBorderColor = defaultFg.copy(alpha = 0.12f),
    )
    Column(
        modifier
            .background(defaultBg)
            .border(2.dp, chrome.copy(alpha = 0.85f))
            .imePadding()
            .systemBottomPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(terminal.title, color = defaultFg, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            FilterChip(
                selected = wrap,
                onClick = { wrap = !wrap },
                label = { Text("Wrap") },
            )
            Spacer(Modifier.width(8.dp))
            if (!live) {
                Text("Unavailable", color = ext.stale, style = MaterialTheme.typography.labelLarge)
            } else if (!terminal.canControl) {
                Text("Observing", color = ext.offline, style = MaterialTheme.typography.labelLarge)
            } else if (terminal.control == TerminalControl.Controlling) {
                Text("Controlling", color = ext.live, style = MaterialTheme.typography.labelLarge)
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
                color = ext.stale,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .detectTerminalPinchZoom(
                    onZoomDelta = { delta ->
                        val zoomMin = if (wrap) TerminalZoomMin else TerminalZoomFitMin
                        val next = (userZoom * liveScale * delta).coerceIn(zoomMin, TerminalZoomMax)
                        liveScale = next / userZoom
                    },
                    onZoomEnd = {
                        val zoomMin = if (wrap) TerminalZoomMin else TerminalZoomFitMin
                        userZoom = (userZoom * liveScale).coerceIn(zoomMin, TerminalZoomMax)
                        liveScale = 1f
                    },
                ),
        ) {
            val density = LocalDensity.current
            val vPx = (constraints.maxWidth - with(density) { 32.dp.roundToPx() }).coerceAtLeast(0)
            fun updateFit(remeasureBuffer: Boolean) {
                if (wrap) {
                    fit = 1f
                    return
                }
                var width = referenceBufferWidth
                if (remeasureBuffer || width <= 0f) {
                    width = maxLineChars * monoAdvancePx
                    referenceBufferWidth = width
                }
                if (liveScale != 1f) return
                fit = if (width > 0f && vPx > 0) minOf(1f, vPx / width) else 1f
            }
            LaunchedEffect(wrap) {
                if (wrap) {
                    updateFit(remeasureBuffer = false)
                } else {
                    if (userZoom < TerminalZoomFitMin) userZoom = TerminalZoomFitMin
                    updateFit(remeasureBuffer = true)
                }
            }
            LaunchedEffect(maxLineChars, monoAdvancePx) {
                if (!wrap) updateFit(remeasureBuffer = true)
            }
            LaunchedEffect(vPx, liveScale == 1f) {
                if (liveScale != 1f) return@LaunchedEffect
                updateFit(remeasureBuffer = false)
            }
            val committedScale = if (wrap) userZoom else fit * userZoom
            val visualScale = committedScale * liveScale
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
                            color = ext.offline,
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
                val minLineWidth = with(density) { (maxLineChars * monoAdvancePx).toDp() }
                Box(Modifier.fillMaxSize().padding(16.dp)) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .then(if (wrap) Modifier else Modifier.horizontalScroll(terminalHorizontal)),
                    ) {
                        SelectionContainer {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .then(if (wrap) Modifier.fillMaxWidth() else Modifier),
                            ) {
                                items(lines.size) { index ->
                                    Text(
                                        lines[index],
                                        color = defaultFg,
                                        fontFamily = LuviaTheme.mono,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = TerminalBaseFontSp.sp,
                                        lineHeight = (TerminalBaseFontSp * 1.3f).sp,
                                        softWrap = wrap,
                                        overflow = TextOverflow.Visible,
                                        modifier = Modifier
                                            .then(
                                                if (wrap) Modifier
                                                else Modifier.widthIn(min = minLineWidth),
                                            )
                                            .terminalPinchLayout(
                                                relative = visualScale,
                                                wrap = wrap,
                                                wrapLayoutWidthPx = if (visualScale <= 0f) {
                                                    0
                                                } else {
                                                    (vPx / visualScale).roundToInt()
                                                },
                                            ),
                                    )
                                }
                            }
                        }
                    }
                }
                JumpToLatestPill(
                    visible = !pinToBottom &&
                        (listState.firstVisibleItemIndex > 0 ||
                            listState.canScrollForward ||
                            listState.canScrollBackward),
                    onClick = { pinToBottom = true },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                )
            }
        }
        if (live && terminal.isTruncated) {
            Text("Output truncated", color = ext.stale, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (live && terminal.canControl && terminal.control == TerminalControl.Controlling) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                KeyChip("Esc") { onSendKey(TerminalKey.Escape) }
                KeyChip("Tab") { onSendKey(TerminalKey.Tab) }
                KeyChip("Ctrl-C") { onSendKey(TerminalKey.CtrlC) }
                KeyChip("Ctrl-D") { onSendKey(TerminalKey.CtrlD) }
                KeyChip("↑") { onSendKey(TerminalKey.Up) }
                KeyChip("↓") { onSendKey(TerminalKey.Down) }
                KeyChip("←") { onSendKey(TerminalKey.Left) }
                KeyChip("→") { onSendKey(TerminalKey.Right) }
                KeyChip("Enter") { onSendKey(TerminalKey.Enter) }
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
}

private const val TerminalBaseFontSp = 13f
private const val TerminalZoomMin = 0.7f
private const val TerminalZoomFitMin = 1f
private const val TerminalZoomMax = 2.5f

private fun measureTerminalCharAdvancePx(
    measurer: TextMeasurer,
    fontFamily: FontFamily,
): Float {
    return measurer.measure(
        text = "M",
        style = TextStyle(
            fontFamily = fontFamily,
            fontSize = TerminalBaseFontSp.sp,
            lineHeight = (TerminalBaseFontSp * 1.3f).sp,
        ),
        softWrap = false,
        overflow = TextOverflow.Visible,
        maxLines = 1,
        constraints = Constraints(),
    ).size.width.toFloat()
}

private suspend fun LazyListState.scrollToBottom(itemCount: Int) {
    if (itemCount <= 0) return
    val last = itemCount - 1
    scrollToItem(last)
    val info = layoutInfo
    val visible = info.visibleItemsInfo.lastOrNull() ?: return
    val overflow = visible.offset + visible.size - info.viewportEndOffset
    if (overflow > 0) {
        scrollToItem(visible.index, overflow)
    }
}

private fun Modifier.terminalPinchLayout(
    relative: Float,
    wrap: Boolean,
    wrapLayoutWidthPx: Int,
): Modifier = layout { measurable, constraints ->
    val childConstraints = if (wrap) {
        val width = wrapLayoutWidthPx.coerceAtLeast(0)
        constraints.copy(minWidth = width, maxWidth = width)
    } else {
        constraints.copy(
            minWidth = 0,
            maxWidth = Constraints.Infinity,
            minHeight = 0,
            maxHeight = Constraints.Infinity,
        )
    }
    val placeable = measurable.measure(childConstraints)
    layout(
        width = (placeable.width * relative).roundToInt().coerceAtLeast(0),
        height = (placeable.height * relative).roundToInt().coerceAtLeast(0),
    ) {
        placeable.placeRelative(0, 0)
    }
}.graphicsLayer {
    scaleX = relative
    scaleY = relative
    transformOrigin = TransformOrigin(0f, 0f)
}

private fun Modifier.detectTerminalPinchZoom(
    onZoomDelta: (Float) -> Unit,
    onZoomEnd: () -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var pinching = false
        var accumulatedZoom = 1f
        var pastTouchSlop = false
        val touchSlop = viewConfiguration.touchSlop
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressedCount = event.changes.count { it.pressed }
                if (pressedCount >= 2) {
                    val zoomChange = event.calculateZoom()
                    if (!pastTouchSlop) {
                        accumulatedZoom *= zoomChange
                        val centroidSize = event.calculateCentroidSize(useCurrent = false)
                        val zoomMotion = abs(1f - accumulatedZoom) * centroidSize
                        if (zoomMotion > touchSlop) {
                            pastTouchSlop = true
                        }
                    }
                    if (pastTouchSlop) {
                        pinching = true
                        if (zoomChange != 1f) {
                            onZoomDelta(zoomChange)
                        }
                        event.changes.forEach { it.consume() }
                    }
                } else if (pinching) {
                    onZoomEnd()
                    pinching = false
                    pastTouchSlop = false
                    accumulatedZoom = 1f
                }
                if (pressedCount == 0) break
            }
        } finally {
            if (pinching) onZoomEnd()
        }
    }
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
    onClearDraft: () -> Unit = {},
    onClearError: () -> Unit = {},
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showScan by remember { mutableStateOf(false) }
    var reachHost by remember { mutableStateOf("") }
    var reachPort by remember { mutableStateOf("22") }
    var reachUser by remember { mutableStateOf("") }
    val compactHeight = LocalConfiguration.current.screenHeightDp < 600
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
                PairingStepRail(
                    step = step,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = if (compactHeight) 4.dp else 8.dp),
                )
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
                            onClearError()
                            onBegin(label, role)
                        },
                        onCancel = onCancel,
                        modifier = Modifier.fillMaxSize(),
                    )
                !showScan ->
                    PairCommandStep(
                        command = command,
                        fingerprint = fingerprint.orEmpty(),
                        onCopyCommand = onCopyCommand,
                        onScan = {
                            onClearError()
                            showScan = true
                        },
                        onBack = onClearDraft,
                        modifier = Modifier.fillMaxSize(),
                    )
                else ->
                    PairScanStep(
                        errorMessage = errorMessage,
                        completing = completing,
                        onComplete = { raw -> onComplete(raw, reachHost, reachPort, reachUser) },
                        onBack = {
                            onClearError()
                            showScan = false
                        },
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
    val compactHeight = LocalConfiguration.current.screenHeightDp < 600
    var label by remember { mutableStateOf(defaultDeviceLabel(context)) }
    var role by remember { mutableStateOf(HostRole.Controller) }
    val fieldSpacing = if (compactHeight) 8.dp else 16.dp
    Column(modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = if (compactHeight) 4.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(fieldSpacing),
        ) {
            if (!compactHeight) {
                Text(
                    "Name this device, then pick whether it may control terminals or only observe.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
            if (!compactHeight) {
                Text(
                    "Observer can watch sessions. Controller can prompt agents, review, tasks, and type in terminals.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(
            Modifier
                .weight(1f)
                .clipToBounds()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = if (compactHeight) 8.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(fieldSpacing),
        ) {
            Text("Reach this Host", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = host,
                onValueChange = onHostChange,
                label = { Text("Host") },
                placeholder = { Text("IP or hostname") },
                supportingText = { Text("Optional") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = port,
                onValueChange = onPortChange,
                label = { Text("Port") },
                supportingText = { Text("Optional") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = user,
                onValueChange = onUserChange,
                label = { Text("Username") },
                placeholder = { Text("SSH user") },
                supportingText = { Text("Optional") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Optional. Overrides addresses in the pairing code. SSH host keys still come from pairing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        errorMessage?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.End,
        ) {
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
    onCopyCommand: (String) -> Unit,
    onScan: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier
                .weight(1f)
                .clipToBounds()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Run the pairing command on the Host, then scan the pairing code it prints.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Pairing command", style = MaterialTheme.typography.labelLarge)
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
            ) {
                SelectionContainer {
                    Text(
                        command,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    )
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
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            FilledTonalButton(onClick = onBack) { Text("Back") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onScan) { Text("Scan pairing code") }
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
    val keyboard = LocalSoftwareKeyboardController.current
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var cameraDenied by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(cameraGranted) }
    var pasted by remember { mutableStateOf("") }
    var pasteHint by remember { mutableStateOf<String?>(null) }
    var lastCode by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraGranted = granted
        cameraDenied = !granted
        showScanner = granted
    }
    val previewVisible = showScanner && cameraGranted && !cameraDenied
    val canPair = PairingCodes.looksLikeCode(pasted)
    LaunchedEffect(previewVisible) {
        if (previewVisible) keyboard?.hide()
    }
    Column(
        modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f).clipToBounds().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Scan the pairing code printed by luvia-host, or paste it. The app will verify it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!previewVisible) {
                Button(
                    onClick = {
                        lastCode = null
                        keyboard?.hide()
                        if (cameraGranted) {
                            showScanner = true
                            cameraDenied = false
                        } else {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    enabled = !completing,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Scan pairing code") }
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
                    if (PairingCodes.looksLikeCode(clip)) {
                        pasted = clip.trim()
                        pasteHint = null
                    } else if (clip.isNotBlank()) {
                        pasteHint = "Clipboard is not a luvia1: pairing code."
                    }
                },
                enabled = !completing,
            ) { Text("Paste code instead") }
            OutlinedTextField(
                value = pasted,
                onValueChange = {
                    pasted = it
                    pasteHint = null
                },
                label = { Text("luvia1: pairing code") },
                supportingText = {
                    val hint = pasteHint
                    when {
                        hint != null -> Text(hint)
                        pasted.isNotBlank() && !canPair -> Text("Paste a luvia1: pairing code.")
                    }
                },
                isError = pasted.isNotBlank() && !canPair,
                minLines = 3,
                maxLines = 4,
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
                enabled = !completing && canPair,
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
            EmptyStep(1, "Install luvia-host on the Host that runs Luvus.")
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


/** Shown only when the page was opened without a project (Hosts sheet, notification). */
@Composable
private fun ProjectPicker(state: HostUhpState, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            Text(
                "Pick a project",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        items(state.projectChoices(), key = { it.id }) { choice ->
            Text(
                choice.label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().clickable { onSelect(choice.id) }.padding(vertical = 14.dp),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}
