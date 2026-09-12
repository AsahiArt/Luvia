package tech.asahiart.luvia

public sealed class TranscriptSegment {
    public class Text(public val text: String) : TranscriptSegment()

    public data object Rule : TranscriptSegment()

    public data object Gap : TranscriptSegment()
}

private const val COLLAPSE_BLANK_LINES = 2

/**
 * Splits a transcript so fill-rule lines (`──────`, `--------`) become
 * [TranscriptSegment.Rule] and two or more consecutive blank lines become
 * a single [TranscriptSegment.Gap].
 */
public fun transcriptSegments(text: String): List<TranscriptSegment> {
    if (text.isEmpty()) return emptyList()
    val out = ArrayList<TranscriptSegment>()
    val buf = StringBuilder()
    var blanks = 0
    fun flushText() {
        if (buf.isEmpty()) return
        out += TranscriptSegment.Text(buf.toString())
        buf.clear()
    }
    fun flushBlanks() {
        if (blanks >= COLLAPSE_BLANK_LINES) {
            flushText()
            out += TranscriptSegment.Gap
        } else if (blanks == 1 && buf.isNotEmpty()) {
            buf.append('\n')
        }
        blanks = 0
    }
    fun appendLine(line: String) {
        if (buf.isNotEmpty()) buf.append('\n')
        buf.append(line)
    }
    text.split('\n').forEach { line ->
        when {
            isFillRuleLine(line) -> {
                flushBlanks()
                flushText()
                out += TranscriptSegment.Rule
            }
            line.isBlank() -> blanks += 1
            else -> {
                flushBlanks()
                appendLine(line)
            }
        }
    }
    flushBlanks()
    flushText()
    return out
}

internal fun isFillRuleLine(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.length < 4) return false
    return trimmed.all { it.isFillRuleChar() }
}

private fun Char.isFillRuleChar(): Boolean =
    when (this) {
        '-', '_', '=' -> true
        else ->
            when (code) {
                0x2500, 0x2501, 0x2504, 0x2505, 0x2508, 0x2509,
                0x254C, 0x254D, 0x2550,
                -> true
                else -> false
            }
    }
