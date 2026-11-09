package tech.asahiart.luvia.thread

import tech.asahiart.luvia.AcpHistorySession
import tech.asahiart.luvia.AcpPermissionKind
import tech.asahiart.luvia.AcpRunState
import tech.asahiart.luvia.AcpState
import tech.asahiart.luvia.AcpTranscriptItem
import tech.asahiart.luvia.AgentKey
import tech.asahiart.luvia.AgentKind
import tech.asahiart.luvia.AgentStatus
import tech.asahiart.luvia.AgentSummary
import tech.asahiart.luvia.HostUhpState
import tech.asahiart.luvia.command.SlashCommand
import tech.asahiart.luvia.command.SlashCommandCatalog
import tech.asahiart.luvia.stripAnsi

/** A pane Agent or an ACP session, shown the same way. */
public data class AgentThread(
    public val id: String,
    public val kind: AgentKind,
    public val hostId: String,
    public val projectKey: String?,
    public val projectLabel: String?,
    public val title: String,
    public val agentType: String?,
    public val status: AgentStatus,
    public val ask: Ask?,
    public val summary: String?,
    public val updatedEpochMs: Long?,
    public val paneId: String? = null,
    public val acpSessionId: String? = null,
    /** Working directory, when the host actually named one. Used to dedup ACP history. */
    public val cwd: String? = null,
    /** True when this row is an ACP session that is not open yet. Tap resumes it. */
    public val acpHistory: Boolean = false,
    public val acpAgentId: String? = null,
) {
    public val needsYou: Boolean get() = ask != null

    public fun commands(): List<SlashCommand> = SlashCommandCatalog.forAgent(agentType ?: title)
}

public enum class AskTone { Primary, Neutral, Destructive }

public sealed class AskAction {
    /** Delivered as one Agent prompt. */
    public data class Prompt(public val text: String) : AskAction()

    public data class Keys(public val keys: List<AgentKey>) : AskAction()

    public data class AcpOption(public val requestId: String, public val optionId: String) : AskAction()
}

public data class AskOption(
    public val label: String,
    public val tone: AskTone,
    public val action: AskAction,
    /** What choosing this option costs or changes, when the host actually said so. */
    public val consequence: String? = null,
    public val recommended: Boolean = false,
)

/**
 * Shown when a waiting agent has neither a permission title nor a question.
 * Decision cards and list rows share this string.
 */
public const val DECISION_QUESTION_FALLBACK: String = "等你确认"

/**
 * A decision an Agent is waiting on. [question] is the permission title or the
 * agent's last question, never the pane status bar. It is
 * [DECISION_QUESTION_FALLBACK] when neither is available.
 * [index] and [total] describe a queue of decisions. They default to a single card.
 */
public data class Ask(
    public val question: String?,
    public val options: List<AskOption>,
    public val index: Int = 1,
    public val total: Int = 1,
)

public fun HostUhpState.threads(hostId: String): List<AgentThread> {
    val panes = agents.map { it.toThread(hostId, cachedTranscriptFor(it.paneId, it.status)) }
    val live = acp.takeIf { it.open }?.toThread(hostId)
    val base = if (live == null) panes else panes + live
    return mergeAcpHistory(base, acp.history.map { it.toHistoryThread(hostId) })
}

/**
 * Live ACP row to open after launch or resume.
 *
 * `open` is set before the host names a session, and the live row id is then
 * the placeholder `"acp"`. That id disappears on ack. Null until
 * [AcpState.info] carries a session id, then the row `acp:<sessionId>`.
 */
public fun awaitingAcpThread(state: HostUhpState, hostId: String): AgentThread? {
    if (!state.acp.open) return null
    val sessionId = state.acp.info?.sessionId?.takeIf { it.isNotBlank() } ?: return null
    return state.threads(hostId).firstOrNull { thread ->
        thread.kind == AgentKind.Acp &&
            !thread.acpHistory &&
            thread.id != "acp" &&
            (thread.id == "acp:$sessionId" || thread.acpSessionId == sessionId)
    }
}

private fun HostUhpState.cachedTranscriptFor(paneId: String, status: AgentStatus): String? {
    agentDetail.transcript?.takeIf { agentDetail.paneId == paneId }?.text?.let { return it }
    val preview = panePreviews[paneId] ?: return null
    if (preview.status != status || preview.text.isBlank()) return null
    return preview.text
}

internal fun AgentSummary.toThread(hostId: String, transcript: String?): AgentThread {
    val plain = transcript?.let(::stripAnsi)
    val last = lastNonEmptyLine(plain)
    val waiting = status == AgentStatus.Blocked
    return AgentThread(
        id = paneId,
        kind = AgentKind.Pane,
        hostId = hostId,
        projectKey = workspaceId ?: cwd,
        projectLabel = workspaceName ?: project ?: workspace,
        title = name?.takeIf { it.isNotBlank() } ?: agent?.takeIf { it.isNotBlank() } ?: "Agent",
        agentType = agent,
        status = status,
        ask = if (waiting) paneAsk(decisionQuestionText(null, plain)) else null,
        summary = last,
        updatedEpochMs = null,
        paneId = paneId,
        cwd = cwd,
    )
}

/**
 * Text for a decision card and a waiting list row.
 * [permissionTitle] wins. Otherwise the last agent question in [transcript].
 * A status bar alone is not a question.
 */
public fun decisionQuestionText(permissionTitle: String?, transcript: String?): String {
    val title = permissionTitle?.trim()?.takeIf { it.isNotEmpty() }
    if (title != null) return title
    return lastAgentQuestion(transcript) ?: DECISION_QUESTION_FALLBACK
}

internal fun lastAgentQuestion(transcript: String?): String? {
    val clean = transcript?.let(::stripAnsi) ?: return null
    val lines = clean.lines().filter { it.isNotBlank() }
    for (raw in lines.asReversed()) {
        val line = raw.trim()
        if (isPaneFooterLine(line) || isOptionLine(line) || isBareOrPromptOnly(line)) continue
        if (isAgentQuestion(line)) return stripLeadingPromptMarkers(line)
        if (isToolCallLine(line) || isToolContinuation(raw) || isDiffStart(raw) || isDiffBody(raw)) continue
        return null
    }
    return null
}

/** A lone ? / ？, or a prompt glyph with no real question, such as "? gradle". */
private fun isBareOrPromptOnly(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return false
    if (trimmed.all { it == '?' || it == '？' }) return true
    val stripped = stripLeadingPromptMarkers(trimmed)
    if (stripped == trimmed) return false
    return !isAgentQuestion(trimmed)
}

/** True when the line asks something: a ? / ？ with real text before it. */
private fun isAgentQuestion(line: String): Boolean {
    val stripped = stripLeadingPromptMarkers(line)
    val qAscii = stripped.indexOf('?')
    val qFull = stripped.indexOf('？')
    val qIdx = when {
        qAscii < 0 -> qFull
        qFull < 0 -> qAscii
        else -> minOf(qAscii, qFull)
    }
    if (qIdx < 0) return false
    val before = stripped.take(qIdx).trim()
    return before.any { it.isLetterOrDigit() || it.code in 0x4E00..0x9FFF }
}

internal fun paneAsk(question: String?): Ask =
    Ask(
        question = question,
        options = listOf(
            AskOption("Yes", AskTone.Primary, AskAction.Prompt("y")),
            AskOption("No", AskTone.Neutral, AskAction.Prompt("n")),
            AskOption("Enter", AskTone.Neutral, AskAction.Keys(listOf(AgentKey.ENTER))),
            AskOption("Esc", AskTone.Neutral, AskAction.Keys(listOf(AgentKey.ESC))),
        ),
    )

public fun AcpState.toThread(hostId: String): AgentThread {
    val request = permission?.takeIf { run == AcpRunState.AwaitingPermission }
    val lastMessage = transcript.lastOrNull { it is AcpTranscriptItem.Message } as AcpTranscriptItem.Message?
    val cwd = info?.cwd?.takeIf { it.isNotBlank() }
    return AgentThread(
        id = info?.sessionId?.let { "acp:$it" } ?: "acp",
        kind = AgentKind.Acp,
        hostId = hostId,
        projectKey = cwd,
        projectLabel = cwd?.trimEnd('/')?.substringAfterLast('/'),
        title = historyTitle(info?.sessionId) ?: info?.agentName?.takeIf { it.isNotBlank() } ?: "Agent",
        agentType = info?.agentId,
        status = run.toAgentStatus(),
        ask = request?.let { req ->
            Ask(
                question = decisionQuestionText(req.title, lastMessage?.text),
                options = req.options.map { option ->
                    AskOption(option.name, option.kind.tone(), AskAction.AcpOption(req.requestId, option.optionId))
                },
            )
        },
        summary = lastNonEmptyLine(lastMessage?.text),
        updatedEpochMs = null,
        acpSessionId = info?.sessionId,
        cwd = cwd,
        acpAgentId = info?.agentId,
    )
}

private fun AcpState.historyTitle(sessionId: String?): String? {
    val sid = sessionId?.takeIf { it.isNotBlank() } ?: return null
    return history.firstOrNull { it.sessionId == sid }?.title?.takeIf { it.isNotBlank() }
}

internal fun AcpHistorySession.toHistoryThread(hostId: String): AgentThread {
    val cwd = cwd.trim()
    return AgentThread(
        id = "acp:$sessionId",
        kind = AgentKind.Acp,
        hostId = hostId,
        projectKey = cwd,
        projectLabel = cwd.trimEnd('/').substringAfterLast('/').ifBlank { cwd },
        title = title?.takeIf { it.isNotBlank() } ?: agentName.ifBlank { agentId },
        agentType = agentId,
        status = AgentStatus.Idle,
        ask = null,
        summary = null,
        updatedEpochMs = rfc3339EpochMs(updatedAt),
        acpSessionId = sessionId,
        cwd = cwd,
        acpHistory = true,
        acpAgentId = agentId,
    )
}

private fun AcpPermissionKind.tone(): AskTone =
    when (this) {
        AcpPermissionKind.AllowOnce, AcpPermissionKind.AllowAlways -> AskTone.Primary
        AcpPermissionKind.RejectOnce, AcpPermissionKind.RejectAlways -> AskTone.Destructive
        AcpPermissionKind.Other -> AskTone.Neutral
    }

internal fun AcpRunState.toAgentStatus(): AgentStatus =
    when (this) {
        AcpRunState.AwaitingPermission -> AgentStatus.Blocked
        AcpRunState.Working, AcpRunState.Starting -> AgentStatus.Working
        AcpRunState.Ready, AcpRunState.Idle -> AgentStatus.Idle
        AcpRunState.Exited -> AgentStatus.Done
    }

internal fun lastNonEmptyLine(text: String?): String? =
    text?.lineSequence()?.map { it.trim() }?.lastOrNull { it.isNotBlank() }
