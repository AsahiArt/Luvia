package tech.asahiart.luvia.thread

import tech.asahiart.luvia.AcpToolCall
import tech.asahiart.luvia.AcpToolStatus
import kotlin.math.roundToInt

/** How many cells the chat test bar always draws. Failures are sampled into this width. */
public const val TEST_BAR_CELLS: Int = 64

public data class DiffLineView(
    /** Null when the tool text has no hunk header, so the UI does not invent a number. */
    public val oldNumber: Int?,
    public val newNumber: Int?,
    /** `"+"`, `"-"`, or `" "` (context). */
    public val marker: String,
    public val text: String,
)

public data class DiffFileView(
    public val path: String?,
    public val lines: List<DiffLineView>,
    public val additions: Int,
    public val deletions: Int,
)

/** Facts the chat trail can show without inventing a tool the host did not send. */
public data class ToolTrailView(
    public val kind: ToolTrailKind,
    public val path: String?,
    public val duration: String?,
    public val diffs: List<DiffFileView>,
    public val meter: RunMeter?,
    public val failedNames: List<String>,
    public val markFailed: Boolean,
)

/** One Xcode-style token. The phone picks the light and dark inks. */
public enum class CodeTokenKind { Keyword, Type, Number, String, Comment, Plain }

public data class CodeToken(
    public val kind: CodeTokenKind,
    public val text: String,
)

/** One fact in the horizontal chip stream. Copy stays in the platform UI. */
public sealed class ToolStreamPart {
    public data class Reads(public val count: Int) : ToolStreamPart()

    public data class Edit(
        public val file: String,
        public val additions: Int,
        public val deletions: Int,
        public val hasDiff: Boolean,
    ) : ToolStreamPart()

    public data class Meter(public val passed: Int, public val total: Int) : ToolStreamPart()

    public data class Run(public val title: String) : ToolStreamPart()

    public data class Other(public val count: Int) : ToolStreamPart()
}

public fun toolTrailView(call: AcpToolCall): ToolTrailView {
    val diffs = parseDiffFiles(call.summary)
    val blob = listOfNotNull(call.title, call.summary).joinToString("\n")
    val path = call.path?.takeIf { it.isNotBlank() }
        ?: diffs.firstNotNullOfOrNull { it.path }
        ?: findPath(call.summary)
        ?: findPath(call.title)
    return ToolTrailView(
        kind = toolTrailKind(call.kind, call.title),
        path = path,
        duration = call.duration?.takeIf { it.isNotBlank() } ?: findDuration(blob),
        diffs = diffs,
        meter = parseRunMeter(call.summary),
        failedNames = findFailedNames(call.summary),
        markFailed = call.status == AcpToolStatus.Failed,
    )
}

/** Run chips stay a short action. Longer commands and cron dumps use [RUN_CHIP_FALLBACK]. */
public const val RUN_CHIP_LIMIT: Int = 12

public const val RUN_CHIP_FALLBACK: String = "运行"

/**
 * `ReconnectPolicy.kt +2 −1`. Returns null for a blank name or the bare word `file`.
 * The plus and minus are the unicode minus, so the phone can color each count.
 */
public fun editChipLabel(file: String, additions: Int, deletions: Int): String? {
    if (!isChipBasename(file)) return null
    return "$file +$additions −$deletions"
}

/**
 * Basename drawn on the edit chip.
 * `0` is the full name [editChipLabel] uses (`ReconnectPolicy.kt`).
 * `1` drops a short extension (`ReconnectPolicy`) when the row is tight.
 * `2` abbreviates that stem (`Reconnect…`) so the test chip can stay `127/128`.
 */
public fun editChipFileName(file: String, tighten: Int = 0): String {
    if (tighten <= 0) return file
    val stem = chipStem(file)
    if (tighten == 1) return stem
    return abbreviateChipStem(stem)
}

/** Stems this long stay whole. Longer ones keep their first camel word. */
private const val EDIT_CHIP_STEM_LIMIT: Int = 12

private fun chipStem(file: String): String {
    val dot = file.lastIndexOf('.')
    if (dot <= 0 || dot == file.lastIndex) return file
    val ext = file.substring(dot + 1)
    if (ext.length !in 1..8 || !ext.all { it.isLetter() }) return file
    val stem = file.substring(0, dot)
    return stem.ifBlank { file }
}

private fun abbreviateChipStem(stem: String): String {
    if (stem.length <= EDIT_CHIP_STEM_LIMIT) return stem
    val head = camelHead(stem)
    val prefix = if (head.length >= 4 && head.length < stem.length) head else stem.take(9).trimEnd()
    return prefix + "…"
}

/** `ReconnectPolicy` → `Reconnect`. A name with no inner capital stays whole. */
private fun camelHead(name: String): String {
    for (index in 1 until name.length) {
        val current = name[index]
        if (current == '_' || current == '-' || current == ' ') return name.substring(0, index)
        if (current.isUpperCase() && name[index - 1].isLowerCase()) return name.substring(0, index)
    }
    return name
}

/** `127/128`. The whole fraction, never a clipped prefix. */
public fun meterChipLabel(passed: Int, total: Int): String = "$passed/$total"

/**
 * A run chip title. Shell text keeps the first command word (`git`).
 * A cron or `schedule:|…` dump becomes [RUN_CHIP_FALLBACK]. Anything longer than
 * [RUN_CHIP_LIMIT] is cut to that limit.
 */
public fun runChipLabel(title: String, summary: String? = null): String {
    val source = title.trim().ifBlank { summary?.trim().orEmpty() }
    if (source.isEmpty() || isScheduleDump(source)) return RUN_CHIP_FALLBACK
    val body = commandLead.find(source)?.groupValues?.get(1)?.trim()?.ifBlank { null } ?: source
    if (isScheduleDump(body)) return RUN_CHIP_FALLBACK
    val word = body.split(Regex("""\s+""")).firstOrNull { token ->
        val clean = token.trim(':', '|', ',', ';', '`', '"', '\'')
        clean.isNotEmpty() && clean != "$" && !clean.startsWith("-") && !clean.startsWith("%")
    }?.trim(':', '|', ',', ';', '`', '"', '\'') ?: return RUN_CHIP_FALLBACK
    if (word.isEmpty() || isScheduleDump(word)) return RUN_CHIP_FALLBACK
    return word.take(RUN_CHIP_LIMIT)
}

/**
 * Reads first, then each edit and run in order, then any other tools.
 * A run with a meter contributes that meter instead of a run chip.
 * Identical run labels and identical meters collapse to one chip.
 * An edit with no real basename is omitted — never a bare `file`.
 */
public fun toolStreamParts(calls: List<AcpToolCall>): List<ToolStreamPart> {
    val views = calls.map(::toolTrailView)
    val parts = mutableListOf<ToolStreamPart>()
    val reads = views.count { it.kind == ToolTrailKind.Read }
    if (reads > 0) parts += ToolStreamPart.Reads(reads)
    val seenRuns = mutableSetOf<String>()
    val seenMeters = mutableSetOf<String>()
    fun addMeter(view: ToolTrailView) {
        val meter = view.meter ?: return
        val key = meterChipLabel(meter.passed, meter.total)
        if (seenMeters.add(key)) parts += ToolStreamPart.Meter(meter.passed, meter.total)
    }
    fun addRun(call: AcpToolCall) {
        val label = runChipLabel(call.title, call.summary)
        if (seenRuns.add(label)) parts += ToolStreamPart.Run(label)
    }
    calls.zip(views).forEach { (call, view) ->
        when (view.kind) {
            ToolTrailKind.Edit -> {
                val chip = editChip(call, view)
                if (chip != null) {
                    parts += chip
                    addMeter(view)
                } else if (view.diffs.isEmpty()) {
                    addRun(call)
                }
            }
            ToolTrailKind.Run -> {
                if (view.diffs.isNotEmpty()) editChip(call, view)?.let { parts += it }
                if (view.meter != null) addMeter(view)
                else if (view.diffs.isEmpty()) addRun(call)
            }
            ToolTrailKind.Read, ToolTrailKind.Other -> Unit
        }
    }
    val others = views.count { it.kind == ToolTrailKind.Other }
    if (others > 0) parts += ToolStreamPart.Other(others)
    return parts
}

private fun editChip(call: AcpToolCall, view: ToolTrailView): ToolStreamPart.Edit? {
    val raw = view.diffs.firstNotNullOfOrNull { it.path }
        ?: view.path
        ?: findPath(call.title)
        ?: findPath(call.summary)
    val file = diffFileTitle(raw).takeIf(::isChipBasename) ?: return null
    val additions = view.diffs.sumOf { it.additions }
    val deletions = view.diffs.sumOf { it.deletions }
    return ToolStreamPart.Edit(file, additions, deletions, view.diffs.isNotEmpty())
}

private fun isChipBasename(file: String): Boolean {
    if (file.isBlank() || file.equals("file", ignoreCase = true)) return false
    if (file.length > 48 || '/' in file || '|' in file) return false
    if (file.contains("schedule:", ignoreCase = true) || file.contains("cron:", ignoreCase = true)) return false
    return true
}

private fun isScheduleDump(text: String): Boolean {
    val lower = text.lowercase()
    return "schedule:" in lower || "cron:" in lower || text.count { it == '|' } >= 2
}

private val commandLead = Regex(
    """(?i)(?:^(?:[•·●]\s*)?%\s+\$:\s*|^(?:[•·●]\s*)?\$:\s*|^(?:cursor|claude|codex|gemini|agent|pi|opencode)\s+\w+\s*[:：]\s*)(.+)$""",
)

/** File name for the diff card header. Empty paths stay the literal `file`. */
public fun diffFileTitle(path: String?): String {
    val raw = path?.trim()?.trimEnd('/').orEmpty()
    if (raw.isEmpty()) return "file"
    return raw.substringAfterLast('/').ifBlank { "file" }
}

/** Parent path shown on the right of the diff header, such as `shared/commonMain`. */
public fun diffModulePath(path: String?): String? {
    val raw = path?.trim()?.trimEnd('/').orEmpty()
    if (raw.isEmpty() || '/' !in raw) return null
    return raw.substringBeforeLast('/').ifBlank { null }
}

/**
 * A unified diff that should replace a grey code bubble.
 * Returns empty unless the text itself starts as a diff, so a markdown list stays prose.
 */
public fun structuredDiffFiles(text: String): List<DiffFileView> {
    val first = text.lineSequence().firstOrNull { it.isNotBlank() }?.trimStart() ?: return emptyList()
    val headed = first.startsWith("diff --git ") ||
        first.startsWith("@@") ||
        first.startsWith("--- a/") ||
        first.startsWith("+++ ")
    if (!headed) return emptyList()
    return parseDiffFiles(text)
}

/** Splits one code line into Xcode-style tokens. The concatenation of [text] is the line. */
public fun highlightCode(text: String): List<CodeToken> {
    if (text.isEmpty()) return emptyList()
    val out = mutableListOf<CodeToken>()
    var index = 0
    fun emit(kind: CodeTokenKind, start: Int, end: Int) {
        if (end > start) out += CodeToken(kind, text.substring(start, end))
    }
    while (index < text.length) {
        val c = text[index]
        if (c == '/' && index + 1 < text.length && text[index + 1] == '/') {
            emit(CodeTokenKind.Comment, index, text.length)
            break
        }
        if (c == '/' && index + 1 < text.length && text[index + 1] == '*') {
            val close = text.indexOf("*/", index + 2)
            val end = if (close < 0) text.length else close + 2
            emit(CodeTokenKind.Comment, index, end)
            index = end
            continue
        }
        if (c == '"' || c == '\'') {
            val end = scanString(text, index, c)
            emit(CodeTokenKind.String, index, end)
            index = end
            continue
        }
        if (c.isDigit()) {
            var end = index + 1
            while (end < text.length && (text[end].isDigit() || text[end] == '_')) end++
            if (end < text.length && text[end] == '.' && end + 1 < text.length && text[end + 1].isDigit()) {
                end++
                while (end < text.length && text[end].isDigit()) end++
            }
            emit(CodeTokenKind.Number, index, end)
            index = end
            continue
        }
        if (c.isLetter() || c == '_') {
            var end = index + 1
            while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_')) end++
            val word = text.substring(index, end)
            val kind = when {
                word in CODE_KEYWORDS -> CodeTokenKind.Keyword
                word.first().isUpperCase() -> CodeTokenKind.Type
                else -> CodeTokenKind.Plain
            }
            emit(kind, index, end)
            index = end
            continue
        }
        if (c == '/') {
            emit(CodeTokenKind.Plain, index, index + 1)
            index++
            continue
        }
        var end = index + 1
        while (end < text.length) {
            val ch = text[end]
            if (ch.isLetterOrDigit() || ch == '_' || ch == '"' || ch == '\'' || ch == '/') break
            end++
        }
        emit(CodeTokenKind.Plain, index, end)
        index = end
    }
    return out
}

/** `true` marks a failed cell. The list is always [TEST_BAR_CELLS] long. */
public fun testBarCells(passed: Int, total: Int, markFailed: Boolean): List<Boolean> {
    if (total <= 0) {
        return List(TEST_BAR_CELLS) { markFailed && it == TEST_BAR_CELLS - 1 }
    }
    val failed = (total - passed).coerceAtLeast(0)
    var failedCells = ((failed.toFloat() / total.toFloat()) * TEST_BAR_CELLS).roundToInt()
    if (failed > 0 && failedCells == 0) failedCells = 1
    if (markFailed && failedCells == 0) failedCells = 1
    failedCells = failedCells.coerceIn(0, TEST_BAR_CELLS)
    return List(TEST_BAR_CELLS) { index -> index >= TEST_BAR_CELLS - failedCells }
}

/** Compact mask so Swift can draw the same bar without reimplementing the ratio. */
public fun testBarMask(passed: Int, total: Int, markFailed: Boolean): String =
    testBarCells(passed, total, markFailed).joinToString("") { if (it) "1" else "0" }

/**
 * Unified diff for an ACP `diff` content block. Line numbers start at 1 because
 * those blocks carry the whole old and new text, not a pre-numbered hunk.
 * Returns null when the texts match. Large edits keep the first changed lines.
 */
internal fun unifiedDiff(path: String, oldText: String, newText: String): String? {
    val oldLines = splitKeep(oldText)
    val newLines = splitKeep(newText)
    if (oldLines == newLines) return null
    var start = 0
    while (start < oldLines.size && start < newLines.size && oldLines[start] == newLines[start]) start++
    var endOld = oldLines.size
    var endNew = newLines.size
    while (endOld > start && endNew > start && oldLines[endOld - 1] == newLines[endNew - 1]) {
        endOld--
        endNew--
    }
    val lead = (start - HUNK_CONTEXT).coerceAtLeast(0)
    val oldChangeEnd = endOld.coerceAtMost(start + MAX_DIFF_LINES)
    val newChangeEnd = endNew.coerceAtMost(start + MAX_DIFF_LINES)
    val body = mutableListOf<String>()
    var index = lead
    while (index < start) {
        body += " " + oldLines[index]
        index++
    }
    var oldIndex = start
    while (oldIndex < oldChangeEnd) {
        body += "-" + oldLines[oldIndex]
        oldIndex++
    }
    var newIndex = start
    while (newIndex < newChangeEnd) {
        body += "+" + newLines[newIndex]
        newIndex++
    }
    if (oldIndex == endOld && newIndex == endNew) {
        var trail = 0
        while (
            trail < HUNK_CONTEXT &&
            endOld + trail < oldLines.size &&
            endNew + trail < newLines.size
        ) {
            body += " " + oldLines[endOld + trail]
            trail++
        }
    }
    val oldCount = body.count { !it.startsWith("+") }
    val newCount = body.count { !it.startsWith("-") }
    val file = path.ifBlank { "file" }
    val oldStart = if (oldCount == 0) 0 else lead + 1
    val newStart = if (newCount == 0) 0 else lead + 1
    val sb = StringBuilder()
    sb.append("diff --git a/").append(file).append(" b/").append(file).append('\n')
    sb.append("--- a/").append(file).append('\n')
    sb.append("+++ b/").append(file).append('\n')
    sb.append("@@ -").append(oldStart).append(',').append(oldCount)
    sb.append(" +").append(newStart).append(',').append(newCount).append(" @@\n")
    body.forEach { sb.append(it).append('\n') }
    return sb.toString().trimEnd()
}

private const val HUNK_CONTEXT: Int = 3
private const val MAX_DIFF_LINES: Int = 80

private val CODE_KEYWORDS = setOf(
    "class", "object", "interface", "enum", "struct", "protocol", "extension", "fun", "func",
    "val", "var", "let", "const", "function", "return", "if", "else", "when", "for", "while",
    "do", "switch", "case", "default", "break", "continue", "try", "catch", "throw", "throws",
    "import", "package", "from", "export", "public", "private", "internal", "protected",
    "fileprivate", "open", "static", "final", "abstract", "override", "data", "sealed",
    "companion", "suspend", "inline", "expect", "actual", "null", "nil", "true", "false",
    "this", "self", "super", "init", "new", "typeof", "instanceof", "async", "await", "yield",
    "in", "is", "as", "of", "where", "guard", "defer", "some", "any", "get", "set",
)

private fun scanString(text: String, start: Int, quote: Char): Int {
    var index = start + 1
    while (index < text.length) {
        if (text[index] == '\\' && index + 1 < text.length) {
            index += 2
            continue
        }
        if (text[index] == quote) return index + 1
        index++
    }
    return text.length
}

private val durationPattern =
    Regex("""(?<!\w)(\d+(?:\.\d+)?)(\s*)(ms|s|秒)(?!\w)""", RegexOption.IGNORE_CASE)

private val failedPrefix = Regex("""(?i)^(?:FAILED|FAIL|✘|✗|×)\s+(\S.*)""")
private val failedSuffix = Regex("""(?i)^([\w.$]+)\s+FAILED\b""")
private val failedCase = Regex("""(?i)^([\w.$]+)\s*>\s*.+\bFAILED\b""")
private val testName = Regex("""^[\w.$]*(?:Test|Spec|Suite)[\w.$]*$""")

internal fun findDuration(text: String?): String? {
    if (text.isNullOrBlank()) return null
    val match = durationPattern.findAll(text).lastOrNull() ?: return null
    val number = match.groupValues[1]
    val unit = match.groupValues[3].lowercase()
    return number + unit
}

private fun findPath(text: String?): String? {
    if (text.isNullOrBlank()) return null
    return text.split(Regex("""\s+"""))
        .map { it.trim().trimEnd('.', ',', ';', ':', '→') }
        .firstOrNull { looksLikePath(it) }
}

private fun looksLikePath(token: String): Boolean {
    if (token.isEmpty() || token.length > 240) return false
    if (token.startsWith("~/") || token.startsWith("/") || token.startsWith("./") || token.startsWith("../")) {
        return true
    }
    if ('/' in token && ' ' !in token) return true
    return looksLikeFile(token)
}

private fun looksLikeFile(token: String): Boolean {
    val name = token.substringAfterLast('/')
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.lastIndex) return false
    if (name[dot - 1].isDigit()) return false
    val ext = name.substring(dot + 1)
    return ext.length in 1..8 && ext.all { it.isLetter() }
}

private fun findFailedNames(summary: String?): List<String> {
    if (summary.isNullOrBlank()) return emptyList()
    val names = mutableListOf<String>()
    for (raw in summary.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty() || isDiffChrome(line) || looksLikePath(line)) continue
        val name = failedPrefix.find(line)?.groupValues?.get(1)
            ?: failedCase.find(line)?.groupValues?.get(1)
            ?: failedSuffix.find(line)?.groupValues?.get(1)
            ?: line.takeIf { testName.matches(it) }
        if (name != null && name !in names) names += name.trim()
    }
    return names
}

private fun isDiffChrome(line: String): Boolean =
    line.startsWith("diff ") ||
        line.startsWith("index ") ||
        line.startsWith("--- ") ||
        line.startsWith("+++ ") ||
        line.startsWith("@@") ||
        line.startsWith("\\") ||
        line.startsWith("+") ||
        line.startsWith("-")

private fun parseDiffFiles(summary: String?): List<DiffFileView> {
    val lines = summary?.lines()?.filter { it.isNotEmpty() }.orEmpty()
    if (lines.isEmpty()) return emptyList()
    val diffish = lines.count { line ->
        line.startsWith("+") || line.startsWith("-") || line.startsWith("@@") || line.startsWith("diff ")
    }
    if (diffish == 0) return emptyList()
    val files = mutableListOf<DiffFileView>()
    var path: String? = null
    var rows = mutableListOf<DiffLineView>()
    var oldNumber: Int? = null
    var newNumber: Int? = null
    fun flush() {
        if (rows.isEmpty() && path == null) return
        val additions = rows.count { it.marker == "+" }
        val deletions = rows.count { it.marker == "-" }
        if (rows.isEmpty() && additions == 0 && deletions == 0) {
            path = null
            return
        }
        files += DiffFileView(path, rows.toList(), additions, deletions)
        rows = mutableListOf()
        path = null
        oldNumber = null
        newNumber = null
    }
    for (line in lines) {
        when {
            line.startsWith("diff --git ") -> {
                flush()
                path = gitPath(line)
            }
            line.startsWith("--- ") || line.startsWith("+++ ") -> {
                val header = headerPath(line)
                if (header != null && header != "/dev/null") path = header
            }
            line.startsWith("@@") -> {
                val hunk = hunkStarts(line)
                oldNumber = hunk?.first
                newNumber = hunk?.second
            }
            line.startsWith("\\") -> Unit
            line.startsWith("+") -> {
                val number = newNumber
                if (number != null) newNumber = number + 1
                rows += DiffLineView(null, number, "+", line.drop(1))
            }
            line.startsWith("-") -> {
                val number = oldNumber
                if (number != null) oldNumber = number + 1
                rows += DiffLineView(number, null, "-", line.drop(1))
            }
            else -> {
                val oldN = oldNumber
                val newN = newNumber
                if (oldN != null) oldNumber = oldN + 1
                if (newN != null) newNumber = newN + 1
                val text = if (line.startsWith(" ")) line.drop(1) else line
                rows += DiffLineView(oldN, newN, " ", text)
            }
        }
        if (rows.size >= MAX_DIFF_LINES) break
    }
    flush()
    return files
}

private fun gitPath(line: String): String? {
    val marker = " b/"
    val index = line.lastIndexOf(marker)
    if (index < 0) return null
    return line.substring(index + marker.length).trim().ifBlank { null }
}

private fun headerPath(line: String): String? {
    val raw = line.drop(4).trim()
    val path = when {
        raw.startsWith("a/") || raw.startsWith("b/") -> raw.drop(2)
        else -> raw
    }
    return path.substringBefore('\t').ifBlank { null }
}

private val hunkPattern = Regex("""@@\s+-(\d+)(?:,\d+)?\s+\+(\d+)(?:,\d+)?\s+@@""")

private fun hunkStarts(line: String): Pair<Int, Int>? {
    val match = hunkPattern.find(line) ?: return null
    val oldStart = match.groupValues[1].toIntOrNull() ?: return null
    val newStart = match.groupValues[2].toIntOrNull() ?: return null
    return oldStart to newStart
}

private fun splitKeep(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val lines = text.lines()
    return if (text.endsWith("\n") && lines.last().isEmpty()) lines.dropLast(1) else lines
}

private fun String.fileName(): String = trimEnd('/').substringAfterLast('/').ifBlank { this }
