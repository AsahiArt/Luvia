package tech.asahiart.luvia.thread

import tech.asahiart.luvia.stripAnsi

/**
 * Facts for the terminal failure bar. Null when the capture has no failed tests.
 *
 * Gradle’s `127 tests completed, 1 failed` is shown as `127/128`: the completed
 * count is the numerator and completed plus failed is the denominator, which is
 * how the wow frame reads that line. A later explicit `passed/total` replaces it.
 * `BUILD FAILED in 41s` supplies the duration. Test names such as `caps at 30s`
 * do not.
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
            if (completed in 0..COUNT_LIMIT && failed in 0..COUNT_LIMIT) {
                found = TestCounts(completed, completed + failed, failed)
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
