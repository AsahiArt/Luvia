package tech.asahiart.luvia

import kotlin.test.Test
import kotlin.test.assertEquals

class AnsiTest {
    @Test
    fun emptyStringUnchanged() {
        assertEquals("", stripAnsi(""))
    }

    @Test
    fun plainTextUnchanged() {
        assertEquals("hello world", stripAnsi("hello world"))
        assertEquals("ok [not a sequence]", stripAnsi("ok [not a sequence]"))
    }

    @Test
    fun stripsSgrResetAndBold() {
        assertEquals("", stripAnsi("\u001B[0;1m"))
        assertEquals("", stripAnsi("\u001B[0m"))
        assertEquals("bold", stripAnsi("\u001B[0;1mbold\u001B[0m"))
    }

    @Test
    fun strips24BitForegroundColor() {
        assertEquals("", stripAnsi("\u001B[38;2;138;190;183m"))
        assertEquals(
            "tinted",
            stripAnsi("\u001B[38;2;138;190;183mtinted\u001B[0m"),
        )
    }

    @Test
    fun strips24BitBackgroundColor() {
        assertEquals("bg", stripAnsi("\u001B[48;2;10;20;30mbg\u001B[0m"))
    }

    @Test
    fun stripsMixedTextAndSeveralSequences() {
        val raw =
            "\u001B[0;1mhi\u001B[0m \u001B[0;38;2;138;190;183mcolor\u001B[0m done"
        assertEquals("hi color done", stripAnsi(raw))
    }

    @Test
    fun stripsOscTitleBel() {
        assertEquals("body", stripAnsi("\u001B]0;title\u0007body"))
        assertEquals("body", stripAnsi("\u001B]0;title\u001B\\body"))
    }

    @Test
    fun isIdempotent() {
        val raw = "\u001B[0;1mplain\u001B[0m"
        assertEquals("plain", stripAnsi(stripAnsi(raw)))
        assertEquals("plain", stripAnsi("plain"))
    }
}
