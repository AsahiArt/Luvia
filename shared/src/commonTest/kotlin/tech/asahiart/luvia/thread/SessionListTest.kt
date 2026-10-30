package tech.asahiart.luvia.thread

import tech.asahiart.luvia.AgentKind
import tech.asahiart.luvia.AgentStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionListTest {
    @Test
    fun filtersAndCountsPendingReplies() {
        val threads = listOf(
            thread("a", AgentStatus.Blocked, ask = Ask("go?", emptyList())),
            thread("b", AgentStatus.Working),
            thread("c", AgentStatus.Idle),
            thread("d", AgentStatus.Done),
        )
        assertEquals(listOf("a"), filterSessions(threads, SessionFilter.NeedsYou).map { it.id })
        assertEquals(listOf("b"), filterSessions(threads, SessionFilter.Running).map { it.id })
        assertEquals(listOf("c", "d"), filterSessions(threads, SessionFilter.Idle).map { it.id })
        assertEquals(4, filterSessions(threads, SessionFilter.All).size)
        assertEquals(1, pendingReplyCount(threads))
    }

    @Test
    fun sortPutsDecisionsBeforeRunningWork() {
        val threads = listOf(
            thread("idle", AgentStatus.Idle, updated = 50),
            thread("run", AgentStatus.Working, updated = 10),
            thread("ask", AgentStatus.Blocked, ask = Ask(null, emptyList()), updated = 1),
            thread("done", AgentStatus.Done, updated = 99),
        )
        assertEquals(listOf("ask", "run", "idle", "done"), sortSessions(threads).map { it.id })
    }

    @Test
    fun toolKindMatchesWordsNotSubstrings() {
        assertEquals(ToolTrailKind.Read, toolTrailKind("read", "Cargo.toml"))
        assertEquals(ToolTrailKind.Edit, toolTrailKind(null, "Edit ReconnectPolicy"))
        assertEquals(ToolTrailKind.Run, toolTrailKind("execute", "gradle test"))
        assertEquals(ToolTrailKind.Other, toolTrailKind(null, "application"))
        assertEquals(ToolTrailKind.Other, toolTrailKind("other", "think"))
    }

    @Test
    fun runMeterAndDiffStayLiteral() {
        val meter = parseRunMeter("tests 127/128\nFooTest")
        assertEquals(127, meter?.passed)
        assertEquals(128, meter?.total)
        assertEquals(1, meter?.failed)
        assertNull(parseRunMeter("no numbers"))
        assertNull(parseRunMeter("9/2"))
        assertEquals(listOf("+ added", "- removed"), diffLines("+ added\n- removed"))
        assertNull(diffLines("edited the file"))
    }

    @Test
    fun askDefaultsStayCompatibleAndRecommendationIsExplicit() {
        val plain = Ask("ship?", listOf(AskOption("Yes", AskTone.Primary, AskAction.Prompt("y"))))
        assertEquals(1, plain.index)
        assertEquals(1, plain.total)
        assertEquals("Yes", recommendedOption(plain)?.label)
        val marked = plain.copy(
            index = 1,
            total = 2,
            options = listOf(
                AskOption("Keep", AskTone.Neutral, AskAction.Prompt("keep"), consequence = "no rewrite"),
                AskOption("Replace", AskTone.Primary, AskAction.Prompt("replace"), recommended = true),
            ),
        )
        assertEquals("Replace", recommendedOption(marked)?.label)
        assertTrue(marked.options[1].recommended)
    }

    private fun thread(
        id: String,
        status: AgentStatus,
        ask: Ask? = null,
        updated: Long? = null,
    ) = AgentThread(
        id = id,
        kind = AgentKind.Pane,
        hostId = "h",
        projectKey = null,
        projectLabel = null,
        title = id,
        agentType = null,
        status = status,
        ask = ask,
        summary = null,
        updatedEpochMs = updated,
    )
}
