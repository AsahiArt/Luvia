package tech.asahiart.luvia.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable
import tech.asahiart.luvia.AgentStatus
import tech.asahiart.luvia.AgentKey
import tech.asahiart.luvia.HostRole
import tech.asahiart.luvia.PairingUiState
import tech.asahiart.luvia.TerminalKey
import tech.asahiart.luvia.HostUhp
import tech.asahiart.luvia.ui.ConnectionBadge
import tech.asahiart.luvia.AgentKind
import tech.asahiart.luvia.HostSection
import tech.asahiart.luvia.HostUhpState
import tech.asahiart.luvia.thread.AgentThread
import tech.asahiart.luvia.thread.AskOption
import tech.asahiart.luvia.thread.NowState

@Serializable
private data object NowRoute : NavKey

@Serializable
private data object HostsRoute : NavKey

@Serializable
private data class HostRoute(val id: String) : NavKey

@Serializable
private data object PairHostRoute : NavKey

@Serializable
private data class AgentRoute(val hostId: String, val paneId: String) : NavKey

@Serializable
private data class AcpRoute(val hostId: String) : NavKey


@Composable
fun LuviaNavigation(
    hosts: List<HostUiModel>,
    now: NowState,
    hostStates: Map<String, HostUhpState>,
    onAnswerNow: (AgentThread, AskOption) -> Unit,
    onMarkViewed: (AgentThread) -> Unit,
    terminalForHost: (String) -> TerminalUiModel?,
    workspace: (String) -> HostUhp,
    onRefreshSection: (String, HostSection) -> Unit,
    pairing: PairingUiState,
    openHostId: String? = null,
    openFirstBlocked: Boolean = false,
    onBeginPairing: (String, HostRole) -> Unit,
    onCompletePairing: (raw: String, host: String, port: String, user: String, onSuccess: () -> Unit) -> Unit,
    onCancelPairing: () -> Unit,
    onClearPairingDraft: () -> Unit = {},
    onClearPairingError: () -> Unit = {},
    onConnect: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    onRefresh: (String) -> Unit,
    onRefreshAll: () -> Unit,
    onUnpair: (String) -> Unit,
    onUpdateConnection: (hostId: String, alias: String, hosts: String, port: String, username: String) -> Unit = { _, _, _, _, _ -> },
    onRequestControl: (String) -> Unit,
    onSendTerminalText: (String, String) -> Unit,
    onSendTerminalKey: (String, TerminalKey) -> Unit = { _, _ -> },
    onStopTerminal: (String) -> Unit = {},
    onSelectTerminalPane: (String, String) -> Unit = { _, _ -> },
    pushEnabled: Boolean = false,
    hasPushDistributor: Boolean = true,
    onSetPushEnabled: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val backStack = rememberNavBackStack(NowRoute)
    // Now is live across Hosts: connect each paired Host once per launch, never again after a manual disconnect.
    val autoConnected = remember { mutableSetOf<String>() }
    LaunchedEffect(hosts.map { it.id }) {
        hosts.forEach { host ->
            if (autoConnected.add(host.id) &&
                host.connection != ConnectionBadge.Live &&
                host.connection != ConnectionBadge.Connecting
            ) {
                onConnect(host.id)
            }
        }
    }
    LaunchedEffect(openHostId) {
        val id = openHostId ?: return@LaunchedEffect
        backStack.removeAll { it is PairHostRoute }
        backStack.removeAll { it is HostRoute }
        backStack.add(HostRoute(id))
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val twoPane = maxWidth >= 600.dp && maxHeight >= 600.dp
        if (twoPane) {
            Row(Modifier.fillMaxSize()) {
                HomePane(
                    now = now,
                    hosts = hosts,
                    states = hostStates,
                    onOpenProject = { hostId, ws -> openProject(backStack, workspace, hostId, ws) },
                    onShowProjects = {
                        hosts.filter { it.connection == ConnectionBadge.Live }.forEach { workspace(it.id).loadProjects() }
                    },
                    onOpenThread = { thread -> openThread(backStack, workspace, onMarkViewed, thread) },
                    onAnswer = onAnswerNow,
                    onOpenHosts = {
                        backStack.removeAll { it !is NowRoute }
                        backStack.add(HostsRoute)
                    },
                    onAddHost = {
                        backStack.removeAll { it is PairHostRoute }
                        backStack.add(PairHostRoute)
                    },
                    onRefreshAll = onRefreshAll,
                    modifier = Modifier.width(360.dp).fillMaxHeight(),
                )
                VerticalDivider()
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    DetailNav(
                        backStack = backStack,
                        hosts = hosts,
                        now = now,
                        hostStates = hostStates,
                        onAnswerNow = onAnswerNow,
                        onMarkViewed = onMarkViewed,
                        terminalForHost = terminalForHost,
                        workspace = workspace,
                        onRefreshSection = onRefreshSection,
                        pairing = pairing,
                        openFirstBlocked = openFirstBlocked,
                        onBeginPairing = onBeginPairing,
                        onCompletePairing = onCompletePairing,
                        onCancelPairing = onCancelPairing,
                        onClearPairingDraft = onClearPairingDraft,
                        onClearPairingError = onClearPairingError,
                        onConnect = onConnect,
                        onDisconnect = onDisconnect,
                        onRefresh = onRefresh,
                        onRefreshAll = onRefreshAll,
                        onUnpair = onUnpair,
                        onUpdateConnection = onUpdateConnection,
                        onRequestControl = onRequestControl,
                        onSendTerminalText = onSendTerminalText,
                        onSendTerminalKey = onSendTerminalKey,
                        onStopTerminal = onStopTerminal,
                        onSelectTerminalPane = onSelectTerminalPane,
                        pushEnabled = pushEnabled,
                        hasPushDistributor = hasPushDistributor,
                        onSetPushEnabled = onSetPushEnabled,
                        showList = false,
                    )
                }
            }
        } else {
            DetailNav(
                backStack = backStack,
                hosts = hosts,
                now = now,
                hostStates = hostStates,
                onAnswerNow = onAnswerNow,
                onMarkViewed = onMarkViewed,
                terminalForHost = terminalForHost,
                workspace = workspace,
                onRefreshSection = onRefreshSection,
                pairing = pairing,
                openFirstBlocked = openFirstBlocked,
                onBeginPairing = onBeginPairing,
                onCompletePairing = onCompletePairing,
                onCancelPairing = onCancelPairing,
                onClearPairingDraft = onClearPairingDraft,
                onClearPairingError = onClearPairingError,
                onConnect = onConnect,
                onDisconnect = onDisconnect,
                onRefresh = onRefresh,
                onRefreshAll = onRefreshAll,
                onUnpair = onUnpair,
                onUpdateConnection = onUpdateConnection,
                onRequestControl = onRequestControl,
                onSendTerminalText = onSendTerminalText,
                onSendTerminalKey = onSendTerminalKey,
                onStopTerminal = onStopTerminal,
                onSelectTerminalPane = onSelectTerminalPane,
                pushEnabled = pushEnabled,
                hasPushDistributor = hasPushDistributor,
                onSetPushEnabled = onSetPushEnabled,
                showList = true,
            )
        }
    }
}

@Composable
private fun DetailNav(
    backStack: NavBackStack<NavKey>,
    hosts: List<HostUiModel>,
    now: NowState,
    hostStates: Map<String, HostUhpState>,
    onAnswerNow: (AgentThread, AskOption) -> Unit,
    onMarkViewed: (AgentThread) -> Unit,
    terminalForHost: (String) -> TerminalUiModel?,
    workspace: (String) -> HostUhp,
    onRefreshSection: (String, HostSection) -> Unit,
    pairing: PairingUiState,
    openFirstBlocked: Boolean,
    onBeginPairing: (String, HostRole) -> Unit,
    onCompletePairing: (raw: String, host: String, port: String, user: String, onSuccess: () -> Unit) -> Unit,
    onCancelPairing: () -> Unit,
    onClearPairingDraft: () -> Unit,
    onClearPairingError: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    onRefresh: (String) -> Unit,
    onRefreshAll: () -> Unit,
    onUnpair: (String) -> Unit,
    onUpdateConnection: (hostId: String, alias: String, hosts: String, port: String, username: String) -> Unit,
    onRequestControl: (String) -> Unit,
    onSendTerminalText: (String, String) -> Unit,
    onSendTerminalKey: (String, TerminalKey) -> Unit,
    onStopTerminal: (String) -> Unit,
    onSelectTerminalPane: (String, String) -> Unit,
    pushEnabled: Boolean,
    hasPushDistributor: Boolean,
    onSetPushEnabled: (Boolean) -> Unit,
    showList: Boolean,
) {
    val context = LocalContext.current
    LaunchedEffect(pairing.pairedHostId, hosts) {
        val id = pairing.pairedHostId ?: return@LaunchedEffect
        val host = hosts.firstOrNull { it.id == id } ?: return@LaunchedEffect
        if (host.hasSnapshot || host.errorMessage != null) {
            backStack.removeAll { it !is NowRoute }
            onCancelPairing()
        }
    }
    NavDisplay(
        modifier = Modifier.fillMaxSize(),
        backStack = backStack,
        onBack = {
            when (val last = backStack.lastOrNull()) {
                is AcpRoute -> {
                    workspace(last.hostId).hideAcp()
                    backStack.removeLastOrNull()
                }
                is AgentRoute -> {
                    workspace(last.hostId).closeAgent()
                    backStack.removeLastOrNull()
                }
                else -> backStack.removeLastOrNull()
            }
        },
        entryProvider = entryProvider {
            entry<NowRoute> {
                if (showList) {
                    HomePane(
                        now = now,
                        hosts = hosts,
                        states = hostStates,
                        onOpenProject = { hostId, ws -> openProject(backStack, workspace, hostId, ws) },
                    onShowProjects = {
                        hosts.filter { it.connection == ConnectionBadge.Live }.forEach { workspace(it.id).loadProjects() }
                    },
                        onOpenThread = { thread -> openThread(backStack, workspace, onMarkViewed, thread) },
                        onAnswer = onAnswerNow,
                        onOpenHosts = { backStack.add(HostsRoute) },
                        onAddHost = {
                            backStack.removeAll { it is PairHostRoute }
                            backStack.add(PairHostRoute)
                        },
                        onRefreshAll = onRefreshAll,
                    )
                } else {
                    EmptySelectionPane("Nothing selected", "Pick an Agent on the left.")
                }
            }
            entry<HostsRoute> {
                run {
                    HostListPane(
                        hosts = hosts,
                        selectedHostId = (backStack.lastOrNull { it is HostRoute } as? HostRoute)?.id,
                        onSelect = { id ->
                            backStack.removeAll { it is HostRoute || it is PairHostRoute }
                            backStack.add(HostRoute(id))
                        },
                        onAddHost = {
                            backStack.removeAll { it is PairHostRoute }
                            backStack.add(PairHostRoute)
                        },
                        onConnect = onConnect,
                        onDisconnect = onDisconnect,
                        onRefreshAll = onRefreshAll,
                    )
                }
            }
            entry<HostRoute> { route ->
                val host = hosts.firstOrNull { it.id == route.id }
                if (host == null) {
                    EmptySelectionPane("Host unavailable", "The saved host was removed.")
                } else {
                    val surface = workspace(route.id)
                    val uhp by surface.state.collectAsStateWithLifecycle()
                    val section = uhp.section
                    val visible = uhp.visibleSections()
                    LaunchedEffect(route.id) {
                        surface.shown()
                        if (host.connection != ConnectionBadge.Live &&
                            host.connection != ConnectionBadge.Connecting
                        ) {
                            onConnect(route.id)
                        }
                    }
                    LaunchedEffect(route.id, section) {
                        surface.show(section)
                    }
                    LaunchedEffect(visible, section) {
                        if (section !in visible) surface.setSection(HostSection.Agents)
                    }
                    LaunchedEffect(uhp.acp.viewing, uhp.acp.open) {
                        if (uhp.acp.open && uhp.acp.viewing && backStack.none { it is AcpRoute }) {
                            backStack.add(AcpRoute(route.id))
                        }
                    }
                    LaunchedEffect(route.id, openFirstBlocked, host.firstBlockedPaneId, uhp.agents) {
                        if (!openFirstBlocked) return@LaunchedEffect
                        val pane = host.firstBlockedPaneId
                            ?: uhp.agents.firstOrNull { it.status == AgentStatus.Blocked }?.paneId
                            ?: return@LaunchedEffect
                        surface.setSection(HostSection.Agents)
                        surface.openAgent(pane)
                        if (backStack.none { it is AgentRoute }) {
                            backStack.add(AgentRoute(route.id, pane))
                        }
                    }
                    HostDetailPane(
                        host = host,
                        section = section,
                        onSection = { next ->
                            surface.setSection(next)
                        },
                        onConnect = { onConnect(route.id) },
                        onDisconnect = { onDisconnect(route.id) },
                        onRefresh = {
                            onRefresh(route.id)
                            onRefreshSection(route.id, section)
                        },
                        onUnpair = {
                            backStack.removeAll { it is HostRoute && it.id == route.id }
                            onUnpair(route.id)
                        },
                        onUpdateConnection = { alias, hosts, port, username ->
                            onUpdateConnection(route.id, alias, hosts, port, username)
                        },
                        pushCapable = uhp.capabilities.push,
                        pushEnabled = pushEnabled,
                        hasPushDistributor = hasPushDistributor,
                        onSetPushEnabled = onSetPushEnabled,
                        sections = visible,
                        state = uhp,
                        attentionCount = uhp.attentionCount(),
                        onSelectWorkspace = { id -> surface.setSelectedWorkspace(id) },
                        agentsContent = { modifier ->
                            AgentsSection(
                                host = host,
                                state = uhp.selectedWorkspaceId?.let { id ->
                                    uhp.copy(agents = uhp.agents.filter { it.workspaceId == null || it.workspaceId == id })
                                } ?: uhp,
                                onRefresh = { onRefreshSection(route.id, HostSection.Agents) },
                                onOpenAgent = { pane ->
                                    surface.openAgent(pane)
                                    backStack.removeAll { it is AgentRoute || it is AcpRoute }
                                    backStack.add(AgentRoute(route.id, pane))
                                },
                                onOpenAcp = {
                                    surface.viewAcp()
                                    backStack.removeAll { it is AgentRoute || it is AcpRoute }
                                    backStack.add(AcpRoute(route.id))
                                },
                                onCheckUnconfirmed = { surface.checkAgent() },
                                onResumeSession = { sessionId -> surface.resumeAgent(sessionId) },
                                onLoadAcpAgents = { surface.loadAcpAgents() },
                                onShowLaunchAcp = { show -> surface.setShowLaunchAcp(show) },
                                onSelectAcpAgent = { id -> surface.setLaunchAcpAgent(id) },
                                onAcpCwdChange = { cwd -> surface.setLaunchAcpCwd(cwd) },
                                onLaunchAcp = { surface.launchAcp() },
                                modifier = modifier,
                            )
                        },
                        filesContent = { modifier ->
                            FilesSection(
                                host = host,
                                state = uhp,
                                onRefresh = { surface.refreshFiles() },
                                onOpenFile = { path -> surface.openFile(path) },
                                onRevealFile = { path -> surface.revealFile(path) },
                                modifier = modifier,
                            )
                        },
                        searchContent = { modifier ->
                            SearchSection(
                                host = host,
                                state = uhp,
                                onRefresh = { onRefreshSection(route.id, HostSection.Search) },
                                onQueryChange = { query -> surface.setSearchQuery(query) },
                                onSearch = { surface.querySearch() },
                                onActivate = { matchId -> surface.activateSearch(matchId) },
                                modifier = modifier,
                            )
                        },
                        reviewContent = { modifier ->
                            ReviewSection(
                                host = host,
                                state = uhp,
                                onRefresh = { onRefreshSection(route.id, HostSection.Review) },
                                onOpenFile = { path, layer -> surface.openDiffFile(path, layer) },
                                onCloseFile = { surface.closeDiffFile() },
                                onAddNote = { file, line, body, layer ->
                                    surface.addReviewNote(file, line, body, layer)
                                },
                                onResolveNote = { id -> surface.resolveReviewNote(id) },
                                onReopenNote = { id -> surface.reopenReviewNote(id) },
                                onRemoveNote = { id -> surface.removeReviewNote(id) },
                                onNoteDraftChange = { text -> surface.setNoteDraft(text) },
                                onSendTargetChange = { pane -> surface.setSendTarget(pane) },
                                onSendNotes = { to -> surface.sendReviewNotes(to) },
                                onCheckUnconfirmed = { surface.checkNotes() },
                                onSelectWorkspace = { id -> surface.setSelectedWorkspace(id) },
                                modifier = modifier,
                            )
                        },
                        worktreesContent = { modifier ->
                            WorktreesSection(
                                host = host,
                                state = uhp,
                                onRefresh = { onRefreshSection(route.id, HostSection.Worktrees) },
                                onShowCreateChange = { show -> surface.setShowCreateWorktree(show) },
                                onCreateBranchChange = { branch -> surface.setCreateWorktreeBranch(branch) },
                                onCreate = { surface.createWorktree() },
                                onOpen = { path -> surface.openWorktree(path) },
                                onRemoveIdChange = { path -> surface.setRemoveWorktreePath(path) },
                                onRemove = { path -> surface.removeWorktree(path) },
                                modifier = modifier,
                            )
                        },
                        automationsContent = { modifier ->
                            AutomationsSection(
                                host = host,
                                state = uhp,
                                onRefresh = { onRefreshSection(route.id, HostSection.Automations) },
                                onEnable = { id -> surface.enableAutomation(id) },
                                onDisable = { id -> surface.disableAutomation(id) },
                                onRun = { id -> surface.runAutomation(id) },
                                onCreate = { draft -> surface.createAutomation(draft) },
                                onUpdate = { id, draft -> surface.updateAutomation(id, draft) },
                                onDelete = { id -> surface.deleteAutomation(id) },
                                onRebind = { id, pane, terminalId -> surface.rebindAutomation(id, pane, terminalId) },
                                onLoadHistory = { id -> surface.loadAutomationHistory(id) },
                                onPreview = { trigger -> surface.previewAutomation(trigger) },
                                onClearPreview = { surface.clearAutomationPreview() },
                                modifier = modifier,
                            )
                        },
                        tasksContent = { modifier ->
                            TasksSection(
                                host = host,
                                state = uhp,
                                onRefresh = { onRefreshSection(route.id, HostSection.Tasks) },
                                onAddTask = { title, paths -> surface.addTask(title, paths) },
                                onShowAddChange = { show -> surface.setShowAddTask(show) },
                                onCompleteIdChange = { id -> surface.setCompleteTaskId(id) },
                                onDeleteIdChange = { id -> surface.setDeleteTaskId(id) },
                                onAddDraftChange = { title, paths -> surface.setAddTaskDraft(title, paths) },
                                onCompleteTask = { id -> surface.completeTask(id) },
                                onClaimTask = { id -> surface.claimTask(id) },
                                onDeleteTask = { id -> surface.deleteTask(id) },
                                onRetryTask = { id -> surface.retryTask(id) },
                                onCheckUnconfirmed = { surface.checkTasks() },
                                onSelectWorkspace = { id -> surface.setSelectedWorkspace(id) },
                                modifier = modifier,
                            )
                        },
                        layoutContent = { modifier ->
                            LayoutSection(
                                host = host,
                                state = uhp,
                                onRefresh = { onRefreshSection(route.id, HostSection.Layout) },
                                onFocusWorkspace = { index -> surface.focusWorkspace(index) },
                                onCloseWorkspaceChange = { index -> surface.setCloseWorkspace(index) },
                                onCloseWorkspace = { index -> surface.closeWorkspace(index) },
                                onFocusPane = { pane -> surface.focusPane(pane) },
                                onClosePaneChange = { pane -> surface.setClosePane(pane) },
                                onClosePane = { pane -> surface.closePane(pane) },
                                onRenamePaneChange = { pane, draft -> surface.setRenamePane(pane, draft) },
                                onRenamePane = { surface.renamePane() },
                                modifier = modifier,
                            )
                        },
                    )
                }
            }
            entry<AgentRoute> { route ->
                val host = hosts.firstOrNull { it.id == route.hostId }
                if (host == null) {
                    EmptySelectionPane("Host unavailable", "The saved host was removed.")
                } else {
                    val surface = workspace(route.hostId)
                    val uhp by surface.state.collectAsStateWithLifecycle()
                    val live = host.connection == ConnectionBadge.Live
                    LaunchedEffect(route.hostId) {
                        if (!live && host.connection != ConnectionBadge.Connecting) onConnect(route.hostId)
                    }
                    LaunchedEffect(route.hostId, route.paneId, live) {
                        surface.openAgent(route.paneId)
                    }
                    val thread = surface.openThread()
                    ThreadPane(
                        thread = thread,
                        timeline = thread?.let(surface::timeline).orEmpty(),
                        state = uhp,
                        draft = uhp.agentDetail.draft,
                        terminal = terminalForHost(route.hostId),
                        actions = ThreadActions(
                            onBack = {
                                surface.closeAgent()
                                backStack.removeLastOrNull()
                            },
                            onDraftChange = surface::setAgentDraft,
                            onSendDraft = { surface.promptAgent(uhp.agentDetail.draft) },
                            onSendCommand = surface::sendToThread,
                            onAnswer = surface::answer,
                            onKey = surface::sendKeyBar,
                            onCheckUnconfirmed = surface::checkAgent,
                            onOpenProject = {
                                uhp.projectWorkspaceId()?.let { surface.setSelectedWorkspace(it) }
                                surface.closeAgent()
                                backStack.removeLastOrNull()
                                surface.setSection(HostSection.Review)
                                surface.show(HostSection.Review)
                            },
                            onShowName = { surface.setShowNameAgent(true) },
                            onShowFork = { surface.setShowForkAgent(true) },
                            onObserveTerminal = { pane -> onSelectTerminalPane(route.hostId, pane) },
                            onStopObserve = { onStopTerminal(route.hostId) },
                            onRequestControl = { onRequestControl(route.hostId) },
                            onSendTerminalText = { text -> onSendTerminalText(route.hostId, text) },
                            onSendTerminalKey = { key -> onSendTerminalKey(route.hostId, key) },
                        ),
                    )
                    AgentNameDialogs(
                        state = uhp,
                        onNameDraft = surface::setNameAgentDraft,
                        onName = surface::nameAgent,
                        onDismissName = { surface.setShowNameAgent(false) },
                        onForkDraft = surface::setForkAgentDraft,
                        onFork = surface::forkAgent,
                        onDismissFork = { surface.setShowForkAgent(false) },
                    )
                }
            }
            entry<AcpRoute> { route ->
                val host = hosts.firstOrNull { it.id == route.hostId }
                if (host == null) {
                    EmptySelectionPane("Host unavailable", "The saved host was removed.")
                } else {
                    val surface = workspace(route.hostId)
                    val uhp by surface.state.collectAsStateWithLifecycle()
                    LaunchedEffect(route.hostId) {
                        if (uhp.acp.open) surface.viewAcp()
                    }
                    val thread = surface.openThread()?.takeIf { it.kind == AgentKind.Acp }
                    ThreadPane(
                        thread = thread,
                        timeline = thread?.let(surface::timeline).orEmpty(),
                        state = uhp,
                        draft = uhp.acp.draft,
                        terminal = null,
                        actions = ThreadActions(
                            onBack = {
                                surface.hideAcp()
                                backStack.removeLastOrNull()
                            },
                            onDraftChange = surface::setAcpDraft,
                            onSendDraft = surface::promptAcp,
                            onSendCommand = surface::sendToThread,
                            onAnswer = surface::answer,
                            onKey = surface::sendKeyBar,
                            onCheckUnconfirmed = {},
                            onCancelTurn = surface::cancelAcp,
                            onEndSession = {
                                surface.closeAcp()
                                backStack.removeLastOrNull()
                            },
                        ),
                    )
                }
            }
            entry<PairHostRoute> {
                PairHostPane(
                    command = pairing.draft?.command,
                    authorizedKeysLine = pairing.draft?.authorizedKeysLine,
                    fingerprint = pairing.draft?.deviceKeyFingerprint,
                    errorMessage = pairing.errorMessage,
                    completing = pairing.completing,
                    pairedHostId = pairing.pairedHostId,
                    onBegin = onBeginPairing,
                    onCopyCommand = { command ->
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("luvia pair command", command))
                    },
                    onComplete = { raw, host, port, user ->
                        onCompletePairing(raw, host, port, user) { }
                    },
                    onClearDraft = onClearPairingDraft,
                    onClearError = onClearPairingError,
                    onCancel = {
                        onCancelPairing()
                        backStack.removeLastOrNull()
                    },
                )
            }
        },
    )
}

@Composable
private fun EmptySelectionPane(title: String, message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun openThread(
    backStack: NavBackStack<NavKey>,
    workspace: (String) -> HostUhp,
    onMarkViewed: (AgentThread) -> Unit,
    thread: AgentThread,
) {
    onMarkViewed(thread)
    val surface = workspace(thread.hostId)
    backStack.removeAll { it !is NowRoute }
    backStack.add(HostRoute(thread.hostId))
    val paneId = thread.paneId
    if (thread.kind == AgentKind.Acp) {
        surface.viewAcp()
        backStack.add(AcpRoute(thread.hostId))
    } else if (paneId != null) {
        surface.openAgent(paneId)
        backStack.add(AgentRoute(thread.hostId, paneId))
    }
}

private fun openProject(
    backStack: NavBackStack<NavKey>,
    workspace: (String) -> HostUhp,
    hostId: String,
    workspaceId: String?,
) {
    val surface = workspace(hostId)
    if (workspaceId != null) surface.setSelectedWorkspace(workspaceId)
    surface.setSection(HostSection.Agents)
    backStack.removeAll { it !is NowRoute }
    backStack.add(HostRoute(hostId))
}
