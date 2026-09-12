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
import tech.asahiart.luvia.ui.TerminalPaneChoice
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
    private val triedTerminals = mutableMapOf<String, MutableSet<String>>()

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
            HostSection.Files -> loadFiles(hostId)
            HostSection.Search -> {
                val query = _uhp.value[hostId]?.search?.query
                if (!query.isNullOrBlank()) querySearch(hostId)
            }
            HostSection.Review -> loadDiff(hostId)
            HostSection.Worktrees -> loadWorktrees(hostId)
            HostSection.Automations -> loadAutomations(hostId)
            HostSection.Tasks -> loadTasks(hostId)
            HostSection.Layout -> loadLayout(hostId)
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
            val sessions = if (session.supports(UhpMethods.AGENT_SESSIONS)) {
                when (val result = session.listAgentSessions()) {
                    is Outcome.Ok -> result.value
                    is Outcome.Err -> emptyList()
                }
            } else {
                emptyList()
            }
            updateHost(hostId) { current ->
                val agents = listed.ifEmpty { snapshotAgents }.ifEmpty { current.agents }
                val openPane = current.agentDetail.paneId
                current.copy(
                    loading = false,
                    agents = agents,
                    agentSessions = sessions,
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

    fun setDeleteTaskId(hostId: String, id: String?) {
        updateHost(hostId) { it.copy(tasks = it.tasks.copy(deleteId = id)) }
    }

    fun setShowNameAgent(hostId: String, show: Boolean) {
        updateHost(hostId) {
            it.copy(agentDetail = it.agentDetail.copy(showName = show, nameDraft = if (show) it.agentDetail.nameDraft else ""))
        }
    }

    fun setNameAgentDraft(hostId: String, text: String) {
        updateHost(hostId) { it.copy(agentDetail = it.agentDetail.copy(nameDraft = text)) }
    }

    fun setShowForkAgent(hostId: String, show: Boolean) {
        updateHost(hostId) {
            it.copy(agentDetail = it.agentDetail.copy(showFork = show, forkDraft = if (show) it.agentDetail.forkDraft else ""))
        }
    }

    fun setForkAgentDraft(hostId: String, text: String) {
        updateHost(hostId) { it.copy(agentDetail = it.agentDetail.copy(forkDraft = text)) }
    }

    fun setSearchQuery(hostId: String, query: String) {
        updateHost(hostId) { it.copy(search = it.search.copy(query = query)) }
    }

    fun setShowCreateWorktree(hostId: String, show: Boolean) {
        updateHost(hostId) {
            it.copy(
                worktrees = when {
                    show -> it.worktrees.copy(showCreate = true)
                    else -> it.worktrees.copy(showCreate = false, createBranch = "")
                },
            )
        }
    }

    fun setCreateWorktreeBranch(hostId: String, branch: String) {
        updateHost(hostId) { it.copy(worktrees = it.worktrees.copy(createBranch = branch)) }
    }

    fun setRemoveWorktreePath(hostId: String, path: String?) {
        updateHost(hostId) { it.copy(worktrees = it.worktrees.copy(removePath = path)) }
    }

    fun setCloseWorkspace(hostId: String, index: Int?) {
        updateHost(hostId) { it.copy(layout = it.layout.copy(closeWorkspace = index)) }
    }

    fun setClosePane(hostId: String, pane: String?) {
        updateHost(hostId) { it.copy(layout = it.layout.copy(closePane = pane)) }
    }

    fun setRenamePane(hostId: String, pane: String?, draft: String) {
        updateHost(hostId) { it.copy(layout = it.layout.copy(renamePane = pane, renameDraft = draft)) }
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
            val fence = state.agentDetail.transcript
            val canFence =
                fence?.contentRevision != null && !fence.terminalId.isNullOrBlank()
            when (
                val result =
                    session.sendAgentKeys(
                        paneId,
                        keys,
                        ifContentRevision = if (canFence) fence.contentRevision else null,
                        terminalId = if (canFence) fence.terminalId else null,
                    )
            ) {
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
        if (path.endsWith('/') || path.endsWith('\\')) {
            updateHost(hostId) {
                it.copy(
                    review = it.review.copy(
                        selectedPath = path,
                        selectedLayer = layer,
                        selectedFile = null,
                        errorText = null,
                    ),
                )
            }
            return
        }
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

    fun claimTask(hostId: String, taskId: String) {
        mutateTask(hostId, taskId, UhpMethods.TASK_CLAIM, UnconfirmedKind.ClaimTask) { session, ifRevision ->
            session.claimTask(taskId, ifRevision = ifRevision)
        }
    }

    fun deleteTask(hostId: String, taskId: String) {
        mutateTask(hostId, taskId, UhpMethods.TASK_DELETE, UnconfirmedKind.DeleteTask) { session, ifRevision ->
            session.deleteTask(taskId, ifRevision = ifRevision)
        }
    }

    fun resumeAgent(hostId: String, sessionId: String) {
        val session = manager.session(hostId) ?: return
        val state = _uhp.value[hostId] ?: return
        if (!state.canMutate || !session.supports(UhpMethods.AGENT_RESUME)) return
        if (sessionId.isBlank()) return
        updateHost(hostId) { it.copy(loading = true, errorText = null) }
        viewModelScope.launch {
            when (val result = session.resumeAgent(sessionId)) {
                is Outcome.Ok -> {
                    updateHost(hostId) { it.copy(loading = false, errorText = null) }
                    loadAgents(hostId)
                }
                is Outcome.Err -> updateHost(hostId) {
                    it.copy(loading = false, errorText = result.failure.toUserMessage())
                }
            }
        }
    }

    fun nameAgent(hostId: String) {
        val session = manager.session(hostId) ?: return
        val state = _uhp.value[hostId] ?: return
        val paneId = state.agentDetail.paneId ?: return
        if (!state.canMutate || state.agentDetail.sending || !session.supports(UhpMethods.AGENT_NAME)) return
        val name = state.agentDetail.nameDraft.trim()
        if (name.isEmpty()) return
        updateAgentPane(hostId, paneId) { it.copy(sending = true, errorText = null) }
        viewModelScope.launch {
            when (val result = session.nameAgent(pane = paneId, name = name)) {
                is Outcome.Ok -> {
                    updateAgentPane(hostId, paneId) {
                        it.copy(sending = false, showName = false, nameDraft = "")
                    }
                    loadAgentDetail(hostId, paneId)
                    loadAgents(hostId)
                }
                is Outcome.Err -> updateAgentPane(hostId, paneId) {
                    it.copy(sending = false, errorText = result.failure.toUserMessage())
                }
            }
        }
    }

    fun forkAgent(hostId: String) {
        val session = manager.session(hostId) ?: return
        val state = _uhp.value[hostId] ?: return
        val paneId = state.agentDetail.paneId ?: return
        if (!state.canMutate || state.agentDetail.sending || !session.supports(UhpMethods.AGENT_FORK)) return
        val name = state.agentDetail.forkDraft.trim().ifBlank { null }
        updateAgentPane(hostId, paneId) { it.copy(sending = true, errorText = null) }
        viewModelScope.launch {
            when (val result = session.forkAgent(target = paneId, name = name)) {
                is Outcome.Ok -> {
                    updateAgentPane(hostId, paneId) {
                        it.copy(sending = false, showFork = false, forkDraft = "")
                    }
                    loadAgents(hostId)
                }
                is Outcome.Err -> updateAgentPane(hostId, paneId) {
                    it.copy(sending = false, errorText = result.failure.toUserMessage())
                }
            }
        }
    }

    fun loadFiles(hostId: String) {
        viewModelScope.launch { refreshFilesTree(hostId, invalidate = false) }
    }

    fun refreshFiles(hostId: String) {
        viewModelScope.launch { refreshFilesTree(hostId, invalidate = true) }
    }

    fun openFile(hostId: String, path: String) {
        val session = manager.session(hostId) ?: return
        val state = _uhp.value[hostId] ?: return
        if (!state.canMutate || !session.supports(UhpMethods.FILES_OPEN) || path.isBlank()) return
        updateHost(hostId) { it.copy(files = it.files.copy(mutating = true, errorText = null)) }
        viewModelScope.launch {
            when (val result = session.openFile(path, FileOpenTarget.TAB)) {
                is Outcome.Ok -> updateHost(hostId) { it.copy(files = it.files.copy(mutating = false)) }
                is Outcome.Err -> updateHost(hostId) {
                    it.copy(files = it.files.copy(mutating = false, errorText = result.failure.toUserMessage()))
                }
            }
        }
    }

    fun revealFile(hostId: String, path: String) {
        val session = manager.session(hostId) ?: return
        val state = _uhp.value[hostId] ?: return
        if (!state.canMutate || !session.supports(UhpMethods.FILES_REVEAL) || path.isBlank()) return
        updateHost(hostId) { it.copy(files = it.files.copy(mutating = true, errorText = null)) }
        viewModelScope.launch {
            when (val result = session.revealFile(path)) {
                is Outcome.Ok -> updateHost(hostId) { it.copy(files = it.files.copy(mutating = false)) }
                is Outcome.Err -> updateHost(hostId) {
                    it.copy(files = it.files.copy(mutating = false, errorText = result.failure.toUserMessage()))
                }
            }
        }
    }

    fun querySearch(hostId: String) {
        viewModelScope.launch { runSearch(hostId) }
    }

    fun activateSearch(hostId: String, matchId: String) {
        val session = manager.session(hostId) ?: return
        val state = _uhp.value[hostId] ?: return
        if (!state.canMutate || !session.supports(UhpMethods.SEARCH_ACTIVATE)) return
        val match = state.search.matches.firstOrNull { it.id == matchId } ?: return
        val kind = searchKind(match.kind) ?: return
        updateHost(hostId) { it.copy(search = it.search.copy(loading = true, errorText = null)) }
        viewModelScope.launch {
            when (val result = session.activateSearch(kind, match.target)) {
                is Outcome.Ok -> updateHost(hostId) { it.copy(search = it.search.copy(loading = false)) }
                is Outcome.Err -> updateHost(hostId) {
                    it.copy(search = it.search.copy(loading = false, errorText = result.failure.toUserMessage()))
                }
            }
        }
    }

    fun loadWorktrees(hostId: String) {
        viewModelScope.launch { refreshWorktrees(hostId) }
    }

    fun createWorktree(hostId: String) {
        val session = manager.session(hostId) ?: return
        val state = _uhp.value[hostId] ?: return
        if (!state.canMutate || state.worktrees.mutating || !session.supports(UhpMethods.WORKTREE_CREATE)) return
        val branch = state.worktrees.createBranch.trim()
        if (branch.isEmpty()) return
        updateHost(hostId) { it.copy(worktrees = it.worktrees.copy(mutating = true, errorText = null)) }
        viewModelScope.launch {
            when (val result = session.createWorktree(branch, workspace = state.worktrees.workspace)) {
                is Outcome.Ok -> {
                    updateHost(hostId) {
                        it.copy(worktrees = it.worktrees.copy(mutating = false, showCreate = false, createBranch = ""))
                    }
                    refreshWorktrees(hostId)
                }
                is Outcome.Err -> updateHost(hostId) {
                    it.copy(worktrees = it.worktrees.copy(mutating = false, errorText = result.failure.toUserMessage()))
                }
            }
        }
    }

    fun openWorktree(hostId: String, path: String) {
        val session = manager.session(hostId) ?: return
        val state = _uhp.value[hostId] ?: return
        if (!state.canMutate || !session.supports(UhpMethods.WORKTREE_OPEN) || path.isBlank()) return
        updateHost(hostId) { it.copy(worktrees = it.worktrees.copy(mutating = true, errorText = null)) }
        viewModelScope.launch {
            when (val result = session.openWorktree(path)) {
                is Outcome.Ok -> {
                    updateHost(hostId) { it.copy(worktrees = it.worktrees.copy(mutating = false)) }
                    refreshWorktrees(hostId)
                }
                is Outcome.Err -> updateHost(hostId) {
                    it.copy(worktrees = it.worktrees.copy(mutating = false, errorText = result.failure.toUserMessage()))
                }
            }
        }
    }

    fun removeWorktree(hostId: String, path: String) {
        val session = manager.session(hostId) ?: return
        val state = _uhp.value[hostId] ?: return
        if (!state.canMutate || !session.supports(UhpMethods.WORKTREE_REMOVE) || path.isBlank()) return
        updateHost(hostId) { it.copy(worktrees = it.worktrees.copy(mutating = true, errorText = null)) }
        viewModelScope.launch {
            when (val result = session.removeWorktree(path)) {
                is Outcome.Ok -> {
                    updateHost(hostId) { it.copy(worktrees = it.worktrees.copy(mutating = false, removePath = null)) }
                    refreshWorktrees(hostId)
                }
                is Outcome.Err -> updateHost(hostId) {
                    it.copy(worktrees = it.worktrees.copy(mutating = false, errorText = result.failure.toUserMessage()))
                }
            }
        }
    }

    fun loadAutomations(hostId: String) {
        viewModelScope.launch { refreshAutomations(hostId) }
    }

    fun enableAutomation(hostId: String, id: String) {
        mutateAutomation(hostId, id, UhpMethods.AUTOMATION_ENABLE) { it.enableAutomation(id) }
    }

    fun disableAutomation(hostId: String, id: String) {
        mutateAutomation(hostId, id, UhpMethods.AUTOMATION_DISABLE) { it.disableAutomation(id) }
    }

    fun runAutomation(hostId: String, id: String) {
        mutateAutomation(hostId, id, UhpMethods.AUTOMATION_RUN) { it.runAutomation(id) }
    }

    fun loadLayout(hostId: String) {
        viewModelScope.launch { refreshLayout(hostId) }
    }

    fun focusWorkspace(hostId: String, index: Int) {
        val session = manager.session(hostId) ?: return
        val state = _uhp.value[hostId] ?: return
        if (!state.canMutate || !session.supports(UhpMethods.WORKSPACE_FOCUS)) return
        updateHost(hostId) { it.copy(layout = it.layout.copy(mutating = true, errorText = null)) }
        viewModelScope.launch {
            when (val result = session.focusWorkspace(index)) {
                is Outcome.Ok -> {
                    updateHost(hostId) { it.copy(layout = it.layout.copy(mutating = false)) }
                    refreshLayout(hostId)
                }
                is Outcome.Err -> updateHost(hostId) {
                    it.copy(layout = it.layout.copy(mutating = false, errorText = result.failure.toUserMessage()))
                }
            }
        }
    }

    fun closeWorkspace(hostId: String, index: Int) {
        val session = manager.session(hostId) ?: return
        val state = _uhp.value[hostId] ?: return
        if (!state.canMutate || !session.supports(UhpMethods.WORKSPACE_CLOSE)) return
        updateHost(hostId) { it.copy(layout = it.layout.copy(mutating = true, errorText = null)) }
        viewModelScope.launch {
            when (val result = session.closeWorkspace(index)) {
                is Outcome.Ok -> {
                    updateHost(hostId) { it.copy(layout = it.layout.copy(mutating = false, closeWorkspace = null)) }
                    refreshLayout(hostId)
                }
                is Outcome.Err -> updateHost(hostId) {
                    it.copy(layout = it.layout.copy(mutating = false, errorText = result.failure.toUserMessage()))
                }
            }
        }
    }

    fun focusPane(hostId: String, pane: String) {
        val session = manager.session(hostId) ?: return
        val state = _uhp.value[hostId] ?: return
        if (!state.canMutate || !session.supports(UhpMethods.PANE_FOCUS) || pane.isBlank()) return
        updateHost(hostId) { it.copy(layout = it.layout.copy(mutating = true, errorText = null)) }
        viewModelScope.launch {
            when (val result = session.focusPane(pane)) {
                is Outcome.Ok -> {
                    updateHost(hostId) { it.copy(layout = it.layout.copy(mutating = false)) }
                    refreshLayout(hostId)
                }
                is Outcome.Err -> updateHost(hostId) {
                    it.copy(layout = it.layout.copy(mutating = false, errorText = result.failure.toUserMessage()))
                }
            }
        }
    }

    fun closePane(hostId: String, pane: String) {
        val session = manager.session(hostId) ?: return
        val state = _uhp.value[hostId] ?: return
        if (!state.canMutate || !session.supports(UhpMethods.PANE_CLOSE) || pane.isBlank()) return
        updateHost(hostId) { it.copy(layout = it.layout.copy(mutating = true, errorText = null)) }
        viewModelScope.launch {
            when (val result = session.closePane(pane)) {
                is Outcome.Ok -> {
                    updateHost(hostId) { it.copy(layout = it.layout.copy(mutating = false, closePane = null)) }
                    refreshLayout(hostId)
                }
                is Outcome.Err -> updateHost(hostId) {
                    it.copy(layout = it.layout.copy(mutating = false, errorText = result.failure.toUserMessage()))
                }
            }
        }
    }

    fun renamePane(hostId: String) {
        val session = manager.session(hostId) ?: return
        val state = _uhp.value[hostId] ?: return
        val pane = state.layout.renamePane ?: return
        if (!state.canMutate || !session.supports(UhpMethods.PANE_RENAME)) return
        val name = state.layout.renameDraft.trim()
        if (name.isEmpty()) return
        updateHost(hostId) { it.copy(layout = it.layout.copy(mutating = true, errorText = null)) }
        viewModelScope.launch {
            when (val result = session.renamePane(name = name, pane = pane)) {
                is Outcome.Ok -> {
                    updateHost(hostId) {
                        it.copy(layout = it.layout.copy(mutating = false, renamePane = null, renameDraft = ""))
                    }
                    refreshLayout(hostId)
                }
                is Outcome.Err -> updateHost(hostId) {
                    it.copy(layout = it.layout.copy(mutating = false, errorText = result.failure.toUserMessage()))
                }
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
                if (update.failure is Failure.ControlConflict) {
                    _terminals.update { map ->
                        val current = map[hostId] ?: return@update map
                        map + (hostId to current.copy(control = TerminalControlState.Conflict))
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
                            errorText = update.failure.toUserMessage(),
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
                    errorText = failure.toUserMessage(),
                    panes = panes,
                    canControl = !observer,
                    control = TerminalControlState.Observing,
                )
                )
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

    private fun mutateTask(
        hostId: String,
        taskId: String,
        method: String,
        kind: UnconfirmedKind,
        call: suspend (LuviaSession, Long?) -> Outcome<TaskMutationResult>,
    ) {
        val session = manager.session(hostId) ?: return
        val state = _uhp.value[hostId] ?: return
        if (!state.canMutate || state.tasks.mutating || state.tasks.unconfirmed != null) return
        if (!session.supports(method)) return
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
                        applyTaskMutationFailure(hostId, kind, taskId, got.failure)
                        return@launch
                    }
                }
            }
            when (val result = call(session, ifRevision)) {
                is Outcome.Ok -> {
                    updateHost(hostId) { current ->
                        current.copy(
                            tasks = current.tasks.copy(
                                mutating = false,
                                unconfirmed = null,
                                unconfirmedTaskId = null,
                                boardRevision = result.value.revision ?: current.tasks.boardRevision,
                                deleteId = null,
                                revisions = current.tasks.revisions + (taskId to (result.value.revision ?: current.tasks.revisions[taskId] ?: 0L)),
                            ),
                        )
                    }
                    loadTasks(hostId)
                }
                is Outcome.Err -> applyTaskMutationFailure(hostId, kind, taskId, result.failure)
            }
        }
    }

    private suspend fun refreshFilesTree(hostId: String, invalidate: Boolean) {
        val session = manager.session(hostId)
        if (session == null) {
            updateHost(hostId) {
                it.copy(connected = false, files = it.files.copy(loading = false, errorText = "Not connected to this host."))
            }
            return
        }
        if (!session.supports(UhpMethods.FILES_TREE)) return
        val canInvalidate = invalidate &&
            (_uhp.value[hostId]?.canMutate == true) &&
            session.supports(UhpMethods.FILES_REFRESH)
        updateHost(hostId) { it.copy(connected = true, files = it.files.copy(loading = true, errorText = null)) }
        if (canInvalidate) {
            when (val refreshed = session.refreshFiles()) {
                is Outcome.Ok -> Unit
                is Outcome.Err -> {
                    updateHost(hostId) {
                        it.copy(files = it.files.copy(loading = false, errorText = refreshed.failure.toUserMessage()))
                    }
                    return
                }
            }
        }
        when (val result = session.fileTree()) {
            is Outcome.Ok -> updateHost(hostId) {
                it.copy(
                    files = it.files.copy(
                        root = result.value.root,
                        rows = result.value.rows,
                        loading = false,
                        mutating = false,
                    ),
                )
            }
            is Outcome.Err -> updateHost(hostId) {
                it.copy(files = it.files.copy(loading = false, errorText = result.failure.toUserMessage()))
            }
        }
    }

    private suspend fun runSearch(hostId: String) {
        val session = manager.session(hostId)
        if (session == null) {
            updateHost(hostId) {
                it.copy(connected = false, search = it.search.copy(loading = false, errorText = "Not connected to this host."))
            }
            return
        }
        if (!session.supports(UhpMethods.SEARCH_QUERY)) return
        val state = _uhp.value[hostId]
        val query = state?.search?.query?.trim().orEmpty()
        if (query.isEmpty()) return
        val runtime = manager.hosts.value.firstOrNull { it.profile.id == hostId }
        val scopeLabel = searchScopeLabel(runtime, state?.files?.root.orEmpty())
        updateHost(hostId) {
            it.copy(
                connected = true,
                search = it.search.copy(
                    loading = true,
                    errorText = null,
                    searched = true,
                    scopeLabel = scopeLabel,
                ),
            )
        }
        when (val result = session.querySearch(query, scope = SearchScope.FILES, limit = 50)) {
            is Outcome.Ok -> updateHost(hostId) {
                it.copy(
                    search = it.search.copy(
                        result = result.value,
                        matches = result.value.matches,
                        loading = false,
                        errorText = null,
                        searched = true,
                        scopeLabel = scopeLabel,
                    ),
                )
            }
            is Outcome.Err -> updateHost(hostId) {
                it.copy(
                    search = it.search.copy(
                        loading = false,
                        errorText = result.failure.toUserMessage(),
                        searched = true,
                        matches = emptyList(),
                        result = null,
                    ),
                )
            }
        }
    }

    private suspend fun refreshWorktrees(hostId: String) {
        val session = manager.session(hostId)
        if (session == null) {
            updateHost(hostId) {
                it.copy(connected = false, worktrees = it.worktrees.copy(loading = false, errorText = "Not connected to this host."))
            }
            return
        }
        if (!session.supports(UhpMethods.WORKTREE_LIST)) return
        val runtime = manager.hosts.value.firstOrNull { it.profile.id == hostId }
        val workspace = preferredGitWorkspace(runtime, _uhp.value[hostId]?.files?.root.orEmpty())
        updateHost(hostId) { it.copy(connected = true, worktrees = it.worktrees.copy(loading = true, errorText = null)) }
        var usedWorkspace = workspace
        val result = session.listWorktrees(workspace).let { first ->
            if (first is Outcome.Err && workspace != null) {
                usedWorkspace = null
                session.listWorktrees()
            } else {
                first
            }
        }
        when (result) {
            is Outcome.Ok -> updateHost(hostId) {
                it.copy(
                    worktrees = it.worktrees.copy(
                        worktrees = result.value,
                        loading = false,
                        mutating = false,
                        workspace = usedWorkspace,
                        errorText = null,
                    ),
                )
            }
            is Outcome.Err -> updateHost(hostId) {
                it.copy(
                    worktrees = it.worktrees.copy(
                        loading = false,
                        workspace = null,
                        errorText = result.failure.toUserMessage(),
                    ),
                )
            }
        }
    }

    private suspend fun refreshAutomations(hostId: String) {
        val session = manager.session(hostId)
        if (session == null) {
            updateHost(hostId) {
                it.copy(
                    connected = false,
                    automations = it.automations.copy(loading = false, errorText = "Not connected to this host."),
                )
            }
            return
        }
        if (!session.supports(UhpMethods.AUTOMATION_LIST)) return
        updateHost(hostId) { it.copy(connected = true, automations = it.automations.copy(loading = true, errorText = null)) }
        val listed = when (val result = session.listAutomations()) {
            is Outcome.Ok -> result.value
            is Outcome.Err -> {
                updateHost(hostId) {
                    it.copy(automations = it.automations.copy(loading = false, errorText = result.failure.toUserMessage()))
                }
                return
            }
        }
        val health = if (session.supports(UhpMethods.AUTOMATION_HEALTH)) {
            when (val result = session.automationHealth()) {
                is Outcome.Ok -> result.value
                is Outcome.Err -> null
            }
        } else {
            null
        }
        updateHost(hostId) {
            it.copy(
                automations = it.automations.copy(
                    automations = listed,
                    health = health ?: it.automations.health,
                    loading = false,
                    mutating = false,
                ),
            )
        }
    }

    private fun mutateAutomation(
        hostId: String,
        id: String,
        method: String,
        call: suspend (LuviaSession) -> Outcome<*>,
    ) {
        val session = manager.session(hostId) ?: return
        val state = _uhp.value[hostId] ?: return
        if (!state.canMutate || state.automations.mutating || !session.supports(method) || id.isBlank()) return
        updateHost(hostId) { it.copy(automations = it.automations.copy(mutating = true, errorText = null)) }
        viewModelScope.launch {
            when (val result = call(session)) {
                is Outcome.Ok -> {
                    updateHost(hostId) { it.copy(automations = it.automations.copy(mutating = false)) }
                    refreshAutomations(hostId)
                }
                is Outcome.Err -> updateHost(hostId) {
                    it.copy(automations = it.automations.copy(mutating = false, errorText = result.failure.toUserMessage()))
                }
            }
        }
    }

    private suspend fun refreshLayout(hostId: String) {
        val session = manager.session(hostId)
        val runtime = manager.hosts.value.firstOrNull { it.profile.id == hostId }
        if (session == null) {
            updateHost(hostId) {
                it.copy(connected = false, layout = it.layout.copy(loading = false, errorText = "Not connected to this host."))
            }
            return
        }
        val canListWorkspaces = session.supports(UhpMethods.WORKSPACE_LIST)
        val canListPanes = session.supports(UhpMethods.PANE_LIST)
        if (!canListWorkspaces && !canListPanes) return
        updateHost(hostId) { it.copy(connected = true, layout = it.layout.copy(loading = true, errorText = null)) }
        var error: String? = null
        val workspaces = if (canListWorkspaces) {
            when (val result = session.listWorkspaces()) {
                is Outcome.Ok -> result.value
                is Outcome.Err -> {
                    error = result.failure.toUserMessage()
                    runtime?.snapshot?.workspaces.orEmpty()
                }
            }
        } else {
            runtime?.snapshot?.workspaces.orEmpty()
        }
        val panes = if (canListPanes) {
            when (val result = session.listPanes()) {
                is Outcome.Ok -> result.value.panes
                is Outcome.Err -> {
                    error = error ?: result.failure.toUserMessage()
                    emptyList()
                }
            }
        } else {
            emptyList()
        }
        updateHost(hostId) {
            it.copy(
                layout = it.layout.copy(
                    workspaces = workspaces,
                    panes = panes,
                    loading = false,
                    mutating = false,
                    errorText = error,
                ),
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
    val raw = when (this) {
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
        is Failure.TerminalGone ->
            "This pane is no longer available. Pick a live pane below, or refresh."
        is Failure.ResyncRequired -> message
        is Failure.RevisionConflict -> message
        is Failure.ContentRevisionConflict ->
            "The agent screen changed. Refresh and send the keys again."
        is Failure.AgentPromptBusy -> "The agent is still handling a previous message. Wait for it to finish."
        is Failure.FrameTooLarge -> message
        is Failure.ServerBusy -> message
        is Failure.UnknownMajor -> "The host speaks an unsupported protocol ($name $major)."
        is Failure.CapabilityMissing -> "The host does not support $method."
        is Failure.IndeterminateMutation -> "The host may already have applied this change. Do not retry automatically."
    }
    return raw.sanitizeHostError()
}

internal fun String.sanitizeHostError(): String =
    when {
        contains("not a git repository", ignoreCase = true) ->
            "This workspace is not a git repository. Focus a project workspace in Layout, then refresh."
        contains("terminal identity no longer exists", ignoreCase = true) ->
            "This pane is no longer available. Pick a live pane below, or refresh."
        else -> this
    }

private fun isGoneTerminal(failure: Failure): Boolean =
    failure is Failure.TerminalGone ||
        failure is Failure.StaleRoute ||
        failure is Failure.StaleServer ||
        failure.toUserMessage().contains("no longer available", ignoreCase = true)

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

private fun searchScopeLabel(runtime: HostRuntime?, filesRoot: String): String? {
    if (filesRoot.isNotBlank()) return filesRoot
    val workspace = runtime?.snapshot?.workspaces?.firstOrNull { it.active }
        ?: runtime?.snapshot?.workspaces?.firstOrNull()
    val name = workspace?.name?.takeIf { it.isNotBlank() }
    val cwd = workspace?.cwd?.takeIf { it.isNotBlank() }
    return listOfNotNull(name, cwd).distinct().joinToString(" · ").takeIf { it.isNotBlank() }
}

private fun preferredGitWorkspace(runtime: HostRuntime?, filesRoot: String): Int? {
    val workspaces = runtime?.snapshot?.workspaces.orEmpty()
    if (workspaces.isEmpty()) return null
    val active = workspaces.firstOrNull { it.active }
    fun WorkspaceSummary.isGit(): Boolean = !branch.isNullOrBlank()
    if (active != null && active.isGit()) return null
    if (filesRoot.isNotBlank()) {
        val match = workspaces.firstOrNull { workspace ->
            val cwd = workspace.cwd ?: return@firstOrNull false
            filesRoot == cwd || filesRoot.startsWith("$cwd/")
        }
        if (match != null) return match.index
    }
    val focusedCwd = runtime?.snapshot?.panes?.firstOrNull { it.focused }?.cwd
        ?: runtime?.snapshot?.agents?.firstOrNull { it.focused }?.cwd
    if (!focusedCwd.isNullOrBlank()) {
        val match = workspaces.firstOrNull { workspace ->
            val cwd = workspace.cwd ?: return@firstOrNull false
            focusedCwd == cwd || focusedCwd.startsWith("$cwd/")
        }
        if (match != null) return match.index
    }
    return workspaces.firstOrNull { it.isGit() }?.index
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
    taskClaim = supports(UhpMethods.TASK_CLAIM),
    taskDelete = supports(UhpMethods.TASK_DELETE),
    agentSessions = supports(UhpMethods.AGENT_SESSIONS),
    agentResume = supports(UhpMethods.AGENT_RESUME),
    agentFork = supports(UhpMethods.AGENT_FORK),
    agentName = supports(UhpMethods.AGENT_NAME),
    filesTree = supports(UhpMethods.FILES_TREE),
    filesOpen = supports(UhpMethods.FILES_OPEN),
    filesReveal = supports(UhpMethods.FILES_REVEAL),
    filesRefresh = supports(UhpMethods.FILES_REFRESH),
    searchQuery = supports(UhpMethods.SEARCH_QUERY),
    searchActivate = supports(UhpMethods.SEARCH_ACTIVATE),
    worktreeList = supports(UhpMethods.WORKTREE_LIST),
    worktreeCreate = supports(UhpMethods.WORKTREE_CREATE),
    worktreeOpen = supports(UhpMethods.WORKTREE_OPEN),
    worktreeRemove = supports(UhpMethods.WORKTREE_REMOVE),
    automationList = supports(UhpMethods.AUTOMATION_LIST),
    automationEnable = supports(UhpMethods.AUTOMATION_ENABLE),
    automationDisable = supports(UhpMethods.AUTOMATION_DISABLE),
    automationRun = supports(UhpMethods.AUTOMATION_RUN),
    automationHealth = supports(UhpMethods.AUTOMATION_HEALTH),
    workspaceList = supports(UhpMethods.WORKSPACE_LIST),
    workspaceFocus = supports(UhpMethods.WORKSPACE_FOCUS),
    workspaceClose = supports(UhpMethods.WORKSPACE_CLOSE),
    paneList = supports(UhpMethods.PANE_LIST),
    paneFocus = supports(UhpMethods.PANE_FOCUS),
    paneClose = supports(UhpMethods.PANE_CLOSE),
    paneRename = supports(UhpMethods.PANE_RENAME),
)


private fun searchKind(kind: String): SearchKind? =
    when (kind.lowercase()) {
        "session" -> SearchKind.SESSION
        "folder" -> SearchKind.FOLDER
        "tab" -> SearchKind.TAB
        "pane" -> SearchKind.PANE
        "agent" -> SearchKind.AGENT
        "file" -> SearchKind.FILE
        "output" -> SearchKind.OUTPUT
        else -> null
    }

private fun Failure.isUnconfirmed(): Boolean =
    this is Failure.IndeterminateMutation ||
        this is Failure.Transport ||
        this is Failure.Bridge ||
        this is Failure.Closed
