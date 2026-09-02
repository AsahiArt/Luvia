package tech.asahiart.luvia

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import tech.asahiart.luvia.ui.HostUhpUiState
import tech.asahiart.luvia.ui.LuviaNavigation
import tech.asahiart.luvia.ui.UhpHostActions

@Composable
fun LuviaApp() {
    val context = LocalContext.current
    val viewModel: LuviaViewModel = viewModel(
        factory = remember(context) { LuviaViewModel.Factory(context.applicationContext) },
    )
    val runtimes by viewModel.hosts.collectAsStateWithLifecycle()
    val pairing by viewModel.pairing.collectAsStateWithLifecycle()
    val terminals by viewModel.terminals.collectAsStateWithLifecycle()
    val uhp by viewModel.uhp.collectAsStateWithLifecycle()
    val notifications = remember { StatusNotificationController(context) }
    val hosts = runtimes.map { it.toUi() }
    val uhpActions = remember(viewModel) {
        UhpHostActions(
            shown = viewModel::ensureUhp,
            sectionShown = viewModel::showSection,
            refreshSection = viewModel::refreshSection,
            openAgent = viewModel::openAgent,
            closeAgent = viewModel::closeAgent,
            promptAgent = viewModel::promptAgent,
            setSection = viewModel::setSection,
            setAgentDraft = viewModel::setAgentDraft,
            sendKeys = viewModel::sendAgentKeys,
            checkAgent = viewModel::checkAgent,
            openDiffFile = viewModel::openDiffFile,
            closeDiffFile = viewModel::closeDiffFile,
            addNote = viewModel::addReviewNote,
            resolveNote = viewModel::resolveReviewNote,
            reopenNote = viewModel::reopenReviewNote,
            removeNote = viewModel::removeReviewNote,
            sendNotes = viewModel::sendReviewNotes,
            checkNotes = viewModel::checkNotes,
            setNoteDraft = viewModel::setNoteDraft,
            setSendTarget = viewModel::setSendTarget,
            addTask = viewModel::addTask,
            completeTask = viewModel::completeTask,
            checkTasks = viewModel::checkTasks,
            setShowAddTask = viewModel::setShowAddTask,
            setCompleteTaskId = viewModel::setCompleteTaskId,
            setAddTaskDraft = viewModel::setAddTaskDraft,
        )
    }

    LaunchedEffect(runtimes) {
        val online = runtimes.firstOrNull { it.link is HostLink.Online }
        if (online == null) {
            notifications.dismiss()
        } else {
            val agents = online.snapshot?.agents ?: online.profile.topology?.agents.orEmpty()
            val sessionName = (online.link as HostLink.Online).sessionName
                ?: online.snapshot?.sessionName
                ?: "session"
            notifications.show(
                AmbientStatus(
                    hostName = online.profile.alias,
                    sessionName = sessionName,
                    connection = online.freshness.name,
                    workingAgents = agents.count { it.status == AgentStatus.Working },
                    blockedAgents = agents.count { it.status == AgentStatus.Blocked },
                    completedAgents = agents.count { it.status == AgentStatus.Done },
                    sensitiveSnippet = null,
                    isStale = online.freshness == ConnectionFreshness.Stale,
                ),
                allowSensitiveSnippet = false,
            )
        }
    }
    DisposableEffect(notifications) {
        onDispose { notifications.dismiss() }
    }

    LuviaNavigation(
        hosts = hosts,
        terminalForHost = { id -> terminals[id] },
        uhpForHost = { id -> uhp[id] ?: HostUhpUiState() },
        uhpActions = uhpActions,
        pairing = pairing,
        onBeginPairing = viewModel::beginPairing,
        onCompletePairing = { raw, onSuccess -> viewModel.completePairing(raw, onSuccess) },
        onCancelPairing = viewModel::cancelPairing,
        onConnect = viewModel::connect,
        onDisconnect = viewModel::disconnect,
        onRefresh = viewModel::refresh,
        onRefreshAll = viewModel::refreshAll,
        onUnpair = viewModel::unpair,
        onRequestControl = viewModel::requestControl,
        onSendTerminalText = viewModel::sendTerminalText,
        onTerminalShown = viewModel::ensureTerminal,
    )
}
