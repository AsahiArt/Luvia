package tech.asahiart.luvia.thread

import tech.asahiart.luvia.AgentStatus

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
