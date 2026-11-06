package tech.asahiart.luvia.thread

import tech.asahiart.luvia.AgentKind
import tech.asahiart.luvia.stripAnsi

/**
 * Facts for the terminal failure bar. Null when the capture has no failed tests.
 *
 * Gradle’s `127 tests completed, 1 failed` already counts the failure inside
 * the completed total, so the bar is `126/127 · 1失败`. A later explicit
 * `passed/total` replaces that pair. `BUILD FAILED in 41s` supplies the
 * duration. Test names such as `caps at 30s` do not.
 */
public data class TerminalFailureBanner(
    public val passed: Int,
    public val total: Int,
    public val failed: Int,
    public val duration: String?,
    /** First failed case, without the trailing FAILED token. */
    public val failedTitle: String?,
    /** 0-based indexes into [terminalLines], including the assertion lines under a failure. */
    public val outlinedLines: List<Int>,
    /** Same indexes as a `0`/`1` mask, one character per line, for callers that cannot take a Kotlin int list. */
    public val outlinedMask: String,
    /** Short failure text to hand to the agent. */
    public val excerpt: String,
)

/** `126/127 · 1 failed · 41s` from the parsed banner. Duration is omitted when the build did not report one. */
public fun terminalFailureSummary(banner: TerminalFailureBanner): String {
    val head = terminalFailureSummaryHead(banner)
    val duration = banner.duration?.takeIf { it.isNotBlank() } ?: return head
    return "$head · $duration"
}

/** Summary without the build duration: `126/127 · 1 failed`. */
public fun terminalFailureSummaryHead(banner: TerminalFailureBanner): String =
    "${banner.passed}/${banner.total} · ${banner.failed} failed"

/** Where a reported duration is drawn. It stays on the summary until that line cannot hold it. */
public enum class TerminalFailureDurationPlace {
    Summary,
    Bar,
}

/**
 * Duration stays on the summary line while [summaryFitsWithDuration] is true.
 * A missing duration never moves. When the summary cannot hold it, it goes on the bar row.
 */
public fun terminalFailureDurationPlace(
    duration: String?,
    summaryFitsWithDuration: Boolean,
): TerminalFailureDurationPlace {
    if (duration.isNullOrBlank() || summaryFitsWithDuration) return TerminalFailureDurationPlace.Summary
    return TerminalFailureDurationPlace.Bar
}

/** Solid failure-bar fill. `passed / total`, clamped to 0..1. Zero when [total] is not positive. */
public fun terminalFailureProgress(passed: Int, total: Int): Float {
    if (total <= 0) return 0f
    return (passed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

/**
 * Columns and rows that fit the measured terminal viewport.
 * Null until both axes and the cell size are positive, so the subtitle never invents `80×32`.
 */
public fun terminalGrid(widthPx: Int, heightPx: Int, cellWidthPx: Int, cellHeightPx: Int): Pair<Int, Int>? {
    if (widthPx <= 0 || heightPx <= 0 || cellWidthPx <= 0 || cellHeightPx <= 0) return null
    val columns = widthPx / cellWidthPx
    val rows = heightPx / cellHeightPx
    if (columns <= 0 || rows <= 0) return null
    return columns to rows
}

/** `agent · 80×32 · 已同步`. Size and the synced word are dropped when they are not known yet. */
public fun terminalStatusLine(
    agent: String,
    columns: Int,
    rows: Int,
    synced: Boolean,
    syncedWord: String,
): String {
    val name = agent.trim()
    val size = if (columns > 0 && rows > 0) "$columns×$rows" else null
    return listOfNotNull(
        name.takeIf { it.isNotEmpty() },
        size,
        syncedWord.takeIf { synced && it.isNotBlank() },
    ).joinToString(" · ")
}

public fun terminalFailureBanner(text: String?): TerminalFailureBanner? {
    val lines = terminalLines(text)
    if (lines.isEmpty() || lines.all { it.isBlank() }) return null
    val outlined = outlinedLines(lines)
    val summary = countsFrom(lines)
    val counts = if ((summary == null || summary.failed == 0) && outlined.isNotEmpty()) {
        countsFromLines(lines, outlined) ?: summary
    } else {
        summary
    }
    if (counts == null || counts.failed <= 0 || counts.total <= 0 || counts.passed > counts.total) return null
    val title = outlined.firstOrNull()?.let { failureTitle(lines[it]) }?.takeIf { it.isNotBlank() }
    val body = outlined.joinToString("\n") { lines[it].trimEnd() }.ifBlank { title.orEmpty() }
    val mask = CharArray(lines.size) { '0' }
    outlined.forEach { index -> if (index in mask.indices) mask[index] = '1' }
    return TerminalFailureBanner(
        passed = counts.passed,
        total = counts.total,
        failed = counts.failed,
        duration = buildDuration(lines),
        failedTitle = title,
        outlinedLines = outlined,
        outlinedMask = mask.concatToString(),
        excerpt = body.take(EXCERPT_LIMIT),
    )
}

/**
 * Equal-width pane chips. Two through five share the row. More than five scroll
 * so a chip keeps a readable width instead of being crushed.
 */
public const val EQUAL_PANE_SEGMENT_MAX: Int = 5

public fun paneSegmentsShareWidth(count: Int): Boolean = count in 1..EQUAL_PANE_SEGMENT_MAX

/** 1-based index shared by the `pane N` header and [terminalSegmentLabel]. */
public fun terminalPaneNumber(index: Int): Int = index + 1

/**
 * Conversation session that owns [paneId], if one is open on that pane.
 * A pane title or foreground command is not a session.
 */
public fun terminalOwningSession(threads: List<AgentThread>, paneId: String?): AgentThread? {
    if (paneId.isNullOrBlank()) return null
    return threads.firstOrNull { thread ->
        thread.kind == AgentKind.Pane && thread.paneId == paneId && !thread.acpHistory
    }
}

/** Official agent behind a pane foreground or a session id. Null for shells and commands. */
public fun recognizedTerminalAgent(raw: String?): String? {
    val logo = agentBrandLogo(raw, "") ?: return null
    return when (logo.assetId) {
        AGENT_BRAND_ASSET_CLAUDE -> "claude"
        AGENT_BRAND_ASSET_OPENAI -> "codex"
        AGENT_BRAND_ASSET_GROK -> "grok"
        AGENT_BRAND_ASSET_PI -> "pi"
        else -> null
    }
}

public enum class TerminalFailureMode { Fixing, Handoff }

public data class TerminalFixChoice(
    public val agentType: String,
    public val name: String,
)

public data class TerminalFailureOffer(
    public val mode: TerminalFailureMode,
    public val agentName: String,
    /** Passed to [agentBrandLogo]. Null means the letter circle. */
    public val avatarAgent: String?,
    public val choices: List<TerminalFixChoice>,
) {
    public val fixingLabel: String get() = "$agentName 在修…"

    public val handoffLabel: String get() = "交给 $agentName 修"
}

/**
 * Host agent ids win. Otherwise the session list's recognized agents.
 * Shells and other unrecognized ids are dropped from both lists.
 */
public fun terminalFixCandidates(hostAgentIds: List<String>, threadAgentTypes: List<String>): List<TerminalFixChoice> {
    fun recognized(raw: String): TerminalFixChoice? {
        val type = raw.trim().ifEmpty { return null }
        val known = recognizedTerminalAgent(type) ?: return null
        return TerminalFixChoice(type, known)
    }
    val fromHost = hostAgentIds.mapNotNull(::recognized).distinctBy { it.name.lowercase() }
    if (fromHost.isNotEmpty()) return fromHost
    return threadAgentTypes.mapNotNull(::recognized).distinctBy { it.name.lowercase() }
}

/** True when both names are the same recognized agent. `claude` and `claude-code` match. */
public fun sameTerminalAgent(left: String?, right: String?): Boolean {
    val a = left?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val b = right?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    if (a.equals(b, ignoreCase = true)) return true
    val knownA = recognizedTerminalAgent(a) ?: return false
    val knownB = recognizedTerminalAgent(b) ?: return false
    return knownA == knownB
}

/** True when [agentType] is a recognized agent other than the open session. Blank stays on the session path. */
public fun terminalFixOpensNewAgent(agentType: String?, sessionAgentType: String?): Boolean {
    val requested = agentType?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    if (recognizedTerminalAgent(requested) == null) return false
    return !sameTerminalAgent(requested, sessionAgentType)
}

/**
 * Foreground agent → 「pi 在修…」. Shell or command → 「交给 pi 修」.
 * [paneForeground] is never used as the session agent. No session agent → 「交给 agent 修」.
 */
public fun terminalFailureOffer(
    paneForeground: String?,
    sessionAgentType: String?,
    sessionDisplayName: String? = null,
    candidates: List<TerminalFixChoice> = emptyList(),
): TerminalFailureOffer {
    val running = recognizedTerminalAgent(paneForeground)
    if (running != null) {
        return TerminalFailureOffer(
            mode = TerminalFailureMode.Fixing,
            agentName = running,
            avatarAgent = running,
            choices = emptyList(),
        )
    }
    val session = recognizedTerminalAgent(sessionAgentType) ?: recognizedTerminalAgent(sessionDisplayName)
    val rawType = sessionAgentType?.trim()?.takeIf { it.isNotEmpty() }
    val name = session ?: rawType ?: "agent"
    val choices = candidates.ifEmpty {
        when {
            session != null -> listOf(TerminalFixChoice(session, session))
            rawType != null -> listOf(TerminalFixChoice(rawType, rawType))
            else -> emptyList()
        }
    }
    return TerminalFailureOffer(
        mode = TerminalFailureMode.Handoff,
        agentName = name,
        avatarAgent = session ?: rawType,
        choices = choices,
    )
}

/** `1 zsh` style. A numeric title becomes the word pane so the chip is never only a number. */
public fun terminalSegmentLabel(index: Int, title: String): String {
    val leaf = title.trim().substringAfterLast('/').trim()
    val word = leaf.split(Regex("""\s+""")).firstOrNull { it.isNotBlank() }.orEmpty()
    val name = when {
        word.isEmpty() || word.all { it.isDigit() } -> "pane"
        word.length > 18 -> word.take(18)
        else -> word
    }
    return "${index + 1} $name"
}

internal fun terminalLines(text: String?): List<String> {
    if (text.isNullOrEmpty()) return emptyList()
    return stripAnsi(text).replace("\r\n", "\n").replace('\r', '\n').split('\n')
}

private data class TestCounts(val passed: Int, val total: Int, val failed: Int)

private val gradleSummary = Regex("""(?i)(\d+)\s+tests?\s+completed,\s+(\d+)\s+failed\b""")
private val junitSummary = Regex("""(?i)Tests run:\s*(\d+).*?Failures:\s*(\d+)""")
private val ratioMeter = Regex("""(?<!\d)(\d+)\s*/\s*(\d+)(?!\d)""")
private val buildIn = Regex("""(?i)\bin\s+(\d+(?:\.\d+)?)(ms|s|m)\b""")
private val buildLine = Regex("""(?i)^\s*BUILD\s+(FAILED|SUCCESSFUL)\b""")
private val failedToken = Regex("""(?i)\bFAILED\b""")
private val passedToken = Regex("""(?i)\bPASSED\b""")

private fun countsFrom(lines: List<String>): TestCounts? {
    var found: TestCounts? = null
    for (line in lines) {
        val gradle = gradleSummary.find(line)
        if (gradle != null) {
            val completed = gradle.groupValues[1].toIntOrNull() ?: continue
            val failed = gradle.groupValues[2].toIntOrNull() ?: continue
            // `completed` already includes `failed`. Do not add them again.
            if (completed in 1..COUNT_LIMIT && failed in 0..completed) {
                found = TestCounts(completed - failed, completed, failed)
            }
            continue
        }
        val junit = junitSummary.find(line)
        if (junit != null) {
            val total = junit.groupValues[1].toIntOrNull() ?: continue
            val failed = junit.groupValues[2].toIntOrNull() ?: continue
            if (total in 1..COUNT_LIMIT && failed in 0..total) {
                found = TestCounts(total - failed, total, failed)
            }
            continue
        }
        if (!line.contains("test", ignoreCase = true) && !line.contains("fail", ignoreCase = true)) continue
        val ratio = ratioMeter.find(line) ?: continue
        val passed = ratio.groupValues[1].toIntOrNull() ?: continue
        val total = ratio.groupValues[2].toIntOrNull() ?: continue
        if (total in 1..COUNT_LIMIT && passed in 0..total) {
            found = TestCounts(passed, total, total - passed)
        }
    }
    return found
}

private fun countsFromLines(lines: List<String>, outlined: List<Int>): TestCounts? {
    if (outlined.isEmpty()) return null
    val failedCases = outlined.count { index -> failedToken.containsMatchIn(lines[index]) }
    if (failedCases <= 0) return null
    val passedCases = lines.count { passedToken.containsMatchIn(it) && !failedToken.containsMatchIn(it) }
    return TestCounts(passedCases, passedCases + failedCases, failedCases)
}

private fun outlinedLines(lines: List<String>): List<Int> {
    val indexes = mutableListOf<Int>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        if (!isFailedCase(line)) {
            index++
            continue
        }
        indexes += index
        var next = index + 1
        var taken = 0
        while (next < lines.size && taken < CONTINUATION_LIMIT && isContinuation(lines[next])) {
            indexes += next
            taken++
            next++
        }
        index = next
    }
    return indexes
}

private fun isFailedCase(line: String): Boolean {
    if (buildLine.containsMatchIn(line)) return false
    if (gradleSummary.containsMatchIn(line) || junitSummary.containsMatchIn(line)) return false
    if (!failedToken.containsMatchIn(line)) return false
    if (passedToken.containsMatchIn(line)) return false
    return true
}

private fun isContinuation(line: String): Boolean {
    if (line.isBlank()) return false
    if (isFailedCase(line) || passedToken.containsMatchIn(line) || buildLine.containsMatchIn(line)) return false
    if (line.first().isWhitespace()) return true
    val trimmed = line.trim()
    return trimmed.startsWith("expected", ignoreCase = true) ||
        trimmed.startsWith("actual", ignoreCase = true) ||
        trimmed.contains("but was", ignoreCase = true) ||
        trimmed.startsWith("Assertion", ignoreCase = true) ||
        trimmed.startsWith("java.") ||
        trimmed.startsWith("kotlin.") ||
        trimmed.startsWith("at ")
}

private fun failureTitle(line: String): String =
    line.replace(Regex("""(?i)\s+FAILED\s*$"""), "")
        .replace(Regex("""(?i)^FAILED\s+"""), "")
        .trim()

private fun buildDuration(lines: List<String>): String? {
    val build = lines.lastOrNull { buildLine.containsMatchIn(it) }
    val match = build?.let { buildIn.find(it) }
        ?: lines.lastOrNull { gradleSummary.containsMatchIn(it) || junitSummary.containsMatchIn(it) }
            ?.let { buildIn.find(it) }
    if (match == null) return null
    return match.groupValues[1] + match.groupValues[2].lowercase()
}

private const val COUNT_LIMIT: Int = 10_000
private const val CONTINUATION_LIMIT: Int = 6
private const val EXCERPT_LIMIT: Int = 800
