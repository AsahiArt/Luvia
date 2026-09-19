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
    val waiting = state.waitingEntries()
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
                if (state.attentionCount() > 0) {
                    item(key = "attention") {
                        AttentionBanner(
                            count = state.attentionCount(),
                            onClick = {
                                val first = waiting.firstOrNull() ?: return@AttentionBanner
                                if (first.kind == AgentKind.Acp) onOpenAcp() else first.paneId?.let(onOpenAgent)
                            },
                        )
                    }
                } else {
                    item(key = "mission") {
                        val summary = state.mission?.summary
                        val detail = summary?.let {
                            buildString {
                                val live = state.mission?.rows?.count { row -> row.kind == MissionRowKind.LIVE } ?: 0
                                append(live)
                                append(" live")
                                if (it.tokens > 0) {
                                    append(" · ")
                                    append(it.tokens)
                                    append(" tokens")
                                }
                            }
                        }
                        MissionStrip(
                            working = host.workingAgents,
                            blocked = host.blockedAgents,
                            done = host.completedAgents,
                            detail = detail,
                        )
                    }
                }
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
    onStopObserve: () -> Unit = {},
    onOpenProjectReview: () -> Unit = {},
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
    var overflowOpen by remember { mutableStateOf(false) }
    val transcriptText = detail.transcript?.text.orEmpty()
    // composition-local draft is lost on every layout switch.
    val draft = detail.draft
    val onSurface = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface
    val transcriptParts = remember(transcriptText) { transcriptSegments(transcriptText) }
    val transcriptScroll = rememberScrollState()
    var pinToBottom by remember { mutableStateOf(true) }
    val highlight = remember { Animatable(0f) }
    var addedSuffix by remember { mutableStateOf("") }
    var lastSeen by remember { mutableStateOf("") }
    val yesNoPrompt = remember(transcriptText) { transcriptLooksLikeYesNo(transcriptText) }
    var showTerminal by remember(detail.paneId) { mutableStateOf(false) }
    val highlightColor = MaterialTheme.colorScheme.tertiaryContainer
    DisposableEffect(showTerminal, detail.paneId) {
        if (showTerminal) {
            detail.paneId?.let(onObserveTerminal)
        }
        onDispose { onStopObserve() }
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
                if (place.isNotBlank()) {
                    Text(
                        place,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(onClick = onOpenProjectReview),
                    )
                }
            }
            AgentStatusPill(status)
            val canName = state.canMutate && state.capabilities.agentName && !mutationPending
            val canFork = state.canMutate && state.capabilities.agentFork && !mutationPending
            if (canName || canFork) {
                Box {
                    IconButton(onClick = { overflowOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                        if (canName) {
                            DropdownMenuItem(
                                text = { Text("Name") },
                                onClick = {
                                    overflowOpen = false
                                    onShowNameChange(true)
                                },
                            )
                        }
                        if (canFork) {
                            DropdownMenuItem(
                                text = { Text("Fork") },
                                onClick = {
                                    overflowOpen = false
                                    onShowForkChange(true)
                                },
                            )
                        }
                    }
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            missionRow?.usage?.let { usage ->
                Text(
                    buildString {
                        usage.model?.let { append(it) }
                        usage.totalTokens?.let {
                            if (isNotEmpty()) append(" · ")
                            append(it)
                            append(" tokens")
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
                            .verticalScroll(transcriptScroll),
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
                                                softWrap = true,
                                            )
                                        }
                                        if (highlightThis) {
                                            Text(
                                                ansiAnnotatedString(addedSuffix, onSurface, surface),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = LuviaTheme.mono,
                                                softWrap = true,
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
                if (blocked) {
                    val actions = buildList {
                        if (yesNoPrompt && (canPrompt || state.isObserver)) {
                            add(BlockedAction("Yes", BlockedActionKind.Allow) { onPrompt("y") })
                            add(BlockedAction("No", BlockedActionKind.Reject) { onPrompt("n") })
                        }
                        if (canKeys || state.isObserver) {
                            add(BlockedAction("Enter") { onSendKeys(listOf(AgentKey.ENTER)) })
                            add(BlockedAction("Esc") { onSendKeys(listOf(AgentKey.ESC)) })
                        }
                    }
                    BlockedCard(
                        title = "Blocked — answer",
                        body = if (yesNoPrompt) {
                            "This Agent is waiting for a yes or no."
                        } else {
                            "This Agent is waiting."
                        },
                        actions = actions,
                        enabled = !mutationPending && !state.isObserver && (canPrompt || canKeys),
                        caption = if (state.isObserver) "Observer — can't answer" else null,
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

