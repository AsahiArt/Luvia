package tech.asahiart.luvia.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tech.asahiart.luvia.ui.theme.LuviaDarkExtended
import tech.asahiart.luvia.ui.theme.LuviaLightExtended

class AnsiTextTest {
    @Test
    fun plainLinesUseDefaultForegroundAtLeastLogFloor() {
        assertEquals(TerminalFg, LuviaDarkExtended.terminalFg)
        assertEquals(TerminalBg, LuviaDarkExtended.terminalBg)
        val lines = terminalDisplayLines(
            text = "hello\n\nworld",
            isAnsi = false,
            defaultForeground = TerminalFg,
            defaultBackground = TerminalBg,
        )
        assertEquals(listOf("hello", "", "world"), lines.map { it.text })
        lines.filter { it.text.isNotEmpty() }.forEach { line ->
            assertTrue(line.spanStyles.isNotEmpty(), line.text)
            line.spanStyles.forEach { range ->
                assertEquals(TerminalFg, range.item.color)
            }
            assertCovers(line.text, line.spanStyles.map { it.start to it.end })
        }
        assertAtLeast(TerminalFg, LogFloor)
    }

    @Test
    fun lightPlainLinesUseOnSurfaceOnMisakaBoard() {
        val bg = LuviaLightExtended.terminalBg
        val fg = LuviaLightExtended.terminalFg
        assertEquals(Color(0xFFEEF5F2), bg)
        assertEquals(Color(0xFF161D1C), fg)
        val lines = terminalDisplayLines(
            text = "hello\nworld",
            isAnsi = false,
            defaultForeground = fg,
            defaultBackground = bg,
            dimForeground = LuviaLightExtended.terminalDim,
        )
        assertEquals(listOf("hello", "world"), lines.map { it.text })
        lines.forEach { line ->
            line.spanStyles.forEach { range ->
                assertEquals(fg, range.item.color)
            }
        }
        assertTrue(contrastRatio(fg, bg) >= 13.0, "contrast ${contrastRatio(fg, bg)}")
    }

    @Test
    fun lightDimUsesOnSurfaceVariant() {
        val line = terminalDisplayLines(
            text = "\u001B[2msecondary",
            isAnsi = true,
            defaultForeground = LuviaLightExtended.terminalFg,
            defaultBackground = LuviaLightExtended.terminalBg,
            dimForeground = LuviaLightExtended.terminalDim,
        ).single()
        assertEquals("secondary", line.text)
        assertEquals(Color(0xFF3F4947), line.spanStyles.single().item.color)
    }

    @Test
    fun darkDimKeepsAlphaScale() {
        val line = terminalDisplayLines(
            text = "\u001B[2mx",
            isAnsi = true,
            defaultForeground = TerminalFg,
            defaultBackground = TerminalBg,
        ).single()
        assertEquals("x", line.text)
        val color = line.spanStyles.single().item.color
        assertEquals(TerminalFg.red, color.red)
        assertEquals(TerminalFg.green, color.green)
        assertEquals(TerminalFg.blue, color.blue)
        assertTrue(kotlin.math.abs(color.alpha - 0.65f) < 0.02f)
    }

    @Test
    fun failureRecolorPaintsPlainBodyAndKeepsAnsi() {
        val light = LuviaLightExtended
        val plain = terminalDisplayLines(
            text = "failed",
            isAnsi = false,
            defaultForeground = light.terminalFg,
            defaultBackground = light.terminalBg,
        ).single().recolorDefaults(setOf(light.terminalFg, light.terminalDim), light.failureInk)
        assertEquals(Color(0xFF410002), plain.spanStyles.single().item.color)
        val ansi = terminalDisplayLines(
            text = "\u001B[31mnope",
            isAnsi = true,
            defaultForeground = light.terminalFg,
            defaultBackground = light.terminalBg,
        ).single()
        val ansiColor = ansi.spanStyles.single().item.color
        val kept = ansi.recolorDefaults(setOf(light.terminalFg, light.terminalDim), light.failureInk)
        assertEquals(ansiColor, kept.spanStyles.single().item.color)
        assertTrue(ansiColor != light.failureInk)
    }

    @Test
    fun ansiFlagWithoutEscapeStillUsesDefaultForeground() {
        val line = terminalDisplayLines(
            text = "plain log",
            isAnsi = true,
            defaultForeground = TerminalFg,
            defaultBackground = TerminalBg,
        ).single()
        assertEquals("plain log", line.text)
        assertEquals(TerminalFg, line.spanStyles.single().item.color)
    }

    private fun assertCovers(text: String, ranges: List<Pair<Int, Int>>) {
        val covered = BooleanArray(text.length)
        for ((start, end) in ranges) {
            for (index in start until end) {
                if (index in covered.indices) covered[index] = true
            }
        }
        assertTrue(covered.all { it }, text)
    }

    private fun assertAtLeast(color: Color, floor: Color) {
        assertTrue(color.red + 1e-4f >= floor.red)
        assertTrue(color.green + 1e-4f >= floor.green)
        assertTrue(color.blue + 1e-4f >= floor.blue)
        assertTrue(color.alpha + 1e-4f >= floor.alpha)
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val lighter = maxOf(relativeLuminance(foreground), relativeLuminance(background))
        val darker = minOf(relativeLuminance(foreground), relativeLuminance(background))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return (0.2126 * channel(color.red)) + (0.7152 * channel(color.green)) + (0.0722 * channel(color.blue))
    }

    private companion object {
        val TerminalBg = Color(0xFF101615)
        val TerminalFg = Color(0xFFE4EEEB)
        val LogFloor = Color(0xFFC8CDD2)
    }
}
