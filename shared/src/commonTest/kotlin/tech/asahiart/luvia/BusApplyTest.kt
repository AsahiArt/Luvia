package tech.asahiart.luvia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.asahiart.luvia.internal.UhpEvent
import tech.asahiart.luvia.internal.parseBusEvent
import tech.asahiart.luvia.internal.projectBusEvent

class BusApplyTest {
    @Test
    fun paneFocusedDoesNotPullSessionOrRelistTasks() {
        val snapshot = sampleSnapshot(focusedPane = "8")
        val event =
            parseBusEvent(
                UhpEvent("pane.focused", 11, buildJsonObject { put("pane", "7") }),
            )
        assertIs<BusEvent.PaneChanged>(event)
        val projection = projectBusEvent(event, snapshot, snapshot.agents, emptyList())
        assertFalse(projection.pullSession)
        assertFalse(projection.relistTasks)
        assertFalse(projection.resync)
        assertEquals(true, projection.snapshot?.panes?.single { it.paneId == "7" }?.focused)
        assertEquals(false, projection.snapshot?.panes?.single { it.paneId == "8" }?.focused)
    }

    @Test
    fun terminalOutputReadyIsTerminalOnlyAndUpdatesRevisionInPlace() {
        val snapshot = sampleSnapshot(focusedPane = "8")
        val event =
            parseBusEvent(
                UhpEvent(
                    "terminal.output_ready",
                    12,
                    buildJsonObject {
                        put("pane_id", "7")
                        put("content_revision", 41)
                    },
                ),
            )
        assertIs<BusEvent.TerminalChanged>(event)
        val projection = projectBusEvent(event, snapshot, snapshot.agents, emptyList())
        assertTrue(projection.terminalOnly)
        assertFalse(projection.pullSession)
        assertFalse(projection.relistTasks)
        assertFalse(projection.resync)
        assertEquals(41L, projection.snapshot?.panes?.single { it.paneId == "7" }?.contentRevision)
    }

    @Test
    fun terminalCreatedIsNotTerminalOnly() {
        val snapshot = sampleSnapshot(focusedPane = "8")
        val event =
            parseBusEvent(
                UhpEvent("terminal.created", 13, buildJsonObject { put("pane_id", "9") }),
            )
        assertIs<BusEvent.TerminalChanged>(event)
        val projection = projectBusEvent(event, snapshot, snapshot.agents, emptyList())
        assertFalse(projection.terminalOnly)
        assertTrue(projection.pullSession)
    }

    @Test
    fun paneCreatedPullsSessionState() {
        val snapshot = sampleSnapshot(focusedPane = "7")
        val event =
            parseBusEvent(
                UhpEvent("pane.created", 12, buildJsonObject { put("pane", "9") }),
            )
        assertIs<BusEvent.PaneChanged>(event)
        val projection = projectBusEvent(event, snapshot, snapshot.agents, emptyList())
        assertTrue(projection.pullSession)
        assertFalse(projection.relistTasks)
    }

    @Test
    fun shellPaneStatusForUnknownPaneIsIgnored() {
        val snapshot = sampleSnapshot(focusedPane = "7")
        val event =
            parseBusEvent(
                UhpEvent(
                    "pane.agent_status_changed",
                    14,
                    buildJsonObject {
                        put("pane", "42")
                        put("status", "working")
                        put("agent", "zsh")
                    },
                ),
            )
        val projection = projectBusEvent(event, snapshot, snapshot.agents, emptyList())
        assertFalse(projection.pullSession)
        assertEquals(snapshot.agents, projection.agents)
    }

    @Test
    fun agentPaneRevertingToShellIsDropped() {
        val snapshot = sampleSnapshot(focusedPane = "7")
        val event =
            parseBusEvent(
                UhpEvent(
                    "pane.agent_status_changed",
                    15,
                    buildJsonObject {
                        put("pane", "8")
                        put("status", "idle")
                        put("agent", "/bin/zsh")
                    },
                ),
            )
        val projection = projectBusEvent(event, snapshot, snapshot.agents, emptyList())
        assertFalse(projection.pullSession)
        assertTrue(projection.agents.none { it.paneId == "8" })
        assertTrue(projection.snapshot?.agents?.none { it.paneId == "8" } == true)
    }

    @Test
    fun agentStatusUpdatesInPlaceWithoutPull() {
        val snapshot = sampleSnapshot(focusedPane = "7")
        val event =
            parseBusEvent(
                UhpEvent(
                    "pane.agent_status_changed",
                    13,
                    buildJsonObject {
                        put("pane", "7")
                        put("status", "working")
                        put("agent", "pi")
                        put("cwd", "/tmp")
                    },
                ),
            )
        val projection = projectBusEvent(event, snapshot, snapshot.agents, emptyList())
        assertFalse(projection.pullSession)
        assertEquals(AgentStatus.Working, projection.agents.single { it.paneId == "7" }.status)
        assertEquals("pi", projection.agents.single { it.paneId == "7" }.agent)
    }

    @Test
    fun mapsAgentHookTaskStartedMergeConflictAndLease() {
        val hook =
            parseBusEvent(
                UhpEvent(
                    "agent.hook",
                    1,
                    buildJsonObject {
                        put("pane", "7")
                        put("agent", "pi")
                        put("kind", "tool")
                        put("message", "ran")
                        put("tool", "bash")
                    },
                ),
            )
        assertIs<BusEvent.AgentHook>(hook)
        val started =
            parseBusEvent(
                UhpEvent(
                    "task.started",
                    2,
                    buildJsonObject {
                        put("id", "t1")
                        put("pane", "7")
                        put("mode", "worktree")
                        put("worktree", "/tmp/wt")
                    },
                ),
            )
        assertIs<BusEvent.TaskPayload>(started)
        assertEquals("t1", (started as BusEvent.TaskPayload).id)
        val conflict =
            parseBusEvent(
                UhpEvent(
                    "task.merge_conflict",
                    3,
                    buildJsonObject {
                        put("id", "t1")
                        put("branch", "feat")
                    },
                ),
            )
        assertIs<BusEvent.TaskPayload>(conflict)
        val lease =
            parseBusEvent(
                UhpEvent(
                    "lease.acquired",
                    4,
                    buildJsonObject {
                        put("id", "l1")
                        put("pane", 9)
                        put("task", "t1")
                        put("acquired", 1)
                    },
                ),
            )
        assertIs<BusEvent.LeaseChanged>(lease)
        assertEquals("9", (lease as BusEvent.LeaseChanged).pane)
    }

    private fun sampleSnapshot(focusedPane: String): SessionSnapshot =
        SessionSnapshot(
            sessionName = "default",
            serverGeneration = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            eventSequence = 10,
            workspaces = listOf(WorkspaceSummary(1, "work", false, true, 1)),
            panes =
                listOf(
                    PaneSummary("7", "t7", "terminal", focusedPane == "7"),
                    PaneSummary("8", "t8", "terminal", focusedPane == "8"),
                ),
            agents =
                listOf(
                    AgentSummary("7", null, AgentStatus.Idle, agent = "zsh", focused = focusedPane == "7"),
                    AgentSummary("8", null, AgentStatus.Idle, agent = "pi", focused = focusedPane == "8"),
                ),
        )
}
