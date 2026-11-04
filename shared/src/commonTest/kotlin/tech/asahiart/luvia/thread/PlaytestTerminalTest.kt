package tech.asahiart.luvia.thread

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaytestTerminalTest {
    @Test
    fun panesAreIndexPlusNameAndTheFailurePaneParses() {
        val panes = playtestTerminalPanes()
        assertEquals(listOf(PLAYTEST_PANE_ZSH, PLAYTEST_PANE_GRADLE, PLAYTEST_PANE_CLAUDE), panes.map { it.id })
        assertEquals(listOf("zsh", "gradle", "claude"), panes.map { it.title })
        assertEquals(PLAYTEST_PANE_CLAUDE, PLAYTEST_TERMINAL_FAILURE_PANE)
        panes.forEachIndexed { index, pane ->
            assertTrue(isPlaytestPane(pane.id))
            val label = terminalSegmentLabel(index, pane.title)
            assertEquals("${index + 1} ${pane.title}", label)
            assertTrue(label.any { !it.isDigit() && !it.isWhitespace() })
        }

        val banner = terminalFailureBanner(playtestTerminalText(PLAYTEST_TERMINAL_FAILURE_PANE))
        checkNotNull(banner)
        assertEquals(126, banner.passed)
        assertEquals(127, banner.total)
        assertEquals(1, banner.failed)
        assertEquals("41s", banner.duration)
        assertEquals("AnsiParserTest > sgr 38;5 truecolor", banner.failedTitle)
        assertTrue(banner.excerpt.contains("expected:<#2EE6B6>"))

        assertNull(terminalFailureBanner(playtestTerminalText(PLAYTEST_PANE_ZSH)))
        assertNull(terminalFailureBanner(playtestTerminalText(PLAYTEST_PANE_GRADLE)))
    }

    @Test
    fun unknownPaneStillCarriesTheFailedRun() {
        val banner = terminalFailureBanner(playtestTerminalText("7"))
        checkNotNull(banner)
        assertEquals(1, banner.failed)
        assertEquals("AnsiParserTest > sgr 38;5 truecolor", banner.failedTitle)
    }
}
