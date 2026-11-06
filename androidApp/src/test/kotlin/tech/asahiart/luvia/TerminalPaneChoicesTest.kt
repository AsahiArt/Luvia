package tech.asahiart.luvia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tech.asahiart.luvia.thread.terminalPaneNumber
import tech.asahiart.luvia.thread.terminalSegmentLabel

class TerminalPaneChoicesTest {
    @Test
    fun choicesStayInsideTheCurrentTabAndShareItsIndex() {
        val noise = (10..40).map { pane("n$it", "noise", workspace = 3, tab = 1) }
        val tab = listOf(
            pane("1", "zsh", workspace = 1, tab = 1),
            pane("2", "gradle", workspace = 1, tab = 1),
            pane("32", "claude", workspace = 1, tab = 1),
        )
        val choices = terminalPaneChoices(runtime(noise + tab), "32")
        assertEquals(listOf("1", "2", "32"), choices.map { it.paneId })
        assertEquals(listOf("zsh", "gradle", "claude"), choices.map { it.title })
        val index = choices.indexOfFirst { it.paneId == "32" }
        assertEquals(2, index)
        assertEquals(3, terminalPaneNumber(index))
        assertEquals("3 claude", terminalSegmentLabel(index, choices[index].title))
        assertEquals(
            terminalPaneNumber(index).toString(),
            terminalSegmentLabel(index, choices[index].title).substringBefore(' '),
        )
    }

    @Test
    fun sameTabIndexInAnotherWorkspaceStaysOut() {
        val panes = listOf(
            pane("1", "zsh", workspace = 1, tab = 1, focused = true),
            pane("2", "gradle", workspace = 1, tab = 1),
            pane("3", "claude", workspace = 1, tab = 2),
            pane("4", "pi", workspace = 2, tab = 1),
        )
        assertEquals(listOf("1", "2"), terminalPaneChoices(runtime(panes), null).map { it.paneId })
        assertEquals(listOf("4"), terminalPaneChoices(runtime(panes), "4").map { it.paneId })
        assertEquals(listOf("3"), terminalPaneChoices(runtime(panes), "3").map { it.paneId })
    }

    @Test
    fun panesWithoutATerminalStayOutOfTheTab() {
        val panes = listOf(
            pane("1", "zsh", workspace = 1, tab = 1, terminal = "t1"),
            pane("2", "gradle", workspace = 1, tab = 1, terminal = null),
            pane("3", "claude", workspace = 1, tab = 1, terminal = "t3"),
        )
        assertEquals(listOf("1", "3"), terminalPaneChoices(runtime(panes), "3").map { it.paneId })
    }

    @Test
    fun missingPlacementKeepsAShortWindowAroundTheCurrentPane() {
        val few = listOf(pane("1", "a"), pane("2", "b"), pane("3", "c"))
        assertEquals(listOf("1", "2", "3"), terminalPaneChoices(runtime(few), "2").map { it.paneId })

        val many = (1..8).map { pane(it.toString(), "p$it") }
        val aroundLast = terminalPaneChoices(runtime(many), "8")
        assertEquals(5, aroundLast.size)
        assertTrue(aroundLast.any { it.paneId == "8" })
        assertEquals(listOf("4", "5", "6", "7", "8"), aroundLast.map { it.paneId })

        val aroundMiddle = terminalPaneChoices(runtime(many), "4")
        assertEquals(listOf("2", "3", "4", "5", "6"), aroundMiddle.map { it.paneId })

        val unknown = terminalPaneChoices(runtime(many), "missing")
        assertEquals(listOf("1", "2", "3", "4", "5"), unknown.map { it.paneId })
    }

    private fun runtime(panes: List<PaneSummary>): HostRuntime =
        HostRuntime(
            profile = HostProfile(
                id = "h",
                alias = "host",
                addresses = emptyList(),
                sshPort = 22,
                username = "u",
                hostKeyFingerprints = emptyList(),
                role = HostRole.Controller,
                lastStatus = HostStatus.Reachable,
                lastUpdatedEpochMs = 0,
                lastConnectedAddress = null,
                topology = null,
            ),
            link = HostLink.Idle,
            snapshot = SessionSnapshot(
                sessionName = "s",
                serverGeneration = "g",
                eventSequence = 1,
                workspaces = emptyList(),
                panes = panes,
                agents = emptyList(),
            ),
            tasks = emptyList(),
            freshness = ConnectionFreshness.Live,
        )

    private fun pane(
        id: String,
        title: String,
        workspace: Int? = null,
        tab: Int? = null,
        focused: Boolean = false,
        terminal: String? = "t$id",
    ): PaneSummary = PaneSummary(
        paneId = id,
        terminalId = terminal,
        kind = "terminal",
        focused = focused,
        agentName = title,
        workspaceIndex = workspace,
        tabIndex = tab,
    )
}
