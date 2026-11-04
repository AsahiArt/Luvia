package tech.asahiart.luvia.thread

import tech.asahiart.luvia.AcpToolCall
import tech.asahiart.luvia.AcpTranscriptItem
import tech.asahiart.luvia.AgentKind
import tech.asahiart.luvia.AgentStatus
import tech.asahiart.luvia.HostUhpState
import kotlin.math.roundToInt

/** Session-list chips. Filtering is presentation; it does not change host state. */
public enum class SessionFilter {
    All,
    NeedsYou,
    Running,
    Idle,
}

public fun filterSessions(threads: List<AgentThread>, filter: SessionFilter): List<AgentThread> =
    when (filter) {
        SessionFilter.All -> threads
        SessionFilter.NeedsYou -> threads.filter { it.needsYou }
        SessionFilter.Running -> threads.filter { it.status == AgentStatus.Working }
        SessionFilter.Idle -> threads.filter {
            it.status == AgentStatus.Idle || it.status == AgentStatus.Done
        }
    }

public fun pendingReplyCount(threads: List<AgentThread>): Int = threads.count { it.needsYou }

/** Needs-you first, then running, then idle and done. Newer rows win ties. */
public fun sortSessions(threads: List<AgentThread>): List<AgentThread> =
    threads.sortedWith(
        compareBy<AgentThread> { thread ->
            when {
                thread.needsYou -> 0
                thread.status == AgentStatus.Working -> 1
                thread.status == AgentStatus.Idle -> 2
                else -> 3
            }
        }.thenByDescending { it.updatedEpochMs ?: 0L },
    )

public enum class ToolTrailKind { Read, Edit, Run, Other }

/** Classifies an ACP tool call for the trail. Unknown kinds stay [ToolTrailKind.Other]. */
public fun toolTrailKind(kind: String?, title: String): ToolTrailKind {
    val blob = listOfNotNull(kind, title).joinToString(" ").lowercase()
    return when {
        hasWord(blob, "edit") || hasWord(blob, "write") || hasWord(blob, "patch") ||
            hasWord(blob, "diff") || hasWord(blob, "apply") || hasWord(blob, "replace") ->
            ToolTrailKind.Edit
        hasWord(blob, "exec") || hasWord(blob, "bash") || hasWord(blob, "shell") ||
            hasWord(blob, "run") || hasWord(blob, "test") || hasWord(blob, "command") ->
            ToolTrailKind.Run
        hasWord(blob, "read") || hasWord(blob, "grep") || hasWord(blob, "search") ||
            hasWord(blob, "list") || hasWord(blob, "glob") ->
            ToolTrailKind.Read
        else -> ToolTrailKind.Other
    }
}

public data class RunMeter(
    public val passed: Int,
    public val total: Int,
) {
    public val failed: Int get() = (total - passed).coerceAtLeast(0)
}

private val runMeterPattern = Regex("""(\d+)\s*/\s*(\d+)""")

/** Pulls a `passed/total` meter out of a tool summary. Returns null when the pair is not a meter. */
public fun parseRunMeter(summary: String?): RunMeter? {
    if (summary.isNullOrBlank()) return null
    val match = runMeterPattern.find(summary) ?: return null
    val passed = match.groupValues[1].toIntOrNull() ?: return null
    val total = match.groupValues[2].toIntOrNull() ?: return null
    if (total <= 0 || passed < 0 || passed > total || total > 10_000) return null
    return RunMeter(passed, total)
}

/** Lines of a tool summary that already look like a diff. Does not invent a diff from prose. */
public fun diffLines(summary: String?): List<String>? {
    val lines = summary?.lineSequence()?.map { it.trimEnd() }?.filter { it.isNotBlank() }?.toList().orEmpty()
    if (lines.isEmpty()) return null
    val diffish = lines.count { line ->
        line.startsWith("+") || line.startsWith("-") || line.startsWith("@@") || line.startsWith("diff ")
    }
    return if (diffish > 0) lines else null
}

/** Explicit recommendation, otherwise the first primary option. */
public fun recommendedOption(ask: Ask): AskOption? =
    ask.options.firstOrNull { it.recommended } ?: ask.options.firstOrNull { it.tone == AskTone.Primary }

/** Counts for the session chips and the large-title stats row. */
public data class SessionRoster(
    public val agents: Int,
    public val running: Int,
    public val waiting: Int,
    public val idle: Int,
)

public fun sessionRoster(threads: List<AgentThread>): SessionRoster =
    SessionRoster(
        agents = threads.size,
        running = filterSessions(threads, SessionFilter.Running).size,
        waiting = filterSessions(threads, SessionFilter.NeedsYou).size,
        idle = filterSessions(threads, SessionFilter.Idle).size,
    )

/** Local title filter. An empty query leaves the list alone. */
public fun searchSessions(threads: List<AgentThread>, query: String): List<AgentThread> {
    val needle = query.trim()
    if (needle.isEmpty()) return threads
    return threads.filter { thread ->
        thread.title.contains(needle, ignoreCase = true) ||
            thread.agentType?.contains(needle, ignoreCase = true) == true ||
            thread.summary?.contains(needle, ignoreCase = true) == true ||
            thread.projectLabel?.contains(needle, ignoreCase = true) == true
    }
}

/** Two-letter mark for `claude-code`, one letter otherwise. */
public fun agentMark(agentType: String?, title: String): String {
    val source = agentType?.trim()?.takeIf { it.isNotEmpty() } ?: title.trim()
    if (source.isEmpty()) return "?"
    return shortInitials(source)
}

/** Stable 0..3 slot so a given agent keeps the same avatar color. */
public fun agentColorSlot(agentType: String?, title: String): Int {
    val key = (agentType?.trim()?.takeIf { it.isNotEmpty() } ?: title.trim()).lowercase()
    if (key.isEmpty()) return 0
    var sum = 0
    for (ch in key) sum += ch.code
    return sum % 4
}

/**
 * Palette for a session avatar. The four product colors are reserved.
 * Anything else hashes into the remaining colors so two unknown agents
 * do not impersonate codex, claude-code, opencode, or gemini.
 */
public enum class AgentAvatarPalette {
    Blue,
    Orange,
    Mint,
    Gray,
    Teal,
    Purple,
    Green,
    Indigo,
    Pink,
    Brown,
}

/** Initials plus a stable color. Android can adopt this later; iOS draws it. */
public data class AgentAvatar(
    public val mark: String,
    public val palette: AgentAvatarPalette,
)

/**
 * Avatar for one session row.
 *
 * Known products win on the agent id (`codex` → C blue, `claude-code` → CC orange,
 * `opencode` → O mint, `gemini` → G gray). A generic runtime such as `pi` is not
 * an identity: the session name supplies the initials and the color hash.
 * Unknown specific agents use their own name, not the session title.
 */
public fun sessionAvatar(agentType: String?, name: String): AgentAvatar {
    val typeKey = agentKey(agentType)
    knownAvatar(typeKey)?.let { return it }
    val nameKey = agentKey(name)
    if (typeKey.isEmpty() || typeKey in genericAgentTypes) {
        knownAvatar(nameKey)?.let { return it }
        val source = name.trim().ifEmpty { agentType?.trim().orEmpty() }
        return hashedAvatar(source)
    }
    return hashedAvatar(agentType?.trim().orEmpty())
}

/**
 * Relative age for a session row. Null when the host never reported a time,
 * or when the stamp is in the future.
 */
public fun sessionAgeLabel(updatedEpochMs: Long?, nowEpochMs: Long): String? {
    val updated = updatedEpochMs ?: return null
    val delta = nowEpochMs - updated
    if (delta < 0) return null
    val minutes = delta / 60_000
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟"
        minutes < 24 * 60 -> "${minutes / 60} 小时"
        else -> null
    }
}

/**
 * Recent tool calls already on the device. [progressPermille] is set only when
 * the latest call carried a `passed/total` meter.
 */
public data class TrailThumb(
    public val label: String,
    public val kinds: List<ToolTrailKind>,
    public val progressPermille: Int? = null,
)

public data class ThreadTrail(
    public val threadId: String,
    public val thumb: TrailThumb,
)

public fun trailThumb(calls: List<AcpToolCall>, limit: Int = 5): TrailThumb? {
    if (calls.isEmpty()) return null
    val recent = calls.takeLast(limit.coerceIn(1, 8))
    val last = recent.last()
    val view = toolTrailView(last)
    val title = last.title.trim().ifBlank { view.path?.substringAfterLast('/').orEmpty() }
    val meter = view.meter
    val label = when {
        meter != null && title.isNotEmpty() -> "$title · ${meter.passed}/${meter.total}"
        meter != null -> "${meter.passed}/${meter.total}"
        title.isNotEmpty() -> title
        else -> null
    } ?: return null
    val progress = meter?.let { (it.passed.toFloat() / it.total.toFloat() * 1000f).roundToInt().coerceIn(0, 1000) }
    return TrailThumb(label, recent.map { toolTrailKind(it.kind, it.title) }, progress)
}

/** How many trajectory dots fit on a session row. Newest steps stay. */
public const val MAX_LIST_DOTS: Int = 6

/** List-density trail mark. Decide is a waiting row, not a tool the host sent. */
public enum class ListTrailDot { Read, Edit, Run, Decide, Other }

/**
 * What one session row can show under the title: a recent step, its dots,
 * and a progress fraction when a running meter is already known.
 */
public data class ListTrail(
    public val label: String?,
    public val dots: List<ListTrailDot>,
    public val progressPermille: Int?,
    /** True when [label] is a `passed/total` tool line and should be monospaced. */
    public val useMono: Boolean,
)

/**
 * A tool line already stored as the session summary. Prose stays prose:
 * this returns null instead of inventing a read, edit, or run.
 */
public fun summaryTrail(summary: String?): TrailThumb? {
    if (summary.isNullOrBlank()) return null
    val tools = splitPaneTranscript(summary.lines()).filterIsInstance<PanePiece.Tool>()
    if (tools.isEmpty()) return null
    val calls = tools.mapIndexed { index, tool ->
        AcpToolCall(
            toolCallId = "s$index",
            title = tool.title,
            kind = tool.kind,
            status = tool.status,
            summary = tool.detail,
        )
    }
    return trailThumb(calls, limit = MAX_LIST_DOTS)
}

/**
 * Row subtitle and dots. A pending question wins the subtitle. A running
 * meter wins over prose. Dots come only from tool calls the phone already
 * has, plus one decide mark when the thread is waiting.
 */
public fun listTrail(thread: AgentThread, thumb: TrailThumb?): ListTrail {
    val derived = thumb ?: summaryTrail(thread.summary)
    val dots = mutableListOf<ListTrailDot>()
    derived?.kinds?.mapTo(dots) { it.asListDot() }
    if (thread.needsYou) dots += ListTrailDot.Decide
    val shown = if (dots.size > MAX_LIST_DOTS) dots.takeLast(MAX_LIST_DOTS) else dots
    val question = thread.ask?.question?.trim()?.takeIf { it.isNotEmpty() }
    val toolLabel = derived?.label?.trim()?.takeIf { it.isNotEmpty() }
    val prose = thread.summary?.trim()?.takeIf { it.isNotEmpty() }
    val progress = if (thread.status == AgentStatus.Working) derived?.progressPermille else null
    val label = when {
        question != null -> question
        progress != null && toolLabel != null -> toolLabel
        prose != null -> prose
        else -> toolLabel
    }
    val useMono = toolLabel != null && label == toolLabel && derived.progressPermille != null
    return ListTrail(label, shown, progress, useMono)
}

/**
 * Trail thumbs from transcripts the phone already holds. Does not read a pane
 * or an ACP session that is not the one currently open.
 */
public fun threadTrails(state: HostUhpState, threads: List<AgentThread>): List<ThreadTrail> {
    val found = mutableListOf<ThreadTrail>()
    val paneId = state.agentDetail.paneId
    val paneThumb = trailThumb(state.agentDetail.timeline.items.mapNotNull { (it as? TimelineItem.Tool)?.call })
    if (paneId != null && paneThumb != null) {
        threads.firstOrNull { it.kind == AgentKind.Pane && it.paneId == paneId }?.let { thread ->
            found += ThreadTrail(thread.id, paneThumb)
        }
    }
    if (state.acp.open) {
        val calls = state.acp.transcript.mapNotNull { (it as? AcpTranscriptItem.Tool)?.call }
        val thumb = trailThumb(calls)
        val sessionId = state.acp.info?.sessionId
        if (thumb != null && sessionId != null) {
            val match = threads.firstOrNull { thread ->
                thread.kind == AgentKind.Acp &&
                    (thread.acpSessionId == sessionId || thread.id == "acp:$sessionId")
            }
            if (match != null) found += ThreadTrail(match.id, thumb)
        }
    }
    return found
}

private val genericAgentTypes = setOf(
    "pi",
    "agent",
    "pane",
    "shell",
    "terminal",
    "unknown",
    "default",
)

private val hashedPalettes = listOf(
    AgentAvatarPalette.Teal,
    AgentAvatarPalette.Purple,
    AgentAvatarPalette.Green,
    AgentAvatarPalette.Indigo,
    AgentAvatarPalette.Pink,
    AgentAvatarPalette.Brown,
)

private fun knownAvatar(key: String): AgentAvatar? =
    when (key) {
        "codex" -> AgentAvatar("C", AgentAvatarPalette.Blue)
        "claude-code", "claude" -> AgentAvatar("CC", AgentAvatarPalette.Orange)
        "opencode", "open-code" -> AgentAvatar("O", AgentAvatarPalette.Mint)
        "gemini" -> AgentAvatar("G", AgentAvatarPalette.Gray)
        else -> null
    }

private fun hashedAvatar(source: String): AgentAvatar {
    val trimmed = source.trim()
    if (trimmed.isEmpty()) return AgentAvatar("?", AgentAvatarPalette.Gray)
    return AgentAvatar(shortInitials(trimmed), hashPalette(agentKey(trimmed)))
}

private fun shortInitials(source: String): String {
    val parts = source.split(Regex("[\\s._-]+")).filter { it.isNotEmpty() }
    val letters = if (parts.size >= 2) {
        "${parts[0].first()}${parts[1].first()}"
    } else {
        source.first().toString()
    }
    return letters.uppercase()
}

private fun agentKey(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    return raw.trim().lowercase().replace(Regex("[\\s._]+"), "-")
}

/** Stable across platforms. Reserved product colors are not in [hashedPalettes]. */
private fun hashPalette(key: String): AgentAvatarPalette {
    if (key.isEmpty()) return AgentAvatarPalette.Gray
    var hash = 0
    for (ch in key) {
        hash = (hash * 33) + ch.code
    }
    val size = hashedPalettes.size
    val mod = hash % size
    val index = if (mod < 0) mod + size else mod
    return hashedPalettes[index]
}

private fun ToolTrailKind.asListDot(): ListTrailDot =
    when (this) {
        ToolTrailKind.Read -> ListTrailDot.Read
        ToolTrailKind.Edit -> ListTrailDot.Edit
        ToolTrailKind.Run -> ListTrailDot.Run
        ToolTrailKind.Other -> ListTrailDot.Other
    }

private fun hasWord(blob: String, word: String): Boolean {
    var from = 0
    while (from <= blob.length - word.length) {
        val index = blob.indexOf(word, from)
        if (index < 0) return false
        val beforeOk = index == 0 || !blob[index - 1].isLetterOrDigit()
        val end = index + word.length
        val afterOk = end == blob.length || !blob[end].isLetterOrDigit()
        if (beforeOk && afterOk) return true
        from = index + 1
    }
    return false
}
