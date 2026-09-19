package tech.asahiart.luvia.internal.uhp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import tech.asahiart.luvia.AcpEvent
import tech.asahiart.luvia.AcpRunState
import tech.asahiart.luvia.AcpSession
import tech.asahiart.luvia.AcpState
import tech.asahiart.luvia.AcpStopReason
import tech.asahiart.luvia.AcpToolCall
import tech.asahiart.luvia.AcpToolCallUpdate
import tech.asahiart.luvia.AcpToolStatus
import tech.asahiart.luvia.AcpTranscriptItem
import tech.asahiart.luvia.AcpTranscriptRole
import tech.asahiart.luvia.Outcome
import tech.asahiart.luvia.UhpMethods
import tech.asahiart.luvia.userMessage

internal class AcpBoard(private val ctx: UhpContext) {
    private var session: AcpSession? = null
    private var collector: Job? = null
    private var nextItem: Int = 0

    fun loadAgents() {
        val session = ctx.session() ?: return
        if (!session.supports(UhpMethods.ACP_AGENTS)) return
        ctx.update { it.copy(acp = it.acp.copy(agentsLoading = true, errorText = null)) }
        ctx.launch {
            when (val result = session.acpAgents()) {
                is Outcome.Ok ->
                    ctx.update { it.copy(acp = it.acp.copy(agents = result.value, agentsLoading = false)) }
                is Outcome.Err ->
                    ctx.update {
                        it.copy(acp = it.acp.copy(agentsLoading = false, errorText = result.failure.userMessage()))
                    }
            }
        }
    }

    fun setShowLaunch(show: Boolean) {
        ctx.update { it.copy(acp = it.acp.copy(showLaunch = show)) }
    }

    fun setLaunchAgent(id: String?) {
        ctx.update { it.copy(acp = it.acp.copy(launchAgentId = id)) }
    }

    fun setLaunchCwd(cwd: String) {
        ctx.update { it.copy(acp = it.acp.copy(launchCwd = cwd)) }
    }

    fun setDraft(text: String) {
        ctx.update { it.copy(acp = it.acp.copy(draft = text)) }
    }

    fun launch() {
        val client = ctx.session() ?: return
        val state = ctx.value()
        if (!state.canMutate || !client.supports(UhpMethods.ACP_SESSION_OPEN)) return
        val agentId = state.acp.launchAgentId ?: return
        val cwd =
            state.acp.launchCwd.ifBlank {
                state.layout.workspaces.firstOrNull()?.cwd.orEmpty()
            }
        if (cwd.isBlank()) {
            ctx.update { it.copy(acp = it.acp.copy(errorText = "Choose a directory")) }
            return
        }
        stop()
        nextItem = 0
        ctx.update {
            it.copy(
                acp =
                    it.acp.copy(
                        run = AcpRunState.Starting,
                        open = true,
                        viewing = true,
                        showLaunch = false,
                        info = null,
                        transcript = emptyList(),
                        plan = emptyList(),
                        permission = null,
                        errorText = null,
                        exitMessage = null,
                    ),
            )
        }
        ctx.launch {
            when (val result = client.openAcpSession(agentId, cwd)) {
                is Outcome.Ok -> {
                    session = result.value
                    ctx.update {
                        it.copy(acp = it.acp.copy(info = result.value.info, run = AcpRunState.Ready, viewing = true))
                    }
                    collector =
                        ctx.launch {
                            try {
                                result.value.events().collect { event -> applyEvent(event) }
                            } catch (e: CancellationException) {
                                throw e
                            }
                        }
                }
                is Outcome.Err ->
                    ctx.update {
                        it.copy(
                            acp =
                                it.acp.copy(
                                    run = AcpRunState.Idle,
                                    open = false,
                                    viewing = false,
                                    errorText = result.failure.userMessage(),
                                ),
                        )
                    }
            }
        }
    }

    fun prompt() {
        val acpSession = session ?: return
        val state = ctx.value()
        if (!state.canMutate || !state.acp.open) return
        val text = state.acp.draft.trim()
        if (text.isEmpty()) return
        ctx.update {
            it.copy(
                acp =
                    it.acp.copy(
                        draft = "",
                        run = AcpRunState.Working,
                        errorText = null,
                        transcript =
                            it.acp.transcript +
                                AcpTranscriptItem.Message(
                                    id = nextId(),
                                    role = AcpTranscriptRole.User,
                                    text = text,
                                    streaming = false,
                                ),
                    ),
            )
        }
        ctx.launch {
            when (val result = acpSession.prompt(text)) {
                is Outcome.Ok -> Unit
                is Outcome.Err ->
                    ctx.update { it.copy(acp = it.acp.copy(errorText = result.failure.userMessage())) }
            }
        }
    }

    fun answerPermission(optionId: String) {
        val acpSession = session ?: return
        val request = ctx.value().acp.permission ?: return
        ctx.update { it.copy(acp = it.acp.copy(permission = null, run = AcpRunState.Working)) }
        ctx.launch {
            when (val result = acpSession.answerPermission(request.requestId, optionId)) {
                is Outcome.Ok -> Unit
                is Outcome.Err ->
                    ctx.update { it.copy(acp = it.acp.copy(errorText = result.failure.userMessage())) }
            }
        }
    }

    fun cancel() {
        val acpSession = session ?: return
        ctx.launch {
            when (val result = acpSession.cancel()) {
                is Outcome.Ok -> Unit
                is Outcome.Err ->
                    ctx.update { it.copy(acp = it.acp.copy(errorText = result.failure.userMessage())) }
            }
        }
    }

    fun view() {
        ctx.update { state ->
            if (!state.acp.open) state else state.copy(acp = state.acp.copy(viewing = true))
        }
    }

    fun hide() {
        ctx.update { it.copy(acp = it.acp.copy(viewing = false)) }
    }

    fun close() {
        stop()
        ctx.update {
            it.copy(
                acp = it.acp.copy(open = false, viewing = false, run = AcpRunState.Idle, permission = null),
            )
        }
    }

    private fun stop() {
        collector?.cancel()
        collector = null
        session?.close()
        session = null
    }

    private fun applyEvent(event: AcpEvent) {
        ctx.update { state -> state.copy(acp = foldEvent(state.acp, event)) }
    }

    private fun foldEvent(acp: AcpState, event: AcpEvent): AcpState =
        when (event) {
            is AcpEvent.AgentMessage ->
                appendChunk(acp, AcpTranscriptRole.Agent, event.text).maybeWorking()
            is AcpEvent.AgentThought ->
                appendChunk(acp, AcpTranscriptRole.Thought, event.text).maybeWorking()
            is AcpEvent.UserMessage -> appendChunk(acp, AcpTranscriptRole.User, event.text)
            is AcpEvent.ToolCall -> appendTool(acp, event.call).maybeWorking()
            is AcpEvent.ToolCallUpdate -> mergeTool(acp, event.update)
            is AcpEvent.Plan -> acp.copy(plan = event.entries)
            is AcpEvent.Permission ->
                acp.copy(permission = event.request, run = AcpRunState.AwaitingPermission)
            is AcpEvent.TurnEnded -> endTurn(acp, event.stopReason)
            is AcpEvent.Exited -> acp.copy(run = AcpRunState.Exited, exitMessage = event.message)
            is AcpEvent.Failed -> acp.copy(errorText = event.failure.userMessage())
        }

    private fun appendChunk(acp: AcpState, role: AcpTranscriptRole, text: String): AcpState {
        val last = acp.transcript.lastOrNull()
        val transcript =
            if (last is AcpTranscriptItem.Message && last.role == role && last.streaming) {
                acp.transcript.dropLast(1) + last.copy(text = last.text + text)
            } else {
                acp.transcript +
                    AcpTranscriptItem.Message(
                        id = nextId(),
                        role = role,
                        text = text,
                        streaming = true,
                    )
            }
        return acp.copy(transcript = transcript)
    }

    private fun appendTool(acp: AcpState, call: AcpToolCall): AcpState =
        acp.copy(transcript = acp.transcript + AcpTranscriptItem.Tool(id = call.toolCallId, call = call))

    private fun mergeTool(acp: AcpState, update: AcpToolCallUpdate): AcpState {
        val index = acp.transcript.indexOfFirst { item ->
            item is AcpTranscriptItem.Tool && item.call.toolCallId == update.toolCallId
        }
        if (index < 0) {
            val call =
                AcpToolCall(
                    toolCallId = update.toolCallId,
                    title = update.title ?: "",
                    kind = update.kind,
                    status = update.status ?: AcpToolStatus.Unknown,
                    summary = update.summary,
                )
            return acp.copy(transcript = acp.transcript + AcpTranscriptItem.Tool(id = call.toolCallId, call = call))
        }
        val existing = acp.transcript[index] as AcpTranscriptItem.Tool
        val merged =
            existing.call.copy(
                title = update.title ?: existing.call.title,
                kind = update.kind ?: existing.call.kind,
                status = update.status ?: existing.call.status,
                summary = update.summary ?: existing.call.summary,
            )
        val transcript = acp.transcript.toMutableList()
        transcript[index] = existing.copy(call = merged)
        return acp.copy(transcript = transcript)
    }

    private fun endTurn(acp: AcpState, stopReason: AcpStopReason): AcpState {
        val last = acp.transcript.lastOrNull()
        val marked =
            if (last is AcpTranscriptItem.Message && last.streaming) {
                acp.transcript.dropLast(1) + last.copy(streaming = false)
            } else {
                acp.transcript
            }
        return acp.copy(
            transcript = marked + AcpTranscriptItem.Turn(id = nextId(), stopReason = stopReason),
            run = AcpRunState.Ready,
        )
    }

    private fun AcpState.maybeWorking(): AcpState =
        if (run == AcpRunState.Ready) copy(run = AcpRunState.Working) else this

    private fun nextId(): String {
        nextItem += 1
        return "acp-$nextItem"
    }
}
