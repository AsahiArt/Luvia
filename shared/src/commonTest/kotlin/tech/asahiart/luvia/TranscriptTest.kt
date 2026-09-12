package tech.asahiart.luvia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TranscriptTest {
    @Test
    fun shortDashesAreNotRules() {
        assertFalse(isFillRuleLine("---"))
        assertFalse(isFillRuleLine("───"))
        assertFalse(isFillRuleLine("not a rule"))
        assertFalse(isFillRuleLine(""))
    }

    @Test
    fun boxDrawingAndAsciiFillLinesAreRules() {
        assertTrue(isFillRuleLine("────────"))
        assertTrue(isFillRuleLine("━━━━━━━━"))
        assertTrue(isFillRuleLine("════════"))
        assertTrue(isFillRuleLine("--------"))
        assertTrue(isFillRuleLine("========"))
        assertTrue(isFillRuleLine("  ────────  "))
    }

    @Test
    fun splitsRulesOutOfSurroundingText() {
        val segments =
            transcriptSegments(
                """
                hello
                ────────
                world
                --------
                end
                """.trimIndent(),
            )
        assertEquals(5, segments.size)
        assertEquals("hello", (segments[0] as TranscriptSegment.Text).text)
        assertIs<TranscriptSegment.Rule>(segments[1])
        assertEquals("world", (segments[2] as TranscriptSegment.Text).text)
        assertIs<TranscriptSegment.Rule>(segments[3])
        assertEquals("end", (segments[4] as TranscriptSegment.Text).text)
    }

    @Test
    fun keepsPlainTranscriptAsOneTextBlock() {
        val segments = transcriptSegments("line one\nline two")
        assertEquals(1, segments.size)
        assertEquals("line one\nline two", (segments[0] as TranscriptSegment.Text).text)
    }

    @Test
    fun keepsASingleBlankLineInsideText() {
        val segments = transcriptSegments("hello\n\nworld")
        assertEquals(1, segments.size)
        assertEquals("hello\n\nworld", (segments[0] as TranscriptSegment.Text).text)
    }

    @Test
    fun collapsesRunsOfBlankLinesIntoOneGap() {
        val segments = transcriptSegments("hello\n\n\n\nworld")
        assertEquals(3, segments.size)
        assertEquals("hello", (segments[0] as TranscriptSegment.Text).text)
        assertIs<TranscriptSegment.Gap>(segments[1])
        assertEquals("world", (segments[2] as TranscriptSegment.Text).text)
    }
}
