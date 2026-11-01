package tech.asahiart.luvia.thread

import tech.asahiart.luvia.AcpSessionInfo
import tech.asahiart.luvia.AcpState
import tech.asahiart.luvia.AcpToolCall
import tech.asahiart.luvia.AcpToolStatus
import tech.asahiart.luvia.AcpTranscriptItem
import tech.asahiart.luvia.AgentDetailState
import tech.asahiart.luvia.AgentKind
import tech.asahiart.luvia.AgentStatus
import tech.asahiart.luvia.HostUhpState
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

    @Test
    fun rosterCountsMatchTheFilters() {
        val threads = listOf(
            thread("a", AgentStatus.Blocked, ask = Ask("go?", emptyList())),
            thread("b", AgentStatus.Working),
            thread("c", AgentStatus.Idle),
            thread("d", AgentStatus.Done),
        )
        val roster = sessionRoster(threads)
        assertEquals(4, roster.agents)
        assertEquals(1, roster.running)
        assertEquals(1, roster.waiting)
        assertEquals(2, roster.idle)
    }

    @Test
    fun searchMatchesTitleAgentAndSummary() {
        val threads = listOf(
            thread("a", AgentStatus.Idle).copy(title = "修复 UHP", agentType = "codex", summary = "gradle"),
            thread("b", AgentStatus.Idle).copy(title = "配对", agentType = "claude-code"),
        )
        assertEquals(listOf("a"), searchSessions(threads, "uhp").map { it.id })
        assertEquals(listOf("b"), searchSessions(threads, "Claude").map { it.id })
        assertEquals(listOf("a"), searchSessions(threads, "Gradle").map { it.id })
        assertEquals(threads, searchSessions(threads, "  "))
    }

    @Test
    fun agentMarkAndAgeStayLiteral() {
        assertEquals("CC", agentMark("claude-code", "重构"))
        assertEquals("C", agentMark("codex", "修复"))
        assertEquals("O", agentMark("opencode", "终端"))
        assertEquals("G", agentMark("gemini", "迁移"))
        assertEquals("?", agentMark("  ", " "))
        assertEquals(agentColorSlot("codex", "x"), agentColorSlot("codex", "y"))
        val now = 1_700_000_000_000L
        assertEquals("刚刚", sessionAgeLabel(now - 10_000, now))
        assertEquals("2 分钟", sessionAgeLabel(now - 120_000, now))
        assertEquals("1 小时", sessionAgeLabel(now - 3_600_000, now))
        assertNull(sessionAgeLabel(null, now))
        assertNull(sessionAgeLabel(now + 1, now))
    }

    @Test
    fun trailThumbUsesRealCallsAndSkipsOtherPanes() {
        assertNull(trailThumb(emptyList()))
        val read = AcpToolCall("r", "Read Pairing.kt", "read", AcpToolStatus.Completed, null)
        val run = AcpToolCall("x", "./gradlew :shared:allTests", "shell", AcpToolStatus.Failed, "96/128")
        val state = HostUhpState(
            agentDetail = AgentDetailState(
                paneId = "p1",
                timeline = PaneTimeline(items = listOf(TimelineItem.Tool("r", read), TimelineItem.Tool("x", run))),
            ),
            acp = AcpState(
                open = true,
                info = AcpSessionInfo("s", "claude", "Claude", 1, "/work"),
                transcript = listOf(
                    AcpTranscriptItem.Tool(
                        "e",
                        AcpToolCall("e", "Edit TokenStore.kt", "edit", AcpToolStatus.Completed, null),
                    ),
                ),
            ),
        )
        val threads = listOf(
            thread("p1", AgentStatus.Working).copy(paneId = "p1"),
            thread("p2", AgentStatus.Idle).copy(paneId = "p2"),
            thread("acp:s", AgentStatus.Blocked, ask = Ask("q", emptyList())).copy(
                kind = AgentKind.Acp,
                acpSessionId = "s",
            ),
        )
        val trails = threadTrails(state, threads).associate { it.threadId to it.thumb }
        assertEquals("./gradlew :shared:allTests · 96/128", trails["p1"]?.label)
        assertEquals(listOf(ToolTrailKind.Read, ToolTrailKind.Run), trails["p1"]?.kinds)
        assertEquals(750, trails["p1"]?.progressPermille)
        assertNull(trails["p2"])
        assertEquals("Edit TokenStore.kt", trails["acp:s"]?.label)
        assertEquals(listOf(ToolTrailKind.Edit), trails["acp:s"]?.kinds)
        assertNull(trails["acp:s"]?.progressPermille)
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
