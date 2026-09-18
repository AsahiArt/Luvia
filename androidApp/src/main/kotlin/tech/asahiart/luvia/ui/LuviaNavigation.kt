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
import tech.asahiart.luvia.HostRole
import tech.asahiart.luvia.PairingUiState
import tech.asahiart.luvia.TerminalKey
import tech.asahiart.luvia.HostUhp
import tech.asahiart.luvia.HostSection

@Serializable
private data object HostsRoute : NavKey

@Serializable
private data class HostRoute(val id: String) : NavKey

@Serializable
private data object PairHostRoute : NavKey

@Composable
fun LuviaNavigation(
    hosts: List<HostUiModel>,
    terminalForHost: (String) -> TerminalUiModel?,
    workspace: (String) -> HostUhp,
    onRefreshSection: (String, HostSection) -> Unit,
    pairing: PairingUiState,
    openHostId: String? = null,
    openFirstBlocked: Boolean = false,
    onBeginPairing: (String, HostRole) -> Unit,
    onCompletePairing: (raw: String, host: String, port: String, user: String, onSuccess: () -> Unit) -> Unit,
    onCancelPairing: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    onRefresh: (String) -> Unit,
    onRefreshAll: () -> Unit,
    onUnpair: (String) -> Unit,
    onUpdateConnection: (hostId: String, alias: String, hosts: String, port: String, username: String) -> Unit = { _, _, _, _, _ -> },
    onRequestControl: (String) -> Unit,
    onSendTerminalText: (String, String) -> Unit,
    onSendTerminalKey: (String, TerminalKey) -> Unit = { _, _ -> },
    onTerminalShown: (String) -> Unit,
    onSelectTerminalPane: (String, String) -> Unit = { _, _ -> },
    pushEnabled: Boolean = false,
    hasPushDistributor: Boolean = true,
    onSetPushEnabled: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val backStack = rememberNavBackStack(HostsRoute)
    LaunchedEffect(openHostId) {
        val id = openHostId ?: return@LaunchedEffect
        backStack.removeAll { it is PairHostRoute }
        backStack.removeAll { it is HostRoute }
        backStack.add(HostRoute(id))
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val twoPane = maxWidth >= 600.dp
        if (twoPane) {
            Row(Modifier.fillMaxSize()) {
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
                    modifier = Modifier.width(320.dp).fillMaxHeight(),
                )
                VerticalDivider()
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    DetailNav(
                        backStack = backStack,
                        hosts = hosts,
                        terminalForHost = terminalForHost,
                        workspace = workspace,
                        onRefreshSection = onRefreshSection,
                        pairing = pairing,
                        openFirstBlocked = openFirstBlocked,
                        onBeginPairing = onBeginPairing,
                        onCompletePairing = onCompletePairing,
                        onCancelPairing = onCancelPairing,
                        onConnect = onConnect,
                        onDisconnect = onDisconnect,
                        onRefresh = onRefresh,
                        onRefreshAll = onRefreshAll,
                        onUnpair = onUnpair,
                        onUpdateConnection = onUpdateConnection,
                        onRequestControl = onRequestControl,
                        onSendTerminalText = onSendTerminalText,
                        onSendTerminalKey = onSendTerminalKey,
                        onTerminalShown = onTerminalShown,
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
                terminalForHost = terminalForHost,
                workspace = workspace,
                onRefreshSection = onRefreshSection,
                pairing = pairing,
                openFirstBlocked = openFirstBlocked,
                onBeginPairing = onBeginPairing,
                onCompletePairing = onCompletePairing,
                onCancelPairing = onCancelPairing,
                onConnect = onConnect,
                onDisconnect = onDisconnect,
                onRefresh = onRefresh,
                onRefreshAll = onRefreshAll,
                onUnpair = onUnpair,
                onUpdateConnection = onUpdateConnection,
                onRequestControl = onRequestControl,
                onSendTerminalText = onSendTerminalText,
                onSendTerminalKey = onSendTerminalKey,
                onTerminalShown = onTerminalShown,
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
    terminalForHost: (String) -> TerminalUiModel?,
    workspace: (String) -> HostUhp,
    onRefreshSection: (String, HostSection) -> Unit,
    pairing: PairingUiState,
    openFirstBlocked: Boolean,
    onBeginPairing: (String, HostRole) -> Unit,
    onCompletePairing: (raw: String, host: String, port: String, user: String, onSuccess: () -> Unit) -> Unit,
    onCancelPairing: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    onRefresh: (String) -> Unit,
    onRefreshAll: () -> Unit,
    onUnpair: (String) -> Unit,
    onUpdateConnection: (hostId: String, alias: String, hosts: String, port: String, username: String) -> Unit,
    onRequestControl: (String) -> Unit,
    onSendTerminalText: (String, String) -> Unit,
    onSendTerminalKey: (String, TerminalKey) -> Unit,
    onTerminalShown: (String) -> Unit,
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
            backStack.removeAll { it is PairHostRoute || it is HostRoute }
            backStack.add(HostRoute(id))
            onCancelPairing()
        }
    }
    NavDisplay(
        modifier = Modifier.fillMaxSize(),
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<HostsRoute> {
                if (showList) {
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
                } else {
                    EmptySelectionPane("Select a host", "Choose a paired host to inspect its sessions.")
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
                    LaunchedEffect(route.id) { surface.shown() }
                    LaunchedEffect(route.id, section) {
                        surface.show(section)
                    }
                    LaunchedEffect(visible, section) {
                        if (section !in visible) surface.setSection(HostSection.Agents)
                    }
                    LaunchedEffect(route.id, openFirstBlocked, host.firstBlockedPaneId, uhp.agents) {
                        if (!openFirstBlocked) return@LaunchedEffect
                        val pane = host.firstBlockedPaneId
                            ?: uhp.agents.firstOrNull { it.status == AgentStatus.Blocked }?.paneId
                            ?: return@LaunchedEffect
                        surface.setSection(HostSection.Agents)
                        surface.openAgent(pane)
                    }
                    HostDetailPane(
                        host = host,
                        section = section,
                        onSection = { next ->
                            surface.setSection(next)
                        },
                        terminal = terminalForHost(route.id),
                        onRequestControl = { onRequestControl(route.id) },
                        onSendText = { text -> onSendTerminalText(route.id, text) },
                        onSendKey = { key -> onSendTerminalKey(route.id, key) },
                        onSelectTerminalPane = { pane -> onSelectTerminalPane(route.id, pane) },
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
                        agentsContent = { modifier ->
                            AgentsSection(
                                host = host,
                                state = uhp,
                                onRefresh = { onRefreshSection(route.id, HostSection.Agents) },
                                onOpenAgent = { pane ->
                                    surface.openAgent(pane)
                                    onSelectTerminalPane(route.id, pane)
                                },
                                onCloseAgent = { surface.closeAgent() },
                                onPrompt = { text -> surface.promptAgent(text) },
                                onDraftChange = { text -> surface.setAgentDraft(text) },
                                onSendKeys = { keys -> surface.sendAgentKeys(keys) },
                                onCheckUnconfirmed = { surface.checkAgent() },
                                onResumeSession = { sessionId -> surface.resumeAgent(sessionId) },
                                onShowNameChange = { show -> surface.setShowNameAgent(show) },
                                onNameDraftChange = { text -> surface.setNameAgentDraft(text) },
                                onNameAgent = { surface.nameAgent() },
                                onShowForkChange = { show -> surface.setShowForkAgent(show) },
                                onForkDraftChange = { text -> surface.setForkAgentDraft(text) },
                                onForkAgent = { surface.forkAgent() },
                                onLoadAcpAgents = { surface.loadAcpAgents() },
                                onShowLaunchAcp = { show -> surface.setShowLaunchAcp(show) },
                                onSelectAcpAgent = { id -> surface.setLaunchAcpAgent(id) },
                                onAcpCwdChange = { cwd -> surface.setLaunchAcpCwd(cwd) },
                                onLaunchAcp = { surface.launchAcp() },
                                onAcpDraftChange = { text -> surface.setAcpDraft(text) },
                                onPromptAcp = { surface.promptAcp() },
                                onAnswerAcpPermission = { optionId -> surface.answerAcpPermission(optionId) },
                                onCancelAcp = { surface.cancelAcp() },
                                onCloseAcp = { surface.closeAcp() },
                                terminal = terminalForHost(route.id),
                                onRequestControl = { onRequestControl(route.id) },
                                onSendTerminalText = { text -> onSendTerminalText(route.id, text) },
                                onSendTerminalKey = { key -> onSendTerminalKey(route.id, key) },
                                onObserveTerminal = { pane -> onSelectTerminalPane(route.id, pane) },
                                onOpenProjectReview = {
                                    surface.setSection(HostSection.Review)
                                    surface.show(HostSection.Review)
                                },
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
