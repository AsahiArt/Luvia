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
import tech.asahiart.luvia.internal.uhp.startingReplay
import tech.asahiart.luvia.thread.threads
import tech.asahiart.luvia.internal.UhpRequest
import tech.asahiart.luvia.internal.decodeAcpEvent
import tech.asahiart.luvia.internal.decodeUhpEvent
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

    @Test
    fun toolCallKeepsPathBesideTextAndRendersDiff() {
        val read = """{"event":"acp.update","sequence":1,"data":{"session_id":"s","update":{"sessionUpdate":"tool_call","toolCallId":"t1","title":"Read","kind":"read","status":"completed","locations":[{"path":"shared/Pairing.kt","line":4}],"content":[{"type":"content","content":{"type":"text","text":"package x"}}]}}}"""
        val readCall = assertIs<AcpEvent.ToolCall>(decodeAcpEvent(decodeUhpEvent(read))).call
        assertEquals("package x", readCall.summary)
        assertEquals("shared/Pairing.kt", readCall.path)

        val edit = """{"event":"acp.update","sequence":2,"data":{"session_id":"s","update":{"sessionUpdate":"tool_call","toolCallId":"t2","title":"Edit","kind":"edit","status":"completed","duration":"1.2s","content":[{"type":"diff","path":"shared/ReconnectPolicy.kt","oldText":"fun retry()\n","newText":"fun reconnect()\n"}]}}}"""
        val editCall = assertIs<AcpEvent.ToolCall>(decodeAcpEvent(decodeUhpEvent(edit))).call
        assertEquals("shared/ReconnectPolicy.kt", editCall.path)
        assertEquals("1.2s", editCall.duration)
        assertTrue(editCall.summary!!.contains("@@ "))
        assertTrue(editCall.summary!!.contains("+fun reconnect()"))
    }

    @Test
    fun acpSessionListMapsRows() = runTest {
        val session = openAcpClient(backgroundScope)
        val listed = (session.acpSessionList("codex", cwd = "/work", cursor = "p1") as Outcome.Ok).value
        assertEquals("codex", listed.agent)
        assertEquals(1, listed.protocolVersion)
        assertEquals("n1", listed.nextCursor)
        val row = listed.sessions.single()
        assertEquals("sess_a", row.sessionId)
        assertEquals("/work", row.cwd)
        assertEquals("Hello", row.title)
        assertEquals("2026-11-05T00:00:00Z", row.updatedAt)
        session.close()
    }

    @Test
    fun resumeAcpRequestsReplayAndReplacesTranscript() = runTest {
        val opens = mutableListOf<JsonObject>()
        val session =
            openAcpClient(
                backgroundScope,
                opens = opens,
                streamEvents = listOf(AGENT_HELLO, TURN_ENDED),
            )
        val runtime = sampleRuntime()
        val uhp = HostUhp(session = { session }, runtime = { runtime }, scope = backgroundScope)
        uhp.applyRuntime(runtime)
        val dirty =
            AcpState(
                transcript =
                    listOf(
                        AcpTranscriptItem.Message("old", AcpTranscriptRole.Agent, "stale", streaming = false),
                    ),
                open = true,
            )
        assertEquals(emptyList(), dirty.startingReplay().transcript)
        uhp.resumeAcp("codex", "/tmp/work", "sess_old")
        val acp =
            withTimeout(2.seconds) {
                uhp.state.first { state ->
                    state.acp.transcript.any { it is AcpTranscriptItem.Turn }
                }.acp
            }
        val messages = acp.transcript.filterIsInstance<AcpTranscriptItem.Message>()
        assertEquals(listOf("Hello"), messages.map { it.text })
        assertTrue(messages.none { it.text == "stale" })
        val params = opens.single()
        assertEquals("resume", params.getValue("mode").jsonPrimitive.content)
        assertEquals("sess_old", params.getValue("session_id").jsonPrimitive.content)
        assertEquals("true", params.getValue("replay").jsonPrimitive.content)
        assertEquals("codex", params.getValue("agent").jsonPrimitive.content)
        uhp.close()
        session.close()
    }

    @Test
    fun resumeReplayStaysIdleAfterAck() = runTest {
        val session =
            openAcpClient(
                backgroundScope,
                streamEvents = listOf(AGENT_HELLO),
            )
        val runtime = sampleRuntime()
        val uhp = HostUhp(session = { session }, runtime = { runtime }, scope = backgroundScope)
        uhp.applyRuntime(runtime)
        uhp.resumeAcp("codex", "/tmp/work", "sess_1")
        val state =
            withTimeout(2.seconds) {
                uhp.state.first { current ->
                    current.acp.info?.sessionId == "sess_1" &&
                        current.acp.transcript.any { item ->
                            item is AcpTranscriptItem.Message && item.text == "Hello"
                        }
                }
            }
        assertEquals(AcpRunState.Ready, state.acp.run)
        val live = state.threads(runtime.profile.id).first { it.kind == AgentKind.Acp && !it.acpHistory }
        assertEquals("acp:sess_1", live.id)
        assertEquals(AgentStatus.Idle, live.status)
        uhp.close()
        session.close()
    }

    @Test
    fun choosingAnotherAgentDraftsIntoTheNewAcpSession() = runTest {
        val opens = mutableListOf<JsonObject>()
        val session = openAcpClient(backgroundScope, opens = opens)
        val runtime = sampleRuntime(agent = "pi", cwd = "/tmp/pane", workspaceCwd = "/tmp/work")
        val uhp = HostUhp(session = { session }, runtime = { runtime }, scope = backgroundScope)
        uhp.applyRuntime(runtime)
        assertEquals(FailureHandoff.Refused, uhp.handoffFailure("   ", "7", "codex"))
        uhp.setAcpDraft("keep")
        assertEquals(FailureHandoff.Refused, uhp.handoffFailure("AnsiParserTest FAILED", "7", "codex"))
        assertEquals("keep", uhp.state.value.acp.draft)
        assertEquals(false, uhp.state.value.acp.open)
        uhp.setAcpDraft("")
        assertEquals(FailureHandoff.Drafted, uhp.handoffFailure("AnsiParserTest FAILED", "7", "codex"))
        assertEquals("codex", uhp.state.value.acp.launchAgentId)
        assertEquals("/tmp/work", uhp.state.value.acp.launchCwd)
        assertEquals(true, uhp.state.value.acp.open)
        assertEquals(true, uhp.state.value.acp.viewing)
        assertEquals("请修这个失败：\nAnsiParserTest FAILED", uhp.state.value.acp.draft)
        assertEquals(false, uhp.state.value.agentDetail.sending)
        assertEquals("", uhp.state.value.agentDetail.draft)
        val opened =
            withTimeout(2.seconds) {
                uhp.state.first { it.acp.info?.sessionId == "sess_1" }.acp
            }
        assertEquals("sess_1", opened.info?.sessionId)
        assertEquals("codex", opened.info?.agentId)
        assertEquals("请修这个失败：\nAnsiParserTest FAILED", opened.draft)
        val params = opens.single()
        assertEquals("codex", params.getValue("agent").jsonPrimitive.content)
        assertEquals("/tmp/work", params.getValue("cwd").jsonPrimitive.content)
        assertTrue("mode" !in params || params.getValue("mode").jsonPrimitive.content == "new")
        uhp.close()
        session.close()
    }

    @Test
    fun newAcpFixWithoutControlRefusesAndLeavesTheDraftEmpty() = runTest {
        val opens = mutableListOf<JsonObject>()
        val session =
            openAcpClient(
                backgroundScope,
                opens = opens,
                methods =
                    listOf(
                        Methods.CAPABILITIES,
                        Methods.ACP_AGENTS,
                        Methods.ACP_SESSION_LIST,
                    ),
            )
        val runtime = sampleRuntime(agent = "pi", cwd = "/tmp/pane", workspaceCwd = "/tmp/work")
        val uhp = HostUhp(session = { session }, runtime = { runtime }, scope = backgroundScope)
        uhp.applyRuntime(runtime)
        assertTrue(uhp.state.value.canMutate)
        assertEquals(false, session.supports(UhpMethods.ACP_SESSION_OPEN))
        uhp.setShowLaunchAcp(true)
        uhp.setLaunchAcpAgent("claude")
        uhp.setLaunchAcpCwd("/tmp/keep")
        val before = uhp.state.value.acp
        assertEquals(FailureHandoff.Refused, uhp.handoffFailure("AnsiParserTest FAILED", "7", "codex"))
        assertEquals("", uhp.state.value.acp.draft)
        assertEquals(before, uhp.state.value.acp)
        assertTrue(opens.isEmpty())
        uhp.close()
        session.close()
    }

    @Test
    fun newAcpFixAsObserverRefusesAndLeavesTheDraftEmpty() = runTest {
        val opens = mutableListOf<JsonObject>()
        val session = openAcpClient(backgroundScope, opens = opens)
        val runtime =
            sampleRuntime(
                agent = "pi",
                cwd = "/tmp/pane",
                workspaceCwd = "/tmp/work",
                role = HostRole.Observer,
            )
        val uhp = HostUhp(session = { session }, runtime = { runtime }, scope = backgroundScope)
        uhp.applyRuntime(runtime)
        assertEquals(true, uhp.state.value.isObserver)
        assertEquals(false, uhp.state.value.canMutate)
        assertTrue(session.supports(UhpMethods.ACP_SESSION_OPEN))
        uhp.setShowLaunchAcp(true)
        uhp.setLaunchAcpAgent("claude")
        uhp.setLaunchAcpCwd("/tmp/keep")
        val before = uhp.state.value.acp
        assertEquals(FailureHandoff.Refused, uhp.handoffFailure("AnsiParserTest FAILED", "7", "codex"))
        assertEquals("", uhp.state.value.acp.draft)
        assertEquals(before, uhp.state.value.acp)
        assertTrue(opens.isEmpty())
        uhp.close()
        session.close()
    }

    @Test
    fun newAcpFixRefusesWhileStartingAndKeepsThatSession() = runTest {
        val session = openAcpClient(backgroundScope)
        val runtime = sampleRuntime(agent = "pi", cwd = "/tmp/pane", workspaceCwd = "/tmp/work")
        val uhp = HostUhp(session = { session }, runtime = { runtime }, scope = backgroundScope)
        uhp.applyRuntime(runtime)
        uhp.setLaunchAcpAgent("codex")
        uhp.setLaunchAcpCwd("/tmp/work")
        uhp.launchAcp()
        val before = uhp.state.value.acp
        assertEquals(AcpRunState.Starting, before.run)
        assertEquals("codex", before.launchAgentId)
        assertEquals(FailureHandoff.Refused, uhp.handoffFailure("AnsiParserTest FAILED", "7", "claude"))
        assertEquals(before, uhp.state.value.acp)
        assertEquals("", uhp.state.value.acp.draft)
        uhp.close()
        session.close()
    }

    @Test
    fun newAcpFixRefusesWhileWorkingAndKeepsThatSession() = runTest {
        val session = openAcpClient(backgroundScope, streamEvents = listOf(AGENT_HELLO, TURN_ENDED))
        val runtime = sampleRuntime(agent = "pi", cwd = "/tmp/pane", workspaceCwd = "/tmp/work")
        val uhp = HostUhp(session = { session }, runtime = { runtime }, scope = backgroundScope)
        uhp.applyRuntime(runtime)
        uhp.setLaunchAcpAgent("codex")
        uhp.setLaunchAcpCwd("/tmp/work")
        uhp.launchAcp()
        withTimeout(2.seconds) {
            uhp.state.first { state ->
                state.acp.run == AcpRunState.Ready &&
                    state.acp.info?.sessionId == "sess_1" &&
                    state.acp.transcript.any { it is AcpTranscriptItem.Turn }
            }
        }
        uhp.setAcpDraft("still working")
        assertTrue(uhp.promptAcp())
        val before = uhp.state.value.acp
        assertEquals(AcpRunState.Working, before.run)
        assertEquals("", before.draft)
        assertEquals(FailureHandoff.Refused, uhp.handoffFailure("AnsiParserTest FAILED", "7", "claude"))
        assertEquals(before, uhp.state.value.acp)
        uhp.close()
        session.close()
    }

    @Test
    fun newAcpFixRefusesWhileAwaitingPermissionAndKeepsThatRequest() = runTest {
        val opens = mutableListOf<JsonObject>()
        val session = openAcpClient(backgroundScope, opens = opens, streamEvents = listOf(PERMISSION))
        val runtime = sampleRuntime(agent = "pi", cwd = "/tmp/pane", workspaceCwd = "/tmp/work")
        val uhp = HostUhp(session = { session }, runtime = { runtime }, scope = backgroundScope)
        uhp.applyRuntime(runtime)
        uhp.setLaunchAcpAgent("codex")
        uhp.setLaunchAcpCwd("/tmp/work")
        uhp.launchAcp()
        val before =
            withTimeout(2.seconds) {
                uhp.state.first { state ->
                    state.acp.run == AcpRunState.AwaitingPermission && state.acp.permission?.requestId == "req-1"
                }.acp
            }
        val opensBefore = opens.size
        assertEquals(1, opensBefore)
        assertEquals("", before.draft)
        assertEquals(FailureHandoff.Refused, uhp.handoffFailure("AnsiParserTest FAILED", "7", "claude"))
        assertEquals(before, uhp.state.value.acp)
        assertEquals(AcpRunState.AwaitingPermission, uhp.state.value.acp.run)
        assertEquals(before.permission, uhp.state.value.acp.permission)
        assertEquals("", uhp.state.value.acp.draft)
        assertEquals(opensBefore, opens.size)
        uhp.close()
        session.close()
    }

    @Test
    fun newAcpFixDraftsOnlyAfterTheChosenAgentStarts() = runTest {
        val opens = mutableListOf<JsonObject>()
        val session = openAcpClient(backgroundScope, opens = opens)
        val runtime = sampleRuntime(agent = "pi", cwd = "/tmp/pane", workspaceCwd = "/tmp/work")
        val uhp = HostUhp(session = { session }, runtime = { runtime }, scope = backgroundScope)
        uhp.applyRuntime(runtime)
        uhp.setLaunchAcpAgent("claude")
        uhp.setLaunchAcpCwd("/tmp/other")
        assertEquals(FailureHandoff.Drafted, uhp.handoffFailure("AnsiParserTest FAILED", "7", "codex"))
        val started = uhp.state.value.acp
        assertEquals(AcpRunState.Starting, started.run)
        assertEquals("codex", started.launchAgentId)
        assertEquals("/tmp/work", started.launchCwd)
        assertEquals("请修这个失败：\nAnsiParserTest FAILED", started.draft)
        val opened =
            withTimeout(2.seconds) {
                uhp.state.first { it.acp.info?.sessionId == "sess_1" }.acp
            }
        assertEquals("codex", opened.launchAgentId)
        assertEquals("codex", opened.info?.agentId)
        assertEquals("请修这个失败：\nAnsiParserTest FAILED", opened.draft)
        val params = opens.single()
        assertEquals("codex", params.getValue("agent").jsonPrimitive.content)
        assertEquals("/tmp/work", params.getValue("cwd").jsonPrimitive.content)
        uhp.close()
        session.close()
    }

    @Test
    fun choosingTheSessionAgentStillUsesThePanePath() = runTest {
        val session = openAcpClient(backgroundScope, extraMethods = listOf(Methods.AGENT_PROMPT))
        val runtime = sampleRuntime(agent = "pi", cwd = "/tmp/work")
        val uhp = HostUhp(session = { session }, runtime = { runtime }, scope = backgroundScope)
        uhp.applyRuntime(runtime)
        assertEquals(FailureHandoff.Sent, uhp.handoffFailure("AnsiParserTest FAILED", "7", "pi"))
        assertEquals(true, uhp.state.value.agentDetail.sending)
        assertEquals(false, uhp.state.value.acp.open)
        assertEquals("", uhp.state.value.acp.draft)
        assertEquals(null, uhp.state.value.acp.launchAgentId)
        uhp.close()
        session.close()
    }

    @Test
    fun choosingTheSameRecognizedAgentKeepsTheSessionPath() = runTest {
        val session = openAcpClient(backgroundScope, extraMethods = listOf(Methods.AGENT_PROMPT))
        val runtime = sampleRuntime(agent = "claude-code", cwd = "/tmp/work")
        val uhp = HostUhp(session = { session }, runtime = { runtime }, scope = backgroundScope)
        uhp.applyRuntime(runtime)
        assertEquals(FailureHandoff.Sent, uhp.handoffFailure("AnsiParserTest FAILED", "7", "claude"))
        assertEquals(true, uhp.state.value.agentDetail.sending)
        assertEquals(false, uhp.state.value.acp.open)
        uhp.close()
        session.close()
    }

}

private suspend fun openAcpClient(
    scope: kotlinx.coroutines.CoroutineScope,
    actions: MutableList<JsonObject>? = null,
    opens: MutableList<JsonObject>? = null,
    streamEvents: List<String> = emptyList(),
    extraMethods: List<String> = emptyList(),
    methods: List<String>? = null,
): LuviaSession {
    val advertised =
        methods
            ?: (
                listOf(
                    Methods.CAPABILITIES,
                    Methods.ACP_AGENTS,
                    Methods.ACP_SESSION_LIST,
                    Methods.ACP_SESSION_OPEN,
                ) + extraMethods
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
                            if (opens != null &&
                                (request.method == Methods.ACP_SESSION_OPEN ||
                                    request.method == Methods.ACP_SESSION_LIST)
                            ) {
                                opens += request.params
                            }
                            respondAcp(framer, request, advertised)
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
            Methods.ACP_SESSION_LIST -> parseObject(ACP_SESSION_LIST_RESULT)
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

private fun sampleRuntime(
    agent: String? = null,
    cwd: String? = null,
    workspaceCwd: String? = null,
    role: HostRole = HostRole.Controller,
): HostRuntime {
    val summary =
        AgentSummary(
            paneId = "7",
            name = "opus",
            status = AgentStatus.Blocked,
            agent = agent,
            cwd = cwd,
        )
    val workspaces =
        workspaceCwd?.let { dir ->
            listOf(WorkspaceSummary(index = 0, name = "work", pinned = false, active = true, tabCount = 1, cwd = dir))
        }.orEmpty()
    return HostRuntime(
        profile =
            HostProfile(
                id = "host-1",
                alias = "studio",
                username = "misaka",
                hostKeyFingerprints = listOf("SHA256:abc"),
                role = role,
                lastStatus = HostStatus.Unknown,
                lastUpdatedEpochMs = 0L,
                topology = null,
                hints = listOf("studio.tailnet"),
                port = 22,
            ),
        link = HostLink.Idle,
        snapshot =
            SessionSnapshot(
                sessionName = "default",
                serverGeneration = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                eventSequence = 1,
                workspaces = workspaces,
                panes = emptyList(),
                agents = listOf(summary),
            ),
        tasks = emptyList(),
        freshness = ConnectionFreshness.Offline,
    )
}

private const val ACP_AGENTS_RESULT: String =
    """{"type":"acp_agents","agents":[{"id":"codex","name":"Codex","command":"codex-acp","available":true}]}"""

private const val ACP_SESSION_LIST_RESULT: String =
    """{"type":"acp_session_list","agent":"codex","sessions":[{"session_id":"sess_a","cwd":"/work","title":"Hello","updated_at":"2026-11-05T00:00:00Z"}],"next_cursor":"n1","protocol_version":1}"""

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
