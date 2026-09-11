package tech.asahiart.luvia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnsiTest {
    @Test
    fun emptyStringUnchanged() {
        assertEquals("", stripAnsi(""))
        assertEquals(emptyList(), parseAnsi(""))
    }

    @Test
    fun plainTextUnchanged() {
        assertEquals("hello world", stripAnsi("hello world"))
        assertEquals("ok [not a sequence]", stripAnsi("ok [not a sequence]"))
        assertEquals(
            listOf(AnsiSpan("hello world")),
            parseAnsi("hello world"),
        )
    }

    @Test
    fun stripsSgrResetAndBold() {
        assertEquals("", stripAnsi("\u001B[0;1m"))
        assertEquals("", stripAnsi("\u001B[0m"))
        assertEquals("bold", stripAnsi("\u001B[0;1mbold\u001B[0m"))
        assertEquals(
            listOf(AnsiSpan("bold", bold = true)),
            parseAnsi("\u001B[0;1mbold\u001B[0m"),
        )
    }

    @Test
    fun strips24BitForegroundColor() {
        assertEquals("", stripAnsi("\u001B[38;2;138;190;183m"))
        assertEquals(
            "tinted",
            stripAnsi("\u001B[38;2;138;190;183mtinted\u001B[0m"),
        )
        val span = parseAnsi("\u001B[38;2;138;190;183mtinted\u001B[0m").single()
        assertEquals("tinted", span.text)
        assertEquals(AnsiRgb(138, 190, 183), span.foreground)
    }

    @Test
    fun strips24BitBackgroundColor() {
        assertEquals("bg", stripAnsi("\u001B[48;2;10;20;30mbg\u001B[0m"))
        val span = parseAnsi("\u001B[48;2;10;20;30mbg\u001B[0m").single()
        assertEquals(AnsiRgb(10, 20, 30), span.background)
    }

    @Test
    fun colonSeparatedTruecolor() {
        val span = parseAnsi("\u001B[38:2:1:2:3mrgb\u001B[0m").single()
        assertEquals("rgb", span.text)
        assertEquals(AnsiRgb(1, 2, 3), span.foreground)
    }

    @Test
    fun indexedAndBrightColors() {
        val red = parseAnsi("\u001B[31mred").single()
        assertEquals(AnsiRgb(0xCD, 0x00, 0x00), red.foreground)
        val bright = parseAnsi("\u001B[91mred").single()
        assertEquals(AnsiRgb(0xFF, 0x00, 0x00), bright.foreground)
        val cube = parseAnsi("\u001B[38;5;196mhot").single()
        assertEquals(AnsiRgb(255, 0, 0), cube.foreground)
        val gray = parseAnsi("\u001B[38;5;232mdim").single()
        assertEquals(AnsiRgb(8, 8, 8), gray.foreground)
    }

    @Test
    fun stripsMixedTextAndSeveralSequences() {
        val raw =
            "\u001B[0;1mhi\u001B[0m \u001B[0;38;2;138;190;183mcolor\u001B[0m done"
        assertEquals("hi color done", stripAnsi(raw))
        val spans = parseAnsi(raw)
        assertEquals("hi", spans[0].text)
        assertTrue(spans[0].bold)
        assertEquals(" ", spans[1].text)
        assertEquals("color", spans[2].text)
        assertEquals(AnsiRgb(138, 190, 183), spans[2].foreground)
        assertEquals(" done", spans[3].text)
        assertNull(spans[3].foreground)
    }

    @Test
    fun stripsOscTitleBel() {
        assertEquals("body", stripAnsi("\u001B]0;title\u0007body"))
        assertEquals("body", stripAnsi("\u001B]0;title\u001B\\body"))
        assertEquals(listOf(AnsiSpan("body")), parseAnsi("\u001B]0;title\u0007body"))
    }

    @Test
    fun dropsUnknownCsi() {
        assertEquals("keep", stripAnsi("\u001B[2Jkeep"))
        assertEquals(listOf(AnsiSpan("keep")), parseAnsi("\u001B[2Jkeep"))
    }

    @Test
    fun eightBitCsiSgr() {
        val span = parseAnsi("\u009B1;32mgo").single()
        assertEquals("go", span.text)
        assertTrue(span.bold)
        assertEquals(AnsiRgb(0x00, 0xCD, 0x00), span.foreground)
    }

    @Test
    fun inverseAndUnderline() {
        val span = parseAnsi("\u001B[7;4mrev").single()
        assertTrue(span.inverse)
        assertTrue(span.underline)
        assertEquals("rev", span.text)
    }

    @Test
    fun mergesAdjacentEqualStyles() {
        val spans = parseAnsi("ab\u001B[0mcd")
        assertEquals(listOf(AnsiSpan("abcd")), spans)
    }

    @Test
    fun isIdempotent() {
        val raw = "\u001B[0;1mplain\u001B[0m"
        assertEquals("plain", stripAnsi(stripAnsi(raw)))
        assertEquals("plain", stripAnsi("plain"))
        assertEquals(stripAnsi(raw), parseAnsi(raw).joinToString("") { it.text })
    }

    @Test
    fun incompleteTruecolorIsIgnored() {
        assertEquals("x", stripAnsi("\u001B[38;2;1;2mx"))
        val span = parseAnsi("\u001B[38;2;1;2mx").single()
        assertEquals(AnsiSpan("x"), span)
    }

    @Test
    fun replacesPowerlineSeparators() {
        assertEquals(">", replaceTerminalGlyphs("\uE0B0"))
        assertEquals("<", replaceTerminalGlyphs("\uE0B2"))
        assertEquals("*", replaceTerminalGlyphs("\uE0A0"))
        assertEquals("a>b", replaceTerminalGlyphs("a\uE0B0b"))
    }

    @Test
    fun replacesOtherPrivateUseWithQuestionMark() {
        assertEquals("?", replaceTerminalGlyphs("\uE000"))
        assertEquals("?", replaceTerminalGlyphs("\uF8FF"))
    }

    @Test
    fun parseAnsiAppliesGlyphReplacement() {
        assertEquals(">", parseAnsi("\uE0B0").single().text)
        assertEquals(">", stripAnsi("\uE0B0"))
        assertEquals("hello", replaceTerminalGlyphs("hello"))
    }
}
