package tech.asahiart.luvia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.asahiart.luvia.internal.Methods
import tech.asahiart.luvia.internal.NdjsonFramer
import tech.asahiart.luvia.internal.UhpRequest
import tech.asahiart.luvia.internal.decodeUhpRequest
import tech.asahiart.luvia.internal.parseObject
import tech.asahiart.luvia.support.ScriptedFactory
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class HostUhpTest {
    @Test
    fun applyRuntimeCopiesSnapshotAgentsAndObserver() = runTest {
        val runtime = sampleRuntime(role = HostRole.Observer)
        val uhp = HostUhp(session = { null }, runtime = { runtime }, scope = backgroundScope)
        uhp.applyRuntime(runtime)
        assertEquals("7", uhp.state.value.agents.single().paneId)
        assertEquals(false, uhp.state.value.connected)
        assertEquals(true, uhp.state.value.isObserver)
        uhp.close()
    }

    @Test
    fun openAgentWithoutSessionLeavesUnconfirmedPathAndReportsDisconnected() = runTest {
        val runtime = sampleRuntime()
        val uhp = HostUhp(session = { null }, runtime = { runtime }, scope = this)
        uhp.applyRuntime(runtime)
        uhp.openAgent("7")
        val detail =
            withTimeout(2.seconds) {
                uhp.state.first { it.agentDetail.errorText != null }.agentDetail
            }
        assertEquals("7", detail.paneId)
        assertEquals(true, detail.open)
        assertEquals("Not connected to this host.", detail.errorText)
        uhp.close()
    }

    @Test
    fun openAgentBlockedWhilePromptInFlight() = runTest {
        val session = openSession(backgroundScope, hangPrompt = true)
        val runtime = sampleRuntime()
        val uhp = HostUhp(session = { session }, runtime = { runtime }, scope = backgroundScope)
        uhp.applyRuntime(runtime)
        uhp.openAgent("7")
        advanceUntilIdle()
        uhp.promptAgent("hello")
        advanceUntilIdle()
        assertEquals(true, uhp.state.value.agentDetail.sending)
        uhp.openAgent("8")
        assertEquals("7", uhp.state.value.agentDetail.paneId)
        assertTrue(uhp.state.value.agentDetail.errorText!!.contains("still in progress"))
        uhp.close()
        session.close()
    }

    @Test
    fun lostMutationKindsAreUnconfirmed() {
        assertTrue(Failure.IndeterminateMutation("agent.prompt").isLostMutation())
        assertTrue(Failure.Closed().isLostMutation())
        assertTrue(Failure.Transport("down").isLostMutation())
        assertTrue(Failure.Bridge("down").isLostMutation())
        assertTrue(!Failure.Forbidden("no").isLostMutation())
        assertTrue(!Failure.RevisionConflict(1, 2, "conflict").isLostMutation())
    }

    @Test
    fun visibleSectionsHideUnsupportedSurfacesWhenConnected() {
        val disconnected = HostUhpState()
        assertEquals(
            HostSection.entries.filter { it != HostSection.Terminal },
            disconnected.visibleSections(),
        )
        val connected =
            HostUhpState(
                connected = true,
                capabilities = HostCapabilities(diffList = true, taskList = true),
            )
        assertEquals(
            listOf(HostSection.Agents, HostSection.Review, HostSection.Tasks),
            connected.visibleSections(),
        )
    }

    @Test
    fun projectWorkspaceIdPrefersSelectedAgentOverFocused() {
        val focused =
            AgentSummary(
                paneId = "1",
                name = "Focused",
                status = AgentStatus.Working,
                focused = true,
                workspace = "0",
                workspaceId = "ws-focused",
                workspaceName = "focused",
            )
        val selected =
            AgentSummary(
                paneId = "2",
                name = "Selected",
                status = AgentStatus.Working,
                focused = false,
                workspace = "1",
                workspaceId = "ws-selected",
                workspaceName = "selected",
            )
        val state =
            HostUhpState(
                agents = listOf(focused, selected),
                agentDetail = AgentDetailState(paneId = "2", open = true),
            )
        assertEquals("ws-selected", state.projectWorkspaceId())
        assertEquals("selected", state.projectLabel())
        assertEquals(1, state.projectWorkspaceIndex())
    }

    @Test
    fun projectTasksKeepOnlySelectedWorkspace() {
        val focused =
            AgentSummary(
                paneId = "1",
                name = "Focused",
                status = AgentStatus.Working,
                focused = true,
                workspace = "0",
                workspaceId = "ws-focused",
            )
        val selected =
            AgentSummary(
                paneId = "2",
                name = "Selected",
                status = AgentStatus.Working,
                workspace = "1",
                workspaceId = "ws-selected",
            )
        val state =
            HostUhpState(
                agents = listOf(focused, selected),
                agentDetail = AgentDetailState(paneId = "2", open = true),
                tasks =
                    TasksState(
                        tasks =
                            listOf(
                                TaskSummary("a", "one", "queued", workspaceId = "ws-selected"),
                                TaskSummary("b", "two", "queued", workspaceId = "ws-focused"),
                                TaskSummary("c", "legacy", "queued"),
                            ),
                    ),
            )
        assertEquals(listOf("a"), state.projectTasks().map { it.id })
    }

    @Test
    fun userMessageDoesNotInviteRetryOnLostMutation() {
        val text = Failure.IndeterminateMutation("agent.prompt").userMessage()
        assertTrue(text.contains("Do not retry automatically"))
    }
}

private fun sampleRuntime(
    role: HostRole = HostRole.Controller,
): HostRuntime {
    val agent =
        AgentSummary(
            paneId = "7",
            name = "opus",
            status = AgentStatus.Blocked,
        )
    return HostRuntime(
        profile =
            HostProfile(
                id = "host-1",
                alias = "studio",
                addresses = listOf("studio.tailnet"),
                sshPort = 22,
                username = "misaka",
                hostKeyFingerprints = listOf("SHA256:abc"),
                role = role,
                lastStatus = HostStatus.Unknown,
                lastUpdatedEpochMs = 0L,
                lastConnectedAddress = null,
                topology = null,
            ),
        link = HostLink.Idle,
        snapshot =
            SessionSnapshot(
                sessionName = "default",
                serverGeneration = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                eventSequence = 1,
                workspaces = emptyList(),
                panes = emptyList(),
                agents = listOf(agent),
            ),
        tasks = emptyList(),
        freshness = ConnectionFreshness.Offline,
    )
}

private suspend fun openSession(
    scope: kotlinx.coroutines.CoroutineScope,
    hangPrompt: Boolean = false,
    dropPrompt: Boolean = false,
    recorded: MutableList<String>? = null,
): LuviaSession {
    val methods =
        listOf(
            Methods.CAPABILITIES,
            Methods.AGENT_PROMPT,
            Methods.AGENT_GET,
            Methods.AGENT_READ,
        )
    val factory =
        ScriptedFactory(scope) { framer ->
            val prelude = parseObject(framer.readFrame())
            when (prelude["operation"]?.let { (it as JsonPrimitive).content }) {
                "discover" ->
                    framer.writeFrame(
                        """{"version":1,"sessions":[{"name":"default","default":true,"running":true,"transport":"unix_socket"}]}""",
                    )
                "open" -> {
                    framer.writeFrame("""{"version":1,"status":"ready","session":"default"}""")
                    try {
                        while (true) {
                            val request = decodeUhpRequest(framer.readFrame())
                            recorded?.add(request.method)
                            if (hangPrompt && request.method == Methods.AGENT_PROMPT) {
                                awaitCancellation()
                            }
                            if (dropPrompt && request.method == Methods.AGENT_PROMPT) {
                                return@ScriptedFactory
                            }
                            respond(framer, request, methods)
                        }
                    } catch (_: tech.asahiart.luvia.internal.FrameException) {
                    }
                }
            }
        }
    return (LuviaClient(factory).open("default") as Outcome.Ok).value
}

private suspend fun respond(framer: NdjsonFramer, request: UhpRequest, methods: List<String>) {
    val result: JsonObject =
        when (request.method) {
            Methods.CAPABILITIES ->
                buildJsonObject {
                    put("type", "uhp_capabilities")
                    put(
                        "protocol",
                        buildJsonObject {
                            put("name", "luvus-uhp")
                            put("major", 1)
                            put("minor", 0)
                        },
                    )
                    put(
                        "methods",
                        buildJsonArray { methods.forEach { add(JsonPrimitive(it)) } },
                    )
                    put("event_sequence", 10)
                }
            Methods.AGENT_GET ->
                buildJsonObject {
                    put("type", "agent_get")
                    put("pane", "7")
                    put("status", "blocked")
                }
            Methods.AGENT_READ ->
                buildJsonObject {
                    put("type", "agent_read")
                    put("pane", "7")
                    put("text", "hi")
                    put("revision", 1)
                }
            else -> buildJsonObject { put("type", "ok") }
        }
    framer.writeFrame(
        tech.asahiart.luvia.internal.compactJson.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("id", request.id)
                put("result", result)
            },
        ),
    )
}
