package tech.asahiart.luvia.thread

import kotlin.test.Test
import kotlin.test.assertEquals

class OutputRunsTest {
    @Test
    fun hardWrappedProseIsJoined() {
        val runs = outputRuns("The terminal wraps this sentence\nat its own width.\n\nNext paragraph.")
        assertEquals(listOf(OutputRun("The terminal wraps this sentence at its own width.\nNext paragraph.", mono = false)), runs)
    }

    @Test
    fun listItemsKeepTheirBreaks() {
        val runs = outputRuns("Plan:\n- first step\n  continues here\n- second")
        assertEquals("Plan:\n- first step continues here\n- second", runs.single().text)
    }

    @Test
    fun boxDrawingStaysMono() {
        val runs = outputRuns("Intro\n╭──╮\n│ab│\n╰──╯\nOutro")
        assertEquals(listOf(false, true, false), runs.map { it.mono })
        assertEquals("╭──╮\n│ab│\n╰──╯", runs[1].text)
    }

    @Test
    fun fencedCodeKeepsLinesAndDropsFences() {
        val runs = outputRuns("```\nval a = 1\nval b = 2\n```")
        assertEquals(listOf(OutputRun("val a = 1\nval b = 2", mono = true)), runs)
    }
}
