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
    onCloseAgent: () -> Unit,
    onPrompt: (String) -> Unit,
    onDraftChange: (String) -> Unit,
    onSendKeys: (List<AgentKey>) -> Unit,
    onCheckUnconfirmed: () -> Unit,
    onResumeSession: (String) -> Unit = {},
    onShowNameChange: (Boolean) -> Unit = {},
    onNameDraftChange: (String) -> Unit = {},
    onNameAgent: () -> Unit = {},
    onShowForkChange: (Boolean) -> Unit = {},
    onForkDraftChange: (String) -> Unit = {},
    onForkAgent: () -> Unit = {},
    onLoadAcpAgents: () -> Unit = {},
    onShowLaunchAcp: (Boolean) -> Unit = {},
    onSelectAcpAgent: (String?) -> Unit = {},
    onAcpCwdChange: (String) -> Unit = {},
    onLaunchAcp: () -> Unit = {},
    onAcpDraftChange: (String) -> Unit = {},
    onPromptAcp: () -> Unit = {},
    onAnswerAcpPermission: (String) -> Unit = {},
    onCancelAcp: () -> Unit = {},
    onCloseAcp: () -> Unit = {},
    terminal: TerminalUiModel? = null,
    onRequestControl: () -> Unit = {},
    onSendTerminalText: (String) -> Unit = {},
    onSendTerminalKey: (TerminalKey) -> Unit = {},
    onObserveTerminal: (String) -> Unit = {},
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
        state.acp.open -> {
            AcpSessionPane(
                host = host,
                state = state,
                onBack = onCloseAcp,
                onDraftChange = onAcpDraftChange,
                onPrompt = onPromptAcp,
                onAnswerPermission = onAnswerAcpPermission,
                onCancel = onCancelAcp,
                onClose = onCloseAcp,
                modifier = modifier,
            )
        }
        state.agentDetail.open -> {
            AgentDetailPane(
                host = host,
                state = state,
                onBack = onCloseAgent,
                onRefresh = onRefresh,
                onPrompt = onPrompt,
                onDraftChange = onDraftChange,
                onSendKeys = onSendKeys,
                onCheckUnconfirmed = onCheckUnconfirmed,
                onShowNameChange = onShowNameChange,
                onNameDraftChange = onNameDraftChange,
                onNameAgent = onNameAgent,
                onShowForkChange = onShowForkChange,
                onForkDraftChange = onForkDraftChange,
                onForkAgent = onForkAgent,
                terminal = terminal,
                onRequestControl = onRequestControl,
                onSendTerminalText = onSendTerminalText,
                onSendTerminalKey = onSendTerminalKey,
                onObserveTerminal = onObserveTerminal,
                modifier = modifier,
            )
        }
        else -> {
            AgentListPane(
                host = host,
                state = state,
                onRefresh = onRefresh,
                onOpenAgent = onOpenAgent,
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
            item {
                AgentsHeaderCard(
                    host = host,
                    mission = state.mission,
                    onWaitingClick = {
                        val pane = host.firstBlockedPaneId
                            ?: state.agents.firstOrNull { it.status == AgentStatus.Blocked }?.paneId
                        if (pane != null) onOpenAgent(pane)
                    },
                )
            }
            state.errorText?.let { error ->
                item {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            state.agentDetail.errorText?.let { error ->
                item {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            state.agentDetail.unconfirmed?.let { kind ->
                item { UnconfirmedBanner(kind = kind, onCheck = onCheckUnconfirmed) }
            }
            if (state.agents.isEmpty()) {
                item {
                    if (state.capabilities.acpSession) {
                        LaunchAgentCard(onLaunch = openLaunch)
                    } else {
                        Text(
                            if (state.connected) "No Agents in the current snapshot." else "Connect to this host",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                val blocked = state.agents.filter { it.status == AgentStatus.Blocked }
                val working = state.agents.filter { it.status == AgentStatus.Working }
                val idle = state.agents.filter { it.status == AgentStatus.Idle || it.status == AgentStatus.Unknown }
                val done = state.agents.filter { it.status == AgentStatus.Done }
                listOf(
                    "Blocked" to blocked,
                    "Working" to working,
                    "Idle" to idle,
                    "Done" to done,
                ).forEach { (title, agents) ->
                    if (agents.isNotEmpty()) {
                        item(key = "section-$title") {
                            Text(
                                title,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        items(agents, key = { it.paneId }) { agent ->
                            AgentRow(agent = agent, onClick = { onOpenAgent(agent.paneId) })
                        }
                    }
                }
            }
            if (state.agentSessions.isNotEmpty()) {
                item {
                    Text(
                        "Resumable sessions",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
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
                text = { Text("Launch") },
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
private fun AgentsHeaderCard(
    host: HostUiModel,
    mission: MissionSnapshot?,
    onWaitingClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (host.blockedAgents > 0) {
            Card(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onWaitingClick),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (host.blockedAgents == 1) {
                            "1 agent waiting for you"
                        } else {
                            "${host.blockedAgents} agents waiting for you"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        "Jump to the first Blocked Agent.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
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
}

@Composable
private fun MetricChip(label: String, value: Int, loud: Boolean = false) {
    val colors = if (loud) {
        AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.primary,
            labelColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.primary,
            disabledLabelColor = MaterialTheme.colorScheme.onPrimary,
        )
    } else {
        AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
    val blocked = agent.status == AgentStatus.Blocked
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (blocked) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.surface
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
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val label = if (blocked) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
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

@Composable
fun AgentDetailPane(
    host: HostUiModel,
    state: HostUhpState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onPrompt: (String) -> Unit,
    onDraftChange: (String) -> Unit,
    onSendKeys: (List<AgentKey>) -> Unit,
    onCheckUnconfirmed: () -> Unit,
    onShowNameChange: (Boolean) -> Unit = {},
    onNameDraftChange: (String) -> Unit = {},
    onNameAgent: () -> Unit = {},
    onShowForkChange: (Boolean) -> Unit = {},
    onForkDraftChange: (String) -> Unit = {},
    onForkAgent: () -> Unit = {},
    terminal: TerminalUiModel? = null,
    onRequestControl: () -> Unit = {},
    onSendTerminalText: (String) -> Unit = {},
    onSendTerminalKey: (TerminalKey) -> Unit = {},
    onObserveTerminal: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val detail = state.agentDetail
    val listed = state.agents.firstOrNull { it.paneId == detail.paneId }
    val summary = detail.summary ?: listed
    val status = detail.detail?.status ?: summary?.status ?: AgentStatus.Unknown
    val blocked = status == AgentStatus.Blocked
    val mutationPending = detail.sending || detail.unconfirmed != null
    val canPrompt = state.canMutate && state.capabilities.agentPrompt && !mutationPending
    val canKeys = state.canMutate && state.capabilities.agentKeys && !mutationPending
    // Held in ViewModel state, not rememberSaveable: the compact and expanded
    // layouts put this pane under different saveable-state scopes, so a
    // composition-local draft is lost on every layout switch.
    val draft = detail.draft
    var pendingKeys by remember { mutableStateOf<PendingAgentAction?>(null) }
    val transcriptText = detail.transcript?.text.orEmpty()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface
    val transcriptParts = remember(transcriptText) { transcriptSegments(transcriptText) }
    val transcriptScroll = rememberScrollState()
    val transcriptHorizontal = rememberScrollState()
    var pinToBottom by remember { mutableStateOf(true) }
    val highlight = remember { Animatable(0f) }
    var addedSuffix by remember { mutableStateOf("") }
    var lastSeen by remember { mutableStateOf("") }
    val yesNoPrompt = remember(transcriptText) { transcriptLooksLikeYesNo(transcriptText) }
    var showTerminal by remember(detail.paneId) { mutableStateOf(false) }
    val highlightColor = MaterialTheme.colorScheme.tertiaryContainer
    LaunchedEffect(detail.paneId) {
        detail.paneId?.let(onObserveTerminal)
    }
    LaunchedEffect(transcriptScroll.isScrollInProgress, transcriptScroll.value, transcriptScroll.maxValue) {
        if (!transcriptScroll.isScrollInProgress) {
            pinToBottom = transcriptScroll.maxValue == 0 ||
                transcriptScroll.value >= transcriptScroll.maxValue - 80
        }
    }
    LaunchedEffect(detail.transcript?.revision, transcriptText) {
        if (lastSeen.isNotEmpty() && transcriptText.startsWith(lastSeen) && transcriptText.length > lastSeen.length) {
            addedSuffix = transcriptText.substring(lastSeen.length)
            highlight.snapTo(1f)
            highlight.animateTo(0f, animationSpec = tween(1600))
        } else {
            addedSuffix = ""
        }
        lastSeen = transcriptText
        if (pinToBottom) {
            transcriptScroll.scrollTo(transcriptScroll.maxValue)
        }
    }
    LaunchedEffect(pinToBottom, transcriptScroll.maxValue) {
        if (pinToBottom) {
            transcriptScroll.scrollTo(transcriptScroll.maxValue)
        }
    }
    val missionRow = state.mission?.rows?.firstOrNull { it.pane == detail.paneId }
    val titleName = listOfNotNull(
        listed?.name,
        summary?.name,
        detail.detail?.name,
        listed?.agent,
        summary?.agent,
        detail.detail?.agent,
    ).firstOrNull { it.isNotBlank() } ?: "Agent"
    val kind = listed?.agent ?: summary?.agent ?: detail.detail?.agent
    val place = listOfNotNull(
        summary?.workspaceName ?: summary?.workspace,
        summary?.branch,
    ).joinToString(" · ")
    val cwd = detail.detail?.cwd ?: summary?.cwd

    val bottomInset = systemBottomInset()
    Box(modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Column(Modifier.weight(1f)) {
                Text(
                    titleName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                )
                if (!kind.isNullOrBlank() && kind != titleName) {
                    Text(kind, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FilledTonalButton(onClick = onRefresh) { Text("Refresh") }
        }
        val canName = state.canMutate && state.capabilities.agentName && !mutationPending
        val canFork = state.canMutate && state.capabilities.agentFork && !mutationPending
        if (canName || canFork) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (canName) {
                    FilledTonalButton(onClick = { onShowNameChange(true) }) { Text("Name") }
                }
                if (canFork) {
                    FilledTonalButton(onClick = { onShowForkChange(true) }) { Text("Fork") }
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AgentStatusChip(status)
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
            if (!cwd.isNullOrBlank()) {
                Text(
                    cwd,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
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
        }
        if (detail.errorText != null || detail.unconfirmed != null) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                detail.errorText?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                detail.unconfirmed?.let { kindBanner ->
                    UnconfirmedBanner(kind = kindBanner, onCheck = onCheckUnconfirmed)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = !showTerminal,
                onClick = { showTerminal = false },
                label = { Text("Transcript") },
            )
            FilterChip(
                selected = showTerminal,
                onClick = { showTerminal = true },
                label = { Text("Terminal") },
            )
        }
        if (showTerminal) {
            val term = terminal
            if (term == null) {
                Text(
                    "No live terminal for this pane.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(16.dp),
                )
            } else {
                TerminalPane(
                    terminal = term,
                    onRequestControl = onRequestControl,
                    onSendText = onSendTerminalText,
                    onSendKey = onSendTerminalKey,
                    boundToPane = true,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (!state.capabilities.agentRead) {
                Text("Transcript is not available on this host.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                SelectionContainer {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(transcriptScroll)
                            .horizontalScroll(transcriptHorizontal),
                    ) {
                        if (transcriptText.isEmpty()) {
                            Text(
                                "No transcript yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            transcriptParts.forEachIndexed { index, part ->
                                when (part) {
                                    is TranscriptSegment.Text -> {
                                        val isLast = index == transcriptParts.indexOfLast { it is TranscriptSegment.Text }
                                        val highlightThis = isLast && addedSuffix.isNotEmpty() && part.text.endsWith(addedSuffix)
                                        val stable = if (highlightThis) part.text.removeSuffix(addedSuffix) else part.text
                                        if (stable.isNotEmpty()) {
                                            Text(
                                                ansiAnnotatedString(stable, onSurface, surface),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = LuviaTheme.mono,
                                                softWrap = false,
                                            )
                                        }
                                        if (highlightThis) {
                                            Text(
                                                ansiAnnotatedString(addedSuffix, onSurface, surface),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = LuviaTheme.mono,
                                                softWrap = false,
                                                modifier = Modifier.background(highlightColor.copy(alpha = highlight.value)),
                                            )
                                        }
                                    }
                                    TranscriptSegment.Rule ->
                                        HorizontalDivider(
                                            Modifier.padding(vertical = 8.dp),
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                                        )
                                    TranscriptSegment.Gap ->
                                        Spacer(Modifier.height(12.dp))
                                }
                            }
                        }
                    }
                }
                JumpToLatestPill(
                    visible = !pinToBottom && transcriptScroll.maxValue > 0,
                    onClick = { pinToBottom = true },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                )
            }
            }
        }
        }
        if (!showTerminal && (canKeys || canPrompt)) {
            val consumeVertical = remember {
                object : NestedScrollConnection {
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        return if (kotlin.math.abs(available.y) > kotlin.math.abs(available.x)) {
                            Offset(0f, available.y)
                        } else {
                            Offset.Zero
                        }
                    }
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .nestedScroll(consumeVertical)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = bottomInset + 8.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (canKeys) {
                    AgentKeyRow(
                        canPrompt = canPrompt,
                        mutationPending = mutationPending,
                        blocked = blocked,
                        prominent = yesNoPrompt,
                        onPrompt = onPrompt,
                        onSendKeys = onSendKeys,
                        onPending = { pendingKeys = it },
                    )
                }
                if (canPrompt) {
                    val canSend = !mutationPending && draft.isNotBlank()
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        tonalElevation = 3.dp,
                        shadowElevation = 8.dp,
                    ) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = onDraftChange,
                            placeholder = { Text("Agent prompt") },
                            enabled = !mutationPending,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium,
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                disabledBorderColor = Color.Transparent,
                            ),
                            trailingIcon = {
                                Box(
                                    modifier = Modifier
                                        .padding(end = 4.dp)
                                        .size(32.dp)
                                        .background(
                                            if (canSend) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                                            },
                                            CircleShape,
                                        )
                                        .clickable(enabled = canSend) {
                                            onPrompt(draft.trim())
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Filled.KeyboardArrowUp,
                                        contentDescription = "Send",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
    if (detail.showName) {
        AlertDialog(
            onDismissRequest = { onShowNameChange(false) },
            title = { Text("Name Agent") },
            text = {
                OutlinedTextField(
                    value = detail.nameDraft,
                    onValueChange = onNameDraftChange,
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = detail.nameDraft.isNotBlank() && !mutationPending,
                    onClick = onNameAgent,
                ) { Text("Name") }
            },
            dismissButton = {
                TextButton(onClick = { onShowNameChange(false) }) { Text("Cancel") }
            },
        )
    }
    if (detail.showFork) {
        AlertDialog(
            onDismissRequest = { onShowForkChange(false) },
            title = { Text("Fork Agent?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Create a forked Agent from this pane.")
                    OutlinedTextField(
                        value = detail.forkDraft,
                        onValueChange = onForkDraftChange,
                        label = { Text("Name (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = !mutationPending, onClick = onForkAgent) { Text("Fork") }
            },
            dismissButton = {
                TextButton(onClick = { onShowForkChange(false) }) { Text("Cancel") }
            },
        )
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
private fun AgentKeyRow(
    canPrompt: Boolean,
    mutationPending: Boolean,
    blocked: Boolean,
    prominent: Boolean,
    onPrompt: (String) -> Unit,
    onSendKeys: (List<AgentKey>) -> Unit,
    onPending: (PendingAgentAction) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .horizontalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (canPrompt) {
            AgentKeyButton("y+Enter", enabled = !mutationPending, prominent = prominent) {
                val action = PendingAgentAction("y+Enter", null, "y")
                if (blocked) onPending(action) else onPrompt("y")
            }
            AgentKeyButton("n+Enter", enabled = !mutationPending, prominent = prominent) {
                val action = PendingAgentAction("n+Enter", null, "n")
                if (blocked) onPending(action) else onPrompt("n")
            }
        }
        AgentKeyButton("Enter", enabled = !mutationPending, prominent = prominent) {
            val action = PendingAgentAction("Enter", listOf(AgentKey.ENTER), null)
            if (blocked) onPending(action) else onSendKeys(action.keys.orEmpty())
        }
        AgentKeyButton("Esc", enabled = !mutationPending, prominent = prominent) {
            val action = PendingAgentAction("Esc", listOf(AgentKey.ESC), null)
            if (blocked) onPending(action) else onSendKeys(action.keys.orEmpty())
        }
        AgentKeyButton("Up", enabled = !mutationPending, prominent = prominent) {
            val action = PendingAgentAction("Up", listOf(AgentKey.UP), null)
            if (blocked) onPending(action) else onSendKeys(action.keys.orEmpty())
        }
        AgentKeyButton("Down", enabled = !mutationPending, prominent = prominent) {
            val action = PendingAgentAction("Down", listOf(AgentKey.DOWN), null)
            if (blocked) onPending(action) else onSendKeys(action.keys.orEmpty())
        }
        AgentKeyButton("Tab", enabled = !mutationPending, prominent = prominent) {
            val action = PendingAgentAction("Tab", listOf(AgentKey.TAB), null)
            if (blocked) onPending(action) else onSendKeys(action.keys.orEmpty())
        }
    }
}

@Composable
private fun AgentKeyButton(label: String, enabled: Boolean, prominent: Boolean = false, onClick: () -> Unit) {
    if (prominent) {
        Button(onClick = onClick, enabled = enabled) { Text(label) }
    } else {
        FilledTonalButton(onClick = onClick, enabled = enabled) { Text(label) }
    }
}

private fun transcriptLooksLikeYesNo(text: String): Boolean {
    val tail = text.trim().takeLast(500)
    return Regex("""(?i)\(y/n\)|\[y/n\]|\by/n\b|\byes\s*/\s*no\b|\(yes/no\)""").containsMatchIn(tail)
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

private data class PendingAgentAction(
    val label: String,
    val keys: List<AgentKey>?,
    val promptText: String?,
)
