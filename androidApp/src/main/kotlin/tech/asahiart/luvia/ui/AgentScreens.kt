@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package tech.asahiart.luvia.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.asahiart.luvia.AgentKey
import tech.asahiart.luvia.AgentStatus
import tech.asahiart.luvia.AgentSummary
import tech.asahiart.luvia.MissionRowKind
import tech.asahiart.luvia.MissionSnapshot

@Composable
fun AgentsSection(
    host: HostUiModel,
    state: HostUhpUiState,
    onRefresh: () -> Unit,
    onOpenAgent: (String) -> Unit,
    onCloseAgent: () -> Unit,
    onPrompt: (String) -> Unit,
    onSendKeys: (List<AgentKey>) -> Unit,
    onCheckUnconfirmed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        !host.connected && !state.connected -> {
            UhpEmptyPane(
                title = "Agents",
                message = "Connect to this host",
                modifier = modifier,
            )
        }
        state.agentDetail.paneId != null -> {
            AgentDetailPane(
                host = host,
                state = state,
                onBack = onCloseAgent,
                onRefresh = onRefresh,
                onPrompt = onPrompt,
                onSendKeys = onSendKeys,
                onCheckUnconfirmed = onCheckUnconfirmed,
                modifier = modifier,
            )
        }
        else -> {
            AgentListPane(
                host = host,
                state = state,
                onRefresh = onRefresh,
                onOpenAgent = onOpenAgent,
                modifier = modifier,
            )
        }
    }
}

@Composable
fun AgentListPane(
    host: HostUiModel,
    state: HostUhpUiState,
    onRefresh: () -> Unit,
    onOpenAgent: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    PullToRefreshBox(
        isRefreshing = state.loading,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                AgentsHeaderCard(host = host, mission = state.mission)
            }
            state.errorText?.let { error ->
                item {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.agents.isEmpty()) {
                item {
                    Text(
                        if (state.connected) "No Agents in the current snapshot." else "Connect to this host",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(state.agents, key = { it.paneId }) { agent ->
                    AgentRow(agent = agent, onClick = { onOpenAgent(agent.paneId) })
                }
            }
        }
    }
}

@Composable
private fun AgentsHeaderCard(host: HostUiModel, mission: MissionSnapshot?) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Mission", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricChip("Working", host.workingAgents)
                MetricChip("Blocked", host.blockedAgents, loud = host.blockedAgents > 0)
                MetricChip("Done", host.completedAgents)
            }
            val summary = mission?.summary
            if (summary != null) {
                Text(
                    buildString {
                        val live = mission.rows.count { it.kind == MissionRowKind.LIVE }
                        val resumable = mission.rows.size - live
                        append(live)
                        append(" live")
                        if (resumable > 0) {
                            append(" · ")
                            append(resumable)
                            append(" resumable")
                        }
                        if (summary.tokens > 0) {
                            append(" · ")
                            append(summary.tokens)
                            append(" tokens")
                        }
                        if (summary.costUsd > 0.0) {
                            append(" · $")
                            append("%.2f".format(summary.costUsd))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            host.activeTask?.let { task ->
                Text(task, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun MetricChip(label: String, value: Int, loud: Boolean = false) {
    val colors = if (loud) {
        AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            labelColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    } else {
        AssistChipDefaults.assistChipColors()
    }
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text("$label $value") },
        colors = colors,
    )
}

@Composable
private fun AgentRow(agent: AgentSummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (agent.status == AgentStatus.Blocked) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    agent.name ?: agent.agent ?: "Agent",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                )
                AgentStatusChip(agent.status)
            }
            val kind = agent.agent
            if (!kind.isNullOrBlank() && kind != agent.name) {
                Text(kind, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val place = listOfNotNull(agent.workspaceName ?: agent.workspace, agent.branch).joinToString(" · ")
            if (place.isNotBlank()) {
                Text(
                    place,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun AgentStatusChip(status: AgentStatus) {
    val blocked = status == AgentStatus.Blocked
    val container = if (blocked) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val label = if (blocked) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(status.name) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = container,
            labelColor = label,
            disabledContainerColor = container,
            disabledLabelColor = label,
        ),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentDetailPane(
    host: HostUiModel,
    state: HostUhpUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onPrompt: (String) -> Unit,
    onSendKeys: (List<AgentKey>) -> Unit,
    onCheckUnconfirmed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val detail = state.agentDetail
    val summary = detail.summary ?: state.agents.firstOrNull { it.paneId == detail.paneId }
    val status = detail.detail?.status ?: summary?.status ?: AgentStatus.Unknown
    val blocked = status == AgentStatus.Blocked
    val canPrompt = state.canMutate && state.capabilities.agentPrompt
    val canKeys = state.canMutate && state.capabilities.agentKeys
    var draft by rememberSaveable { mutableStateOf("") }
    var pendingKeys by remember { mutableStateOf<PendingAgentAction?>(null) }
    val transcriptText = detail.transcript?.text.orEmpty()
    val lines = remember(transcriptText) { transcriptText.split('\n') }
    val listState = rememberLazyListState()
    LaunchedEffect(detail.transcript?.revision, transcriptText) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size)
    }
    val missionRow = state.mission?.rows?.firstOrNull { it.pane == detail.paneId }

    Column(modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(
                summary?.name ?: summary?.agent ?: "Agent",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
            FilledTonalButton(onClick = onRefresh) { Text("Refresh") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (!state.capabilities.agentRead) {
                Text("Transcript is not available on this host.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                SelectionContainer {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        item(key = "header") {
                            Column(
                                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    AgentStatusChip(status)
                                    val place = listOfNotNull(
                                        summary?.workspaceName ?: summary?.workspace,
                                        summary?.branch,
                                    ).joinToString(" · ")
                                    if (place.isNotBlank()) {
                                        Text(place, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                val cwd = detail.detail?.cwd ?: summary?.cwd
                                if (!cwd.isNullOrBlank()) {
                                    Text(
                                        cwd,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                missionRow?.usage?.let { usage ->
                                    Text(
                                        buildString {
                                            usage.model?.let { append(it) }
                                            usage.totalTokens?.let {
                                                if (isNotEmpty()) append(" · ")
                                                append(it)
                                                append(" tokens")
                                            }
                                            usage.context?.let {
                                                if (isNotEmpty()) append(" · ")
                                                append("context ")
                                                append("%.0f".format(it * 100))
                                                append("%")
                                            }
                                            usage.costUsd?.let {
                                                if (isNotEmpty()) append(" · ")
                                                append("$")
                                                append("%.2f".format(it))
                                            }
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                detail.errorText?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                                detail.unconfirmed?.let { kind ->
                                    UnconfirmedBanner(kind = kind, onCheck = onCheckUnconfirmed)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text("Transcript", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        items(lines.size) { index ->
                            Text(
                                lines[index],
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
        if (canKeys) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AgentKeyButton("Enter", enabled = !detail.sending) {
                    val action = PendingAgentAction("Enter", listOf(AgentKey.ENTER), null)
                    if (blocked) pendingKeys = action else onSendKeys(action.keys.orEmpty())
                }
                AgentKeyButton("Esc", enabled = !detail.sending) {
                    val action = PendingAgentAction("Esc", listOf(AgentKey.ESC), null)
                    if (blocked) pendingKeys = action else onSendKeys(action.keys.orEmpty())
                }
                AgentKeyButton("Up", enabled = !detail.sending) {
                    val action = PendingAgentAction("Up", listOf(AgentKey.UP), null)
                    if (blocked) pendingKeys = action else onSendKeys(action.keys.orEmpty())
                }
                AgentKeyButton("Down", enabled = !detail.sending) {
                    val action = PendingAgentAction("Down", listOf(AgentKey.DOWN), null)
                    if (blocked) pendingKeys = action else onSendKeys(action.keys.orEmpty())
                }
                AgentKeyButton("Tab", enabled = !detail.sending) {
                    val action = PendingAgentAction("Tab", listOf(AgentKey.TAB), null)
                    if (blocked) pendingKeys = action else onSendKeys(action.keys.orEmpty())
                }
                if (canPrompt) {
                    AgentKeyButton("y+Enter", enabled = !detail.sending) {
                        val action = PendingAgentAction("y+Enter", null, "y")
                        if (blocked) pendingKeys = action else onPrompt("y")
                    }
                    AgentKeyButton("n+Enter", enabled = !detail.sending) {
                        val action = PendingAgentAction("n+Enter", null, "n")
                        if (blocked) pendingKeys = action else onPrompt("n")
                    }
                }
            }
        }
        if (canPrompt) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Agent prompt") },
                enabled = !detail.sending,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                minLines = 1,
                maxLines = 3,
                textStyle = MaterialTheme.typography.bodyMedium,
                trailingIcon = {
                    TextButton(
                        enabled = !detail.sending && draft.isNotBlank(),
                        onClick = {
                            val text = draft.trim()
                            draft = ""
                            onPrompt(text)
                        },
                    ) { Text("Send") }
                },
            )
        }
    }
    pendingKeys?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingKeys = null },
            title = { Text("Send to Blocked Agent?") },
            text = {
                Text(
                    if (pending.promptText != null) {
                        "Send Agent prompt \"${pending.label}\" to this Blocked Agent."
                    } else {
                        "Send Agent keys ${pending.label} to this Blocked Agent."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val action = pending
                        pendingKeys = null
                        if (action.promptText != null) onPrompt(action.promptText) else onSendKeys(action.keys.orEmpty())
                    },
                ) { Text("Send") }
            },
            dismissButton = {
                TextButton(onClick = { pendingKeys = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
internal fun UnconfirmedBanner(kind: UnconfirmedKind, onCheck: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                when (kind) {
                    UnconfirmedKind.AgentPrompt -> "Agent prompt Unconfirmed"
                    UnconfirmedKind.AgentKeys -> "Agent keys Unconfirmed"
                    UnconfirmedKind.SendNotes -> "Send notes Unconfirmed"
                    UnconfirmedKind.AddTask -> "Add Task Unconfirmed"
                    UnconfirmedKind.CompleteTask -> "Complete Task Unconfirmed"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCheck) { Text("Check") }
        }
    }
}

@Composable
private fun AgentKeyButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled) { Text(label) }
}

@Composable
internal fun UhpEmptyPane(
    title: String,
    message: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null && onAction != null) {
                Button(onClick = onAction) { Text(action) }
            }
        }
    }
}

private data class PendingAgentAction(
    val label: String,
    val keys: List<AgentKey>?,
    val promptText: String?,
)
