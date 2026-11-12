package tech.asahiart.luvia.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import tech.asahiart.luvia.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.asahiart.luvia.AgentKind
import tech.asahiart.luvia.LuviaViewModel
import tech.asahiart.luvia.resolveStartupHost
import tech.asahiart.luvia.shouldPresentPairing
import tech.asahiart.luvia.toSortedUi
import tech.asahiart.luvia.StatusNotificationController
import tech.asahiart.luvia.thread.AgentThread
import tech.asahiart.luvia.thread.SessionFilter
import tech.asahiart.luvia.thread.awaitingAcpThread
import tech.asahiart.luvia.thread.filterSessions
import tech.asahiart.luvia.FailureHandoff
import tech.asahiart.luvia.thread.sessionRoster
import tech.asahiart.luvia.thread.unreadChatCount
import tech.asahiart.luvia.thread.sortSessions
import tech.asahiart.luvia.thread.terminalFixCandidates
import tech.asahiart.luvia.thread.terminalFixOpensNewAgent
import tech.asahiart.luvia.thread.terminalOwningSession
import tech.asahiart.luvia.thread.threadTrails
import tech.asahiart.luvia.thread.threads

private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val TerminalOrigin = TransformOrigin(0.92f, 0.04f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatFirstApp(
    viewModel: LuviaViewModel,
    launchIntent: Intent?,
    pushEnabled: Boolean,
    hasPushDistributor: Boolean,
    onSetPushEnabled: (Boolean) -> Unit,
) {
    val runtimes by viewModel.hosts.collectAsStateWithLifecycle()
    val catalogReady by viewModel.catalogReady.collectAsStateWithLifecycle()
    val hostStates by viewModel.hostStates.collectAsStateWithLifecycle()
    val pairing by viewModel.pairing.collectAsStateWithLifecycle()
    val terminals by viewModel.terminals.collectAsStateWithLifecycle()
    val terminalPreviews by viewModel.terminalPreviews.collectAsStateWithLifecycle()
    val hosts = runtimes.toSortedUi(hostStates.mapValues { it.value.attentionCount() })

    var stage by rememberSaveable { mutableStateOf("sessions") }
    var userOpenedPairing by rememberSaveable { mutableStateOf(false) }
    var hostId by rememberSaveable { mutableStateOf<String?>(null) }
    var startupApplied by rememberSaveable { mutableStateOf(false) }
    var threadId by rememberSaveable { mutableStateOf<String?>(null) }
    var filterName by rememberSaveable { mutableStateOf(SessionFilter.All.name) }
    var terminalReturn by rememberSaveable { mutableStateOf("sessions") }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showNewSession by rememberSaveable { mutableStateOf(false) }
    var confirmUnpair by rememberSaveable { mutableStateOf(false) }
    var awaitingAcp by rememberSaveable { mutableStateOf(false) }
    var seenChatIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val filter = runCatching { SessionFilter.valueOf(filterName) }.getOrDefault(SessionFilter.All)
    val profiles = runtimes.map { it.profile }
    val resolvedId = if (startupApplied) hostId else resolveStartupHost(profiles, hostId)?.id ?: hostId
    val selected = hosts.firstOrNull { it.id == resolvedId } ?: hosts.firstOrNull()
    val state = selected?.let { hostStates[it.id] }
    val allThreads = sortSessions(state?.threads(selected.id).orEmpty())
    val threads = filterSessions(allThreads, filter)
    val openThread = allThreads.firstOrNull { it.id == threadId }
    val openBlocked = launchIntent?.getBooleanExtra(StatusNotificationController.EXTRA_OPEN_BLOCKED, false) == true
    var consumedLaunch by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(profiles.map { it.id }) {
        if (startupApplied || profiles.isEmpty()) return@LaunchedEffect
        hostId = resolveStartupHost(profiles, hostId)?.id
        startupApplied = true
    }
    val pairingInProgress = pairing.draft != null
    LaunchedEffect(catalogReady, hosts.size, userOpenedPairing, pairingInProgress) {
        if (!catalogReady && !userOpenedPairing && !pairingInProgress) {
            if (stage == "pair") stage = "sessions"
            return@LaunchedEffect
        }
        val present = shouldPresentPairing(catalogReady, hosts.size, userOpenedPairing, pairingInProgress)
        if (hosts.isEmpty() && present) {
            stage = "pair"
        } else if (present && userOpenedPairing && stage == "sessions") {
            stage = "pair"
        } else if (!present && stage == "pair") {
            userOpenedPairing = false
            stage = "sessions"
        }
    }
    LaunchedEffect(selected?.id) {
        val host = selected ?: return@LaunchedEffect
        val id = host.id
        if (startupApplied) hostId = id
        viewModel.autoConnect(id)
        viewModel.refresh(id)
        viewModel.workspace(id).shown()
        if (state?.connected == true) {
            viewModel.workspace(id).loadAcpAgents()
        }
    }
    LaunchedEffect(selected?.id, state?.connected) {
        val id = selected?.id ?: return@LaunchedEffect
        if (state?.connected == true) {
            viewModel.workspace(id).loadAcpAgents()
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, selected?.id) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                selected?.id?.let(viewModel::autoConnect)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(launchIntent, allThreads, consumedLaunch) {
        if (consumedLaunch) return@LaunchedEffect
        val id = launchIntent?.getStringExtra(StatusNotificationController.EXTRA_HOST_ID) ?: return@LaunchedEffect
        hostId = id
        filterName = if (openBlocked) SessionFilter.NeedsYou.name else filterName
        val thread = if (openBlocked) allThreads.firstOrNull { it.hostId == id && it.needsYou } else null
        if (thread != null) {
            threadId = thread.id
            stage = "chat"
            consumedLaunch = true
        } else if (!openBlocked) {
            stage = "sessions"
            consumedLaunch = true
        }
    }
    LaunchedEffect(
        awaitingAcp,
        state?.acp?.open,
        state?.acp?.errorText,
        state?.acp?.info?.sessionId,
    ) {
        if (!awaitingAcp) return@LaunchedEffect
        val id = selected?.id ?: return@LaunchedEffect
        val hostState = state ?: return@LaunchedEffect
        if (hostState.acp.errorText != null && !hostState.acp.open) {
            awaitingAcp = false
            return@LaunchedEffect
        }
        val thread = awaitingAcpThread(hostState, id) ?: return@LaunchedEffect
        viewModel.workspace(id).viewAcp()
        threadId = thread.id
        viewModel.markViewed(thread)
        stage = "chat"
        showNewSession = false
        awaitingAcp = false
    }

    fun openThread(thread: AgentThread) {
        val id = thread.hostId
        hostId = id
        threadId = thread.id
        viewModel.markViewed(thread)
        val workspace = viewModel.workspace(id)
        if (thread.acpHistory) {
            val agentId = thread.acpAgentId
            val cwd = thread.cwd
            val sessionId = thread.acpSessionId
            if (agentId != null && !cwd.isNullOrBlank() && sessionId != null) {
                workspace.resumeAcp(agentId, cwd, sessionId)
                awaitingAcp = true
                return
            }
        }
        when (thread.kind) {
            AgentKind.Acp -> workspace.viewAcp()
            AgentKind.Pane -> thread.paneId?.let { workspace.openAgent(it) }
        }
        stage = "chat"
    }

    fun conversationIds(thread: AgentThread?): Set<String> {
        if (thread == null) return emptySet()
        return viewModel.workspace(thread.hostId).timeline(thread).map { it.id }.toSet()
    }

    fun openTerminal(paneId: String?) {
        val id = selected?.id ?: return
        if (stage != "terminal") {
            terminalReturn = stage
            seenChatIds = conversationIds(openThread)
        }
        viewModel.ensureTerminal(id, paneId)
        stage = "terminal"
    }

    BackHandler(enabled = stage != "sessions" || showSettings || showNewSession) {
        when {
            showNewSession -> showNewSession = false
            showSettings -> showSettings = false
            stage == "terminal" -> {
                selected?.id?.let { viewModel.stopTerminal(it) }
                stage = terminalReturn
            }
            stage == "chat" -> stage = "sessions"
            stage == "pair" -> if (hosts.isNotEmpty()) {
                userOpenedPairing = false
                stage = "sessions"
            }
        }
    }

    val underlying = if (stage == "terminal") terminalReturn else stage
    val holdForCatalog = !catalogReady && hosts.isEmpty() && !userOpenedPairing && pairing.draft == null
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        if (stage != "terminal") when {
            holdForCatalog && underlying == "sessions" -> androidx.compose.foundation.layout.Box(Modifier.fillMaxSize())
            underlying == "pair" -> PairingScreen(
                pairing = pairing,
                onBegin = viewModel::beginPairing,
                onComplete = { raw, shortHost ->
                    viewModel.completePairing(raw, shortHost, onSuccess = {
                        userOpenedPairing = false
                        stage = "sessions"
                    })
                },
                onCancel = viewModel::cancelPairing,
                onClearError = viewModel::clearPairingError,
                onBack = if (hosts.isNotEmpty()) {
                    {
                        userOpenedPairing = false
                        stage = "sessions"
                    }
                } else {
                    null
                },
            )
            underlying == "chat" -> {
                val thread = openThread
                if (thread == null) {
                    Column(Modifier.fillMaxSize().padding(24.dp)) {
                        Text(stringResource(R.string.session_gone))
                        TextButton(onClick = { stage = "sessions" }) { Text(stringResource(R.string.back_to_sessions)) }
                    }
                } else {
                    val workspace = viewModel.workspace(thread.hostId)
                    val items = state?.let { workspace.timeline(thread) }.orEmpty()
                    val pending = allThreads.filter { it.needsYou }
                    val queueIndex = pending.indexOfFirst { it.id == thread.id }.let { if (it < 0) thread.ask?.index ?: 1 else it + 1 }
                    val queueTotal = pending.size.coerceAtLeast(thread.ask?.total ?: 1)
                    val initialDraft = when (thread.kind) {
                        AgentKind.Acp -> state?.acp?.draft.orEmpty()
                        AgentKind.Pane ->
                            if (state?.agentDetail?.paneId == thread.paneId) {
                                state?.agentDetail?.draft.orEmpty()
                            } else {
                                ""
                            }
                    }
                    ChatScreen(
                        thread = thread,
                        items = items,
                        queueIndex = queueIndex,
                        queueTotal = queueTotal,
                        initialDraft = initialDraft,
                        onSend = { text -> workspace.sendToThread(text) },
                        onAnswer = { option -> viewModel.answer(thread, option) },
                        onBack = { stage = "sessions" },
                        onOpenTerminal = { openTerminal(thread.paneId) },
                        terminalPreview = selected?.id?.let { terminalPreviews[it] ?: terminals[it]?.text },
                        sending = state?.agentDetail?.sending == true,
                        isObserver = selected?.isObserver == true,
                        connection = selected?.connection,
                        pairingRevoked = selected?.pairingRevoked == true,
                        onRetry = { selected?.id?.let(viewModel::connect) },
                    )
                }
            }
            else -> SessionListScreen(
                hosts = hosts,
                selected = selected,
                threads = threads,
                filter = filter,
                onFilter = { filterName = it.name },
                onSelectHost = {
                    startupApplied = true
                    hostId = it
                },
                onPair = {
                    viewModel.cancelPairing()
                    userOpenedPairing = true
                    stage = "pair"
                },
                onOpenSettings = { showSettings = true },
                onOpenThread = ::openThread,
                onAnswer = viewModel::answer,
                onNewSession = {
                    selected?.id?.let { viewModel.workspace(it).loadAcpAgents() }
                    showNewSession = true
                },
                onOpenTerminal = { openTerminal(openThread?.paneId) },
                onRetry = { selected?.id?.let(viewModel::connect) },
                roster = sessionRoster(allThreads),
                trails = state?.let { hostState ->
                    threadTrails(hostState, allThreads).associate { it.threadId to it.thumb }
                }.orEmpty(),
            )
        }
        if (stage == "terminal") {
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)),
            )
        }
        AnimatedVisibility(
            visible = stage == "terminal",
            enter = fadeIn(tween(350, easing = Emphasized)) + scaleIn(
                initialScale = 0.84f,
                transformOrigin = TerminalOrigin,
                animationSpec = tween(350, easing = Emphasized),
            ),
            exit = fadeOut(tween(220)) + scaleOut(
                targetScale = 0.84f,
                transformOrigin = TerminalOrigin,
                animationSpec = tween(350, easing = Emphasized),
            ),
        ) {
            val id = selected?.id
            val workspace = id?.let { viewModel.workspace(it) }
            val fixPane = id?.let { terminals[it]?.paneId }
            val fixSession = terminalOwningSession(allThreads, fixPane)
            // Owning chat session wins; pane title is never the session agent.
            val sessionAgentType = openThread?.agentType ?: fixSession?.agentType
            val sessionDisplayName = openThread?.title ?: fixSession?.title
            TerminalScreen(
                model = id?.let { terminals[it] },
                sessionAgentType = sessionAgentType,
                sessionDisplayName = sessionDisplayName,
                fixCandidates = terminalFixCandidates(
                    state?.acp?.agents?.map { agent -> agent.id.ifBlank { agent.name } }.orEmpty(),
                    allThreads.mapNotNull { it.agentType },
                ),
                unreadCount = unreadChatCount(seenChatIds, conversationIds(openThread)),
                hasPendingDecision = openThread?.needsYou == true,
                onBack = {
                    id?.let { viewModel.stopTerminal(it) }
                    stage = terminalReturn
                },
                onSelectPane = { pane -> id?.let { viewModel.ensureTerminal(it, pane) } },
                onRequestControl = { id?.let { viewModel.requestControl(it) } },
                onReleaseControl = { id?.let { viewModel.releaseControl(it) } },
                onSendText = { text -> id?.let { viewModel.sendTerminalText(it, text) } },
                onSendKey = { key -> id?.let { viewModel.sendTerminalKey(it, key) } },
                connection = selected?.connection,
                onAskFix = { excerpt, choice ->
                    val ws = workspace ?: return@TerminalScreen false
                    val requested = choice?.agentType
                    when (ws.handoffFailure(excerpt, fixPane, requested)) {
                        FailureHandoff.Sent -> true
                        FailureHandoff.Drafted -> {
                            if (terminalFixOpensNewAgent(requested, sessionAgentType)) awaitingAcp = true
                            ws.openThread()?.let { threadId = it.id }
                                ?: fixPane?.let { pane ->
                                    threadId = allThreads.firstOrNull { it.paneId == pane }?.id ?: pane
                                }
                            viewModel.stopTerminal(id)
                            stage = "chat"
                            true
                        }
                        FailureHandoff.Refused -> false
                    }
                },
            )
        }
    }

    LaunchedEffect(pairing.pairedHostId) {
        val id = pairing.pairedHostId ?: return@LaunchedEffect
        startupApplied = true
        hostId = id
        userOpenedPairing = false
        stage = "sessions"
        viewModel.clearPairingDraft()
    }

    if (showNewSession && selected != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showNewSession = false }, sheetState = sheetState) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.new_session), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.new_session_body),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                )
                var cwd by rememberSaveable(selected.id) { mutableStateOf("") }
                OutlinedTextField(
                    value = cwd,
                    onValueChange = { cwd = it },
                    label = { Text(stringResource(R.string.directory)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.directory_hint)) },
                )
                val agents = state?.acp?.agents.orEmpty()
                if (state?.acp?.agentsLoading == true && agents.isEmpty()) {
                    CircularProgressIndicator()
                }
                agents.forEach { agent ->
                    TextButton(
                        onClick = {
                            val workspace = viewModel.workspace(selected.id)
                            workspace.setLaunchAcpCwd(cwd)
                            workspace.setLaunchAcpAgent(agent.id)
                            workspace.launchAcp()
                            awaitingAcp = true
                        },
                        enabled = agent.available && !awaitingAcp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (agent.available) agent.name else stringResource(R.string.agent_unavailable, agent.name))
                    }
                }
                if (agents.isEmpty() && state?.acp?.agentsLoading != true) {
                    Text(stringResource(R.string.no_acp_agents))
                }
                state?.acp?.errorText?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showSettings && selected != null) {
        HostSettingsSheet(
            host = selected,
            pushEnabled = pushEnabled,
            hasPushDistributor = hasPushDistributor,
            onSetPushEnabled = onSetPushEnabled,
            onDismiss = { showSettings = false },
            onSave = { name, addresses, port, user ->
                viewModel.updateConnection(selected.id, name, addresses, port, user)
            },
            onConnect = { viewModel.connect(selected.id) },
            onDisconnect = { viewModel.disconnect(selected.id) },
            onRefresh = { viewModel.refresh(selected.id) },
            onUnpair = { confirmUnpair = true },
        )
    }
    if (confirmUnpair && selected != null) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text(stringResource(R.string.unpair_title, selected.name)) },
            text = { Text(stringResource(R.string.unpair_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.unpair(selected.id)
                    confirmUnpair = false
                    showSettings = false
                }) { Text(stringResource(R.string.unpair)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnpair = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostSettingsSheet(
    host: HostUiModel,
    pushEnabled: Boolean,
    hasPushDistributor: Boolean,
    onSetPushEnabled: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit,
    onUnpair: () -> Unit,
) {
    var name by rememberSaveable(host.id) { mutableStateOf(host.name) }
    var addresses by rememberSaveable(host.id) { mutableStateOf(host.addresses.joinToString("\n")) }
    var port by rememberSaveable(host.id) { mutableStateOf(host.port.toString()) }
    var user by rememberSaveable(host.id) { mutableStateOf(host.user) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(host.name, style = MaterialTheme.typography.headlineSmall)
            Text(host.connectionCopy(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!host.pairingRevoked) {
                host.errorMessage?.let { Text(localizeUiMessage(it), color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(addresses, { addresses = it }, label = { Text(stringResource(R.string.addresses)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(port, { port = it }, label = { Text(stringResource(R.string.ssh_port)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(user, { user = it }, label = { Text(stringResource(R.string.user)) }, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    onSave(name, addresses, port, user)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                shape = RoundedCornerShape(20.dp),
            ) { Text(stringResource(R.string.save_connection)) }
            TextButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.refresh)) }
            if (!host.pairingRevoked) {
                TextButton(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.connect)) }
            }
            TextButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.disconnect)) }
            if (hasPushDistributor) {
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.wake_phone), modifier = Modifier.weight(1f))
                    Switch(checked = pushEnabled, onCheckedChange = onSetPushEnabled)
                }
            }
            TextButton(onClick = onUnpair, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.unpair), color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
