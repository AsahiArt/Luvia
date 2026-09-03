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
                    val agentDetail = _uhp.value[hostId]?.agentDetail ?: return@forEach
                    if (!agentDetail.open) return@forEach
                    val openPane = agentDetail.paneId ?: return@forEach
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
            val openPane = _uhp.value[hostId]?.agentDetail?.takeIf { it.open }?.paneId
            if (openPane != null) {
                loadAgentDetail(hostId, openPane)
            }
        }
    }

    fun openAgent(hostId: String, paneId: String) {
        val current = _uhp.value[hostId]
        val existing = current?.agentDetail
        if (existing != null &&
            existing.paneId != null &&
            existing.paneId != paneId &&
            (existing.unconfirmed != null || existing.sending)
        ) {
            val name = existing.summary?.name ?: existing.summary?.agent ?: existing.paneId
            val message = if (existing.unconfirmed != null) {
                "Unconfirmed result for $name is still pending. Check it before opening another Agent."
            } else {
                "An Agent action is still in progress for $name. Wait before opening another Agent."
            }
            updateHost(hostId) {
                it.copy(agentDetail = it.agentDetail.copy(errorText = message))
            }
            return
        }
        val summary = current?.agents?.firstOrNull { it.paneId == paneId }
        val samePane = existing?.paneId == paneId
        updateHost(hostId) {
            it.copy(
                agentDetail = if (samePane) {
                    it.agentDetail.copy(
                        open = true,
                        paneId = paneId,
                        summary = summary ?: it.agentDetail.summary,
                        loading = true,
                    )
                } else {
                    AgentDetailUi(
                        paneId = paneId,
                        open = true,
                        summary = summary,
                        loading = true,
                    )
                },
            )
        }
        viewModelScope.launch { loadAgentDetail(hostId, paneId) }
    }

    fun closeAgent(hostId: String) {
        updateHost(hostId) { it.copy(agentDetail = it.agentDetail.copy(open = false)) }
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
                tasks = when {
                    show -> it.tasks.copy(showAdd = true)
                    it.tasks.unconfirmed != null -> it.tasks.copy(showAdd = false)
                    else -> it.tasks.copy(showAdd = false, addTitle = "", addPaths = "")
                },
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
        val state = _uhp.value[hostId] ?: return
        val paneId = state.agentDetail.paneId ?: return
        if (!state.canMutate || state.agentDetail.sending || state.agentDetail.unconfirmed != null) return
        if (!session.supports(UhpMethods.AGENT_PROMPT)) return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        updateAgentPane(hostId, paneId) { it.copy(sending = true, errorText = null) }
        viewModelScope.launch {
            when (val result = session.promptAgent(paneId, trimmed, wait = false)) {
                is Outcome.Ok -> {
                    updateAgentPane(hostId, paneId) { detail ->
                        detail.copy(
                            sending = false,
                            unconfirmed = null,
                            draft = if (detail.draft.trim() == trimmed) "" else detail.draft,
                        )
                    }
                    loadAgentDetail(hostId, paneId)
                }
                is Outcome.Err -> applyAgentMutationFailure(hostId, paneId, UnconfirmedKind.AgentPrompt, result.failure)
            }
        }
    }

    fun sendAgentKeys(hostId: String, keys: List<AgentKey>) {
        val session = manager.session(hostId) ?: return
        val state = _uhp.value[hostId] ?: return
        val paneId = state.agentDetail.paneId ?: return
        if (!state.canMutate || state.agentDetail.sending || state.agentDetail.unconfirmed != null) return
        if (!session.supports(UhpMethods.AGENT_KEYS)) return
        if (keys.isEmpty()) return
        updateAgentPane(hostId, paneId) { it.copy(sending = true, errorText = null) }
        viewModelScope.launch {
            when (val result = session.sendAgentKeys(paneId, keys)) {
                is Outcome.Ok -> {
                    updateAgentPane(hostId, paneId) { it.copy(sending = false, unconfirmed = null) }
                    loadAgentDetail(hostId, paneId)
                }
                is Outcome.Err -> applyAgentMutationFailure(hostId, paneId, UnconfirmedKind.AgentKeys, result.failure)
            }
        }
    }

    fun checkAgent(hostId: String) {
        val paneId = _uhp.value[hostId]?.agentDetail?.paneId ?: return
        viewModelScope.launch {
            val session = manager.session(hostId)
            if (session == null) {
                updateAgentPane(hostId, paneId) {
                    it.copy(loading = false, errorText = "Not connected to this host.")
                }
                return@launch
            }
            if (!session.supports(UhpMethods.AGENT_GET) && !session.supports(UhpMethods.AGENT_READ)) {
                updateAgentPane(hostId, paneId) {
                    it.copy(
                        loading = false,
                        errorText = "The host does not support agent.get or agent.read.",
                    )
                }
                return@launch
            }
            if (loadAgentDetail(hostId, paneId)) {
                updateAgentPane(hostId, paneId) { it.copy(unconfirmed = null) }
            }
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
        val state = _uhp.value[hostId] ?: return
        if (!state.canMutate || state.review.sending || state.review.unconfirmed != null) return
        if (!session.supports(UhpMethods.DIFF_NOTE_ADD)) return
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return
        updateHost(hostId) {
            it.copy(review = it.review.copy(sending = true, errorText = null))
        }
        viewModelScope.launch {
            when (val result = session.addReviewNote(file = file, line = line, body = trimmed, layer = layer)) {
                is Outcome.Ok -> {
                    updateHost(hostId) {
                        it.copy(
                            review = it.review.copy(
                                sending = false,
                                unconfirmed = null,
                                errorText = null,
                                noteDraft = "",
                            ),
                        )
                    }
                    refreshNotes(hostId, file)
                }
                is Outcome.Err ->
                    applyReviewMutationFailure(hostId, UnconfirmedKind.AddReviewNote, result.failure)
            }
        }
    }

    fun resolveReviewNote(hostId: String, id: String) {
        mutateNote(hostId, UhpMethods.DIFF_NOTE_RESOLVE, UnconfirmedKind.ResolveReviewNote) {
            it.resolveReviewNote(id)
        }
    }

    fun reopenReviewNote(hostId: String, id: String) {
        mutateNote(hostId, UhpMethods.DIFF_NOTE_REOPEN, UnconfirmedKind.ReopenReviewNote) {
            it.reopenReviewNote(id)
        }
    }

    fun removeReviewNote(hostId: String, id: String) {
        mutateNote(hostId, UhpMethods.DIFF_NOTE_REMOVE, UnconfirmedKind.RemoveReviewNote) {
            it.removeReviewNote(id)
        }
    }

    fun sendReviewNotes(hostId: String, to: String) {
        val session = manager.session(hostId) ?: return
        val state = _uhp.value[hostId] ?: return
        if (!state.canMutate || state.review.sending || state.review.unconfirmed != null) return
        if (!session.supports(UhpMethods.DIFF_NOTE_SEND)) return
        updateHost(hostId) { it.copy(review = it.review.copy(sending = true, errorText = null)) }
        viewModelScope.launch {
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
                is Outcome.Err ->
                    applyReviewMutationFailure(hostId, UnconfirmedKind.SendNotes, result.failure)
            }
        }
    }

    fun checkNotes(hostId: String) {
        viewModelScope.launch {
            val session = manager.session(hostId)
            if (session == null) {
                updateHost(hostId) {
                    it.copy(review = it.review.copy(errorText = "Not connected to this host."))
                }
                return@launch
            }
            if (!session.supports(UhpMethods.DIFF_NOTE_LIST)) {
                updateHost(hostId) {
                    it.copy(review = it.review.copy(errorText = "The host does not support diff.note.list."))
                }
                return@launch
            }
            if (refreshNotes(hostId, _uhp.value[hostId]?.review?.selectedPath)) {
                updateHost(hostId) { it.copy(review = it.review.copy(unconfirmed = null)) }
            }
        }
    }

    fun loadTasks(hostId: String) {
        viewModelScope.launch { refreshTasks(hostId) }
    }

    private suspend fun refreshTasks(hostId: String): Boolean {
        val session = manager.session(hostId)
        if (session == null) {
            updateHost(hostId) {
                it.copy(
                    connected = false,
                    tasks = it.tasks.copy(loading = false, errorText = "Not connected to this host."),
                )
            }
            return false
        }
        if (!session.supports(UhpMethods.TASK_LIST)) return false
        updateHost(hostId) {
            it.copy(connected = true, tasks = it.tasks.copy(loading = true, errorText = null, boardChanged = false))
        }
        return when (val result = session.listTasks()) {
            is Outcome.Ok -> {
                updateHost(hostId) {
                    it.copy(tasks = it.tasks.copy(tasks = result.value, loading = false))
                }
                true
            }
            is Outcome.Err -> {
                updateHost(hostId) {
                    it.copy(tasks = it.tasks.copy(loading = false, errorText = result.failure.toUserMessage()))
                }
                false
            }
        }
    }

    fun addTask(hostId: String, title: String, paths: List<String>) {
        val session = manager.session(hostId) ?: return
        val state = _uhp.value[hostId] ?: return
        if (!state.canMutate || state.tasks.mutating || state.tasks.unconfirmed != null) return
        if (!session.supports(UhpMethods.TASK_ADD)) return
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        updateHost(hostId) { it.copy(tasks = it.tasks.copy(mutating = true, errorText = null, boardChanged = false)) }
        viewModelScope.launch {
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
                                showAdd = false,
                                addTitle = "",
                                addPaths = "",
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
        val state = _uhp.value[hostId] ?: return
        if (!state.canMutate || state.tasks.mutating || state.tasks.unconfirmed != null) return
        if (!session.supports(UhpMethods.TASK_DONE)) return
        updateHost(hostId) { it.copy(tasks = it.tasks.copy(mutating = true, errorText = null, boardChanged = false)) }
        viewModelScope.launch {
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
                                completeId = null,
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
            if (session == null) {
                updateHost(hostId) {
                    it.copy(
                        connected = false,
                        tasks = it.tasks.copy(errorText = "Not connected to this host."),
                    )
                }
                return@launch
            }
            var verified = false
            if (taskId != null && session.supports(UhpMethods.TASK_GET)) {
                when (val got = session.getTask(taskId)) {
                    is Outcome.Ok -> {
                        updateHost(hostId) { current ->
                            current.copy(
                                tasks = current.tasks.copy(
                                    boardRevision = got.value.revision ?: current.tasks.boardRevision,
                                    revisions = current.tasks.revisions + (taskId to (got.value.revision ?: 0L)),
                                ),
                            )
                        }
                        verified = true
                    }
                    is Outcome.Err -> updateHost(hostId) {
                        it.copy(tasks = it.tasks.copy(errorText = got.failure.toUserMessage()))
                    }
                }
            }
            val listed = refreshTasks(hostId)
            if (verified || listed) {
                updateHost(hostId) {
                    it.copy(tasks = it.tasks.copy(unconfirmed = null, unconfirmedTaskId = null, mutating = false))
                }
            } else if (!session.supports(UhpMethods.TASK_LIST) &&
                (taskId == null || !session.supports(UhpMethods.TASK_GET))
            ) {
                val message = if (taskId != null) {
                    "The host does not support task.get or task.list."
                } else {
                    "The host does not support task.list."
                }
                updateHost(hostId) {
                    it.copy(tasks = it.tasks.copy(errorText = message))
                }
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

    private suspend fun loadAgentDetail(hostId: String, paneId: String): Boolean {
        val session = manager.session(hostId)
        if (session == null) {
            updateAgentPane(hostId, paneId) {
                it.copy(loading = false, errorText = "Not connected to this host.")
            }
            return false
        }
        val canGet = session.supports(UhpMethods.AGENT_GET)
        val canRead = session.supports(UhpMethods.AGENT_READ)
        if (!canGet && !canRead) {
            updateAgentPane(hostId, paneId) { it.copy(loading = false) }
            return false
        }
        var error: String? = null
        var readOk = false
        val detail = if (canGet) {
            when (val result = session.getAgent(paneId)) {
                is Outcome.Ok -> {
                    readOk = true
                    result.value
                }
                is Outcome.Err -> {
                    error = result.failure.toUserMessage()
                    null
                }
            }
        } else {
            null
        }
        val transcript = if (canRead) {
            when (val result = session.readAgent(paneId)) {
                is Outcome.Ok -> {
                    readOk = true
                    result.value
                }
                is Outcome.Err -> {
                    error = error ?: result.failure.toUserMessage()
                    null
                }
            }
        } else {
            null
        }
        var applied = false
        updateHost(hostId) { current ->
            if (current.agentDetail.paneId != paneId) current
            else {
                applied = true
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
        return readOk && applied
    }

    private suspend fun fetchDiffFile(hostId: String, path: String, layer: DiffLayer?) {
        val session = manager.session(hostId) ?: return
        var diffLoadFailed = false
        if (session.supports(UhpMethods.DIFF_GET)) {
            when (val result = session.getDiff(path, layer, includePatch = true)) {
                is Outcome.Ok -> updateHost(hostId) {
                    it.copy(review = it.review.copy(selectedFile = result.value, selectedPath = path, selectedLayer = layer))
                }
                is Outcome.Err -> {
                    diffLoadFailed = true
                    updateHost(hostId) {
                        it.copy(review = it.review.copy(errorText = result.failure.toUserMessage()))
                    }
                }
            }
        }
        refreshNotes(hostId, path, clearErrorOnSuccess = !diffLoadFailed)
    }

    private suspend fun refreshNotes(
        hostId: String,
        file: String?,
        clearErrorOnSuccess: Boolean = true,
    ): Boolean {
        val session = manager.session(hostId) ?: return false
        if (!session.supports(UhpMethods.DIFF_NOTE_LIST)) return false
        return when (val result = session.listReviewNotes(file = file)) {
            is Outcome.Ok -> {
                updateHost(hostId) {
                    it.copy(
                        review = it.review.copy(
                            notes = result.value,
                            errorText = if (clearErrorOnSuccess) null else it.review.errorText,
                        ),
                    )
                }
                true
            }
            is Outcome.Err -> {
                updateHost(hostId) {
                    it.copy(review = it.review.copy(errorText = result.failure.toUserMessage()))
                }
                false
            }
        }
    }

    private fun mutateNote(
        hostId: String,
        method: String,
        kind: UnconfirmedKind,
        call: suspend (LuviaSession) -> Outcome<*>,
    ) {
        val session = manager.session(hostId) ?: return
        val state = _uhp.value[hostId] ?: return
        if (!state.canMutate || state.review.sending || state.review.unconfirmed != null) return
        if (!session.supports(method)) return
        updateHost(hostId) {
            it.copy(review = it.review.copy(sending = true, errorText = null))
        }
        viewModelScope.launch {
            when (val result = call(session)) {
                is Outcome.Ok -> {
                    updateHost(hostId) {
                        it.copy(
                            review = it.review.copy(
                                sending = false,
                                unconfirmed = null,
                                errorText = null,
                            ),
                        )
                    }
                    refreshNotes(hostId, _uhp.value[hostId]?.review?.selectedPath)
                }
                is Outcome.Err -> applyReviewMutationFailure(hostId, kind, result.failure)
            }
        }
    }

    private fun applyReviewMutationFailure(hostId: String, kind: UnconfirmedKind, failure: Failure) {
        updateHost(hostId) {
            it.copy(
                review = if (failure.isUnconfirmed()) {
                    it.review.copy(sending = false, unconfirmed = kind, errorText = null)
                } else {
                    it.review.copy(sending = false, errorText = failure.toUserMessage())
                },
            )
        }
    }

    private fun applyAgentMutationFailure(hostId: String, paneId: String, kind: UnconfirmedKind, failure: Failure) {
        updateAgentPane(hostId, paneId) {
            if (failure.isUnconfirmed()) {
                it.copy(sending = false, unconfirmed = kind, errorText = null)
            } else {
                it.copy(sending = false, errorText = failure.toUserMessage())
            }
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

    private fun updateAgentPane(
        hostId: String,
        paneId: String,
        transform: (AgentDetailUi) -> AgentDetailUi,
    ) {
        updateHost(hostId) { current ->
            if (current.agentDetail.paneId != paneId) current
            else current.copy(agentDetail = transform(current.agentDetail))
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
