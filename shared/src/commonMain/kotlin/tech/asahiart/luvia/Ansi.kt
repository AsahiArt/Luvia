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

/**
 * Removes CSI / SGR / OSC and other common VT escape sequences, including
 * 24-bit `38;2;r;g;b` / `48;2;...` colors, leaving readable text.
 */
public fun stripAnsi(text: String): String {
    if (text.isEmpty()) return text
    val out = StringBuilder(text.length)
    var i = 0
    val n = text.length
    while (i < n) {
        when (val c = text[i]) {
            ESC -> i = skipEsc(text, i)
            CSI_8 -> i = skipCsi(text, i + 1)
            OSC_8, DCS_8, SOS_8, PM_8, APC_8 -> i = skipTerminated(text, i + 1)
            ST_8 -> i++
            else -> {
                out.append(c)
                i++
            }
        }
    }
    return out.toString()
}

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
