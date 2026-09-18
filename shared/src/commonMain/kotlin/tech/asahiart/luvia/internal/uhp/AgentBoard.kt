package tech.asahiart.luvia.internal.uhp

import tech.asahiart.luvia.AgentDetailState
import tech.asahiart.luvia.AgentKey
import tech.asahiart.luvia.Failure
import tech.asahiart.luvia.HostRole
import tech.asahiart.luvia.HostRuntime
import tech.asahiart.luvia.Outcome
import tech.asahiart.luvia.UhpMethods
import tech.asahiart.luvia.UnconfirmedKind
import tech.asahiart.luvia.forkAgent
import tech.asahiart.luvia.isLostMutation
import tech.asahiart.luvia.nameAgent
import tech.asahiart.luvia.resumeAgent
import tech.asahiart.luvia.toCapabilities
import tech.asahiart.luvia.userMessage

internal class AgentBoard(private val ctx: UhpContext) {
    fun applyRuntime(runtime: HostRuntime) {
        val session = ctx.session()
        val snapshotAgents = runtime.snapshot?.agents.orEmpty()
        val openPane = ctx.value().agentDetail.paneId
        val wasOpen = ctx.value().agentDetail.open
        val oldStatus = ctx.value().agentDetail.summary?.status
        ctx.update { current ->
            current.copy(
                connected = session != null,
                isObserver = runtime.profile.role == HostRole.Observer,
                capabilities = session?.toCapabilities() ?: if (session == null) {
                    tech.asahiart.luvia.HostCapabilities()
                } else {
                    current.capabilities
                },
                agents = snapshotAgents.ifEmpty { current.agents },
                backend = runtime.backend,
                agentDetail = current.agentDetail.copy(
                    summary = snapshotAgents.firstOrNull { it.paneId == openPane }
                        ?: current.agentDetail.summary,
                ),
            )
        }

        val newStatus = snapshotAgents.firstOrNull { it.paneId == openPane }?.status
        if (wasOpen && openPane != null && newStatus != null && oldStatus != null && newStatus != oldStatus) {
            ctx.launch { loadDetail(openPane) }
        }
    }

    fun load() {
        ctx.launch {
            val session = ctx.session()
            val runtime = ctx.runtime()
            if (session == null) {
                ctx.update {
                    it.copy(
                        connected = false,
                        loading = false,
                        isObserver = runtime?.profile?.role == HostRole.Observer,
                        capabilities = tech.asahiart.luvia.HostCapabilities(),
                        agents = runtime?.snapshot?.agents.orEmpty().ifEmpty { it.agents },
                        backend = runtime?.backend ?: "luvus",
                    )
                }
                return@launch
            }
            ctx.update {
                it.copy(
                    connected = true,
                    loading = true,
                    errorText = null,
                    isObserver = runtime?.profile?.role == HostRole.Observer,
                    capabilities = session.toCapabilities(),
                    backend = runtime?.backend ?: it.backend,
                )
            }

            val snapshotAgents = runtime?.snapshot?.agents.orEmpty()
            val listed =
                if (session.supports(UhpMethods.AGENT_LIST)) {
                    when (val result = session.listAgents()) {
                        is Outcome.Ok -> result.value
                        is Outcome.Err -> {
                            ctx.update { it.copy(loading = false, errorText = result.failure.userMessage()) }
                            return@launch
                        }
                    }
                } else {
                    emptyList()
                }
            val mission =
                if (session.supports(UhpMethods.MISSION_SNAPSHOT)) {
                    when (val result = session.missionSnapshot()) {
                        is Outcome.Ok -> result.value
                        is Outcome.Err -> null
                    }
                } else {
                    null
                }
            val sessions =
                if (session.supports(UhpMethods.AGENT_SESSIONS)) {
                    when (val result = session.listAgentSessions()) {
                        is Outcome.Ok -> result.value
                        is Outcome.Err -> emptyList()
                    }
                } else {
                    emptyList()
                }
            ctx.update { current ->
                val agents = listed.ifEmpty { snapshotAgents }.ifEmpty { current.agents }
                val openPane = current.agentDetail.paneId
                current.copy(
                    loading = false,
                    agents = agents,
                    mission = mission ?: current.mission,
                    agentSessions = sessions,
                    agentDetail = current.agentDetail.copy(
                        summary = agents.firstOrNull { it.paneId == openPane } ?: current.agentDetail.summary,
                    ),
                )
            }
            val openPane = ctx.value().agentDetail.takeIf { it.open }?.paneId
            if (openPane != null) {
                loadDetail(openPane)
            }
        }
    }

    fun open(paneId: String) {
        val current = ctx.value()
        val existing = current.agentDetail
        if (existing.paneId != null &&
            existing.paneId != paneId &&
            (existing.unconfirmed != null || existing.sending)
        ) {
            val name = existing.summary?.name ?: existing.summary?.agent ?: existing.paneId
            val message =
                if (existing.unconfirmed != null) {
                    "Unconfirmed result for $name is still pending. Check it before opening another Agent."
                } else {
                    "An Agent action is still in progress for $name. Wait before opening another Agent."
                }
            ctx.update { it.copy(agentDetail = it.agentDetail.copy(errorText = message)) }
            return
        }
        val summary = current.agents.firstOrNull { it.paneId == paneId }
        val samePane = existing.paneId == paneId
        ctx.update {
            it.copy(
                selectedWorkspaceId = summary?.workspaceId ?: it.selectedWorkspaceId,
                agentDetail =
                    if (samePane) {
                        it.agentDetail.copy(
                            open = true,
                            paneId = paneId,
                            summary = summary ?: it.agentDetail.summary,
                            loading = true,
                        )
                    } else {
                        AgentDetailState(
                            paneId = paneId,
                            open = true,
                            summary = summary,
                            loading = true,
                        )
                    },
            )
        }
        ctx.launch { loadDetail(paneId) }
    }

    fun close() {
        ctx.update { it.copy(agentDetail = it.agentDetail.copy(open = false)) }
    }

    fun setDraft(text: String) {
        ctx.update { it.copy(agentDetail = it.agentDetail.copy(draft = text)) }
    }

    fun setShowName(show: Boolean) {
        ctx.update {
            it.copy(
                agentDetail = it.agentDetail.copy(
                    showName = show,
                    nameDraft = if (show) it.agentDetail.nameDraft else "",
                ),
            )
        }
    }

    fun setNameDraft(text: String) {
        ctx.update { it.copy(agentDetail = it.agentDetail.copy(nameDraft = text)) }
    }

    fun setShowFork(show: Boolean) {
        ctx.update {
            it.copy(
                agentDetail = it.agentDetail.copy(
                    showFork = show,
                    forkDraft = if (show) it.agentDetail.forkDraft else "",
                ),
            )
        }
    }

    fun setForkDraft(text: String) {
        ctx.update { it.copy(agentDetail = it.agentDetail.copy(forkDraft = text)) }
    }

    fun prompt(text: String) {
        val session = ctx.session() ?: return
        val state = ctx.value()
        val paneId = state.agentDetail.paneId ?: return
        if (!state.canMutate || state.agentDetail.sending || state.agentDetail.unconfirmed != null) return
        if (!session.supports(UhpMethods.AGENT_PROMPT)) return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        updatePane(paneId) { it.copy(sending = true, errorText = null) }
        ctx.launch {
            when (val result = session.promptAgent(paneId, trimmed, wait = false)) {
                is Outcome.Ok -> {
                    updatePane(paneId) { detail ->
                        detail.copy(
                            sending = false,
                            unconfirmed = null,
                            draft = if (detail.draft.trim() == trimmed) "" else detail.draft,
                        )
                    }
                    loadDetail(paneId)
                }
                is Outcome.Err -> applyMutationFailure(paneId, UnconfirmedKind.AgentPrompt, result.failure)
            }
        }
    }

    fun sendKeys(keys: List<AgentKey>) {
        val session = ctx.session() ?: return
        val state = ctx.value()
        val paneId = state.agentDetail.paneId ?: return
        if (!state.canMutate || state.agentDetail.sending || state.agentDetail.unconfirmed != null) return
        if (!session.supports(UhpMethods.AGENT_KEYS)) return
        if (keys.isEmpty()) return
        updatePane(paneId) { it.copy(sending = true, errorText = null) }
        ctx.launch {
            val fence = state.agentDetail.transcript
            val canFence = fence?.contentRevision != null && !fence.terminalId.isNullOrBlank()
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
                    updatePane(paneId) { it.copy(sending = false, unconfirmed = null) }
                    loadDetail(paneId)
                }
                is Outcome.Err -> applyMutationFailure(paneId, UnconfirmedKind.AgentKeys, result.failure)
            }
        }
    }

    fun check() {
        val paneId = ctx.value().agentDetail.paneId ?: return
        ctx.launch {
            val session = ctx.session()
            if (session == null) {
                updatePane(paneId) { it.copy(loading = false, errorText = NOT_CONNECTED) }
                return@launch
            }
            if (!session.supports(UhpMethods.AGENT_GET) && !session.supports(UhpMethods.AGENT_READ)) {
                updatePane(paneId) {
                    it.copy(loading = false, errorText = "The host does not support agent.get or agent.read.")
                }
                return@launch
            }
            if (loadDetail(paneId)) {
                updatePane(paneId) { it.copy(unconfirmed = null) }
            }
        }
    }

    fun resume(sessionId: String) {
        val session = ctx.session() ?: return
        val state = ctx.value()
        if (!state.canMutate || !session.supports(UhpMethods.AGENT_RESUME)) return
        if (sessionId.isBlank()) return
        ctx.update { it.copy(loading = true, errorText = null) }
        ctx.launch {
            when (val result = session.resumeAgent(sessionId)) {
                is Outcome.Ok -> {
                    ctx.update { it.copy(loading = false) }
                    load()
                }
                is Outcome.Err -> ctx.update { it.copy(loading = false, errorText = result.failure.userMessage()) }
            }
        }
    }

    fun name() {
        val session = ctx.session() ?: return
        val state = ctx.value()
        val paneId = state.agentDetail.paneId ?: return
        if (!state.canMutate || state.agentDetail.sending || !session.supports(UhpMethods.AGENT_NAME)) return
        val name = state.agentDetail.nameDraft.trim()
        if (name.isEmpty()) return
        updatePane(paneId) { it.copy(sending = true, errorText = null) }
        ctx.launch {
            when (val result = session.nameAgent(pane = paneId, name = name)) {
                is Outcome.Ok -> {
                    updatePane(paneId) {
                        it.copy(sending = false, showName = false, nameDraft = "")
                    }
                    load()
                }
                is Outcome.Err -> {
                    updatePane(paneId) { it.copy(sending = false, errorText = result.failure.userMessage()) }
                }
            }
        }
    }

    fun fork() {
        val session = ctx.session() ?: return
        val state = ctx.value()
        val paneId = state.agentDetail.paneId ?: return
        if (!state.canMutate || state.agentDetail.sending || !session.supports(UhpMethods.AGENT_FORK)) return
        val name = state.agentDetail.forkDraft.trim().ifBlank { null }
        updatePane(paneId) { it.copy(sending = true, errorText = null) }
        ctx.launch {
            when (val result = session.forkAgent(target = paneId, name = name)) {
                is Outcome.Ok -> {
                    updatePane(paneId) { it.copy(sending = false, showFork = false, forkDraft = "") }
                    load()
                }
                is Outcome.Err -> {
                    updatePane(paneId) { it.copy(sending = false, errorText = result.failure.userMessage()) }
                }
            }
        }
    }

    suspend fun loadDetail(paneId: String): Boolean {
        val session = ctx.session()
        if (session == null) {
            updatePane(paneId) { it.copy(loading = false, errorText = NOT_CONNECTED) }
            return false
        }
        val canGet = session.supports(UhpMethods.AGENT_GET)
        val canRead = session.supports(UhpMethods.AGENT_READ)
        if (!canGet && !canRead) {
            updatePane(paneId) { it.copy(loading = false) }
            return false
        }
        var error: String? = null
        var readOk = false
        val detail =
            if (canGet) {
                when (val result = session.getAgent(paneId)) {
                    is Outcome.Ok -> {
                        readOk = true
                        result.value
                    }
                    is Outcome.Err -> {
                        error = result.failure.userMessage()
                        null
                    }
                }
            } else {
                null
            }
        val transcript =
            if (canRead) {
                when (val result = session.readAgent(paneId)) {
                    is Outcome.Ok -> {
                        readOk = true
                        result.value
                    }
                    is Outcome.Err -> {
                        error = error ?: result.failure.userMessage()
                        null
                    }
                }
            } else {
                null
            }
        var applied = false
        ctx.update { current ->
            if (current.agentDetail.paneId != paneId) {
                current
            } else {
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

    private fun applyMutationFailure(paneId: String, kind: UnconfirmedKind, failure: Failure) {
        updatePane(paneId) {
            if (failure.isLostMutation()) {
                it.copy(sending = false, unconfirmed = kind, errorText = null)
            } else {
                it.copy(sending = false, errorText = failure.userMessage())
            }
        }
    }

    private fun updatePane(paneId: String, transform: (AgentDetailState) -> AgentDetailState) {
        ctx.update { current ->
            if (current.agentDetail.paneId != paneId) current
            else current.copy(agentDetail = transform(current.agentDetail))
        }
    }
}
