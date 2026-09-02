package tech.asahiart.luvia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import tech.asahiart.luvia.internal.ControlFrame
import tech.asahiart.luvia.internal.Methods
import tech.asahiart.luvia.internal.NdjsonFramer
import tech.asahiart.luvia.internal.ReconcileAction
import tech.asahiart.luvia.internal.SubscribeSnapshotReconciler
import tech.asahiart.luvia.internal.UhpEvent
import tech.asahiart.luvia.internal.UhpRequest
import tech.asahiart.luvia.internal.UhpResponse
import tech.asahiart.luvia.internal.decodeUhpRequest
import tech.asahiart.luvia.internal.decodeUhpResponse
import tech.asahiart.luvia.internal.encodeControlFrame
import tech.asahiart.luvia.internal.mapAgentExplain
import tech.asahiart.luvia.internal.mapAgentList
import tech.asahiart.luvia.internal.mapSnapshot
import tech.asahiart.luvia.internal.mapTaskNext
import tech.asahiart.luvia.internal.mapTerminalInventory
import tech.asahiart.luvia.internal.mapWorkspaceGet
import tech.asahiart.luvia.internal.parseBusEvent
import tech.asahiart.luvia.internal.parseObject
import tech.asahiart.luvia.internal.toFailure
import tech.asahiart.luvia.support.ScriptedFactory

class ContractTest {
    @Test
    fun openWorkspaceSendsPathNotIndex() = runTest {
        val seen = mutableListOf<UhpRequest>()
        val session = openSession(backgroundScope) { seen += it }
        val result = session.openWorkspace("/Users/misaka/Developer/AsahiArt/Luvia")
        assertIs<Outcome.Ok<*>>(result)
        val params = seen.single { it.method == Methods.WORKSPACE_OPEN }.params
        assertEquals("/Users/misaka/Developer/AsahiArt/Luvia", params.getValue("path").jsonPrimitive.content)
        assertFalse("workspace" in params)
        session.close()
    }

    @Test
    fun splitPaneSendsDirectionNotDownBoolean() = runTest {
        val seen = mutableListOf<UhpRequest>()
        val session = openSession(backgroundScope) { seen += it }
        val result = session.splitPane(SplitDirection.Down, focus = true)
        assertIs<Outcome.Ok<*>>(result)
        val params = seen.single { it.method == Methods.PANE_SPLIT }.params
        assertEquals("down", params.getValue("direction").jsonPrimitive.content)
        assertEquals("true", params.getValue("focus").jsonPrimitive.content)
        assertFalse("down" in params && params.getValue("down") is JsonPrimitive && !params.getValue("down").jsonPrimitive.isString)
        session.close()
    }

    @Test
    fun heartbeatSendsRequiredContext() = runTest {
        val seen = mutableListOf<UhpRequest>()
        val session = openSession(backgroundScope) { seen += it }
        val result = session.heartbeatTask("t1", 0.4)
        assertIs<Outcome.Ok<*>>(result)
        val params = seen.single { it.method == Methods.TASK_HEARTBEAT }.params
        assertEquals("t1", params.getValue("id").jsonPrimitive.content)
        assertEquals("0.4", params.getValue("context").jsonPrimitive.content)
        session.close()
    }

    @Test
    fun terminalSnapshotSendsEmptyParams() = runTest {
        val seen = mutableListOf<UhpRequest>()
        val session = openSession(backgroundScope) { seen += it }
        val result = session.terminalSnapshot()
        assertIs<Outcome.Ok<*>>(result)
        val params = seen.single { it.method == Methods.TERMINAL_SNAPSHOT }.params
        assertTrue(params.isEmpty())
        session.close()
    }

    @Test
    fun controlActionFramesOmitLocator() {
        val encoded =
            encodeControlFrame(
                ControlFrame(
                    id = "input-1",
                    action = ControlFrame.Action.TypeLiteral,
                    params = buildJsonObject { put("text", "ls") },
                ),
            )
        assertTrue(encoded.contains("\"action\":\"type_literal\""))
        assertTrue(encoded.contains("\"text\":\"ls\""))
        assertFalse(encoded.contains("server_generation"))
        assertFalse(encoded.contains("terminal_id"))
        assertFalse(encoded.contains("pane_id"))
    }

    @Test
    fun nextTaskNoneAndReadyVariants() {
        val none =
            mapTaskNext(
                parseObject("""{"type":"none","message":"no ready tasks","revision":1551062}"""),
            )
        val noneResult = none as TaskNextResult.None
        assertEquals("no ready tasks", noneResult.message)
        assertEquals(1551062L, noneResult.revision)
        val ready =
            mapTaskNext(
                parseObject(
                    """{"type":"task","revision":9,"task":{"id":"t1","title":"wire","status":"claimed","assignee":13,"deps":[],"paths":[],"gate":null,"outputs":[],"notes":[],"worktree":null,"branch":null,"context":null,"created":1,"updated":2}}""",
                ),
            )
        val claimed = ready as TaskNextResult.Ready
        assertEquals("t1", claimed.task.id)
        assertEquals(TaskStatus.Claimed, claimed.task.status)
        assertEquals(13L, claimed.task.assignee)
    }

    @Test
    fun decodesLiveAgentListAndExplain() {
        val list = mapAgentList(parseObject(AGENT_LIST_RESULT))
        val pi = list.agents.first { it.paneId == "2" }
        assertNull(pi.name)
        assertEquals("pi", pi.agent)
        assertEquals(AgentStatus.Idle, pi.status)
        assertEquals("OpenTactic", pi.workspaceName)
        assertEquals(false, pi.worktree)
        val explain = mapAgentExplain(parseObject(AGENT_EXPLAIN_RESULT))
        assertEquals("1", explain.pane)
        assertEquals("zsh", explain.agent)
        assertTrue(explain.available)
        assertNull(explain.authority)
        assertEquals(1551062L, explain.revision)
    }

    @Test
    fun decodesLiveWorkspaceGetAndInventoryAndSnapshot() {
        val ws = mapWorkspaceGet(parseObject(WORKSPACE_GET_RESULT))
        assertEquals("1", ws.workspace)
        assertEquals("OpenTactic", ws.name)
        assertNull(ws.ahead)
        assertEquals(1551062L, ws.revision)
        val inv = mapTerminalInventory(parseObject(INVENTORY_RESULT))
        assertEquals("217854434c900f8f55353d65833bc820", inv.serverGeneration)
        assertEquals("1", inv.terminals.first().paneId)
        assertEquals(false, inv.truncated)
        val snapshot = mapSnapshot(parseObject(SNAPSHOT_RESULT))
        assertEquals("default", snapshot.sessionName)
        assertEquals("emacs", snapshot.workspaces.first().name)
        assertEquals("main", snapshot.workspaces.first().branch)
        assertEquals("1", snapshot.panes.first().paneId)
        assertEquals("/Users/misaka/.config/emacs", snapshot.panes.first().cwd)
        assertEquals("zsh", snapshot.agents.first().agent)
        assertEquals(35989L, snapshot.panes.first().rootProcessPid)
    }

    @Test
    fun afterSequenceIsSentOnSubscribe() = runTest {
        val seen = mutableListOf<UhpRequest>()
        val session = openSession(backgroundScope) { seen += it }
        session.events().take(1).toList()
        val subscribe = seen.first { it.method == Methods.EVENTS_SUBSCRIBE }
        assertEquals(10L, subscribe.params["after_sequence"]?.jsonPrimitive?.content?.toLong())
        session.close()
    }

    @Test
    fun subscribeAckInvalidParamsDropsCursor() = runTest {
        val session =
            openSession(backgroundScope, subscribeError = "invalid_params") { }
        val updates = session.events().take(2).toList()
        assertTrue(updates.any { it is SessionUpdate.Snapshot })
        assertTrue(updates.any { it is SessionUpdate.Resyncing })
        session.close()
    }

    @Test
    fun subscribeAckResyncRequiredDropsCursor() = runTest {
        val session =
            openSession(backgroundScope, subscribeError = "resync_required") { }
        val updates = session.events().take(2).toList()
        assertTrue(updates.any { it is SessionUpdate.Snapshot })
        assertTrue(updates.any { it is SessionUpdate.Resyncing })
        session.close()
    }

    @Test
    fun eventsResyncRequiredIsOverflow() {
        val recon = SubscribeSnapshotReconciler()
        recon.onSnapshot(
            SessionSnapshot("default", "gen", 10, emptyList(), emptyList(), emptyList()),
        )
        val actions =
            recon.onEvent(
                UhpEvent(
                    name = "events.resync_required",
                    sequence = 11,
                    data = buildJsonObject { put("reason", "subscriber_overflow") },
                ),
            )
        assertEquals(ResyncReason.Overflow, (actions.single() as ReconcileAction.Resync).reason)
    }

    @Test
    fun unknownEventsAreIgnoredSafely() {
        val recon = SubscribeSnapshotReconciler()
        recon.onSnapshot(
            SessionSnapshot("default", "gen", 10, emptyList(), emptyList(), emptyList()),
        )
        val actions =
            recon.onEvent(UhpEvent("module.totally_unknown", 11, buildJsonObject { put("x", 1) }))
        val applied = actions.single() as ReconcileAction.ApplyEvent
        assertEquals("module.totally_unknown", applied.event.name)
        val bus = parseBusEvent(UhpEvent("module.totally_unknown", 11, buildJsonObject { }))
        assertIs<BusEvent.Ignored>(bus)
    }

    @Test
    fun mapsRevisionConflictExpectedAndActual() {
        val failure = errorFailure("revision_conflict", expected = 10, actual = 12)
        val conflict = failure as Failure.RevisionConflict
        assertEquals(10L, conflict.expected)
        assertEquals(12L, conflict.actual)
    }

    @Test
    fun mapsEveryNewFailureVariant() {
        assertIs<Failure.Forbidden>(errorFailure("forbidden"))
        assertIs<Failure.NotFound>(errorFailure("not_found"))
        assertIs<Failure.InvalidParams>(errorFailure("invalid_params", sequence = 99))
        assertEquals(99L, (errorFailure("invalid_params", sequence = 99) as Failure.InvalidParams).sequence)
        assertIs<Failure.InvalidRequest>(errorFailure("invalid_request"))
        assertIs<Failure.StaleServer>(errorFailure("stale_server"))
        assertIs<Failure.StaleRoute>(errorFailure("stale_route"))
        assertIs<Failure.TerminalGone>(errorFailure("terminal_gone"))
        assertIs<Failure.ResyncRequired>(errorFailure("resync_required", sequence = 4))
        assertIs<Failure.ControlConflict>(errorFailure("control_conflict"))
        assertIs<Failure.FrameTooLarge>(errorFailure("frame_too_large"))
        assertIs<Failure.AgentPromptBusy>(errorFailure("agent_prompt_busy"))
        val busy = errorFailure("server_busy", retryable = true) as Failure.ServerBusy
        assertTrue(busy.retryable)
        assertIs<Failure.Remote>(errorFailure("already_claimed"))
    }

    @Test
    fun getAgentSendsTargetExplainSendsPane() = runTest {
        val seen = mutableListOf<UhpRequest>()
        val session = openSession(backgroundScope) { seen += it }
        session.getAgent("pi")
        session.explainAgent("1")
        val get = seen.single { it.method == Methods.AGENT_GET }.params
        val explain = seen.single { it.method == Methods.AGENT_EXPLAIN }.params
        assertEquals("pi", get.getValue("target").jsonPrimitive.content)
        assertFalse("pane" in get)
        assertEquals("1", explain.getValue("pane").jsonPrimitive.content)
        assertFalse("target" in explain)
        session.close()
    }

    @Test
    @Suppress("DEPRECATION_ERROR")
    fun revisionGuardOnlyOnAcceptedMutations() = runTest {
        val seen = mutableListOf<UhpRequest>()
        val session = openSession(backgroundScope) { seen += it }
        session.openWorkspace("/tmp/x", ifRevision = 8)
        session.nextTask()
        session.startAgent("a", "pi")
        assertEquals("8", seen.single { it.method == Methods.WORKSPACE_OPEN }.params.getValue("if_revision").jsonPrimitive.content)
        assertFalse("if_revision" in seen.single { it.method == Methods.TASK_NEXT }.params)
        assertFalse("if_revision" in seen.single { it.method == Methods.AGENT_START }.params)
        session.close()
    }
}

private suspend fun openSession(
    scope: kotlinx.coroutines.CoroutineScope,
    subscribeError: String? = null,
    onRequest: (UhpRequest) -> Unit,
): LuviaSession {
    val factory =
        ScriptedFactory(scope) { framer ->
            dispatchContract(framer, subscribeError, onRequest)
        }
    return (LuviaClient(factory).open("default") as Outcome.Ok).value
}

private suspend fun dispatchContract(
    framer: NdjsonFramer,
    subscribeError: String?,
    onRequest: (UhpRequest) -> Unit,
) {
    val prelude = parseObject(framer.readFrame())
    when (prelude["operation"]?.let { (it as JsonPrimitive).content }) {
        "discover" ->
            framer.writeFrame(
                """{"version":1,"sessions":[{"name":"default","default":true,"running":true,"transport":"unix_socket"}]}""",
            )
        "open" -> {
            framer.writeFrame("""{"version":1,"status":"ready","session":"default"}""")
            val request = decodeUhpRequest(framer.readFrame())
            onRequest(request)
            respondContract(framer, request, subscribeError)
        }
    }
}

private suspend fun respondContract(framer: NdjsonFramer, request: UhpRequest, subscribeError: String?) {
    if (request.method == Methods.EVENTS_SUBSCRIBE && subscribeError != null) {
        framer.writeFrame(
            """{"id":"${request.id}","error":{"code":"$subscribeError","message":"cursor","sequence":10}}""",
        )
        return
    }
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
                    put("methods", stringArray(ALL_METHODS))
                    put("event_sequence", 10)
                }
            Methods.SNAPSHOT ->
                buildJsonObject {
                    put("type", "session_snapshot")
                    put(
                        "protocol",
                        buildJsonObject {
                            put("name", "luvus-uhp")
                            put("major", 1)
                            put("minor", 0)
                        },
                    )
                    put("session", "default")
                    put("server_generation", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                    put("event_sequence", 10)
                    put("workspaces", buildJsonArray { })
                }
            Methods.EVENTS_SUBSCRIBE ->
                buildJsonObject {
                    put("type", "subscription_started")
                    put("sequence", 10)
                    put("replayed", 0)
                    put("queue_capacity", 256)
                    put("loss_behavior", "resync_required_then_close")
                }
            Methods.TASK_HEARTBEAT ->
                buildJsonObject {
                    put("type", "ok")
                    put("over_threshold", false)
                    put("revision", 11)
                }
            Methods.TASK_NEXT ->
                buildJsonObject {
                    put("type", "none")
                    put("message", "no ready tasks")
                    put("revision", 11)
                }
            Methods.PANE_SPLIT ->
                buildJsonObject {
                    put("type", "pane")
                    put("pane", "9")
                    put("revision", 11)
                }
            Methods.WORKSPACE_OPEN ->
                buildJsonObject {
                    put("type", "workspace")
                    put("workspace", "0")
                    put("revision", 11)
                }
            Methods.TERMINAL_SNAPSHOT ->
                buildJsonObject {
                    put("type", "terminal_backend_snapshot")
                    put("server_generation", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                    put("event_sequence", 10)
                    put("terminals", buildJsonArray { })
                    put("truncated", false)
                }
            Methods.AGENT_LIST -> parseObject(AGENT_LIST_RESULT)
            Methods.AGENT_EXPLAIN, Methods.AGENT_GET -> parseObject(AGENT_EXPLAIN_RESULT)
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
    if (request.method == Methods.EVENTS_SUBSCRIBE) {
        framer.writeFrame("""{"event":"pane.focused","sequence":11,"data":{"pane":"7"}}""")
    }
}

private fun errorFailure(
    code: String,
    expected: Long? = null,
    actual: Long? = null,
    sequence: Long? = null,
    retryable: Boolean? = null,
): Failure {
    val error =
        buildJsonObject {
            put("code", code)
            put("message", "x")
            if (expected != null) put("expected", expected)
            if (actual != null) put("actual", actual)
            if (sequence != null) put("sequence", sequence)
            if (retryable != null) put("retryable", retryable)
        }
    val decoded =
        decodeUhpResponse(
            tech.asahiart.luvia.internal.compactJson.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("id", "1")
                    put("error", error)
                },
            ),
            "1",
        ) as UhpResponse.Failure
    return decoded.error.toFailure()
}

private fun stringArray(values: List<String>): JsonArray =
    buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }

private val ALL_METHODS =
    listOf(
        Methods.CAPABILITIES,
        Methods.SNAPSHOT,
        Methods.EVENTS_SUBSCRIBE,
        Methods.WORKSPACE_OPEN,
        Methods.WORKSPACE_FOCUS,
        Methods.PANE_SPLIT,
        Methods.AGENT_LIST,
        Methods.AGENT_GET,
        Methods.AGENT_EXPLAIN,
        Methods.AGENT_START,
        Methods.TASK_NEXT,
        Methods.TASK_HEARTBEAT,
        Methods.TERMINAL_SNAPSHOT,
        Methods.TERMINAL_TYPE,
        Methods.TERMINAL_OBSERVE,
        Methods.TERMINAL_CONTROL,
    )

private const val AGENT_LIST_RESULT: String =
    """{"agents":[{"agent":"pi","authority":"process_tree","branch":"main","cwd":"/Users/misaka/Developer/OpenTactic","focused":false,"name":null,"pane":"2","project":"OpenTactic","repo":"/Users/misaka/Developer/OpenTactic/.git","session":null,"state_source":"no_positive_state_evidence","status":"idle","tab":"1","workspace":"1","workspace_name":"OpenTactic","worktree":false}],"revision":1551062,"type":"agent_list"}"""

private const val AGENT_EXPLAIN_RESULT: String =
    """{"agent":"zsh","authority":null,"available":true,"identity":{"confidence":"none","source":"command_fallback"},"pane":"1","revision":1551062,"session":null,"state_evidence":{"blocked_hint":null,"confidence":"none","rule_priority":null,"rule_region":null,"source":"no_positive_state_evidence"},"status":"idle","type":"agent_explanation"}"""

private const val WORKSPACE_GET_RESULT: String =
    """{"active":false,"active_tab":"1","ahead":null,"behind":null,"branch":"main","cwd":"/Users/misaka/Developer/OpenTactic","display_position":"1","name":"OpenTactic","pinned":false,"revision":1551062,"tabs":1,"terminal_cwd":"/Users/misaka/Developer/OpenTactic","type":"workspace","workspace":"1","workspace_id":"workspace_b247ba91ad347edde038c056c9511a54"}"""

private const val INVENTORY_RESULT: String =
    """{"server_generation":"217854434c900f8f55353d65833bc820","terminals":[{"content_revision":20,"cwd":"/Users/misaka/.config/emacs","label":null,"pane_id":"1","root_process":{"pid":35989,"start_marker":"1788094672.822376"},"tab":{"index":1,"name":null},"terminal_id":"b73a178a4292efa92678eee2609f966a","terminal_title":"misaka@Yui:~/.config/emacs","workspace":{"index":1,"name":"emacs","root":"/Users/misaka/.config/emacs"}}],"truncated":false,"type":"terminal_backend_inventory"}"""

private const val SNAPSHOT_RESULT: String =
    """{"event_sequence":1546888,"protocol":{"major":1,"minor":0,"name":"luvus-uhp"},"server_generation":"217854434c900f8f55353d65833bc820","session":"default","type":"session_snapshot","workspaces":[{"active":false,"branch":"main","cwd":"/Users/misaka/.config/emacs","index":1,"name":"emacs","pinned":false,"tabs":[{"active":true,"index":1,"kind":"panes","name":null,"panes":[{"agent":"zsh","agent_authority":"command_fallback","agent_session":null,"agent_status":"idle","content_revision":20,"cwd":"/Users/misaka/.config/emacs","focused":false,"kind":"terminal","pane_id":"1","root_process":{"pid":35989,"start_marker":"1788094672.822376"},"terminal_id":"b73a178a4292efa92678eee2609f966a"}]}]}]}"""
