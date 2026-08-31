@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package tech.asahiart.luvia.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun HostListPane(
    hosts: List<HostUiModel>,
    selectedHostId: String?,
    onSelect: (String) -> Unit,
    onAddHost: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(hosts, key = { it.id }) { host ->
                    HostRow(host, selectedHostId == host.id, onSelect)
                }
            }
        }
    }
}

@Composable
private fun HostRow(host: HostUiModel, selected: Boolean, onSelect: (String) -> Unit) {
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
        }
        Text(host.connection.label(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(host.name)
                    Text(host.sessionName ?: host.address, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
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
            HostSection.Agents -> EmptyPane("Agents", "Connect to load agent state.", modifier = Modifier.weight(1f))
            HostSection.Tasks -> EmptyPane("Tasks", "Connect to load orchestration tasks.", modifier = Modifier.weight(1f))
            HostSection.Terminal -> if (terminal == null) {
                EmptyPane("Terminal unavailable", "Select a live pane to observe or request control.", modifier = Modifier.weight(1f))
            } else {
                TerminalPane(terminal, onRequestControl, onSendText, Modifier.weight(1f))
            }
        }
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
            if (terminal.control != TerminalControl.Controlling) {
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
                onClick = { onSendText(input); input = "" },
            ) { Text("Send") }
        }
    }
}

@Composable
fun PairHostPane(
    host: String,
    user: String,
    port: String,
    fingerprint: String,
    publicKey: String,
    onHostChange: (String) -> Unit,
    onUserChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onFingerprintChange: (String) -> Unit,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { CenterAlignedTopAppBar(title = { Text("Pair host") }) },
    ) { padding ->
        Column(Modifier.padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(host, onHostChange, label = { Text("MagicDNS name or IP") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(user, onUserChange, label = { Text("SSH user") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(port, onPortChange, label = { Text("SSH port") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(fingerprint, onFingerprintChange, label = { Text("Host key SHA256") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (publicKey.isNotBlank()) {
                Text(publicKey, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            Text("Run luvia-host pair --name Android --role controller and paste this public key.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                FilledTonalButton(onClick = onCancel) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onContinue,
                    enabled = host.isNotBlank() && user.isNotBlank() && fingerprint.isNotBlank() && port.toIntOrNull() in 1..65535,
                ) { Text("Continue") }
            }
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
