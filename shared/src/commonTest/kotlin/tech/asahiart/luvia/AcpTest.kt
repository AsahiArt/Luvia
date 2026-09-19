package tech.asahiart.luvia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import tech.asahiart.luvia.internal.Methods
import tech.asahiart.luvia.internal.NdjsonFramer
import tech.asahiart.luvia.internal.UhpRequest
import tech.asahiart.luvia.internal.decodeUhpRequest
import tech.asahiart.luvia.internal.parseObject
import tech.asahiart.luvia.support.ScriptedFactory
import kotlin.time.Duration.Companion.seconds

class AcpTest {
    @Test
    fun acpAgentsMapsSamplePayload() = runTest {
        val session = openAcpClient(backgroundScope)
        val agents = (session.acpAgents() as Outcome.Ok).value
        assertEquals(1, agents.size)
        val agent = agents.single()
        assertEquals("codex", agent.id)
        assertEquals("Codex", agent.name)
        assertEquals("codex-acp", agent.command)
        assertEquals(true, agent.available)
        session.close()
    }

    @Test
    fun openAcpSessionYieldsEventsAndWritesPermissionAction() = runTest {
        val actions = mutableListOf<JsonObject>()
        val session =
            openAcpClient(
                backgroundScope,
                actions = actions,
                streamEvents =
                    listOf(
                        AGENT_HELLO,
                        PERMISSION,
                        TURN_ENDED,
                    ),
            )
        val acp = (session.openAcpSession("codex", "/tmp/work") as Outcome.Ok).value
        assertEquals("sess_1", acp.info.sessionId)
        assertEquals("codex", acp.info.agentId)
        assertEquals("Codex", acp.info.agentName)
        assertEquals(1, acp.info.protocolVersion)
        assertEquals("/tmp/work", acp.info.cwd)

        val incoming = Channel<AcpEvent>(Channel.UNLIMITED)
        val collect = backgroundScope.launch { acp.events().collect { incoming.send(it) } }
        val events = listOf(incoming.receive(), incoming.receive(), incoming.receive())

        val message = assertIs<AcpEvent.AgentMessage>(events[0])
        assertEquals("Hello", message.text)
        val permission = assertIs<AcpEvent.Permission>(events[1])
        assertEquals("req-1", permission.request.requestId)
        assertEquals("Run command", permission.request.title)
        assertNull(permission.request.description)
        assertEquals("Bash", permission.request.toolTitle)
        assertEquals("execute", permission.request.toolKind)
        assertEquals("allow", permission.request.options.single().optionId)
        assertEquals(AcpPermissionKind.AllowOnce, permission.request.options.single().kind)
        val turn = assertIs<AcpEvent.TurnEnded>(events[2])
        assertEquals(AcpStopReason.EndTurn, turn.stopReason)

        val answered = acp.answerPermission("req-1", "allow")
        assertTrue(answered is Outcome.Ok<*>)
        val action = actions.single()
        assertEquals("permission", action.getValue("action").jsonPrimitive.content)
        val params = action.getValue("params").jsonObject
        assertEquals("req-1", params.getValue("request_id").jsonPrimitive.content)
        assertEquals("allow", params.getValue("option_id").jsonPrimitive.content)
        assertTrue(action.containsKey("id"))

        collect.cancel()
        acp.close()
        session.close()
    }

    @Test
    fun acpBoardCoalescesAgentChunksUntilTurnEnded() = runTest {
        val session =
            openAcpClient(
                backgroundScope,
                streamEvents = listOf(AGENT_HEL, AGENT_LO, TURN_ENDED),
            )
        val runtime = sampleRuntime()
        val uhp = HostUhp(session = { session }, runtime = { runtime }, scope = backgroundScope)
        uhp.applyRuntime(runtime)
        uhp.setLaunchAcpAgent("codex")
        uhp.setLaunchAcpCwd("/tmp/work")
        uhp.launchAcp()
        val acp =
            withTimeout(2.seconds) {
                uhp.state.first { state ->
                    state.acp.transcript.any { it is AcpTranscriptItem.Turn }
                }.acp
            }
        val messages = acp.transcript.filterIsInstance<AcpTranscriptItem.Message>()
        assertEquals(1, messages.size)
        assertEquals(AcpTranscriptRole.Agent, messages.single().role)
        assertEquals("Hello", messages.single().text)
        assertEquals(false, messages.single().streaming)
        val turn = acp.transcript.filterIsInstance<AcpTranscriptItem.Turn>().single()
        assertEquals(AcpStopReason.EndTurn, turn.stopReason)
        assertEquals(AcpRunState.Ready, acp.run)
        uhp.close()
        session.close()
    }

    @Test
    fun hideAcpKeepsSessionOpenAndCloseAcpClearsIt() = runTest {
        val session =
            openAcpClient(
                backgroundScope,
                streamEvents = listOf(AGENT_HELLO, TURN_ENDED),
            )
        val runtime = sampleRuntime()
        val uhp = HostUhp(session = { session }, runtime = { runtime }, scope = backgroundScope)
        uhp.applyRuntime(runtime)
        uhp.setLaunchAcpAgent("codex")
        uhp.setLaunchAcpCwd("/tmp/work")
        uhp.launchAcp()
        val opened =
            withTimeout(2.seconds) {
                uhp.state.first { state ->
                    state.acp.open && state.acp.viewing && state.acp.transcript.any { it is AcpTranscriptItem.Turn }
                }
            }
        val transcript = opened.acp.transcript
        assertEquals(true, opened.acp.viewing)
        assertEquals(true, opened.acp.open)

        uhp.hideAcp()
        val hidden = uhp.state.value
        assertEquals(false, hidden.acp.viewing)
        assertEquals(true, hidden.acp.open)
        assertEquals(transcript, hidden.acp.transcript)

        uhp.viewAcp()
        assertEquals(true, uhp.state.value.acp.viewing)
        assertEquals(true, uhp.state.value.acp.open)

        uhp.closeAcp()
        val closed = uhp.state.value.acp
        assertEquals(false, closed.open)
        assertEquals(false, closed.viewing)
        uhp.close()
        session.close()
    }

}

private suspend fun openAcpClient(
    scope: kotlinx.coroutines.CoroutineScope,
    actions: MutableList<JsonObject>? = null,
    streamEvents: List<String> = emptyList(),
): LuviaSession {
    val methods =
        listOf(
            Methods.CAPABILITIES,
            Methods.ACP_AGENTS,
            Methods.ACP_SESSION_OPEN,
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
                            respondAcp(framer, request, methods)
                            if (request.method == Methods.ACP_SESSION_OPEN) {
                                streamEvents.forEach { framer.writeFrame(it) }
                                if (actions != null) {
                                    while (true) {
                                        val frame = parseObject(framer.readFrame())
                                        actions += frame
                                        val id = frame.getValue("id").jsonPrimitive.content
                                        framer.writeFrame(
                                            """{"id":"$id","result":{"type":"ok"}}""",
                                        )
                                    }
                                } else {
                                    awaitCancellation()
                                }
                            }
                        }
                    } catch (_: tech.asahiart.luvia.internal.FrameException) {
                    } catch (_: kotlinx.coroutines.CancellationException) {
                    }
                }
            }
        }
    return (LuviaClient(factory).open("default") as Outcome.Ok).value
}

private suspend fun respondAcp(framer: NdjsonFramer, request: UhpRequest, methods: List<String>) {
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
                    put("methods", stringArray(methods))
                    put("event_sequence", 10)
                }
            Methods.ACP_AGENTS -> parseObject(ACP_AGENTS_RESULT)
            Methods.ACP_SESSION_OPEN -> parseObject(ACP_SESSION_ACK)
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

private fun stringArray(values: List<String>): JsonArray =
    buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }

private fun sampleRuntime(): HostRuntime {
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
                role = HostRole.Controller,
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

private const val ACP_AGENTS_RESULT: String =
    """{"type":"acp_agents","agents":[{"id":"codex","name":"Codex","command":"codex-acp","available":true}]}"""

private const val ACP_SESSION_ACK: String =
    """{"type":"acp_session","session_id":"sess_1","agent":"codex","agent_name":"Codex","protocol_version":1,"cwd":"/tmp/work"}"""

private const val AGENT_HELLO: String =
    """{"event":"acp.update","sequence":1,"data":{"session_id":"sess_1","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Hello"}}}}"""

private const val AGENT_HEL: String =
    """{"event":"acp.update","sequence":1,"data":{"session_id":"sess_1","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Hel"}}}}"""

private const val AGENT_LO: String =
    """{"event":"acp.update","sequence":2,"data":{"session_id":"sess_1","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"lo"}}}}"""

private const val PERMISSION: String =
    """{"event":"acp.permission","sequence":2,"data":{"request_id":"req-1","session_id":"sess_1","title":"Run command","description":null,"tool_call":{"tool_call_id":"tc1","title":"Bash","kind":"execute","status":"pending"},"options":[{"option_id":"allow","name":"Allow","kind":"allow_once"}]}}"""

private const val TURN_ENDED: String =
    """{"event":"acp.turn","sequence":3,"data":{"stop_reason":"end_turn"}}"""
