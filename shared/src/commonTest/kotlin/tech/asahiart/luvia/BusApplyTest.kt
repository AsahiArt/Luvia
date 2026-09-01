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
