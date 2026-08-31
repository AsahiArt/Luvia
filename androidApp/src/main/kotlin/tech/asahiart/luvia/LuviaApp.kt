package tech.asahiart.luvia

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.io.File
import kotlinx.coroutines.launch
import tech.asahiart.luvia.ui.ConnectionBadge
import tech.asahiart.luvia.ui.HostUiModel
import tech.asahiart.luvia.ui.LuviaNavigation
import tech.asahiart.luvia.ui.TerminalControl
import tech.asahiart.luvia.ui.TerminalUiModel

@Composable
fun LuviaApp() {
    val context = LocalContext.current
    val store = remember {
        HostStore(File(context.filesDir, "hosts.json").absolutePath)
    }
    val keys = remember { DeviceKeyStore(context) }
    val scope = rememberCoroutineScope()
    val catalog by produceState(HostCatalog(emptyList(), 0L), store) {
        store.catalog.collect { value = it }
    }
    var terminals by remember { mutableStateOf<Map<String, TerminalUiModel>>(emptyMap()) }

    LuviaNavigation(
        hosts = catalog.hosts.map { it.toUi() },
        terminalForHost = { id -> terminals[id] },
        onPairHost = { host, user, port, fingerprint ->
            scope.launch { pairHost(store, keys, host, user, port, fingerprint) }
        },
        onRequestControl = { hostId ->
            terminals = terminals + (hostId to (
                terminals[hostId]?.copy(control = TerminalControl.Requesting)
                    ?: return@LuviaNavigation
                ))
        },
        onSendTerminalText = { _, _ -> },
    )
}

private suspend fun pairHost(
    store: HostStore,
    keys: DeviceKeyStore,
    address: String,
    user: String,
    port: Int,
    fingerprint: String,
) {
    val generated = when (val result = DeviceKeys.generate()) {
        is Outcome.Ok -> result.value
        is Outcome.Err -> return
    }
    val id = generated.identity.fingerprint
    keys.save(id, generated.privateKeyOpenssh)
    store.upsert(
        HostProfile(
            id = id,
            alias = address.substringBefore('.').ifBlank { address },
            address = address,
            sshPort = port,
            username = user,
            hostKeySha256 = fingerprint,
            lastStatus = HostStatus.Unknown,
            lastUpdatedEpochMs = System.currentTimeMillis(),
            topology = null,
        ),
    )
}

private fun HostProfile.toUi(): HostUiModel =
    HostUiModel(
        id = id,
        name = alias,
        address = address,
        sessionName = topology?.sessionName,
        connection = when (lastStatus) {
            HostStatus.Reachable -> ConnectionBadge.Live
            HostStatus.Unreachable -> ConnectionBadge.Offline
            HostStatus.Stale -> ConnectionBadge.Stale
            HostStatus.Unknown -> ConnectionBadge.Stale
        },
        workingAgents = topology?.agents.orEmpty().count { it.status == AgentStatus.Working },
        blockedAgents = topology?.agents.orEmpty().count { it.status == AgentStatus.Blocked },
        completedAgents = topology?.agents.orEmpty().count { it.status == AgentStatus.Done },
        activeTask = topology?.tasks?.firstOrNull { it.status != "done" }?.title,
        updatedAt = lastUpdatedEpochMs.takeIf { it > 0 }?.toString(),
    )
