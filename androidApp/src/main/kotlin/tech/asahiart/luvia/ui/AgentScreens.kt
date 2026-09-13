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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.asahiart.luvia.AgentKey
import tech.asahiart.luvia.AgentStatus
import tech.asahiart.luvia.AgentSummary
import tech.asahiart.luvia.MissionRowKind
import tech.asahiart.luvia.MissionSnapshot
import tech.asahiart.luvia.TranscriptSegment
import tech.asahiart.luvia.transcriptSegments

@Composable
fun AgentsSection(
    host: HostUiModel,
    state: HostUhpUiState,
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
    onCheckUnconfirmed: () -> Unit,
    onResumeSession: (String) -> Unit = {},
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
                    Text(
                        if (state.connected) "No Agents in the current snapshot." else "Connect to this host",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
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
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        "Jump to the first Blocked Agent.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
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
    val blocked = agent.status == AgentStatus.Blocked
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (blocked) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(
                        if (blocked) MaterialTheme.colorScheme.error else Color.Transparent,
                    ),
            )
            Column(Modifier.padding(16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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

@Composable
fun AgentDetailPane(
    host: HostUiModel,
    state: HostUhpUiState,
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
    val highlightColor = MaterialTheme.colorScheme.tertiaryContainer
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
                                                fontFamily = FontFamily.Monospace,
                                                softWrap = false,
                                            )
                                        }
                                        if (highlightThis) {
                                            Text(
                                                ansiAnnotatedString(addedSuffix, onSurface, surface),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace,
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
        if (canKeys || canPrompt) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = bottomInset),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    if (canKeys && yesNoPrompt) {
                        AgentKeyRow(
                            canPrompt = canPrompt,
                            mutationPending = mutationPending,
                            blocked = blocked,
                            prominent = true,
                            onPrompt = onPrompt,
                            onSendKeys = onSendKeys,
                            onPending = { pendingKeys = it },
                        )
                    }
                    if (canPrompt) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = onDraftChange,
                            placeholder = { Text(if (yesNoPrompt) "Agent prompt" else "Agent prompt") },
                            enabled = !mutationPending,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = if (yesNoPrompt) 48.dp else 64.dp)
                                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium,
                            trailingIcon = {
                                TextButton(
                                    enabled = !mutationPending && draft.isNotBlank(),
                                    onClick = {
                                        val text = draft.trim()
                                        onPrompt(text)
                                    },
                                ) { Text("Send") }
                            },
                        )
                    }
                    if (canKeys && !yesNoPrompt) {
                        AgentKeyRow(
                            canPrompt = canPrompt,
                            mutationPending = mutationPending,
                            blocked = blocked,
                            prominent = false,
                            onPrompt = onPrompt,
                            onSendKeys = onSendKeys,
                            onPending = { pendingKeys = it },
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
