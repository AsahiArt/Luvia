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
import tech.asahiart.luvia.ui.AgentDetailUi
import tech.asahiart.luvia.ui.ConnectionBadge
import tech.asahiart.luvia.ui.HostCapabilitiesUi
import tech.asahiart.luvia.ui.HostSection
import tech.asahiart.luvia.ui.HostUhpUiState
import tech.asahiart.luvia.ui.HostUiModel
import tech.asahiart.luvia.ui.TerminalUiModel
import tech.asahiart.luvia.ui.UnconfirmedKind
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

    private val _uhp = MutableStateFlow<Map<String, HostUhpUiState>>(emptyMap())
    val uhp: StateFlow<Map<String, HostUhpUiState>> = _uhp.asStateFlow()

    init {
        viewModelScope.launch {
            var previous = emptyMap<String, Map<String, AgentStatus>>()
            manager.hosts.collect { runtimes ->
                val ids = runtimes.map { it.profile.id }.toSet()
                _uhp.update { it.filterKeys { id -> id in ids } }
                runtimes.forEach { runtime -> applyRuntime(runtime) }
                runtimes.forEach { runtime ->
                    val hostId = runtime.profile.id
                    val openPane = _uhp.value[hostId]?.agentDetail?.paneId ?: return@forEach
                    val newStatus = runtime.snapshot?.agents?.firstOrNull { it.paneId == openPane }?.status
                    val oldStatus = previous[hostId]?.get(openPane)
                    if (newStatus != null && oldStatus != null && newStatus != oldStatus) {
                        readOpenTranscript(hostId, openPane)
                    }
                }
                previous = runtimes.associate { runtime ->
                    runtime.profile.id to runtime.snapshot?.agents.orEmpty().associate { it.paneId to it.status }
                }
            }
        }
    }

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
        loadAgents(hostId)
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

    fun ensureUhp(hostId: String) {
        loadAgents(hostId)
    }

    fun showSection(hostId: String, section: HostSection) {
        when (section) {
            HostSection.Agents -> loadAgents(hostId)
            HostSection.Review -> loadDiff(hostId)
            HostSection.Tasks -> loadTasks(hostId)
            HostSection.Terminal -> Unit
        }
    }

    fun refreshSection(hostId: String, section: HostSection) {
        viewModelScope.launch {
            manager.refresh(hostId)
        }
        showSection(hostId, section)
    }

    fun loadAgents(hostId: String) {
        viewModelScope.launch {
            val session = manager.session(hostId)
            val runtime = manager.hosts.value.firstOrNull { it.profile.id == hostId }
            if (session == null) {
                updateHost(hostId) {
                    it.copy(
                        connected = false,
                        loading = false,
                        isObserver = runtime?.profile?.role == HostRole.Observer,
                        capabilities = HostCapabilitiesUi(),
                        agents = runtime?.snapshot?.agents.orEmpty().ifEmpty { it.agents },
                    )
                }
                return@launch
            }
            updateHost(hostId) {
                it.copy(
                    connected = true,
                    loading = true,
                    errorText = null,
                    isObserver = runtime?.profile?.role == HostRole.Observer,
                    capabilities = session.toCaps(),
                )
            }
            val snapshotAgents = runtime?.snapshot?.agents.orEmpty()
            val listed = if (session.supports(UhpMethods.AGENT_LIST)) {
                when (val result = session.listAgents()) {
                    is Outcome.Ok -> result.value
                    is Outcome.Err -> {
                        if (result.failure !is Failure.CapabilityMissing) {
                            updateHost(hostId) { it.copy(errorText = result.failure.toUserMessage()) }
                        }
                        snapshotAgents
                    }
                }
            } else {
                snapshotAgents
            }
            val mission = if (session.supports(UhpMethods.MISSION_SNAPSHOT)) {
                when (val result = session.missionSnapshot()) {
                    is Outcome.Ok -> result.value
                    is Outcome.Err -> null
                }
            } else {
                null
            }
            updateHost(hostId) { current ->
                val agents = listed.ifEmpty { snapshotAgents }.ifEmpty { current.agents }
                val openPane = current.agentDetail.paneId
                current.copy(
                    loading = false,
                    agents = agents,
                    mission = mission ?: current.mission,
                    capabilities = session.toCaps(),
                    agentDetail = current.agentDetail.copy(
                        summary = agents.firstOrNull { it.paneId == openPane } ?: current.agentDetail.summary,
                    ),
                )
            }
            val openPane = _uhp.value[hostId]?.agentDetail?.paneId
            if (openPane != null) {
                loadAgentDetail(hostId, openPane)
            }
        }
    }

    fun openAgent(hostId: String, paneId: String) {
        val summary = _uhp.value[hostId]?.agents?.firstOrNull { it.paneId == paneId }
        updateHost(hostId) {
            it.copy(
                agentDetail = AgentDetailUi(
                    paneId = paneId,
                    summary = summary,
                    loading = true,
                    errorText = null,
                ),
            )
        }
        viewModelScope.launch { loadAgentDetail(hostId, paneId) }
    }

    fun closeAgent(hostId: String) {
        updateHost(hostId) { it.copy(agentDetail = AgentDetailUi()) }
    }

    fun setSection(hostId: String, section: HostSection) {
        updateHost(hostId) { it.copy(section = section) }
    }

    fun setAgentDraft(hostId: String, text: String) {
        updateHost(hostId) { it.copy(agentDetail = it.agentDetail.copy(draft = text)) }
    }

    fun setNoteDraft(hostId: String, text: String) {
        updateHost(hostId) { it.copy(review = it.review.copy(noteDraft = text)) }
    }

    fun setSendTarget(hostId: String, paneId: String?) {
        updateHost(hostId) { it.copy(review = it.review.copy(sendTarget = paneId)) }
    }

    fun setShowAddTask(hostId: String, show: Boolean) {
        updateHost(hostId) {
            it.copy(
                tasks = if (show) it.tasks.copy(showAdd = true)
                else it.tasks.copy(showAdd = false, addTitle = "", addPaths = ""),
            )
        }
    }

    fun setCompleteTaskId(hostId: String, id: String?) {
        updateHost(hostId) { it.copy(tasks = it.tasks.copy(completeId = id)) }
    }

    fun setAddTaskDraft(hostId: String, title: String, paths: String) {
        updateHost(hostId) { it.copy(tasks = it.tasks.copy(addTitle = title, addPaths = paths)) }
    }

    fun promptAgent(hostId: String, text: String) {
        val session = manager.session(hostId) ?: return
        val paneId = _uhp.value[hostId]?.agentDetail?.paneId ?: return
        if (_uhp.value[hostId]?.canMutate != true) return
        if (!session.supports(UhpMethods.AGENT_PROMPT)) return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            updateHost(hostId) { it.copy(agentDetail = it.agentDetail.copy(sending = true, errorText = null)) }
            when (val result = session.promptAgent(paneId, trimmed, wait = false)) {
                is Outcome.Ok -> {
                    updateHost(hostId) {
                        it.copy(agentDetail = it.agentDetail.copy(sending = false, unconfirmed = null))
                    }
                    loadAgentDetail(hostId, paneId)
                }
                is Outcome.Err -> applyAgentMutationFailure(hostId, UnconfirmedKind.AgentPrompt, result.failure)
            }
        }
    }

    fun sendAgentKeys(hostId: String, keys: List<AgentKey>) {
        val session = manager.session(hostId) ?: return
        val paneId = _uhp.value[hostId]?.agentDetail?.paneId ?: return
        if (_uhp.value[hostId]?.canMutate != true) return
        if (!session.supports(UhpMethods.AGENT_KEYS)) return
        if (keys.isEmpty()) return
        viewModelScope.launch {
            updateHost(hostId) { it.copy(agentDetail = it.agentDetail.copy(sending = true, errorText = null)) }
            when (val result = session.sendAgentKeys(paneId, keys)) {
                is Outcome.Ok -> {
                    updateHost(hostId) {
                        it.copy(agentDetail = it.agentDetail.copy(sending = false, unconfirmed = null))
                    }
                    loadAgentDetail(hostId, paneId)
                }
                is Outcome.Err -> applyAgentMutationFailure(hostId, UnconfirmedKind.AgentKeys, result.failure)
            }
        }
    }

    fun checkAgent(hostId: String) {
        val paneId = _uhp.value[hostId]?.agentDetail?.paneId ?: return
        viewModelScope.launch {
            loadAgentDetail(hostId, paneId)
            updateHost(hostId) { it.copy(agentDetail = it.agentDetail.copy(unconfirmed = null)) }
        }
    }

    fun loadDiff(hostId: String) {
        viewModelScope.launch {
            val session = manager.session(hostId)
            if (session == null) {
                updateHost(hostId) { it.copy(connected = false) }
                return@launch
            }
            if (!session.supports(UhpMethods.DIFF_LIST)) return@launch
            updateHost(hostId) {
                it.copy(connected = true, review = it.review.copy(loading = true, errorText = null))
            }
            val list = when (val result = session.listDiff()) {
                is Outcome.Ok -> result.value
                is Outcome.Err -> {
                    updateHost(hostId) {
                        it.copy(review = it.review.copy(loading = false, errorText = result.failure.toUserMessage()))
                    }
                    return@launch
                }
            }
            val notes = if (session.supports(UhpMethods.DIFF_NOTE_LIST)) {
                when (val result = session.listReviewNotes()) {
                    is Outcome.Ok -> result.value
                    is Outcome.Err -> emptyList()
                }
            } else {
                emptyList()
            }
            updateHost(hostId) {
                it.copy(review = it.review.copy(list = list, notes = notes, loading = false))
            }
            val selected = _uhp.value[hostId]?.review
            val path = selected?.selectedPath
            if (path != null) {
                fetchDiffFile(hostId, path, selected.selectedLayer)
            }
        }
    }

    fun openDiffFile(hostId: String, path: String, layer: DiffLayer?) {
        updateHost(hostId) {
            it.copy(review = it.review.copy(selectedPath = path, selectedLayer = layer, selectedFile = null, errorText = null))
        }
        viewModelScope.launch { fetchDiffFile(hostId, path, layer) }
    }

    fun closeDiffFile(hostId: String) {
        updateHost(hostId) {
            it.copy(review = it.review.copy(selectedPath = null, selectedLayer = null, selectedFile = null))
        }
    }

    fun addReviewNote(hostId: String, file: String, line: ReviewLine, body: String, layer: DiffLayer?) {
        val session = manager.session(hostId) ?: return
        if (_uhp.value[hostId]?.canMutate != true) return
        if (!session.supports(UhpMethods.DIFF_NOTE_ADD)) return
        viewModelScope.launch {
            when (val result = session.addReviewNote(file = file, line = line, body = body, layer = layer)) {
                is Outcome.Ok -> refreshNotes(hostId, file)
                is Outcome.Err -> updateHost(hostId) {
                    it.copy(review = it.review.copy(errorText = result.failure.toUserMessage()))
                }
            }
        }
    }

    fun resolveReviewNote(hostId: String, id: String) {
        mutateNote(hostId, "diff.note.resolve") { it.resolveReviewNote(id) }
    }

    fun reopenReviewNote(hostId: String, id: String) {
        mutateNote(hostId, "diff.note.reopen") { it.reopenReviewNote(id) }
    }

    fun removeReviewNote(hostId: String, id: String) {
        val session = manager.session(hostId) ?: return
        if (_uhp.value[hostId]?.canMutate != true) return
        if (!session.supports(UhpMethods.DIFF_NOTE_REMOVE)) return
        viewModelScope.launch {
            when (val result = session.removeReviewNote(id)) {
                is Outcome.Ok -> refreshNotes(hostId, _uhp.value[hostId]?.review?.selectedPath)
                is Outcome.Err -> updateHost(hostId) {
                    it.copy(review = it.review.copy(errorText = result.failure.toUserMessage()))
                }
            }
        }
    }

    fun sendReviewNotes(hostId: String, to: String) {
        val session = manager.session(hostId) ?: return
        if (_uhp.value[hostId]?.canMutate != true) return
        if (!session.supports(UhpMethods.DIFF_NOTE_SEND)) return
        viewModelScope.launch {
            updateHost(hostId) { it.copy(review = it.review.copy(sending = true, errorText = null)) }
            when (val result = session.sendReviewNotes(to = to, allOpen = true)) {
                is Outcome.Ok -> {
                    updateHost(hostId) {
                        it.copy(
                            review = it.review.copy(
                                sending = false,
                                unconfirmed = null,
                                lastSend = result.value,
                            ),
                        )
                    }
                    refreshNotes(hostId, _uhp.value[hostId]?.review?.selectedPath)
                }
                is Outcome.Err -> {
                    if (result.failure.isUnconfirmed()) {
                        updateHost(hostId) {
                            it.copy(
                                review = it.review.copy(
                                    sending = false,
                                    unconfirmed = UnconfirmedKind.SendNotes,
                                    errorText = null,
                                ),
                            )
                        }
                    } else {
                        updateHost(hostId) {
                            it.copy(review = it.review.copy(sending = false, errorText = result.failure.toUserMessage()))
                        }
                    }
                }
            }
        }
    }

    fun checkNotes(hostId: String) {
        viewModelScope.launch {
            refreshNotes(hostId, _uhp.value[hostId]?.review?.selectedPath)
            updateHost(hostId) { it.copy(review = it.review.copy(unconfirmed = null)) }
        }
    }

    fun loadTasks(hostId: String) {
        viewModelScope.launch {
            val session = manager.session(hostId)
            if (session == null) {
                updateHost(hostId) { it.copy(connected = false) }
                return@launch
            }
            if (!session.supports(UhpMethods.TASK_LIST)) return@launch
            updateHost(hostId) {
                it.copy(connected = true, tasks = it.tasks.copy(loading = true, errorText = null, boardChanged = false))
            }
            when (val result = session.listTasks()) {
                is Outcome.Ok -> updateHost(hostId) {
                    it.copy(tasks = it.tasks.copy(tasks = result.value, loading = false))
                }
                is Outcome.Err -> updateHost(hostId) {
                    it.copy(tasks = it.tasks.copy(loading = false, errorText = result.failure.toUserMessage()))
                }
            }
        }
    }

    fun addTask(hostId: String, title: String, paths: List<String>) {
        val session = manager.session(hostId) ?: return
        if (_uhp.value[hostId]?.canMutate != true) return
        if (!session.supports(UhpMethods.TASK_ADD)) return
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            updateHost(hostId) { it.copy(tasks = it.tasks.copy(mutating = true, errorText = null, boardChanged = false)) }
            val ifRevision = _uhp.value[hostId]?.tasks?.boardRevision
            when (val result = session.addTask(title = trimmed, paths = paths, ifRevision = ifRevision)) {
                is Outcome.Ok -> {
                    val task = result.value.task
                    updateHost(hostId) { current ->
                        val revisions = current.tasks.revisions + (task.id to (result.value.revision ?: current.tasks.revisions[task.id] ?: 0L))
                        current.copy(
                            tasks = current.tasks.copy(
                                mutating = false,
                                unconfirmed = null,
                                boardRevision = result.value.revision ?: current.tasks.boardRevision,
                                revisions = revisions,
                            ),
                        )
                    }
                    loadTasks(hostId)
                }
                is Outcome.Err -> applyTaskMutationFailure(hostId, UnconfirmedKind.AddTask, null, result.failure)
            }
        }
    }

    fun completeTask(hostId: String, taskId: String) {
        val session = manager.session(hostId) ?: return
        if (_uhp.value[hostId]?.canMutate != true) return
        if (!session.supports(UhpMethods.TASK_DONE)) return
        viewModelScope.launch {
            updateHost(hostId) { it.copy(tasks = it.tasks.copy(mutating = true, errorText = null, boardChanged = false)) }
            var ifRevision = _uhp.value[hostId]?.tasks?.revisions?.get(taskId)
            if (ifRevision == null && session.supports(UhpMethods.TASK_GET)) {
                when (val got = session.getTask(taskId)) {
                    is Outcome.Ok -> {
                        ifRevision = got.value.revision
                        updateHost(hostId) { current ->
                            current.copy(
                                tasks = current.tasks.copy(
                                    boardRevision = got.value.revision ?: current.tasks.boardRevision,
                                    revisions = current.tasks.revisions + (taskId to (got.value.revision ?: 0L)),
                                ),
                            )
                        }
                    }
                    is Outcome.Err -> {
                        applyTaskMutationFailure(hostId, UnconfirmedKind.CompleteTask, taskId, got.failure)
                        return@launch
                    }
                }
            }
            when (val result = session.completeTask(taskId, ifRevision)) {
                is Outcome.Ok -> {
                    updateHost(hostId) { current ->
                        current.copy(
                            tasks = current.tasks.copy(
                                mutating = false,
                                unconfirmed = null,
                                unconfirmedTaskId = null,
                                boardRevision = result.value.revision ?: current.tasks.boardRevision,
                                revisions = current.tasks.revisions + (taskId to (result.value.revision ?: current.tasks.revisions[taskId] ?: 0L)),
                            ),
                        )
                    }
                    loadTasks(hostId)
                }
                is Outcome.Err -> applyTaskMutationFailure(hostId, UnconfirmedKind.CompleteTask, taskId, result.failure)
            }
        }
    }

    fun checkTasks(hostId: String) {
        val taskId = _uhp.value[hostId]?.tasks?.unconfirmedTaskId
        viewModelScope.launch {
            val session = manager.session(hostId)
            if (session != null && taskId != null && session.supports(UhpMethods.TASK_GET)) {
                when (val got = session.getTask(taskId)) {
                    is Outcome.Ok -> updateHost(hostId) { current ->
                        current.copy(
                            tasks = current.tasks.copy(
                                boardRevision = got.value.revision ?: current.tasks.boardRevision,
                                revisions = current.tasks.revisions + (taskId to (got.value.revision ?: 0L)),
                            ),
                        )
                    }
                    is Outcome.Err -> Unit
                }
            }
            loadTasks(hostId)
            updateHost(hostId) {
                it.copy(tasks = it.tasks.copy(unconfirmed = null, unconfirmedTaskId = null, mutating = false))
            }
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

    private fun applyRuntime(runtime: HostRuntime) {
        val hostId = runtime.profile.id
        val session = manager.session(hostId)
        val snapshotAgents = runtime.snapshot?.agents.orEmpty()
        updateHost(hostId) { current ->
            val openPane = current.agentDetail.paneId
            current.copy(
                connected = session != null,
                isObserver = runtime.profile.role == HostRole.Observer,
                capabilities = session?.toCaps() ?: if (session == null) HostCapabilitiesUi() else current.capabilities,
                agents = snapshotAgents.ifEmpty { current.agents },
                agentDetail = current.agentDetail.copy(
                    summary = snapshotAgents.firstOrNull { it.paneId == openPane } ?: current.agentDetail.summary,
                ),
            )
        }
    }

    private fun readOpenTranscript(hostId: String, paneId: String) {
        viewModelScope.launch { loadAgentDetail(hostId, paneId) }
    }

    private suspend fun loadAgentDetail(hostId: String, paneId: String) {
        val session = manager.session(hostId) ?: return
        var error: String? = null
        val detail = if (session.supports(UhpMethods.AGENT_GET)) {
            when (val result = session.getAgent(paneId)) {
                is Outcome.Ok -> result.value
                is Outcome.Err -> {
                    error = result.failure.toUserMessage()
                    null
                }
            }
        } else {
            null
        }
        val transcript = if (session.supports(UhpMethods.AGENT_READ)) {
            when (val result = session.readAgent(paneId)) {
                is Outcome.Ok -> result.value
                is Outcome.Err -> {
                    error = error ?: result.failure.toUserMessage()
                    null
                }
            }
        } else {
            null
        }
        updateHost(hostId) { current ->
            if (current.agentDetail.paneId != paneId) current
            else {
                current.copy(
                    agentDetail = current.agentDetail.copy(
                        detail = detail ?: current.agentDetail.detail,
                        transcript = transcript ?: current.agentDetail.transcript,
                        loading = false,
                        errorText = error,
                    ),
                )
            }
        }
    }

    private suspend fun fetchDiffFile(hostId: String, path: String, layer: DiffLayer?) {
        val session = manager.session(hostId) ?: return
        if (session.supports(UhpMethods.DIFF_GET)) {
            when (val result = session.getDiff(path, layer, includePatch = true)) {
                is Outcome.Ok -> updateHost(hostId) {
                    it.copy(review = it.review.copy(selectedFile = result.value, selectedPath = path, selectedLayer = layer))
                }
                is Outcome.Err -> updateHost(hostId) {
                    it.copy(review = it.review.copy(errorText = result.failure.toUserMessage()))
                }
            }
        }
        refreshNotes(hostId, path)
    }

    private suspend fun refreshNotes(hostId: String, file: String?) {
        val session = manager.session(hostId) ?: return
        if (!session.supports(UhpMethods.DIFF_NOTE_LIST)) return
        when (val result = session.listReviewNotes(file = file)) {
            is Outcome.Ok -> updateHost(hostId) { it.copy(review = it.review.copy(notes = result.value)) }
            is Outcome.Err -> updateHost(hostId) {
                it.copy(review = it.review.copy(errorText = result.failure.toUserMessage()))
            }
        }
    }

    private fun mutateNote(hostId: String, method: String, call: suspend (LuviaSession) -> Outcome<ReviewNote>) {
        val session = manager.session(hostId) ?: return
        if (_uhp.value[hostId]?.canMutate != true) return
        if (!session.supports(method)) return
        viewModelScope.launch {
            when (val result = call(session)) {
                is Outcome.Ok -> refreshNotes(hostId, _uhp.value[hostId]?.review?.selectedPath)
                is Outcome.Err -> updateHost(hostId) {
                    it.copy(review = it.review.copy(errorText = result.failure.toUserMessage()))
                }
            }
        }
    }

    private fun applyAgentMutationFailure(hostId: String, kind: UnconfirmedKind, failure: Failure) {
        updateHost(hostId) {
            val detail = if (failure.isUnconfirmed()) {
                it.agentDetail.copy(sending = false, unconfirmed = kind, errorText = null)
            } else {
                it.agentDetail.copy(sending = false, errorText = failure.toUserMessage())
            }
            it.copy(agentDetail = detail)
        }
    }

    private fun applyTaskMutationFailure(
        hostId: String,
        kind: UnconfirmedKind,
        taskId: String?,
        failure: Failure,
    ) {
        if (failure is Failure.RevisionConflict) {
            updateHost(hostId) {
                it.copy(
                    tasks = it.tasks.copy(
                        mutating = false,
                        boardChanged = true,
                        errorText = "Board changed, review and try again",
                    ),
                )
            }
            loadTasks(hostId)
            return
        }
        updateHost(hostId) {
            it.copy(
                tasks = if (failure.isUnconfirmed()) {
                    it.tasks.copy(
                        mutating = false,
                        unconfirmed = kind,
                        unconfirmedTaskId = taskId,
                        errorText = null,
                    )
                } else {
                    it.tasks.copy(mutating = false, errorText = failure.toUserMessage())
                },
            )
        }
    }

    private fun updateHost(hostId: String, transform: (HostUhpUiState) -> HostUhpUiState) {
        _uhp.update { map ->
            val current = map[hostId] ?: HostUhpUiState()
            map + (hostId to transform(current))
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
        is Failure.AgentPromptBusy -> "The agent is still handling a previous message. Wait for it to finish."
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

private fun LuviaSession.toCaps(): HostCapabilitiesUi = HostCapabilitiesUi(
    agentRead = supports(UhpMethods.AGENT_READ),
    agentPrompt = supports(UhpMethods.AGENT_PROMPT),
    agentKeys = supports(UhpMethods.AGENT_KEYS),
    missionSnapshot = supports(UhpMethods.MISSION_SNAPSHOT),
    diffList = supports(UhpMethods.DIFF_LIST),
    diffGet = supports(UhpMethods.DIFF_GET),
    diffNoteList = supports(UhpMethods.DIFF_NOTE_LIST),
    diffNoteAdd = supports(UhpMethods.DIFF_NOTE_ADD),
    diffNoteSend = supports(UhpMethods.DIFF_NOTE_SEND),
    diffNoteResolve = supports(UhpMethods.DIFF_NOTE_RESOLVE),
    diffNoteReopen = supports(UhpMethods.DIFF_NOTE_REOPEN),
    diffNoteRemove = supports(UhpMethods.DIFF_NOTE_REMOVE),
    taskList = supports(UhpMethods.TASK_LIST),
    taskAdd = supports(UhpMethods.TASK_ADD),
    taskDone = supports(UhpMethods.TASK_DONE),
    taskGet = supports(UhpMethods.TASK_GET),
)

private fun Failure.isUnconfirmed(): Boolean =
    this is Failure.IndeterminateMutation ||
        this is Failure.Transport ||
        this is Failure.Bridge ||
        this is Failure.Closed
