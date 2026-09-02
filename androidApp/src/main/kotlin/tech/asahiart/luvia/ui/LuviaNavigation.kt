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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
private data class TerminalRoute(val hostId: String) : NavKey

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
                            backStack.removeAll { it is HostRoute || it is TerminalRoute || it is PairHostRoute }
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
                            backStack.removeAll { it is HostRoute || it is TerminalRoute || it is PairHostRoute }
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
                    var section by remember(route.id) { mutableStateOf(HostSection.Agents) }
                    val uhp = uhpForHost(route.id)
                    val visible = uhp.visibleSections()
                    LaunchedEffect(route.id) { uhpActions.shown(route.id) }
                    LaunchedEffect(route.id, section) {
                        uhpActions.sectionShown(route.id, section)
                        if (section == HostSection.Terminal) onTerminalShown(route.id)
                    }
                    LaunchedEffect(visible, section) {
                        if (section !in visible) section = HostSection.Agents
                    }
                    HostDetailPane(
                        host = host,
                        section = section,
                        onSection = { next ->
                            section = next
                            if (next == HostSection.Terminal) {
                                backStack.removeAll { it is TerminalRoute }
                                backStack.add(TerminalRoute(route.id))
                            } else {
                                backStack.removeAll { it is TerminalRoute }
                            }
                        },
                        terminal = terminalForHost(route.id),
                        onRequestControl = { onRequestControl(route.id) },
                        onSendText = { text -> onSendTerminalText(route.id, text) },
                        onConnect = { onConnect(route.id) },
                        onDisconnect = { onDisconnect(route.id) },
                        onRefresh = {
                            onRefresh(route.id)
                            uhpActions.refreshSection(route.id, section)
                        },
                        onUnpair = {
                            backStack.removeAll { it is HostRoute && it.id == route.id || it is TerminalRoute && it.hostId == route.id }
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
                                onSendKeys = { keys -> uhpActions.sendKeys(route.id, keys) },
                                onCheckUnconfirmed = { uhpActions.checkAgent(route.id) },
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
                                onSendNotes = { to -> uhpActions.sendNotes(route.id, to) },
                                onCheckUnconfirmed = { uhpActions.checkNotes(route.id) },
                                modifier = modifier,
                            )
                        },
                        tasksContent = { modifier ->
                            TasksSection(
                                host = host,
                                state = uhp,
                                onRefresh = { uhpActions.refreshSection(route.id, HostSection.Tasks) },
                                onAddTask = { title, paths -> uhpActions.addTask(route.id, title, paths) },
                                onCompleteTask = { id -> uhpActions.completeTask(route.id, id) },
                                onCheckUnconfirmed = { uhpActions.checkTasks(route.id) },
                                modifier = modifier,
                            )
                        },
                    )
                }
            }
            entry<TerminalRoute> { route ->
                LaunchedEffect(route.hostId) { onTerminalShown(route.hostId) }
                val terminal = terminalForHost(route.hostId)
                if (terminal == null) {
                    EmptySelectionPane("Terminal unavailable", "Select a live pane to observe or request control.")
                } else {
                    TerminalPane(
                        terminal = terminal,
                        onRequestControl = { onRequestControl(route.hostId) },
                        onSendText = { text -> onSendTerminalText(route.hostId, text) },
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
