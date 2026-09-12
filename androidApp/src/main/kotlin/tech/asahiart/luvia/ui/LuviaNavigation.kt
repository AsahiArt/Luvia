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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable
import tech.asahiart.luvia.HostRole
import tech.asahiart.luvia.PairingUiState

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
    uhpForHost: (String) -> HostUhpUiState,
    uhpActions: UhpHostActions,
    pairing: PairingUiState,
    onBeginPairing: (String, HostRole) -> Unit,
    onCompletePairing: (raw: String, onSuccess: () -> Unit) -> Unit,
    onCancelPairing: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    onRefresh: (String) -> Unit,
    onRefreshAll: () -> Unit,
    onUnpair: (String) -> Unit,
    onRequestControl: (String) -> Unit,
    onSendTerminalText: (String, String) -> Unit,
    onTerminalShown: (String) -> Unit,
    onSelectTerminalPane: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    MaterialTheme {
        val backStack = rememberNavBackStack(HostsRoute)
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
                            uhpForHost = uhpForHost,
                            uhpActions = uhpActions,
                            pairing = pairing,
                            onBeginPairing = onBeginPairing,
                            onCompletePairing = onCompletePairing,
                            onCancelPairing = onCancelPairing,
                            onConnect = onConnect,
                            onDisconnect = onDisconnect,
                            onRefresh = onRefresh,
                            onRefreshAll = onRefreshAll,
                            onUnpair = onUnpair,
                            onRequestControl = onRequestControl,
                            onSendTerminalText = onSendTerminalText,
                            onTerminalShown = onTerminalShown,
                            onSelectTerminalPane = onSelectTerminalPane,
                            showList = false,
                        )
                    }
                }
            } else {
                DetailNav(
                    backStack = backStack,
                    hosts = hosts,
                    terminalForHost = terminalForHost,
                    uhpForHost = uhpForHost,
                    uhpActions = uhpActions,
                    pairing = pairing,
                    onBeginPairing = onBeginPairing,
                    onCompletePairing = onCompletePairing,
                    onCancelPairing = onCancelPairing,
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                    onRefresh = onRefresh,
                    onRefreshAll = onRefreshAll,
                    onUnpair = onUnpair,
                    onRequestControl = onRequestControl,
                    onSendTerminalText = onSendTerminalText,
                    onTerminalShown = onTerminalShown,
                    onSelectTerminalPane = onSelectTerminalPane,
                    showList = true,
                )
            }
        }
    }
}

@Composable
private fun DetailNav(
    backStack: NavBackStack<NavKey>,
    hosts: List<HostUiModel>,
    terminalForHost: (String) -> TerminalUiModel?,
    uhpForHost: (String) -> HostUhpUiState,
    uhpActions: UhpHostActions,
    pairing: PairingUiState,
    onBeginPairing: (String, HostRole) -> Unit,
    onCompletePairing: (raw: String, onSuccess: () -> Unit) -> Unit,
    onCancelPairing: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    onRefresh: (String) -> Unit,
    onRefreshAll: () -> Unit,
    onUnpair: (String) -> Unit,
    onRequestControl: (String) -> Unit,
    onSendTerminalText: (String, String) -> Unit,
    onTerminalShown: (String) -> Unit,
    onSelectTerminalPane: (String, String) -> Unit,
    showList: Boolean,
) {
    val context = LocalContext.current
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
                    val uhp = uhpForHost(route.id)
                    val section = uhp.section
                    val visible = uhp.visibleSections()
                    LaunchedEffect(route.id) { uhpActions.shown(route.id) }
                    LaunchedEffect(route.id, section) {
                        uhpActions.sectionShown(route.id, section)
                        if (section == HostSection.Terminal) onTerminalShown(route.id)
                    }
                    LaunchedEffect(visible, section) {
                        if (section !in visible) uhpActions.setSection(route.id, HostSection.Agents)
                    }
                    HostDetailPane(
                        host = host,
                        section = section,
                        onSection = { next ->
                            uhpActions.setSection(route.id, next)
                        },
                        terminal = terminalForHost(route.id),
                        onRequestControl = { onRequestControl(route.id) },
                        onSendText = { text -> onSendTerminalText(route.id, text) },
                        onSelectTerminalPane = { pane -> onSelectTerminalPane(route.id, pane) },
                        onConnect = { onConnect(route.id) },
                        onDisconnect = { onDisconnect(route.id) },
                        onRefresh = {
                            onRefresh(route.id)
                            uhpActions.refreshSection(route.id, section)
                        },
                        onUnpair = {
                            backStack.removeAll { it is HostRoute && it.id == route.id }
                            onUnpair(route.id)
                        },
                        sections = visible,
                        agentsContent = { modifier ->
                            AgentsSection(
                                host = host,
                                state = uhp,
                                onRefresh = { uhpActions.refreshSection(route.id, HostSection.Agents) },
                                onOpenAgent = { pane -> uhpActions.openAgent(route.id, pane) },
                                onCloseAgent = { uhpActions.closeAgent(route.id) },
                                onPrompt = { text -> uhpActions.promptAgent(route.id, text) },
                                onDraftChange = { text -> uhpActions.setAgentDraft(route.id, text) },
                                onSendKeys = { keys -> uhpActions.sendKeys(route.id, keys) },
                                onCheckUnconfirmed = { uhpActions.checkAgent(route.id) },
                                onResumeSession = { sessionId -> uhpActions.resumeAgent(route.id, sessionId) },
                                onShowNameChange = { show -> uhpActions.setShowNameAgent(route.id, show) },
                                onNameDraftChange = { text -> uhpActions.setNameAgentDraft(route.id, text) },
                                onNameAgent = { uhpActions.nameAgent(route.id) },
                                onShowForkChange = { show -> uhpActions.setShowForkAgent(route.id, show) },
                                onForkDraftChange = { text -> uhpActions.setForkAgentDraft(route.id, text) },
                                onForkAgent = { uhpActions.forkAgent(route.id) },
                                modifier = modifier,
                            )
                        },
                        filesContent = { modifier ->
                            FilesSection(
                                host = host,
                                state = uhp,
                                onRefresh = { uhpActions.refreshFiles(route.id) },
                                onOpenFile = { path -> uhpActions.openFile(route.id, path) },
                                onRevealFile = { path -> uhpActions.revealFile(route.id, path) },
                                modifier = modifier,
                            )
                        },
                        searchContent = { modifier ->
                            SearchSection(
                                host = host,
                                state = uhp,
                                onRefresh = { uhpActions.refreshSection(route.id, HostSection.Search) },
                                onQueryChange = { query -> uhpActions.setSearchQuery(route.id, query) },
                                onSearch = { uhpActions.querySearch(route.id) },
                                onActivate = { matchId -> uhpActions.activateSearch(route.id, matchId) },
                                modifier = modifier,
                            )
                        },
                        reviewContent = { modifier ->
                            ReviewSection(
                                host = host,
                                state = uhp,
                                onRefresh = { uhpActions.refreshSection(route.id, HostSection.Review) },
                                onOpenFile = { path, layer -> uhpActions.openDiffFile(route.id, path, layer) },
                                onCloseFile = { uhpActions.closeDiffFile(route.id) },
                                onAddNote = { file, line, body, layer ->
                                    uhpActions.addNote(route.id, file, line, body, layer)
                                },
                                onResolveNote = { id -> uhpActions.resolveNote(route.id, id) },
                                onReopenNote = { id -> uhpActions.reopenNote(route.id, id) },
                                onRemoveNote = { id -> uhpActions.removeNote(route.id, id) },
                                onNoteDraftChange = { text -> uhpActions.setNoteDraft(route.id, text) },
                                onSendTargetChange = { pane -> uhpActions.setSendTarget(route.id, pane) },
                                onSendNotes = { to -> uhpActions.sendNotes(route.id, to) },
                                onCheckUnconfirmed = { uhpActions.checkNotes(route.id) },
                                modifier = modifier,
                            )
                        },
                        worktreesContent = { modifier ->
                            WorktreesSection(
                                host = host,
                                state = uhp,
                                onRefresh = { uhpActions.refreshSection(route.id, HostSection.Worktrees) },
                                onShowCreateChange = { show -> uhpActions.setShowCreateWorktree(route.id, show) },
                                onCreateBranchChange = { branch -> uhpActions.setCreateWorktreeBranch(route.id, branch) },
                                onCreate = { uhpActions.createWorktree(route.id) },
                                onOpen = { path -> uhpActions.openWorktree(route.id, path) },
                                onRemoveIdChange = { path -> uhpActions.setRemoveWorktreePath(route.id, path) },
                                onRemove = { path -> uhpActions.removeWorktree(route.id, path) },
                                modifier = modifier,
                            )
                        },
                        automationsContent = { modifier ->
                            AutomationsSection(
                                host = host,
                                state = uhp,
                                onRefresh = { uhpActions.refreshSection(route.id, HostSection.Automations) },
                                onEnable = { id -> uhpActions.enableAutomation(route.id, id) },
                                onDisable = { id -> uhpActions.disableAutomation(route.id, id) },
                                onRun = { id -> uhpActions.runAutomation(route.id, id) },
                                modifier = modifier,
                            )
                        },
                        tasksContent = { modifier ->
                            TasksSection(
                                host = host,
                                state = uhp,
                                onRefresh = { uhpActions.refreshSection(route.id, HostSection.Tasks) },
                                onAddTask = { title, paths -> uhpActions.addTask(route.id, title, paths) },
                                onShowAddChange = { show -> uhpActions.setShowAddTask(route.id, show) },
                                onCompleteIdChange = { id -> uhpActions.setCompleteTaskId(route.id, id) },
                                onDeleteIdChange = { id -> uhpActions.setDeleteTaskId(route.id, id) },
                                onAddDraftChange = { title, paths -> uhpActions.setAddTaskDraft(route.id, title, paths) },
                                onCompleteTask = { id -> uhpActions.completeTask(route.id, id) },
                                onClaimTask = { id -> uhpActions.claimTask(route.id, id) },
                                onDeleteTask = { id -> uhpActions.deleteTask(route.id, id) },
                                onCheckUnconfirmed = { uhpActions.checkTasks(route.id) },
                                modifier = modifier,
                            )
                        },
                        layoutContent = { modifier ->
                            LayoutSection(
                                host = host,
                                state = uhp,
                                onRefresh = { uhpActions.refreshSection(route.id, HostSection.Layout) },
                                onFocusWorkspace = { index -> uhpActions.focusWorkspace(route.id, index) },
                                onCloseWorkspaceChange = { index -> uhpActions.setCloseWorkspace(route.id, index) },
                                onCloseWorkspace = { index -> uhpActions.closeWorkspace(route.id, index) },
                                onFocusPane = { pane -> uhpActions.focusPane(route.id, pane) },
                                onClosePaneChange = { pane -> uhpActions.setClosePane(route.id, pane) },
                                onClosePane = { pane -> uhpActions.closePane(route.id, pane) },
                                onRenamePaneChange = { pane, draft -> uhpActions.setRenamePane(route.id, pane, draft) },
                                onRenamePane = { uhpActions.renamePane(route.id) },
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
                    onBegin = onBeginPairing,
                    onCopyCommand = { command ->
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("luvia pair command", command))
                    },
                    onComplete = { raw ->
                        onCompletePairing(raw) {
                            backStack.removeAll { it is PairHostRoute }
                        }
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
