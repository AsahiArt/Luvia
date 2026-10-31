package tech.asahiart.luvia.thread

import tech.asahiart.luvia.AcpToolStatus

/** One readable piece of a pane TUI buffer. Prose stays prose; tool lines and cost bars are pulled out. */
public sealed class PanePiece {
    public data class Prose(val text: String) : PanePiece()

    public data class Tool(
        val title: String,
        val kind: String?,
        val status: AcpToolStatus,
        val detail: String?,
    ) : PanePiece()

    public data class Status(val text: String) : PanePiece()
}

private val sessionChrome = Regex("""^Session\s+(title|renamed)\b""", RegexOption.IGNORE_CASE)
private val money = Regex("""\$\s*\d+(?:\.\d+)?""")
private val tokens =
    Regex("""(?:↑|↓)\s*\d|\d+(?:\.\d+)?\s*[kKmM]\b|\btokens?\b|\bcontext\b""", RegexOption.IGNORE_CASE)
private val percentTool = Regex("""^%\s+[A-Za-z][\w.-]*\b""")
private val shellTool = Regex("""^(?:[•·●]\s*)?\$:\s*\S""")
private val clock = Regex("""^\d{1,2}:\d{2}(\s*[AP]M)?$""", RegexOption.IGNORE_CASE)
private val percentKind = Regex("""^%\s+(\S+)""")

internal fun isCostStatusLine(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.length > 160) return false
    if (sessionChrome.containsMatchIn(trimmed)) return true
    return money.containsMatchIn(trimmed) && tokens.containsMatchIn(trimmed)
}

internal fun isToolCallLine(line: String): Boolean {
    val trimmed = line.trim()
    return percentTool.containsMatchIn(trimmed) || shellTool.containsMatchIn(trimmed)
}

private fun isStatusContinuation(line: String): Boolean = clock.matches(line.trim())

private fun looksLikeAgentProse(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return false
    val cjk = trimmed.count { it.code in 0x4E00..0x9FFF }
    if (cjk >= 6) return true
    if (
        trimmed.length > 48 &&
        trimmed.count { it == ' ' } >= 4 &&
        !trimmed.contains("$:") &&
        !trimmed.startsWith("%")
    ) {
        return true
    }
    return false
}

internal fun isToolContinuation(line: String): Boolean {
    if (line.isBlank()) return false
    if (isToolCallLine(line) || isCostStatusLine(line) || looksLikeAgentProse(line)) return false
    val trimmed = line.trim()
    if (line.startsWith(" ") || line.startsWith("\t")) return true
    if (trimmed.startsWith("~") || trimmed.startsWith("/") || trimmed.startsWith("./")) return true
    if (trimmed.contains("→")) return true
    if (
        trimmed.startsWith("EXIT") ||
        trimmed.startsWith("Traceback") ||
        trimmed.startsWith("File \"") ||
        trimmed.startsWith("at ")
    ) {
        return true
    }
    if (trimmed.length < 180 && trimmed.none { it.code in 0x4E00..0x9FFF } && !trimmed.contains('。')) {
        return trimmed.contains("$") ||
            trimmed.contains(";") ||
            trimmed.contains("&&") ||
            trimmed.startsWith("\"") ||
            trimmed.startsWith("'")
    }
    return false
}

/**
 * Splits a pane transcript chunk into prose paragraphs, tool-call blocks, and status/cost bars.
 * Does not invent plans or permissions. Contiguous prose lines stay one paragraph; a blank line
 * starts another. Hard wraps inside a paragraph are preserved for [outputRuns] to reflow.
 */
public fun splitPaneTranscript(lines: List<String>): List<PanePiece> {
    val pieces = mutableListOf<PanePiece>()
    val prose = mutableListOf<String>()
    fun flushProse() {
        val text = prose.joinToString("\n").trim('\n')
        prose.clear()
        if (text.isNotBlank()) pieces += PanePiece.Prose(text)
    }
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        when {
            line.isBlank() -> {
                flushProse()
                index++
            }
            isCostStatusLine(line) -> {
                flushProse()
                val block = mutableListOf(line.trim())
                var next = index + 1
                while (next < lines.size && isStatusContinuation(lines[next])) {
                    block += lines[next].trim()
                    next++
                }
                pieces += PanePiece.Status(block.joinToString(" · "))
                index = next
            }
            isToolCallLine(line) -> {
                flushProse()
                val block = mutableListOf(line)
                var next = index + 1
                while (next < lines.size && isToolContinuation(lines[next])) {
                    block += lines[next]
                    next++
                }
                pieces += toolPiece(block)
                index = next
            }
            else -> {
                prose += line
                index++
            }
        }
    }
    flushProse()
    return pieces
}

private fun toolPiece(lines: List<String>): PanePiece.Tool {
    val title = lines.first().trim()
    val detail = lines.drop(1).joinToString("\n").trim().ifBlank { null }
    val kind = percentKind.find(title)?.groupValues?.get(1) ?: if (title.contains("$:")) "bash" else null
    val blob = listOfNotNull(title, detail).joinToString("\n")
    val status =
        when {
            blob.contains("Traceback") || blob.contains("FAILED") || Regex("""(?i)\bfailed\b""").containsMatchIn(blob) ->
                AcpToolStatus.Failed
            blob.contains("→") || Regex("""(?i)\bdone\b""").containsMatchIn(blob) -> AcpToolStatus.Completed
            else -> AcpToolStatus.Unknown
        }
    return PanePiece.Tool(title, kind, status, detail)
}
