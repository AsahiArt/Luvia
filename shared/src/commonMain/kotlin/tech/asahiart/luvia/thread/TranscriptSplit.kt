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
private val percentShell = Regex("""^%\s+\$:\s*\S""")
private val labeledTool = Regex(
    """^(?:[•·●]\s*)?(?:Cursor|Claude|Codex|Gemini|Agent|Pi|opencode)\s+(?:shell|read|edit|write|grep|bash|run|search|glob)\s*[:：]\s*\S""",
    RegexOption.IGNORE_CASE,
)
private val verbPath = Regex("""^(?:[•·●]\s*)?(Read|Edit|Write|Grep|Glob|Bash|Shell)\s+(\S+)\s*$""")
private val labeledKind = Regex("""(?i)\b(shell|bash|read|edit|write|grep|glob|search|run)\b""")

internal fun isCostStatusLine(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.length > 160) return false
    if (sessionChrome.containsMatchIn(trimmed)) return true
    return money.containsMatchIn(trimmed) && tokens.containsMatchIn(trimmed)
}

private val footerPwd =
    Regex("""^(?:~|/|[.][.]?/)[^ ?？]+(?: +[(][^)]+[)])?(?: +[•·] +[^ ]+.*)?$""")
private val footerStats = Regex("""(?:↑|↓) *[0-9]""")
private val footerCache = Regex("""(?:^|[^A-Za-z0-9_])(?:CH|R|W)[0-9]""")
private val footerContext = Regex("""[0-9]+(?:[.][0-9]+)?%""")

/** Pi's cwd row and token/cost bar. Not an agent question. */
internal fun isPaneFooterLine(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.length > 200) return false
    if (isCostStatusLine(trimmed) || clock.matches(trimmed)) return true
    if (footerPwd.matches(trimmed)) return true
    return footerStats.containsMatchIn(trimmed) &&
        (footerCache.containsMatchIn(trimmed) ||
            footerContext.containsMatchIn(trimmed) ||
            money.containsMatchIn(trimmed))
}

private val leadingBox = Regex("""^[│┃|▌▎]+\s*""")
private val promptMarker = Regex("""^(?:[›❯>]\s+|[?？]\s+)""")
private val optionLine = Regex("""^(?:[❯›>•·●]\s*)?\d+[.)、]\s+\S""")
private val grokChoiceLine = Regex(
    """^(?:[•·●*]\s*)?(\d+|[zZ])\s*\(\s*([○●*])\s*\)\s*(.+)$""",
)
private val keyHintLine = Regex(
    """(?i)(?:(?:^|[\s│┃|])(?:tab|esc|enter)\s*:)|shift\s*\+\s*x|↑\s*/\s*↓|↑↓""",
)

/**
 * Claude/Codex `1. Yes` rows and Grok `1 (○) label` / `z (○) Type your answer` rows.
 * Skip these when scanning for the question.
 */
internal fun isOptionLine(line: String): Boolean {
    val trimmed = stripLeadingPromptMarkers(line.trim())
    if (trimmed.isEmpty()) return false
    return optionLine.containsMatchIn(trimmed) || grokChoiceLine.containsMatchIn(trimmed)
}

/** Keybinding chrome (`Tab:`, `Esc:`, `Enter:submit`, `Shift+x`, `↑/↓ navigate`). Not a question or an option. */
internal fun isKeyHintLine(line: String): Boolean {
    val trimmed = stripLeadingPromptMarkers(line.trim())
    if (trimmed.isEmpty() || trimmed.length > 200) return false
    return keyHintLine.containsMatchIn(trimmed)
}

/**
 * Drops the trailing TUI selection block: radio or numbered rows, their wrapped
 * descriptions, and keybinding chrome (`↑/↓ navigate`, `Enter:submit`, `Tab:next`).
 * The agent's question above that block stays. Text with no such block is unchanged.
 */
public fun withoutAskSelectionBlock(text: String): String {
    if (text.isEmpty()) return text
    val lines = text.lines()
    var cut = lines.size
    var started = false
    for (index in lines.indices.reversed()) {
        val line = lines[index]
        val core = selectionCore(line)
        if (!started) {
            if (line.isBlank() || core.isEmpty()) continue
            if (isSelectionQuestion(core)) break
            if (isOptionLine(core) || isKeyHintLine(core)) {
                started = true
                cut = index
                continue
            }
            break
        }
        if (isSelectionQuestion(core)) break
        if (
            line.isBlank() ||
            core.isEmpty() ||
            isOptionLine(core) ||
            isKeyHintLine(core) ||
            isPaneFooterLine(core) ||
            isHangingIndent(line)
        ) {
            cut = index
            continue
        }
        break
    }
    if (!started) return text
    return lines.take(cut).joinToString("\n").trim()
}

private fun selectionCore(line: String): String =
    stripLeadingPromptMarkers(stripLeadingBox(line.trim()))

private fun isSelectionQuestion(core: String): Boolean {
    val qAscii = core.indexOf('?')
    val qFull = core.indexOf('？')
    val qIdx = when {
        qAscii < 0 -> qFull
        qFull < 0 -> qAscii
        else -> minOf(qAscii, qFull)
    }
    if (qIdx < 0) return false
    val before = core.take(qIdx).trim()
    return before.any { it.isLetterOrDigit() || it.code in 0x4E00..0x9FFF }
}

/**
 * Radio row `N (○|●) rest`. [GrokChoice.filled] is true for `(●)`.
 * [stripAnsi] folds U+25CF to `*`, so `(*)` is the same filled radio.
 */
internal data class GrokChoice(val key: String, val rest: String, val filled: Boolean)

/** Radio row `N (○|●) rest`, or null when the line is not one. The key keeps its original case. */
internal fun matchGrokChoice(line: String): GrokChoice? {
    val trimmed = stripLeadingPromptMarkers(line.trim())
    val match = grokChoiceLine.find(trimmed) ?: return null
    val mark = match.groupValues[2]
    return GrokChoice(
        key = match.groupValues[1],
        rest = match.groupValues[3],
        filled = mark == "●" || mark == "*",
    )
}

/** Claude/Codex cursor, detected before prompt glyphs are stripped. `>` alone is not a cursor. */
internal fun hasClaudeCursor(raw: String): Boolean {
    val stripped = stripLeadingBox(raw.trim())
    return stripped.startsWith("❯") || stripped.startsWith("›")
}

private val numberedChoiceLine = Regex("""^(?:[❯›>•·●]\s*)?(\d+)[.)、]\s+(.+)""")

/** Claude/Codex row `1. Yes` / `2. No`, or null. */
internal fun matchNumberedChoice(line: String): Pair<String, String>? {
    val trimmed = stripLeadingPromptMarkers(line.trim())
    val match = numberedChoiceLine.find(trimmed) ?: return null
    return match.groupValues[1] to match.groupValues[2]
}

/** Frame columns (`│┃|\▌▎`) and the padding that follows them. Prompt glyphs stay. */
internal fun stripLeadingBox(line: String): String {
    var s = line.trimStart()
    while (true) {
        val next = leadingBox.replaceFirst(s, "").trimStart()
        if (next == s) return s
        s = next
    }
}

/**
 * True when a following line is indented under a radio row (the description wrap),
 * not the two-space frame padding on the question itself.
 */
internal fun isHangingIndent(raw: String): Boolean {
    val afterBar = raw.trimStart().dropWhile { it == '│' || it == '┃' || it == '|' || it == '▌' || it == '▎' }
    return afterBar.takeWhile { it == ' ' || it == '\t' }.length >= 4
}

/**
 * Drop leading box bars and › / ❯ / > / ? prompt glyphs so the card shows the real question.
 * Bars are stripped again after a prompt glyph, and the other way around.
 */
internal fun stripLeadingPromptMarkers(line: String): String {
    var s = line.trimStart()
    while (true) {
        val noBox = leadingBox.replaceFirst(s, "")
        val noPrompt = promptMarker.replaceFirst(noBox, "").trimStart()
        val next = leadingBox.replaceFirst(noPrompt, "").trimStart()
        if (next == s) return s
        s = next
    }
}

/**
 * Leading box/bar chrome only (`|`, U+2500–U+257F, U+2580–U+259F), plus block elements
 * U+2580–U+259F anywhere (scrollbar █). A pipe inside the text, as in `a | b`, stays.
 * CJK punctuation is outside these ranges and stays.
 */
internal fun stripTuiChrome(text: String): String {
    var start = 0
    while (start < text.length && isLeadingBarChrome(text[start])) start++
    if (start >= text.length) return ""
    if (start == 0 && text.none { it.code in 0x2580..0x259F }) return text
    val sb = StringBuilder(text.length - start)
    for (index in start until text.length) {
        val ch = text[index]
        if (ch.code in 0x2580..0x259F) continue
        sb.append(ch)
    }
    return sb.toString()
}

private fun isLeadingBarChrome(ch: Char): Boolean {
    val code = ch.code
    return ch == '|' || code in 0x2500..0x257F || code in 0x2580..0x259F
}

private fun isLatinAlnum(ch: Char): Boolean =
    ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9'

/**
 * Join hard-wrapped pane lines. Spaces already in a fragment stay.
 * One space is inserted only when both sides are Latin letters or digits and the
 * previous fragment does not end in `-` or `_`. CJK seams and `term-` + `probe.txt` stay tight.
 * Block elements are removed from each fragment so a scrollbar █ does not survive the join.
 */
internal fun joinHardWrapped(parts: List<String>): String {
    val sb = StringBuilder()
    for (part in parts) {
        val clean = stripTuiChrome(part)
        if (clean.isEmpty()) continue
        if (sb.isNotEmpty()) {
            val left = sb[sb.length - 1]
            val right = clean[0]
            if (left != '-' && left != '_' && isLatinAlnum(left) && isLatinAlnum(right)) sb.append(' ')
        }
        sb.append(clean)
    }
    return sb.toString()
}

internal fun isToolCallLine(line: String): Boolean {
    val trimmed = line.trim()
    if (percentShell.containsMatchIn(trimmed)) return true
    if (percentTool.containsMatchIn(trimmed) || shellTool.containsMatchIn(trimmed)) return true
    if (labeledTool.containsMatchIn(trimmed)) return true
    val verb = verbPath.find(trimmed) ?: return false
    return looksLikeToolArg(verb.groupValues[2])
}

internal fun isDiffStart(line: String): Boolean {
    val trimmed = line.trimStart()
    return trimmed.startsWith("diff --git ") ||
        trimmed.startsWith("@@") ||
        trimmed.startsWith("--- a/") ||
        trimmed.startsWith("+++ b/") ||
        trimmed.startsWith("--- /dev/null") ||
        trimmed.startsWith("+++ /dev/null")
}

internal fun isDiffBody(line: String): Boolean {
    if (line.isEmpty()) return false
    if (isDiffStart(line)) return true
    val trimmed = line.trimStart()
    if (
        trimmed.startsWith("index ") ||
        trimmed.startsWith("new file ") ||
        trimmed.startsWith("deleted file ") ||
        trimmed.startsWith("similarity ") ||
        trimmed.startsWith("rename ") ||
        trimmed.startsWith("old mode ") ||
        trimmed.startsWith("new mode ") ||
        trimmed.startsWith("\\")
    ) {
        return true
    }
    return line.startsWith("+") || line.startsWith("-") || line.startsWith(" ") ||
        line.startsWith("\t+") || line.startsWith("\t-") || line.startsWith("\t ")
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
                var inDiff = false
                while (next < lines.size) {
                    val follow = lines[next]
                    if (!inDiff && looksLikeEdit(line) && isDiffStart(follow)) inDiff = true
                    if (inDiff) {
                        if (follow.isBlank() || !isDiffBody(follow)) break
                        block += follow
                        next++
                        continue
                    }
                    if (!isToolContinuation(follow)) break
                    block += follow
                    next++
                }
                pieces += toolPiece(block)
                index = next
            }
            isDiffStart(line) -> {
                flushProse()
                val block = mutableListOf(line)
                var next = index + 1
                while (next < lines.size && lines[next].isNotBlank() && isDiffBody(lines[next])) {
                    block += lines[next]
                    next++
                }
                pieces += PanePiece.Tool("edit", "edit", AcpToolStatus.Completed, block.joinToString("\n"))
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

private fun looksLikeEdit(title: String): Boolean {
    val lower = title.lowercase()
    return lower.contains("edit") || lower.contains("write") || lower.contains("patch") || lower.contains("diff")
}

private fun looksLikeToolArg(token: String): Boolean {
    if (token.startsWith("-") || token.startsWith("~/") || token.startsWith("/") || token.startsWith("./") || token.startsWith("../")) {
        return true
    }
    if ('/' in token) return true
    val name = token.substringAfterLast('/')
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.lastIndex) return false
    val ext = name.substring(dot + 1)
    return ext.length in 1..8 && ext.all { it.isLetter() }
}

private fun kindFromTitle(title: String): String? {
    if (title.contains("$:")) return "bash"
    percentKind.find(title)?.groupValues?.get(1)?.let { return it }
    val match = labeledKind.find(title)?.groupValues?.get(1)?.lowercase() ?: return null
    return when (match) {
        "shell", "bash", "run" -> "bash"
        "edit", "write" -> "edit"
        "read", "grep", "glob", "search" -> "read"
        else -> null
    }
}

private fun toolPiece(lines: List<String>): PanePiece.Tool {
    val title = lines.first().trim()
    val detail = lines.drop(1).joinToString("\n").trim().ifBlank { null }
    val kind = kindFromTitle(title)
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
