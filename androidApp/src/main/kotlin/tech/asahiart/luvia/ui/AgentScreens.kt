@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package tech.asahiart.luvia.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.asahiart.luvia.AgentKey
import tech.asahiart.luvia.AgentKind
import tech.asahiart.luvia.AgentStatus
import tech.asahiart.luvia.AgentSummary
import tech.asahiart.luvia.TerminalKey
import tech.asahiart.luvia.MissionRowKind
import tech.asahiart.luvia.MissionSnapshot
import tech.asahiart.luvia.TranscriptSegment
import tech.asahiart.luvia.transcriptSegments
import tech.asahiart.luvia.HostUhpState
import tech.asahiart.luvia.UnconfirmedKind
import tech.asahiart.luvia.ui.theme.LuviaTheme

@Composable
fun AgentsSection(
    host: HostUiModel,
    state: HostUhpState,
    onRefresh: () -> Unit,
    onOpenAgent: (String) -> Unit,
    onOpenAcp: () -> Unit = {},
    onCheckUnconfirmed: () -> Unit,
    onResumeSession: (String) -> Unit = {},
    onLoadAcpAgents: () -> Unit = {},
    onShowLaunchAcp: (Boolean) -> Unit = {},
    onSelectAcpAgent: (String?) -> Unit = {},
    onAcpCwdChange: (String) -> Unit = {},
    onLaunchAcp: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val neverConnected = !host.connected && !state.connected && state.agents.isEmpty() && !host.hasSnapshot
    when {
        neverConnected && host.connection == ConnectionBadge.Connecting -> {
            EmptyState(title = "Agents", message = "Connecting…", modifier = modifier)
        }
        neverConnected -> {
            EmptyState(
                title = "Agents",
                message = "This Host has not connected yet.",
                modifier = modifier,
            )
        }
        else -> {
            AgentListPane(
                host = host,
                state = state,
                onRefresh = onRefresh,
                onOpenAgent = onOpenAgent,
                onOpenAcp = onOpenAcp,
                onCheckUnconfirmed = onCheckUnconfirmed,
                onResumeSession = onResumeSession,
                onLoadAcpAgents = onLoadAcpAgents,
                onShowLaunchAcp = onShowLaunchAcp,
                onSelectAcpAgent = onSelectAcpAgent,
                onAcpCwdChange = onAcpCwdChange,
                onLaunchAcp = onLaunchAcp,
                modifier = modifier,
            )
        }
    }
}

@Composable
fun AgentListPane(
    host: HostUiModel,
    state: HostUhpState,
    onRefresh: () -> Unit,
    onOpenAgent: (String) -> Unit,
    onOpenAcp: () -> Unit = {},
    onCheckUnconfirmed: () -> Unit,
    onResumeSession: (String) -> Unit = {},
    onLoadAcpAgents: () -> Unit = {},
    onShowLaunchAcp: (Boolean) -> Unit = {},
    onSelectAcpAgent: (String?) -> Unit = {},
    onAcpCwdChange: (String) -> Unit = {},
    onLaunchAcp: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val openLaunch = {
        onLoadAcpAgents()
        onShowLaunchAcp(true)
    }
    val entries = state.agentEntries()
    val nowEpochMs = System.currentTimeMillis()
    Box(modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.errorText?.let { error ->
                    item {
                        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                state.agentDetail.unconfirmed?.let { kind ->
                    item { UnconfirmedBanner(kind = kind, onCheck = onCheckUnconfirmed) }
                }
                if (entries.isEmpty()) {
                    item {
                        if (state.capabilities.acpSession) {
                            LaunchAgentCard(onLaunch = openLaunch)
                        } else {
                            EmptyState(
                                title = "No Agents",
                                message = if (state.connected) {
                                    "No Agents. Launch one, or start one in Luvus."
                                } else {
                                    "This Host has not connected yet."
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                } else {
                    val waitingRows = entries.filter { it.status == AgentStatus.Blocked }
                    val working = entries.filter { it.status == AgentStatus.Working }
                    val idle = entries.filter { it.status == AgentStatus.Idle || it.status == AgentStatus.Unknown }
                    val done = entries.filter { it.status == AgentStatus.Done }
                    listOf(
                        "Waiting" to waitingRows,
                        "Working" to working,
                        "Idle" to idle,
                        "Done" to done,
                    ).forEach { (title, rows) ->
                        if (rows.isNotEmpty()) {
                            item(key = "section-$title") { SectionHeader(title) }
                            items(rows, key = { it.id }) { entry ->
                                AgentRow(
                                    entry = entry,
                                    nowEpochMs = nowEpochMs,
                                    onClick = {
                                        if (entry.kind == AgentKind.Acp) onOpenAcp() else entry.paneId?.let(onOpenAgent)
                                    },
                                )
                            }
                        }
                    }
                }
                if (state.agentSessions.isNotEmpty()) {
                    item { SectionHeader("Resumable sessions") }
                    items(state.agentSessions, key = { it.sessionId }) { session ->
                        AgentSessionRow(
                            sessionId = session.sessionId,
                            agent = session.agent,
                            cwd = session.cwd,
                            canResume = state.canMutate && state.capabilities.agentResume,
                            onResume = { onResumeSession(session.sessionId) },
                        )
                    }
                }
            }
        }
        if (state.capabilities.acpSession) {
            ExtendedFloatingActionButton(
                text = { Text("New agent") },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = openLaunch,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            )
        }
        if (state.acp.showLaunch) {
            AcpLaunchSheet(
                state = state.acp,
                onDismiss = { onShowLaunchAcp(false) },
                onSelectAgent = onSelectAcpAgent,
                onCwdChange = onAcpCwdChange,
                onLaunch = onLaunchAcp,
            )
        }
    }
}




@Composable
internal fun UnconfirmedBanner(kind: UnconfirmedKind, onCheck: () -> Unit) {
    val agentKind = kind == UnconfirmedKind.AgentPrompt || kind == UnconfirmedKind.AgentKeys
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    when (kind) {
                        UnconfirmedKind.AgentPrompt -> "Agent prompt Unconfirmed"
                        UnconfirmedKind.AgentKeys -> "Agent keys Unconfirmed"
                        UnconfirmedKind.AddReviewNote -> "Add Review note Unconfirmed"
                        UnconfirmedKind.ResolveReviewNote -> "Resolve Review note Unconfirmed"
                        UnconfirmedKind.ReopenReviewNote -> "Reopen Review note Unconfirmed"
                        UnconfirmedKind.RemoveReviewNote -> "Remove Review note Unconfirmed"
                        UnconfirmedKind.SendNotes -> "Send notes Unconfirmed"
                        UnconfirmedKind.AddTask -> "Add Task Unconfirmed"
                        UnconfirmedKind.CompleteTask -> "Complete Task Unconfirmed"
                        UnconfirmedKind.ClaimTask -> "Claim Task Unconfirmed"
                        UnconfirmedKind.DeleteTask -> "Delete Task Unconfirmed"
                        UnconfirmedKind.RetryTask -> "Retry Task Unconfirmed"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onCheck) {
                    Text(if (agentKind) "Re-read agent" else "Check")
                }
            }
            if (agentKind) {
                Text(
                    "The Host may already have it. Re-read Agent state instead of sending again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}


@Composable
internal fun UhpEmptyPane(
    title: String,
    message: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    EmptyState(
        title = title,
        message = message,
        modifier = modifier,
        action = action,
        onAction = onAction,
    )
}

@Composable
private fun AgentSessionRow(
    sessionId: String,
    agent: String,
    cwd: String,
    canResume: Boolean,
    onResume: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    agent.ifBlank { "Session" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    sessionId,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (cwd.isNotBlank()) {
                    Text(
                        cwd,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (canResume) {
                FilledTonalButton(onClick = onResume) { Text("Resume") }
            }
        }
    }
}

