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
import tech.asahiart.luvia.ui.TerminalPaneChoice
import tech.asahiart.luvia.ui.TerminalUiModel
import tech.asahiart.luvia.TerminalControl as SharedTerminalControl
import tech.asahiart.luvia.ui.TerminalControl as TerminalControlState

data class PairingUiState(
    val draft: PairingDraft? = null,
    val errorMessage: String? = null,
    val completing: Boolean = false,
    val pairedHostId: String? = null,
)

class LuviaViewModel(
    store: HostStore,
    vault: DeviceKeyVault,
) : ViewModel() {
    private val manager = HostManager(store, vault, viewModelScope)
    private val uhpRegistry = HostUhpRegistry(manager, viewModelScope)
    private val observeJobs = mutableMapOf<String, Job>()
    private val controls = mutableMapOf<String, SharedTerminalControl>()
    private val identities = mutableMapOf<String, TerminalIdentity>()
    private val triedTerminals = mutableMapOf<String, MutableSet<String>>()

    val hosts: StateFlow<List<HostRuntime>> = manager.hosts

    private val _pairing = MutableStateFlow(PairingUiState())
    val pairing: StateFlow<PairingUiState> = _pairing.asStateFlow()

    private val _terminals = MutableStateFlow<Map<String, TerminalUiModel>>(emptyMap())
    val terminals: StateFlow<Map<String, TerminalUiModel>> = _terminals.asStateFlow()

    val uhp: StateFlow<Map<String, HostUhpState>> = uhpRegistry.states

    fun beginPairing(deviceLabel: String, role: HostRole) {
        val label = deviceLabel.trim()
        if (label.isEmpty()) {
            _pairing.update { it.copy(errorMessage = "Enter a device label.") }
            return
        }
        when (val result = manager.beginPairing(label, role)) {
            is Outcome.Ok -> _pairing.value = PairingUiState(draft = result.value)
            is Outcome.Err -> _pairing.update { it.copy(errorMessage = result.failure.pairingMessage()) }
        }
    }

    fun completePairing(rawCode: String, onSuccess: () -> Unit) {
        val draft = _pairing.value.draft ?: return
        if (_pairing.value.completing) return
        viewModelScope.launch {
            _pairing.update { it.copy(completing = true, errorMessage = null) }
            when (val result = manager.completePairing(draft, rawCode.trim())) {
                is Outcome.Ok -> {
                    _pairing.value = PairingUiState(pairedHostId = result.value.id)
                    onSuccess()
                }
                is Outcome.Err ->
                    _pairing.update {
                        it.copy(completing = false, errorMessage = result.failure.pairingMessage())
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
        uhpRegistry.workspace(hostId).shown()
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

    fun ensureTerminal(hostId: String, paneId: String? = null) {
        val runtime = manager.hosts.value.firstOrNull { it.profile.id == hostId } ?: return
        val observer = runtime.profile.role == HostRole.Observer
        val panes = terminalPaneChoices(runtime)
        val identity = terminalIdentity(runtime, paneId)
        if (identity == null) {
            observeJobs.remove(hostId)?.cancel()
            identities.remove(hostId)
            _terminals.update {
                it + (
                    hostId to TerminalUiModel(
                        title = runtime.snapshot?.sessionName ?: runtime.profile.alias,
                        text = "",
                        isAnsi = false,
                        isTruncated = false,
                        control = TerminalControlState.Observing,
                        canControl = !observer,
                        errorText = "No live pane to observe. Focus a pane on the Host, then refresh.",
                        panes = panes,
                    )
                    )
            }
            return
        }
        val currentIdentity = identities[hostId]
        val jobActive = observeJobs[hostId]?.isActive == true
        if (paneId == null && currentIdentity == identity && jobActive) {
            _terminals.update { map ->
                val current = map[hostId] ?: return@update map
                map + (hostId to current.copy(panes = panes, paneId = identity.paneId))
            }
            return
        }
        if (paneId != null) {
            triedTerminals[hostId] = mutableSetOf(identity.terminalId)
        }
        startTerminalObserve(hostId, runtime, identity, observer, panes)
    }

    private fun startTerminalObserve(
        hostId: String,
        runtime: HostRuntime,
        identity: TerminalIdentity,
        observer: Boolean,
        panes: List<TerminalPaneChoice>,
    ) {
        observeJobs.remove(hostId)?.cancel()
        identities[hostId] = identity
        val title = panes.firstOrNull { it.paneId == identity.paneId }?.title
            ?: runtime.snapshot?.sessionName
            ?: runtime.profile.alias
        _terminals.update { current ->
            val previous = current[hostId]
            current + (
                hostId to TerminalUiModel(
                    title = title,
                    text = if (previous?.paneId == identity.paneId) previous.text else "",
                    isAnsi = if (previous?.paneId == identity.paneId) previous.isAnsi else false,
                    isTruncated = if (previous?.paneId == identity.paneId) previous.isTruncated else false,
                    control = TerminalControlState.Observing,
                    canControl = !observer,
                    errorText = null,
                    paneId = identity.paneId,
                    panes = panes,
                )
                )
        }
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
                    val conflict = result.failure as? Failure.ControlConflict
                    _terminals.update { map ->
                        val current = map[hostId] ?: return@update map
                        map + (
                            hostId to current.copy(
                                control = if (conflict != null) {
                                    TerminalControlState.Conflict
                                } else {
                                    TerminalControlState.Observing
                                },
                                conflictMessage = conflict?.conflictMessage(),
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

    fun sendTerminalKey(hostId: String, key: TerminalKey) {
        val control = controls[hostId] ?: return
        viewModelScope.launch {
            control.sendKey(key)
        }
    }

    fun ensureUhp(hostId: String) = uhpRegistry.workspace(hostId).shown()

    fun showSection(hostId: String, section: HostSection) = uhpRegistry.workspace(hostId).show(section)

    fun refreshSection(hostId: String, section: HostSection) = uhpRegistry.refreshSection(hostId, section)

    fun setSection(hostId: String, section: HostSection) = uhpRegistry.workspace(hostId).setSection(section)

    fun openAgent(hostId: String, paneId: String) = uhpRegistry.workspace(hostId).openAgent(paneId)

    fun closeAgent(hostId: String) = uhpRegistry.workspace(hostId).closeAgent()

    fun setAgentDraft(hostId: String, text: String) = uhpRegistry.workspace(hostId).setAgentDraft(text)

    fun promptAgent(hostId: String, text: String) = uhpRegistry.workspace(hostId).promptAgent(text)

    fun sendAgentKeys(hostId: String, keys: List<AgentKey>) = uhpRegistry.workspace(hostId).sendAgentKeys(keys)

    fun checkAgent(hostId: String) = uhpRegistry.workspace(hostId).checkAgent()

    fun resumeAgent(hostId: String, sessionId: String) = uhpRegistry.workspace(hostId).resumeAgent(sessionId)

    fun setShowNameAgent(hostId: String, show: Boolean) = uhpRegistry.workspace(hostId).setShowNameAgent(show)

    fun setNameAgentDraft(hostId: String, text: String) = uhpRegistry.workspace(hostId).setNameAgentDraft(text)

    fun nameAgent(hostId: String) = uhpRegistry.workspace(hostId).nameAgent()

    fun setShowForkAgent(hostId: String, show: Boolean) = uhpRegistry.workspace(hostId).setShowForkAgent(show)

    fun setForkAgentDraft(hostId: String, text: String) = uhpRegistry.workspace(hostId).setForkAgentDraft(text)

    fun forkAgent(hostId: String) = uhpRegistry.workspace(hostId).forkAgent()

    fun openDiffFile(hostId: String, path: String, layer: DiffLayer?) =
        uhpRegistry.workspace(hostId).openDiffFile(path, layer)

    fun closeDiffFile(hostId: String) = uhpRegistry.workspace(hostId).closeDiffFile()

    fun addReviewNote(hostId: String, file: String, line: ReviewLine, body: String, layer: DiffLayer?) =
        uhpRegistry.workspace(hostId).addReviewNote(file, line, body, layer)

    fun resolveReviewNote(hostId: String, id: String) = uhpRegistry.workspace(hostId).resolveReviewNote(id)

    fun reopenReviewNote(hostId: String, id: String) = uhpRegistry.workspace(hostId).reopenReviewNote(id)

    fun removeReviewNote(hostId: String, id: String) = uhpRegistry.workspace(hostId).removeReviewNote(id)

    fun sendReviewNotes(hostId: String, to: String) = uhpRegistry.workspace(hostId).sendReviewNotes(to)

    fun checkNotes(hostId: String) = uhpRegistry.workspace(hostId).checkNotes()

    fun setNoteDraft(hostId: String, text: String) = uhpRegistry.workspace(hostId).setNoteDraft(text)

    fun setSendTarget(hostId: String, paneId: String?) = uhpRegistry.workspace(hostId).setSendTarget(paneId)

    fun addTask(hostId: String, title: String, paths: List<String>) =
        uhpRegistry.workspace(hostId).addTask(title, paths)

    fun completeTask(hostId: String, taskId: String) = uhpRegistry.workspace(hostId).completeTask(taskId)

    fun claimTask(hostId: String, taskId: String) = uhpRegistry.workspace(hostId).claimTask(taskId)

    fun deleteTask(hostId: String, taskId: String) = uhpRegistry.workspace(hostId).deleteTask(taskId)

    fun checkTasks(hostId: String) = uhpRegistry.workspace(hostId).checkTasks()

    fun setShowAddTask(hostId: String, show: Boolean) = uhpRegistry.workspace(hostId).setShowAddTask(show)

    fun setCompleteTaskId(hostId: String, id: String?) = uhpRegistry.workspace(hostId).setCompleteTaskId(id)

    fun setDeleteTaskId(hostId: String, id: String?) = uhpRegistry.workspace(hostId).setDeleteTaskId(id)

    fun setAddTaskDraft(hostId: String, title: String, paths: String) =
        uhpRegistry.workspace(hostId).setAddTaskDraft(title, paths)

    fun refreshFiles(hostId: String) = uhpRegistry.workspace(hostId).refreshFiles()

    fun openFile(hostId: String, path: String) = uhpRegistry.workspace(hostId).openFile(path)

    fun revealFile(hostId: String, path: String) = uhpRegistry.workspace(hostId).revealFile(path)

    fun setSearchQuery(hostId: String, query: String) = uhpRegistry.workspace(hostId).setSearchQuery(query)

    fun querySearch(hostId: String) = uhpRegistry.workspace(hostId).querySearch()

    fun activateSearch(hostId: String, matchId: String) = uhpRegistry.workspace(hostId).activateSearch(matchId)

    fun setShowCreateWorktree(hostId: String, show: Boolean) =
        uhpRegistry.workspace(hostId).setShowCreateWorktree(show)

    fun setCreateWorktreeBranch(hostId: String, branch: String) =
        uhpRegistry.workspace(hostId).setCreateWorktreeBranch(branch)

    fun createWorktree(hostId: String) = uhpRegistry.workspace(hostId).createWorktree()

    fun openWorktree(hostId: String, path: String) = uhpRegistry.workspace(hostId).openWorktree(path)

    fun setRemoveWorktreePath(hostId: String, path: String?) =
        uhpRegistry.workspace(hostId).setRemoveWorktreePath(path)

    fun removeWorktree(hostId: String, path: String) = uhpRegistry.workspace(hostId).removeWorktree(path)

    fun enableAutomation(hostId: String, id: String) = uhpRegistry.workspace(hostId).enableAutomation(id)

    fun disableAutomation(hostId: String, id: String) = uhpRegistry.workspace(hostId).disableAutomation(id)

    fun runAutomation(hostId: String, id: String) = uhpRegistry.workspace(hostId).runAutomation(id)

    fun focusWorkspace(hostId: String, index: Int) = uhpRegistry.workspace(hostId).focusWorkspace(index)

    fun setCloseWorkspace(hostId: String, index: Int?) = uhpRegistry.workspace(hostId).setCloseWorkspace(index)

    fun closeWorkspace(hostId: String, index: Int) = uhpRegistry.workspace(hostId).closeWorkspace(index)

    fun focusPane(hostId: String, pane: String) = uhpRegistry.workspace(hostId).focusPane(pane)

    fun setClosePane(hostId: String, pane: String?) = uhpRegistry.workspace(hostId).setClosePane(pane)

    fun closePane(hostId: String, pane: String) = uhpRegistry.workspace(hostId).closePane(pane)

    fun setRenamePane(hostId: String, pane: String?, draft: String) =
        uhpRegistry.workspace(hostId).setRenamePane(pane, draft)

    fun renamePane(hostId: String) = uhpRegistry.workspace(hostId).renamePane()

    override fun onCleared() {
        observeJobs.values.forEach { it.cancel() }
        observeJobs.clear()
        controls.values.forEach { it.close() }
        controls.clear()
        uhpRegistry.close()
        manager.close()
        super.onCleared()
    }

    private fun closeTerminal(hostId: String) {
        observeJobs.remove(hostId)?.cancel()
        controls.remove(hostId)?.close()
        identities.remove(hostId)
        triedTerminals.remove(hostId)
        _terminals.update { it - hostId }
    }

    private fun applyTerminalUpdate(hostId: String, update: TerminalUpdate) {
        when (update) {
            is TerminalUpdate.Frame -> {
                triedTerminals.remove(hostId)
                _terminals.update { map ->
                    val current = map[hostId] ?: return@update map
                    map + (
                        hostId to current.copy(
                            text = update.frame.text,
                            isAnsi = update.frame.ansi,
                            isTruncated = update.frame.truncated,
                            errorText = null,
                        )
                        )
                }
            }
            is TerminalUpdate.Failed -> {
                val conflict = update.failure as? Failure.ControlConflict
                if (conflict != null) {
                    _terminals.update { map ->
                        val current = map[hostId] ?: return@update map
                        map + (
                            hostId to current.copy(
                                control = TerminalControlState.Conflict,
                                conflictMessage = conflict.conflictMessage(),
                            )
                            )
                    }
                    return
                }
                if (isGoneTerminal(update.failure)) {
                    rebindTerminal(hostId, update.failure)
                    return
                }
                _terminals.update { map ->
                    val current = map[hostId] ?: return@update map
                    map + (
                        hostId to current.copy(
                            errorText = update.failure.userMessage(),
                            control = TerminalControlState.Observing,
                        )
                        )
                }
            }
            is TerminalUpdate.Resyncing -> Unit
        }
    }

    private fun rebindTerminal(hostId: String, failure: Failure) {
        val runtime = manager.hosts.value.firstOrNull { it.profile.id == hostId }
        val observer = runtime?.profile?.role == HostRole.Observer
        val panes = runtime?.let { terminalPaneChoices(it) }.orEmpty()
        val tried = triedTerminals.getOrPut(hostId) { mutableSetOf() }
        identities[hostId]?.terminalId?.let { tried += it }
        val next = runtime?.let { terminalIdentities(it).firstOrNull { identity -> identity.terminalId !in tried } }
        if (runtime != null && next != null) {
            tried += next.terminalId
            startTerminalObserve(hostId, runtime, next, observer, panes)
            return
        }
        observeJobs.remove(hostId)?.cancel()
        identities.remove(hostId)
        _terminals.update { map ->
            val current = map[hostId]
            map + (
                hostId to (
                    current ?: TerminalUiModel(
                        title = runtime?.snapshot?.sessionName ?: runtime?.profile?.alias ?: "Terminal",
                        text = "",
                        isAnsi = false,
                        isTruncated = false,
                        control = TerminalControlState.Observing,
                        canControl = !observer,
                    )
                    ).copy(
                    text = current?.text.orEmpty(),
                    errorText = failure.userMessage(),
                    panes = panes,
                    canControl = !observer,
                    control = TerminalControlState.Observing,
                )
                )
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
        lastUpdatedEpochMs = profile.lastUpdatedEpochMs,
        errorMessage = (link as? HostLink.Failed)?.failure?.connectMessage(),
        isObserver = profile.role == HostRole.Observer,
        connected = connected,
        hasSnapshot = snapshot != null,
        firstBlockedPaneId = agents.firstOrNull { it.status == AgentStatus.Blocked }?.paneId,
    )
}

internal fun List<HostRuntime>.toSortedUi(): List<HostUiModel> =
    mapIndexed { index, runtime -> index to runtime.toUi() }
        .sortedWith(
            compareBy<Pair<Int, HostUiModel>> { (_, host) ->
                when {
                    host.blockedAgents > 0 -> 0
                    host.connection == ConnectionBadge.Live -> 1
                    else -> 2
                }
            }.thenBy { it.first },
        )
        .map { it.second }

private fun isGoneTerminal(failure: Failure): Boolean =
    failure is Failure.TerminalGone ||
        failure is Failure.StaleRoute ||
        failure is Failure.StaleServer ||
        failure.userMessage().contains("no longer available", ignoreCase = true)

private fun terminalIdentity(runtime: HostRuntime, paneId: String? = null): TerminalIdentity? {
    val identities = terminalIdentities(runtime)
    return if (paneId != null) identities.firstOrNull { it.paneId == paneId } else identities.firstOrNull()
}

private fun terminalIdentities(runtime: HostRuntime): List<TerminalIdentity> {
    val snapshot = runtime.snapshot ?: return emptyList()
    val panes = snapshot.panes.filter { !it.terminalId.isNullOrBlank() }
    val ordered = panes.filter { it.focused } + panes.filter { !it.focused }
    return ordered.mapNotNull { pane ->
        val terminalId = pane.terminalId ?: return@mapNotNull null
        TerminalIdentity(
            serverGeneration = snapshot.serverGeneration,
            terminalId = terminalId,
            paneId = pane.paneId,
        )
    }
}

private fun terminalPaneChoices(runtime: HostRuntime): List<TerminalPaneChoice> {
    val snapshot = runtime.snapshot ?: return emptyList()
    val agents = snapshot.agents.associateBy { it.paneId }
    return snapshot.panes.filter { !it.terminalId.isNullOrBlank() }.map { pane ->
        val agent = agents[pane.paneId]
        TerminalPaneChoice(
            paneId = pane.paneId,
            title = listOfNotNull(
                agent?.name?.takeIf { it.isNotBlank() },
                agent?.agent?.takeIf { it.isNotBlank() },
                pane.cwd?.substringAfterLast('/'),
            ).firstOrNull() ?: "Pane ${pane.paneId}",
            cwd = pane.cwd ?: agent?.cwd,
        )
    }
}
