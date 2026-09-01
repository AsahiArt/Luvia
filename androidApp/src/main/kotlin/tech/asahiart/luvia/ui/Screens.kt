@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package tech.asahiart.luvia.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.asahiart.luvia.HostRole

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
    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Luvia") },
                actions = {
                    IconButton(onClick = onAddHost, modifier = Modifier.semantics { contentDescription = "Add host" }) {
                        Text("+")
                    }
                },
            )
        },
    ) { padding ->
        if (hosts.isEmpty()) {
            EmptyPane(
                title = "No hosts",
                message = "Pair a Luvus host to begin.",
                action = "Add host",
                onAction = onAddHost,
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
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(hosts, key = { it.id }) { host ->
                        HostRow(
                            host = host,
                            selected = selectedHostId == host.id,
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
    onSelect: (String) -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: (String) -> Unit,
) {
    val container = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Row(
        modifier = Modifier.fillMaxWidth().background(container).clickable { onSelect(host.id) }.padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(color = host.connection.color(), shape = RoundedCornerShape(99.dp), modifier = Modifier.size(10.dp)) {}
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(host.name, fontWeight = FontWeight.SemiBold)
            Text(host.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            host.errorMessage?.let { error ->
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(host.connection.label(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            FilledTonalButton(
                onClick = {
                    if (host.connected) onDisconnect(host.id) else onConnect(host.id)
                },
            ) {
                Text(
                    when {
                        host.connection == ConnectionBadge.Connecting -> "Cancel"
                        host.connected -> "Disconnect"
                        else -> "Connect"
                    },
                )
            }
        }
    }
}

@Composable
fun HostDetailPane(
    host: HostUiModel,
    section: HostSection,
    onSection: (HostSection) -> Unit,
    terminal: TerminalUiModel?,
    onRequestControl: () -> Unit,
    onSendText: (String) -> Unit,
    onConnect: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onUnpair: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var confirmUnpair by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(host.name)
                    Text(host.sessionName ?: host.address, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (host.connected) {
                FilledTonalButton(onClick = onDisconnect) {
                    Text(if (host.connection == ConnectionBadge.Connecting) "Cancel" else "Disconnect")
                }
            } else {
                Button(onClick = onConnect) { Text("Connect") }
            }
            FilledTonalButton(onClick = onRefresh) { Text("Refresh") }
            TextButton(onClick = { confirmUnpair = true }) { Text("Unpair") }
        }
        host.errorMessage?.let { error ->
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        PrimaryTabRow(selectedTabIndex = section.ordinal) {
            HostSection.entries.forEach { item ->
                Tab(
                    selected = item == section,
                    onClick = { onSection(item) },
                    text = { Text(item.name) },
                )
            }
        }
        when (section) {
            HostSection.Overview -> OverviewPane(host, Modifier.weight(1f))
            HostSection.Agents -> EmptyPane("Agents", if (host.connected) "No agents in the current snapshot." else "Connect to load agent state.", modifier = Modifier.weight(1f))
            HostSection.Tasks -> EmptyPane("Tasks", if (host.connected) "No orchestration tasks." else "Connect to load orchestration tasks.", modifier = Modifier.weight(1f))
            HostSection.Terminal -> if (terminal == null) {
                EmptyPane("Terminal unavailable", "Select a live pane to observe or request control.", modifier = Modifier.weight(1f))
            } else {
                TerminalPane(terminal, onRequestControl, onSendText, Modifier.weight(1f))
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
}

@Composable
private fun OverviewPane(host: HostUiModel, modifier: Modifier = Modifier) {
    val metrics = listOf(
        Triple("Working", host.workingAgents, MaterialTheme.colorScheme.primary),
        Triple("Blocked", host.blockedAgents, MaterialTheme.colorScheme.tertiary),
        Triple("Done", host.completedAgents, Color(0xFF2E7D32)),
    )
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(metrics) { (label, value, color) -> MetricCard(label, value, color) }
        host.activeTask?.let { task ->
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Card {
                    Column(Modifier.padding(18.dp)) {
                        Text("Current task", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text(task)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricCard(label: String, value: Int, color: Color) {
    Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f))) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value.toString(), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun TerminalPane(
    terminal: TerminalUiModel,
    onRequestControl: () -> Unit,
    onSendText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    Column(modifier.background(Color(0xFF111318)).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(terminal.title, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (!terminal.canControl) {
                Text("Observing", color = Color(0xFF9AA4B2), style = MaterialTheme.typography.labelLarge)
            } else if (terminal.control != TerminalControl.Controlling) {
                FilledTonalButton(onClick = onRequestControl, enabled = terminal.control != TerminalControl.Requesting) {
                    Text(if (terminal.control == TerminalControl.Conflict) "Request control" else "Control")
                }
            } else {
                Text("Controlling", color = Color(0xFF75D69C), style = MaterialTheme.typography.labelLarge)
            }
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
        SelectionContainer {
            Text(
                terminal.text,
                color = Color(0xFFE4E7EC),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp),
            )
        }
        if (terminal.isTruncated) {
            Text("Output truncated", color = Color(0xFFFFC66D), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (terminal.canControl) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Exact input") },
                    enabled = terminal.control == TerminalControl.Controlling,
                    singleLine = true,
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

@Composable
fun PairHostPane(
    command: String?,
    authorizedKeysLine: String?,
    fingerprint: String?,
    errorMessage: String?,
    completing: Boolean,
    onBegin: (String, HostRole) -> Unit,
    onCopyCommand: (String) -> Unit,
    onComplete: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showScan by remember { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        topBar = { CenterAlignedTopAppBar(title = { Text("Pair host") }) },
    ) { padding ->
        when {
            command == null ->
                PairLabelStep(
                    errorMessage = errorMessage,
                    onBegin = { label, role ->
                        showScan = false
                        onBegin(label, role)
                    },
                    onCancel = onCancel,
                    modifier = Modifier.padding(padding),
                )
            !showScan ->
                PairCommandStep(
                    command = command,
                    authorizedKeysLine = authorizedKeysLine.orEmpty(),
                    fingerprint = fingerprint.orEmpty(),
                    errorMessage = errorMessage,
                    onCopyCommand = onCopyCommand,
                    onScan = { showScan = true },
                    onBack = onCancel,
                    modifier = Modifier.padding(padding),
                )
            else ->
                PairScanStep(
                    errorMessage = errorMessage,
                    completing = completing,
                    onComplete = onComplete,
                    onBack = { showScan = false },
                    modifier = Modifier.padding(padding),
                )
        }
    }
}

@Composable
private fun PairLabelStep(
    errorMessage: String?,
    onBegin: (String, HostRole) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var label by remember { mutableStateOf(Build.MODEL.orEmpty()) }
    var role by remember { mutableStateOf(HostRole.Controller) }
    Column(
        modifier.padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Name this device, then pick whether it may control terminals or only observe.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("Device label") },
            singleLine = true,
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
    authorizedKeysLine: String,
    fingerprint: String,
    errorMessage: String?,
    onCopyCommand: (String) -> Unit,
    onScan: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val commandScroll = rememberScrollState()
    Column(
        modifier.padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Run this command on the host machine, then scan the QR it prints.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(
                command,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().horizontalScroll(commandScroll).padding(vertical = 8.dp),
            )
        }
        Button(onClick = { onCopyCommand(command) }, modifier = Modifier.fillMaxWidth()) { Text("Copy command") }
        if (authorizedKeysLine.isNotBlank()) {
            Text("Public key", style = MaterialTheme.typography.labelLarge)
            SelectionContainer {
                Text(
                    authorizedKeysLine,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                )
            }
        }
        if (fingerprint.isNotBlank()) {
            Text(
                "Device key $fingerprint",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
        errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            FilledTonalButton(onClick = onBack) { Text("Back") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onScan) { Text("Scan QR code") }
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
    Column(
        modifier.padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Scan the QR printed by luvia-host, or paste the pairing code. The app will verify it.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(
            onClick = {
                lastCode = null
                if (cameraGranted) {
                    showScanner = true
                } else {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
            enabled = !completing,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Scan QR code") }
        if (cameraDenied) {
            Text(
                "Camera permission denied. Paste the luvia1: code instead.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (showScanner && cameraGranted) {
            QrScanner(
                onQrCode = { code ->
                    if (!completing && code != lastCode) {
                        lastCode = code
                        onComplete(code)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(280.dp),
            )
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
        errorMessage?.let {
            Text(
                "$it Scan again or paste a different code. The draft is still valid.",
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

private fun ConnectionBadge.label() = name
private fun ConnectionBadge.color() = when (this) {
    ConnectionBadge.Live -> Color(0xFF2E7D32)
    ConnectionBadge.Connecting -> Color(0xFF1565C0)
    ConnectionBadge.Stale -> Color(0xFFEF6C00)
    ConnectionBadge.Offline -> Color(0xFF757575)
}
