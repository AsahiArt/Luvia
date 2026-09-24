package tech.asahiart.luvia.thread

import tech.asahiart.luvia.AcpPermissionKind
import tech.asahiart.luvia.AcpPermissionOption
import tech.asahiart.luvia.AcpPermissionRequest
import tech.asahiart.luvia.AcpRunState
import tech.asahiart.luvia.AcpSessionInfo
import tech.asahiart.luvia.AcpState
import tech.asahiart.luvia.AcpStopReason
import tech.asahiart.luvia.AcpTranscriptItem
import tech.asahiart.luvia.AcpTranscriptRole
import tech.asahiart.luvia.AgentDetailState
import tech.asahiart.luvia.AgentKind
import tech.asahiart.luvia.AgentReadResult
import tech.asahiart.luvia.AgentStatus
import tech.asahiart.luvia.AgentSummary
import tech.asahiart.luvia.ConnectionFreshness
import tech.asahiart.luvia.HostUhpState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreadTest {
    private fun pane(id: String, status: AgentStatus, agent: String = "claude") =
        AgentSummary(paneId = id, name = null, status = status, agent = agent, workspaceId = "w1", workspaceName = "luvus")

    private fun acp(run: AcpRunState, permission: AcpPermissionRequest? = null) =
        AcpState(
            open = true,
            info = AcpSessionInfo("s1", "codex", "Codex", 1, "/src/luvia"),
            run = run,
            permission = permission,
            transcript = listOf(
                AcpTranscriptItem.Message("m1", AcpTranscriptRole.User, "hi", false),
                AcpTranscriptItem.Message("m2", AcpTranscriptRole.Thought, "hmm", false),
                AcpTranscriptItem.Message("m3", AcpTranscriptRole.Agent, "done\n", false),
                AcpTranscriptItem.Turn("t1", AcpStopReason.entries.first()),
            ),
        )

    @Test
    fun blockedPaneAsksWithCachedQuestion() {
        val state = HostUhpState(
            agents = listOf(pane("p1", AgentStatus.Blocked)),
            agentDetail = AgentDetailState(paneId = "p1", transcript = AgentReadResult("p1", "work\n\u001B[1mRun migration? (y/n)\u001B[0m\n\n", null)),
        )
        val thread = state.threads("h").single()
        assertEquals("luvus", thread.projectLabel)
        assertEquals("Run migration? (y/n)", thread.ask?.question)
        assertEquals(listOf("Yes", "No", "Enter", "Esc"), thread.ask?.options?.map { it.label })
        assertEquals(AskAction.Prompt("y"), thread.ask?.options?.first()?.action)
        assertTrue(thread.commands().any { it.name == "/compact" })
    }

    @Test
    fun blockedPaneWithoutTranscriptHasNoQuestion() {
        val thread = HostUhpState(agents = listOf(pane("p1", AgentStatus.Blocked))).threads("h").single()
        assertNull(thread.ask?.question)
        assertNull(HostUhpState(agents = listOf(pane("p2", AgentStatus.Working))).threads("h").single().ask)
    }

    @Test
    fun acpPermissionMapsToAsk() {
        val request = AcpPermissionRequest(
            "r1", "Write build.gradle.kts", null, null, null,
            listOf(
                AcpPermissionOption("a", "Allow once", AcpPermissionKind.AllowOnce),
                AcpPermissionOption("b", "Reject", AcpPermissionKind.RejectOnce),
            ),
        )
        val thread = HostUhpState(acp = acp(AcpRunState.AwaitingPermission, request)).threads("h").single()
        assertEquals(AgentKind.Acp, thread.kind)
        assertEquals(AgentStatus.Blocked, thread.status)
        assertEquals("luvia", thread.projectLabel)
        assertEquals("done", thread.summary)
        assertEquals(AskAction.AcpOption("r1", "b"), thread.ask!!.options[1].action)
        assertEquals(AskTone.Destructive, thread.ask!!.options[1].tone)
    }

    @Test
    fun acpTimelineMapsItems() {
        val state = acp(AcpRunState.Ready)
        val items = state.timeline(null)
        assertIs<TimelineItem.Mine>(items[0])
        assertIs<TimelineItem.Thought>(items[1])
        assertIs<TimelineItem.Output>(items[2])
        assertIs<TimelineItem.Status>(items[3])
        assertIs<TimelineItem.AskCard>(state.timeline(paneAsk(null)).last())
    }

    @Test
    fun transcriptChunksOnlyNewLines() {
        var t = PaneTimeline().ingestTranscript("a\nb\n")
        t = t.ingestTranscript("a\nb\nc\n")
        t = t.ingestTranscript("b\nc\nd\ne")
        t = t.ingestTranscript("b\nc\nd\ne\n\n")
        assertEquals(listOf("a\nb", "c", "d\ne"), t.items.map { (it as TimelineItem.Output).text })
    }

    @Test
    fun statusAndMineAndUnconfirmed() {
        var t = PaneTimeline().recordStatus(AgentStatus.Working).recordStatus(AgentStatus.Working)
        assertTrue(t.items.isEmpty())
        t = t.recordMine("go").recordStatus(AgentStatus.Idle).markLastUnconfirmed()
        assertEquals(TimelineItem.Unconfirmed("p1", "go"), t.items[0])
        assertEquals(AgentStatus.Idle, (t.items[1] as TimelineItem.Status).status)
    }

    @Test
    fun timelineIsBounded() {
        var t = PaneTimeline()
        repeat(PaneTimeline.MAX_ITEMS + 20) { t = t.recordMine("m$it") }
        assertEquals(PaneTimeline.MAX_ITEMS, t.items.size)
        assertEquals("m20", (t.items.first() as TimelineItem.Mine).text)
    }

    @Test
    fun nowGroupsByHostAndExpiresDone() {
        val a = NowHost("a", "studio", ConnectionFreshness.Live, false,
            HostUhpState(agents = listOf(pane("p1", AgentStatus.Blocked), pane("p2", AgentStatus.Working), pane("p3", AgentStatus.Done))))
        val b = NowHost("b", "nas", ConnectionFreshness.Stale, true,
            HostUhpState(agents = listOf(pane("q1", AgentStatus.Blocked))))
        val threads = listOf(a, b).flatMap { it.state!!.threads(it.hostId) }
        var ledger = DoneLedger().observe(threads, nowMs = 0)

        val now = buildNowState(listOf(b, a), ledger, nowMs = 1)
        assertEquals(listOf("a", "b"), now.needsYou.map { it.hostId })
        assertEquals(2, now.needsYouCount)
        assertEquals(listOf("p2"), now.inProgress.single().threads.map { it.id })
        assertEquals(listOf("p3"), now.recentlyDone.single().threads.map { it.id })

        assertTrue(buildNowState(listOf(a), ledger, DoneLedger.DAY_MS).recentlyDone.isEmpty())
        ledger = ledger.markViewed(threads.first { it.id == "p3" })
        assertTrue(buildNowState(listOf(a), ledger, 1).recentlyDone.isEmpty())
    }
}
