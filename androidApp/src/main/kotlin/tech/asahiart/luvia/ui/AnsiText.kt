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
