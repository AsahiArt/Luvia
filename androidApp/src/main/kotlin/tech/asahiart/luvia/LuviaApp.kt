package tech.asahiart.luvia

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import tech.asahiart.luvia.ui.LuviaNavigation

@Composable
fun LuviaApp() {
    val context = LocalContext.current
    val viewModel: LuviaViewModel = viewModel(
        factory = remember(context) { LuviaViewModel.Factory(context.applicationContext) },
    )
    val runtimes by viewModel.hosts.collectAsStateWithLifecycle()
    val pairing by viewModel.pairing.collectAsStateWithLifecycle()
    val terminals by viewModel.terminals.collectAsStateWithLifecycle()
    val notifications = remember { StatusNotificationController(context) }
    val hosts = runtimes.map { it.toUi() }

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
