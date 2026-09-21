package tech.asahiart.luvia.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import tech.asahiart.luvia.AnsiRgb
import tech.asahiart.luvia.AnsiSpan
import tech.asahiart.luvia.parseAnsi
import tech.asahiart.luvia.ui.theme.LuviaTheme

internal fun ansiAnnotatedString(
    text: String,
    defaultForeground: Color,
    defaultBackground: Color,
): AnnotatedString {
    val spans = parseAnsi(text)
    if (spans.isEmpty()) return AnnotatedString("")
    return buildAnnotatedString {
        for (span in spans) {
            withStyle(span.toSpanStyle(defaultForeground, defaultBackground)) {
                append(span.text)
            }
        }
    }
}

internal fun terminalDisplayLines(
    text: String,
    isAnsi: Boolean,
    defaultForeground: Color,
    defaultBackground: Color,
): List<AnnotatedString> {
    if (text.isEmpty()) return emptyList()
    if (!isAnsi || !text.hasAnsiEscape()) {
        return text.split('\n').map { AnnotatedString(it) }
    }
    return ansiSpansToLines(parseAnsi(text), defaultForeground, defaultBackground)
}

private fun String.hasAnsiEscape(): Boolean {
    for (i in indices) {
        val c = this[i]
        if (c == '\u001B' || c == '\u009B') return true
    }
    return false
}

private fun ansiSpansToLines(
    spans: List<AnsiSpan>,
    defaultForeground: Color,
    defaultBackground: Color,
): List<AnnotatedString> {
    if (spans.isEmpty()) return listOf(AnnotatedString(""))
    val lines = ArrayList<AnnotatedString>()
    var builder = AnnotatedString.Builder()
    for (span in spans) {
        val style = span.toSpanStyle(defaultForeground, defaultBackground)
        val t = span.text
        var start = 0
        while (true) {
            val nl = t.indexOf('\n', start)
            val end = if (nl < 0) t.length else nl
            if (end > start) {
                val from = builder.length
                builder.append(t, start, end)
                builder.addStyle(style, from, builder.length)
            }
            if (nl < 0) break
            lines.add(builder.toAnnotatedString())
            builder = AnnotatedString.Builder()
            start = nl + 1
        }
    }
    lines.add(builder.toAnnotatedString())
    return lines
}


private fun AnsiSpan.toSpanStyle(
    defaultForeground: Color,
    defaultBackground: Color,
): SpanStyle {
    var fg = foreground?.toColor() ?: defaultForeground
    var bg = background?.toColor() ?: defaultBackground
    if (inverse) {
        val swapped = fg
        fg = bg
        bg = swapped
    }
    if (dim) fg = fg.copy(alpha = fg.alpha * 0.65f)
    val decorations = buildList {
        if (underline) add(TextDecoration.Underline)
        if (strikethrough) add(TextDecoration.LineThrough)
    }
    return SpanStyle(
        color = fg,
        background = if (background != null || inverse) bg else Color.Unspecified,
        fontFamily = LuviaTheme.mono,
        fontWeight = if (bold) FontWeight.Bold else null,
        fontStyle = if (italic) FontStyle.Italic else null,
        textDecoration = when (decorations.size) {
            0 -> null
            1 -> decorations[0]
            else -> TextDecoration.combine(decorations)
        },
    )
}

private fun AnsiRgb.toColor(): Color = Color(red = r / 255f, green = g / 255f, blue = b / 255f)
