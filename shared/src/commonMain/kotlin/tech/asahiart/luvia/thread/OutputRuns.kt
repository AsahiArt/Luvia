package tech.asahiart.luvia.thread

/** A span of Agent output: prose in body sans, or preformatted text that keeps its columns. */
public data class OutputRun(val text: String, val mono: Boolean)

/**
 * Splits Agent output into prose and preformatted runs. Fenced code, indented code and box
 * drawing stay mono. Prose is reflowed: a TUI hard-wraps at its own width, so lines inside a
 * paragraph are joined and the phone wraps them again. List items and headings keep their breaks.
 */
public fun outputRuns(text: String): List<OutputRun> {
    val runs = mutableListOf<OutputRun>()
    var fenced = false
    for (line in text.lines()) {
        if (line.trimStart().startsWith("```")) {
            fenced = !fenced
            continue
        }
        val mono = fenced || looksPreformatted(line)
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
            paragraphs.last().append(' ').append(line.trim())
        } else {
            paragraphs += StringBuilder(line.trim())
            open = true
        }
    }
    return paragraphs.joinToString("\n")
}
