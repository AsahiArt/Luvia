package tech.asahiart.luvia.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnsiTextTest {
    @Test
    fun plainLinesUseDefaultForegroundAtLeastLogFloor() {
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

    private companion object {
        val TerminalBg = Color(0xFF101615)
        val TerminalFg = Color(0xFFE4EEEB)
        val LogFloor = Color(0xFFC8CDD2)
    }
}
