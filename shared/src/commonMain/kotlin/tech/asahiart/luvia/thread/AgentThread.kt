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
        ask = if (waiting) paneDecision(plain) else null,
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
    if (title != null) return stripTuiChrome(title).trim().ifEmpty { DECISION_QUESTION_FALLBACK }
    return lastAgentQuestion(transcript) ?: DECISION_QUESTION_FALLBACK
}

internal fun lastAgentQuestion(transcript: String?): String? {
    val clean = transcript?.let(::stripAnsi) ?: return null
    val lines = clean.lines()
    var index = lines.lastIndex
    while (index >= 0) {
        val raw = lines[index]
        if (raw.isBlank()) {
            index--
            continue
        }
        val fragment = questionFragment(raw)
        if (
            fragment.isEmpty() ||
            isPaneFooterLine(fragment) ||
            isKeyHintLine(fragment) ||
            isOptionLine(fragment) ||
            isBareOrPromptOnly(raw) ||
            isOptionContinuation(lines, index)
        ) {
            index--
            continue
        }
        if (isAgentQuestion(fragment)) {
            val parts = mutableListOf(fragment)
            var up = index - 1
            while (up >= 0) {
                if (lines[up].isBlank()) break
                val prev = questionFragment(lines[up])
                if (prev.isEmpty()) break
                if (
                    isPaneFooterLine(prev) ||
                    isKeyHintLine(prev) ||
                    isOptionLine(prev) ||
                    isBareOrPromptOnly(lines[up])
                ) {
                    break
                }
                if (!isQuestionWrapBefore(prev, parts.first())) break
                parts.add(0, prev)
                up--
            }
            return stripTuiChrome(joinHardWrapped(parts)).trim()
        }
        if (isSkippableToolNoise(raw)) {
            index--
            continue
        }
        return null
    }
    return null
}

private fun questionFragment(raw: String): String =
    stripTuiChrome(stripLeadingPromptMarkers(raw.trim()))

/** A lone ? / ？, or a prompt glyph with no real question, such as "? gradle". Box bars alone are not a prompt. */
private fun isBareOrPromptOnly(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return false
    val withoutBox = stripLeadingBox(trimmed)
    if (withoutBox.isEmpty() || withoutBox.all { it == '?' || it == '？' }) return true
    val stripped = stripLeadingPromptMarkers(withoutBox)
    if (stripped == withoutBox) return false
    return !isAgentQuestion(trimmed)
}

/**
 * The line above a wrapped question is the same sentence when the seam is a hard wrap:
 * previous text does not already end a sentence, and the next line continues a token or CJK.
 */
private fun isQuestionWrapBefore(previous: String, next: String): Boolean {
    if (previous.isEmpty() || next.isEmpty()) return false
    val end = previous.last()
    if (end == '.' || end == '?' || end == '!' || end == '。' || end == '？' || end == '！' || end == '…') {
        return false
    }
    val start = next.first()
    if (start.isLowerCase() || start.isDigit()) return true
    if (end == '-' || end == '/' || end == '_' || end == '\\') return true
    if (isCjk(end) && (isCjk(start) || start.isLetterOrDigit())) return true
    if (isCjk(start) && (isCjk(end) || end.isLetterOrDigit())) return true
    return false
}

private fun isCjk(ch: Char): Boolean = ch.code in 0x4E00..0x9FFF

/** Indented description under a `N (○)` row. Not a new question, so scanning keeps walking. */
private fun isOptionContinuation(lines: List<String>, index: Int): Boolean {
    if (!isHangingIndent(lines[index])) return false
    val fragment = questionFragment(lines[index])
    if (fragment.isEmpty() || isOptionLine(fragment) || isKeyHintLine(fragment) || isAgentQuestion(fragment)) {
        return false
    }
    var up = index - 1
    while (up >= 0) {
        if (lines[up].isBlank()) return false
        val prev = questionFragment(lines[up])
        if (prev.isEmpty()) {
            up--
            continue
        }
        if (isOptionLine(prev)) return true
        if (!isHangingIndent(lines[up])) return false
        up--
    }
    return false
}

private fun isSkippableToolNoise(raw: String): Boolean {
    val fragment = questionFragment(raw)
    val framed = raw.trimStart().firstOrNull()?.let { it == '│' || it == '┃' || it == '|' || it == '▌' || it == '▎' } == true
    return if (framed) {
        isToolCallLine(fragment) || isDiffStart(fragment) || isDiffBody(fragment)
    } else {
        isToolCallLine(fragment) || isToolContinuation(raw) || isDiffStart(raw) || isDiffBody(raw)
    }
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

/** Question plus radio options from a blocked pane. Yes/No when the pane has no `N (○)` rows. */
internal fun paneDecision(transcript: String?): Ask {
    val question = decisionQuestionText(null, transcript)
    val parsed = parseGrokAskOptions(transcript)
    return if (parsed.isNotEmpty()) Ask(question, parsed) else paneAsk(question)
}

/**
 * Yes/No fallback. Enter and Esc are pane chrome, not answers, so they are not options.
 */
internal fun paneAsk(question: String?): Ask =
    Ask(
        question = question,
        options = listOf(
            AskOption("Yes", AskTone.Primary, AskAction.Prompt("y")),
            AskOption("No", AskTone.Neutral, AskAction.Prompt("n")),
        ),
    )

/**
 * Grok Ask rows: `N (○) label    description`, description wraps on a hanging indent.
 * `z (○) Type your answer here` is the free-text note, not an option.
 * [AskOption.recommended] is set only when that row says Recommended.
 */
internal fun parseGrokAskOptions(transcript: String?): List<AskOption> {
    val clean = transcript?.let(::stripAnsi) ?: return emptyList()
    val lines = clean.lines()
    val options = mutableListOf<AskOption>()
    var index = 0
    while (index < lines.size) {
        val choice = matchGrokChoice(lines[index])
        if (choice == null) {
            index++
            continue
        }
        val key = choice.first
        val rest = choice.second.trim()
        if (isFreeTextChoice(key, rest) || isKeyHintLine(rest)) {
            index++
            continue
        }
        val split = splitLabelAndConsequence(rest)
        val label = stripTuiChrome(stripTrailingRecommended(split.label)).trim()
        if (label.isEmpty() || isKeyHintLabel(label)) {
            index++
            continue
        }
        val recommended = marksRecommended(rest)
        val parts = mutableListOf<String>()
        val firstConsequence = stripTuiChrome(split.consequence).trim()
        if (firstConsequence.isNotEmpty()) parts += firstConsequence
        var next = index + 1
        while (next < lines.size) {
            if (lines[next].isBlank()) break
            val follow = questionFragment(lines[next])
            if (follow.isEmpty()) {
                next++
                continue
            }
            if (matchGrokChoice(lines[next]) != null || isOptionLine(follow) || isKeyHintLine(follow) || isPaneFooterLine(follow)) {
                break
            }
            if (!isHangingIndent(lines[next])) break
            parts += follow
            next++
        }
        options += AskOption(
            label = label,
            tone = optionTone(label, recommended),
            action = AskAction.Prompt(key),
            consequence = stripTuiChrome(joinHardWrapped(parts)).trim().ifBlank { null },
            recommended = recommended,
        )
        index = next
    }
    return options
}

private data class LabelSplit(val label: String, val consequence: String)

private fun splitLabelAndConsequence(rest: String): LabelSplit {
    val gap = Regex("""\s{2,}""").find(rest)
    if (gap == null) return LabelSplit(rest.trim(), "")
    return LabelSplit(
        rest.substring(0, gap.range.first).trim(),
        rest.substring(gap.range.last + 1).trim(),
    )
}

private fun isFreeTextChoice(key: String, rest: String): Boolean {
    if (key.equals("z", ignoreCase = true)) return true
    return splitLabelAndConsequence(rest).label.trim().equals("type your answer here", ignoreCase = true)
}

private fun marksRecommended(text: String): Boolean {
    val lower = text.lowercase()
    if (lower.contains("(recommended)")) return true
    return hasRecommendedToken(lower)
}

private fun hasRecommendedToken(lower: String): Boolean {
    val token = "recommended"
    var from = 0
    while (from < lower.length) {
        val at = lower.indexOf(token, from)
        if (at < 0) return false
        val beforeOk = at == 0 || !lower[at - 1].isLetter()
        val after = at + token.length
        val afterOk = after == lower.length || !lower[after].isLetter()
        if (beforeOk && afterOk) return true
        from = at + token.length
    }
    return false
}

private fun stripTrailingRecommended(label: String): String {
    var s = label.trim()
    while (true) {
        val lower = s.lowercase()
        s = when {
            lower.endsWith("(recommended)") -> s.dropLast("(recommended)".length).trim()
            else -> {
                val tokenAt = lower.length - "recommended".length
                if (
                    tokenAt >= 0 &&
                    lower.endsWith("recommended") &&
                    (tokenAt == 0 || !lower[tokenAt - 1].isLetter())
                ) {
                    s.dropLast("recommended".length).trim()
                } else {
                    return s
                }
            }
        }
    }
}

private fun isKeyHintLabel(label: String): Boolean {
    val n = label.trim().lowercase()
    if (n.contains("shift+x") || n.contains("↑") || n.contains("↓")) return true
    return n in setOf(
        "tab", "esc", "escape", "enter", "submit", "scrollback", "dismiss", "navigate", "copy",
    )
}

private fun optionTone(label: String, recommended: Boolean): AskTone {
    val n = label.trim().lowercase()
    if (n == "no" || n == "n" || n == "否") return AskTone.Neutral
    if (n.contains("不允许") || n.contains("拒绝") || n.contains("deny") || n.contains("reject")) {
        return AskTone.Destructive
    }
    if (recommended || n == "yes" || n == "y" || n.contains("允许") || n.contains("allow")) return AskTone.Primary
    return AskTone.Neutral
}

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
