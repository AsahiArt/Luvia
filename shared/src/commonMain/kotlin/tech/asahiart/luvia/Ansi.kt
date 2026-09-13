package tech.asahiart.luvia

private const val BEL = '\u0007'
private const val ESC = '\u001B'
private const val CSI_8 = '\u009B'
private const val OSC_8 = '\u009D'
private const val DCS_8 = '\u0090'
private const val SOS_8 = '\u0098'
private const val ST_8 = '\u009C'
private const val PM_8 = '\u009E'
private const val APC_8 = '\u009F'

public data class AnsiRgb(
    public val r: Int,
    public val g: Int,
    public val b: Int,
)

public data class AnsiSpan(
    public val text: String,
    public val bold: Boolean = false,
    public val dim: Boolean = false,
    public val italic: Boolean = false,
    public val underline: Boolean = false,
    public val strikethrough: Boolean = false,
    public val inverse: Boolean = false,
    public val foreground: AnsiRgb? = null,
    public val background: AnsiRgb? = null,
)

/**
 * Maps Powerline / Nerd Font private-use glyphs to ASCII so system
 * monospace does not render missing-glyph boxes.
 */
public fun replaceTerminalGlyphs(text: String): String {
    if (text.isEmpty()) return text
    val out = StringBuilder(text.length)
    var i = 0
    val n = text.length
    while (i < n) {
        val cp: Int
        val width: Int
        val c = text[i]
        if (c.isHighSurrogate() && i + 1 < n && text[i + 1].isLowSurrogate()) {
            cp = ((c.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00) + 0x10000
            width = 2
        } else {
            cp = c.code
            width = 1
        }
        val mapped = mapTerminalGlyph(cp)
        if (mapped != null) out.append(mapped) else out.appendRange(text, i, i + width)
        i += width
    }
    return out.toString()
}

private fun mapTerminalGlyph(cp: Int): String? =
    when (cp) {
        0xE0A0 -> "*"
        0xE0A1 -> "#"
        0xE0A2 -> "RO"
        0xE0A3 -> ":"
        0xE0B0, 0xE0B1 -> ">"
        0xE0B2, 0xE0B3 -> "<"
        0xE0B4, 0xE0B5 -> ")"
        0xE0B6, 0xE0B7 -> "("
        0xE0B8, 0xE0B9, 0xE0BC, 0xE0BE -> "/"
        0xE0BA, 0xE0BD, 0xE0BF -> "\\"
        0xE0BB -> "|"
        0x25C6, 0x25C8, 0x25CF, 0x2022 -> "*"
        0x25C7, 0x25CA -> "o"
        else ->
            if (cp in 0xE000..0xF8FF ||
                cp in 0xF0000..0xFFFFD ||
                cp in 0x100000..0x10FFFD
            ) {
                "?"
            } else {
                null
            }
    }

/**
 * Parses CSI SGR (including 16 / 256 / 24-bit colors) into styled spans.
 * Unknown CSI, OSC, and other VT sequences are dropped, same as [stripAnsi].
 */
public fun parseAnsi(text: String): List<AnsiSpan> {
    if (text.isEmpty()) return emptyList()
    val source = replaceTerminalGlyphs(text)
    val spans = ArrayList<AnsiSpan>()
    val buf = StringBuilder(source.length)
    var style = SgrStyle()
    var i = 0
    val n = source.length

    fun flush() {
        if (buf.isEmpty()) return
        val span = style.toSpan(buf.toString())
        buf.clear()
        val last = spans.lastOrNull()
        if (last != null && last.sameStyleAs(span)) {
            spans[spans.lastIndex] = last.copy(text = last.text + span.text)
        } else {
            spans += span
        }
    }

    while (i < n) {
        when (val c = source[i]) {
            ESC -> {
                if (i + 1 < n && source[i + 1] == '[') {
                    val csi = readCsi(source, i + 2)
                    if (csi.isSgr) {
                        flush()
                        style = applySgr(style, csi.params)
                    }
                    i = csi.end
                } else {
                    i = skipEsc(source, i)
                }
            }
            CSI_8 -> {
                val csi = readCsi(source, i + 1)
                if (csi.isSgr) {
                    flush()
                    style = applySgr(style, csi.params)
                }
                i = csi.end
            }
            OSC_8, DCS_8, SOS_8, PM_8, APC_8 -> i = skipTerminated(source, i + 1)
            ST_8 -> i++
            else -> {
                buf.append(c)
                i++
            }
        }
    }
    flush()
    return spans
}

/**
 * Removes CSI / SGR / OSC and other common VT escape sequences, including
 * 24-bit `38;2;r;g;b` / `48;2;...` colors, leaving readable text.
 */
public fun stripAnsi(text: String): String {
    if (text.isEmpty()) return text
    return parseAnsi(text).joinToString("") { it.text }
}

private data class SgrStyle(
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val inverse: Boolean = false,
    val foreground: AnsiRgb? = null,
    val background: AnsiRgb? = null,
) {
    fun toSpan(text: String): AnsiSpan =
        AnsiSpan(
            text = text,
            bold = bold,
            dim = dim,
            italic = italic,
            underline = underline,
            strikethrough = strikethrough,
            inverse = inverse,
            foreground = foreground,
            background = background,
        )
}

private fun AnsiSpan.sameStyleAs(other: AnsiSpan): Boolean =
    bold == other.bold &&
        dim == other.dim &&
        italic == other.italic &&
        underline == other.underline &&
        strikethrough == other.strikethrough &&
        inverse == other.inverse &&
        foreground == other.foreground &&
        background == other.background

private data class Csi(
    val params: String,
    val intermediates: String,
    val final: Char?,
    val end: Int,
) {
    val isSgr: Boolean get() = intermediates.isEmpty() && final == 'm'
}

private fun readCsi(text: String, start: Int): Csi {
    var i = start
    val n = text.length
    val paramStart = i
    while (i < n && text[i] in '\u0030'..'\u003F') i++
    val params = text.substring(paramStart, i)
    val interStart = i
    while (i < n && text[i] in '\u0020'..'\u002F') i++
    val intermediates = text.substring(interStart, i)
    val final = if (i < n && text[i] in '\u0040'..'\u007E') text[i++] else null
    return Csi(params, intermediates, final, i)
}

private fun applySgr(style: SgrStyle, params: String): SgrStyle {
    val codes = parseSgrCodes(params)
    var s = style
    var i = 0
    while (i < codes.size) {
        val code = codes[i]
        if (code == 38 || code == 48 || code == 58) {
            val (color, next) = readColor(codes, i + 1)
            s = when {
                code == 38 && color != null -> s.copy(foreground = color)
                code == 48 && color != null -> s.copy(background = color)
                else -> s
            }
            i = next
            continue
        }
        if (code == null) {
            i++
            continue
        }
        s = when (code) {
            0 -> SgrStyle()
            1 -> s.copy(bold = true)
            2 -> s.copy(dim = true)
            3 -> s.copy(italic = true)
            4 -> s.copy(underline = true)
            7 -> s.copy(inverse = true)
            9 -> s.copy(strikethrough = true)
            21, 22 -> s.copy(bold = false, dim = false)
            23 -> s.copy(italic = false)
            24 -> s.copy(underline = false)
            27 -> s.copy(inverse = false)
            29 -> s.copy(strikethrough = false)
            in 30..37 -> s.copy(foreground = TABLE_16[code - 30])
            39 -> s.copy(foreground = null)
            in 40..47 -> s.copy(background = TABLE_16[code - 40])
            49 -> s.copy(background = null)
            in 90..97 -> s.copy(foreground = TABLE_16[code - 90 + 8])
            in 100..107 -> s.copy(background = TABLE_16[code - 100 + 8])
            else -> s
        }
        i++
    }
    return s
}

private fun parseSgrCodes(params: String): List<Int?> {
    if (params.isEmpty()) return listOf(0)
    return params.split(';', ':').map { part ->
        if (part.isEmpty()) 0 else part.toIntOrNull()
    }
}

private fun readColor(codes: List<Int?>, start: Int): Pair<AnsiRgb?, Int> {
    if (start >= codes.size) return null to start
    return when (codes[start]) {
        5 -> {
            val index = codes.getOrNull(start + 1)
            if (index == null) null to codes.size else indexedRgb(index) to start + 2
        }
        2 -> {
            val r = codes.getOrNull(start + 1)
            val g = codes.getOrNull(start + 2)
            val b = codes.getOrNull(start + 3)
            if (r == null || g == null || b == null) {
                null to codes.size
            } else {
                AnsiRgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255)) to start + 4
            }
        }
        else -> null to start + 1
    }
}

private fun indexedRgb(index: Int): AnsiRgb {
    val clamped = index.coerceIn(0, 255)
    if (clamped < 16) return TABLE_16[clamped]
    if (clamped >= 232) {
        val v = 8 + (clamped - 232) * 10
        return AnsiRgb(v, v, v)
    }
    val n = clamped - 16
    fun level(i: Int): Int = if (i == 0) 0 else 55 + i * 40
    return AnsiRgb(level(n / 36), level((n % 36) / 6), level(n % 6))
}

private val TABLE_16: Array<AnsiRgb> =
    intArrayOf(
        0x000000, 0xCD0000, 0x00CD00, 0xCDCD00,
        0x0000EE, 0xCD00CD, 0x00CDCD, 0xE5E5E5,
        0x7F7F7F, 0xFF0000, 0x00FF00, 0xFFFF00,
        0x5C5CFF, 0xFF00FF, 0x00FFFF, 0xFFFFFF,
    ).map { hex ->
        AnsiRgb((hex shr 16) and 0xFF, (hex shr 8) and 0xFF, hex and 0xFF)
    }.toTypedArray()

private fun skipEsc(text: String, start: Int): Int {
    val n = text.length
    if (start + 1 >= n) return n
    return when (text[start + 1]) {
        '[' -> skipCsi(text, start + 2)
        ']' -> skipTerminated(text, start + 2)
        'P', 'X', '^', '_' -> skipTerminated(text, start + 2)
        '\\' -> start + 2
        else -> {
            var i = start + 1
            while (i < n && text[i] in '\u0020'..'\u002F') i++
            if (i < n && text[i] in '\u0030'..'\u007E') i + 1 else i
        }
    }
}

private fun skipCsi(text: String, start: Int): Int {
    var i = start
    val n = text.length
    while (i < n && text[i] in '\u0030'..'\u003F') i++
    while (i < n && text[i] in '\u0020'..'\u002F') i++
    if (i < n && text[i] in '\u0040'..'\u007E') i++
    return i
}

private fun skipTerminated(text: String, start: Int): Int {
    var i = start
    val n = text.length
    while (i < n) {
        when (text[i]) {
            BEL, ST_8 -> return i + 1
            ESC -> return if (i + 1 < n && text[i + 1] == '\\') i + 2 else i + 1
            else -> i++
        }
    }
    return n
}
