package tech.asahiart.luvia

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.asahiart.luvia.ui.ConnectionBadge
import tech.asahiart.luvia.ui.HostUiModel
import tech.asahiart.luvia.ui.TerminalUiModel
import tech.asahiart.luvia.TerminalControl as SharedTerminalControl
import tech.asahiart.luvia.ui.TerminalControl as TerminalControlState

data class PairingUiState(
    val draft: PairingDraft? = null,
    val errorMessage: String? = null,
    val completing: Boolean = false,
)

class LuviaViewModel(
    store: HostStore,
    vault: DeviceKeyVault,
) : ViewModel() {
    private val manager = HostManager(store, vault, viewModelScope)
    private val observeJobs = mutableMapOf<String, Job>()
    private val controls = mutableMapOf<String, SharedTerminalControl>()
    private val identities = mutableMapOf<String, TerminalIdentity>()

    val hosts: StateFlow<List<HostRuntime>> = manager.hosts

    private val _pairing = MutableStateFlow(PairingUiState())
    val pairing: StateFlow<PairingUiState> = _pairing.asStateFlow()

    private val _terminals = MutableStateFlow<Map<String, TerminalUiModel>>(emptyMap())
    val terminals: StateFlow<Map<String, TerminalUiModel>> = _terminals.asStateFlow()

    fun beginPairing(deviceLabel: String, role: HostRole) {
        val label = deviceLabel.trim()
        if (label.isEmpty()) {
            _pairing.update { it.copy(errorMessage = "Enter a device label.") }
            return
        }
        when (val result = manager.beginPairing(label, role)) {
            is Outcome.Ok -> _pairing.value = PairingUiState(draft = result.value)
            is Outcome.Err -> _pairing.update { it.copy(errorMessage = result.failure.toUserMessage()) }
        }
    }

    fun completePairing(rawCode: String, onSuccess: () -> Unit) {
        val draft = _pairing.value.draft ?: return
        if (_pairing.value.completing) return
        viewModelScope.launch {
            _pairing.update { it.copy(completing = true, errorMessage = null) }
            when (val result = manager.completePairing(draft, rawCode.trim())) {
                is Outcome.Ok -> {
                    _pairing.value = PairingUiState()
                    onSuccess()
                }
                is Outcome.Err ->
                    _pairing.update {
                        it.copy(completing = false, errorMessage = result.failure.toUserMessage())
                    }
            }
        }
    }

    fun cancelPairing() {
        _pairing.value = PairingUiState()
    }

    fun connect(hostId: String) {
        manager.connect(hostId)
    }

    fun disconnect(hostId: String) {
        closeTerminal(hostId)
        manager.disconnect(hostId)
    }

    fun refresh(hostId: String) {
        viewModelScope.launch {
            manager.refresh(hostId)
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            manager.hosts.value.forEach { runtime ->
                manager.refresh(runtime.profile.id)
            }
        }
    }

    fun unpair(hostId: String) {
        viewModelScope.launch {
            closeTerminal(hostId)
            manager.unpair(hostId)
        }
    }

    fun ensureTerminal(hostId: String) {
        val runtime = manager.hosts.value.firstOrNull { it.profile.id == hostId } ?: return
        val identity = terminalIdentity(runtime) ?: return
        identities[hostId] = identity
        val observer = runtime.profile.role == HostRole.Observer
        _terminals.update { current ->
            if (current.containsKey(hostId)) {
                current
            } else {
                current + (
                    hostId to TerminalUiModel(
                        title = runtime.snapshot?.sessionName ?: runtime.profile.alias,
                        text = "",
                        isAnsi = false,
                        isTruncated = false,
                        control = TerminalControlState.Observing,
                        canControl = !observer,
                    )
                    )
            }
        }
        if (observeJobs[hostId]?.isActive == true) return
        observeJobs[hostId] = viewModelScope.launch {
            manager.observeTerminal(hostId, identity).collect { update ->
                applyTerminalUpdate(hostId, update)
            }
        }
    }

    fun requestControl(hostId: String) {
        val runtime = manager.hosts.value.firstOrNull { it.profile.id == hostId } ?: return
        if (runtime.profile.role != HostRole.Controller) return
        val identity = identities[hostId] ?: terminalIdentity(runtime) ?: return
        identities[hostId] = identity
        _terminals.update { map ->
            val current = map[hostId] ?: return@update map
            map + (hostId to current.copy(control = TerminalControlState.Requesting))
        }
        viewModelScope.launch {
            when (val result = manager.openTerminal(hostId, identity)) {
                is Outcome.Ok -> {
                    controls.remove(hostId)?.close()
                    controls[hostId] = result.value
                    _terminals.update { map ->
                        val current = map[hostId] ?: return@update map
                        map + (hostId to current.copy(control = TerminalControlState.Controlling))
                    }
                }
                is Outcome.Err -> {
                    val conflict = result.failure is Failure.ControlConflict
                    _terminals.update { map ->
                        val current = map[hostId] ?: return@update map
                        map + (
                            hostId to current.copy(
                                control = if (conflict) {
                                    TerminalControlState.Conflict
                                } else {
                                    TerminalControlState.Observing
                                },
                            )
                            )
                    }
                }
            }
        }
    }

    fun sendTerminalText(hostId: String, text: String) {
        val control = controls[hostId] ?: return
        viewModelScope.launch {
            control.typeLiteral(text)
        }
    }

    override fun onCleared() {
        observeJobs.values.forEach { it.cancel() }
        observeJobs.clear()
        controls.values.forEach { it.close() }
        controls.clear()
        manager.close()
        super.onCleared()
    }

    private fun closeTerminal(hostId: String) {
        observeJobs.remove(hostId)?.cancel()
        controls.remove(hostId)?.close()
        identities.remove(hostId)
        _terminals.update { it - hostId }
    }

    private fun applyTerminalUpdate(hostId: String, update: TerminalUpdate) {
        _terminals.update { map ->
            val current = map[hostId] ?: return@update map
            when (update) {
                is TerminalUpdate.Frame ->
                    map + (
                        hostId to current.copy(
                            text = update.frame.text,
                            isAnsi = update.frame.ansi,
                            isTruncated = update.frame.truncated,
                        )
                        )
                is TerminalUpdate.Failed ->
                    map + (
                        hostId to current.copy(
                            text = current.text.ifEmpty { update.failure.toUserMessage() },
                            control = if (update.failure is Failure.ControlConflict) {
                                TerminalControlState.Conflict
                            } else {
                                current.control
                            },
                        )
                        )
                is TerminalUpdate.Resyncing -> map
            }
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = context.applicationContext
            return LuviaViewModel(
                HostStore(File(app.filesDir, "hosts.json").absolutePath),
                DeviceKeyVault(app),
            ) as T
        }
    }
}

internal fun HostRuntime.toUi(): HostUiModel {
    val agents = snapshot?.agents ?: profile.topology?.agents.orEmpty()
    val taskList = if (snapshot != null) tasks else profile.topology?.tasks.orEmpty()
    val sessionName = when (val current = link) {
        is HostLink.Online -> current.sessionName
        else -> snapshot?.sessionName ?: profile.topology?.sessionName
    }
    val (badge, connected) = when (link) {
        is HostLink.Connecting -> ConnectionBadge.Connecting to true
        is HostLink.Online -> when (freshness) {
            ConnectionFreshness.Live -> ConnectionBadge.Live to true
            ConnectionFreshness.Stale -> ConnectionBadge.Stale to true
            ConnectionFreshness.Offline -> ConnectionBadge.Offline to true
        }
        is HostLink.Failed -> ConnectionBadge.Offline to false
        is HostLink.Idle -> when (freshness) {
            ConnectionFreshness.Stale -> ConnectionBadge.Stale to false
            ConnectionFreshness.Live -> ConnectionBadge.Stale to false
            ConnectionFreshness.Offline -> ConnectionBadge.Offline to false
        }
    }
    return HostUiModel(
        id = profile.id,
        name = profile.alias,
        address = profile.lastConnectedAddress ?: profile.addresses.firstOrNull().orEmpty(),
        sessionName = sessionName,
        connection = badge,
        workingAgents = agents.count { it.status == AgentStatus.Working },
        blockedAgents = agents.count { it.status == AgentStatus.Blocked },
        completedAgents = agents.count { it.status == AgentStatus.Done },
        activeTask = taskList.firstOrNull { !it.status.equals("done", ignoreCase = true) }?.title,
        updatedAt = profile.lastUpdatedEpochMs.takeIf { it > 0 }?.toString(),
        errorMessage = (link as? HostLink.Failed)?.failure?.toUserMessage(),
        isObserver = profile.role == HostRole.Observer,
        connected = connected,
    )
}

internal fun Failure.toUserMessage(): String {
    if (this is Failure.ProtocolError &&
        reason.contains("pairing code is for a different device key")
    ) {
        return "This pairing code is for a different device key. Run the command shown in this app on the host, then scan the QR it prints — not a code generated for another phone."
    }
    return when (this) {
        is Failure.ProtocolError -> reason
        is Failure.InvalidRequest -> message
        is Failure.InvalidParams -> message
        is Failure.Forbidden -> message
        is Failure.NotFound -> message
        is Failure.Transport -> reason
        is Failure.Bridge -> reason
        is Failure.Remote -> message
        is Failure.Frame -> reason
        is Failure.Closed -> "Not connected to this host."
        is Failure.ControlConflict -> message
        is Failure.StaleServer -> message
        is Failure.StaleRoute -> message
        is Failure.TerminalGone -> message
        is Failure.ResyncRequired -> message
        is Failure.RevisionConflict -> message
        is Failure.FrameTooLarge -> message
        is Failure.ServerBusy -> message
        is Failure.UnknownMajor -> "The host speaks an unsupported protocol ($name $major)."
        is Failure.CapabilityMissing -> "The host does not support $method."
        is Failure.IndeterminateMutation -> "The host may already have applied this change. Do not retry automatically."
    }
}

private fun terminalIdentity(runtime: HostRuntime): TerminalIdentity? {
    val snapshot = runtime.snapshot ?: return null
    val pane = snapshot.panes.firstOrNull { !it.terminalId.isNullOrBlank() } ?: return null
    val terminalId = pane.terminalId ?: return null
    return TerminalIdentity(
        serverGeneration = snapshot.serverGeneration,
        terminalId = terminalId,
        paneId = pane.paneId,
    )
}
