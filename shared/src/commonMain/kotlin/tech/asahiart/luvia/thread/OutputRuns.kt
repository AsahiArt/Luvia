package tech.asahiart.luvia.thread

/** A span of Agent output: prose in body sans, or preformatted text that keeps its columns. */
public data class OutputRun(val text: String, val mono: Boolean)

public enum class ProseSpanKind { Text, Code }

/** A prose fragment. [ProseSpanKind.Code] is a symbol or a backtick span the bubble can chip. */
public data class ProseSpan(
    public val kind: ProseSpanKind,
    public val text: String,
)

private val proseCode = Regex("""`([^`\n]+)`|([A-Za-z][A-Za-z0-9]*[A-Z][A-Za-z0-9]*)""")

/**
 * Drops lines the trail already owns: `Cursor shell:`, `Read path`, `% $:`, `$:`, and unified diffs.
 * Chips win. The grey bubble keeps only the prose that is left.
 */
public fun proseWithoutTrail(text: String): String {
    if (text.isBlank()) return ""
    val kept = mutableListOf<String>()
    val lines = text.lines()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        if (isDiffStart(line)) {
            index++
            while (index < lines.size && lines[index].isNotBlank() && isDiffBody(lines[index])) index++
            continue
        }
        if (isToolCallLine(line)) {
            index++
            while (index < lines.size && isToolContinuation(lines[index])) index++
            continue
        }
        kept += line
        index++
    }
    return kept.joinToString("\n").trim()
}

/**
 * Marks backtick spans and hump identifiers (`CompanionClient`) so the bubble can draw a chip.
 * Short tokens such as `iOS` stay ordinary text. Trail-owned lines are removed first, so a shell
 * or diff that is already a chip produces no prose spans.
 */
public fun proseSpans(text: String): List<ProseSpan> {
    val visible = proseWithoutTrail(text)
    if (visible.isEmpty()) return emptyList()
    val spans = mutableListOf<ProseSpan>()
    var cursor = 0
    fun appendText(chunk: String) {
        if (chunk.isEmpty()) return
        val last = spans.lastOrNull()
        if (last != null && last.kind == ProseSpanKind.Text) {
            spans[spans.lastIndex] = last.copy(text = last.text + chunk)
        } else {
            spans += ProseSpan(ProseSpanKind.Text, chunk)
        }
    }
    for (match in proseCode.findAll(visible)) {
        val tick = match.groups[1]?.value
        val hump = match.groups[2]?.value
        val code = when {
            !tick.isNullOrEmpty() -> tick
            !hump.isNullOrEmpty() && hump.length >= 4 -> hump
            else -> null
        }
        if (code == null) {
            appendText(visible.substring(cursor, match.range.last + 1))
            cursor = match.range.last + 1
            continue
        }
        if (match.range.first > cursor) appendText(visible.substring(cursor, match.range.first))
        spans += ProseSpan(ProseSpanKind.Code, code)
        cursor = match.range.last + 1
    }
    if (cursor < visible.length) appendText(visible.substring(cursor))
    return spans
}

/**
 * Splits Agent output into prose and preformatted runs. Fenced code, indented code and box
 * drawing stay mono. Prose is reflowed: a TUI hard-wraps at its own width, so lines inside a
 * paragraph are joined and the phone wraps them again. List items and headings keep their breaks.
 */
public fun outputRuns(text: String): List<OutputRun> {
    val runs = mutableListOf<OutputRun>()
    var fenced = false
    var inDiff = false
    for (line in text.lines()) {
        if (line.trimStart().startsWith("```")) {
            fenced = !fenced
            inDiff = false
            continue
        }
        if (inDiff && (line.isBlank() || !isDiffBody(line))) inDiff = false
        if (!fenced && !inDiff && isDiffStart(line)) inDiff = true
        val mono = fenced || inDiff || looksPreformatted(line)
        val last = runs.lastOrNull()
        if (last != null && last.mono == mono) {
            runs[runs.lastIndex] = last.copy(text = last.text + "\n" + line)
        } else {
            runs += OutputRun(line, mono)
        }
    }
    return runs
        .map { run -> if (run.mono) run.copy(text = run.text.trim('\n')) else run.copy(text = reflow(run.text)) }
        .filter { it.text.isNotBlank() }
}

private fun looksPreformatted(line: String): Boolean =
    line.startsWith("    ") || line.startsWith("\t") ||
        line.any { it in '\u2500'..'\u259F' }

private val listMarker = Regex("""^\s*([-*•+]|\d+[.)])\s""")

private fun startsBlock(line: String): Boolean =
    listMarker.containsMatchIn(line) || line.trimStart().startsWith("#") || line.trimStart().startsWith(">")

internal fun reflow(text: String): String {
    val paragraphs = mutableListOf<StringBuilder>()
    var open = false
    for (raw in text.lines()) {
        val line = raw.trimEnd()
        if (line.isBlank()) {
            open = false
            continue
        }
        if (open && !startsBlock(line)) {
            val next = line.trim()
            val builder = paragraphs.last()
            if (needsJoinSpace(builder, next)) builder.append(' ')
            builder.append(next)
        } else {
            paragraphs += StringBuilder(line.trim())
            open = true
        }
    }
    return paragraphs.joinToString("\n")
}

/** English wraps need a space. CJK hard-wraps must not gain one in the middle of a word. */
private fun needsJoinSpace(current: StringBuilder, next: String): Boolean {
    if (current.isEmpty() || next.isEmpty()) return false
    val left = current.last()
    val right = next.first()
    if (left.isWhitespace() || right.isWhitespace()) return false
    if (isCjk(left) || isCjk(right)) return false
    return true
}

private fun isCjk(ch: Char): Boolean {
    val code = ch.code
    return code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code in 0x3000..0x303F || code in 0xFF00..0xFFEF
}
