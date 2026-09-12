package tech.asahiart.luvia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import tech.asahiart.luvia.internal.NdjsonFramer
import tech.asahiart.luvia.internal.UhpRequest
import tech.asahiart.luvia.internal.decodeUhpRequest
import tech.asahiart.luvia.internal.parseObject
import tech.asahiart.luvia.support.ScriptedFactory

class TerminalWaitSurfaceTest {
    @Test
    fun encodesAssignedUnaryMethodsWithExactParams() = runTest {
        val seen = mutableListOf<UhpRequest>()
        val session = openWaitSurface(backgroundScope) { seen += it }
        val identity = fixtureIdentity()
        val root = ProcessIdentity(pid = 4242, startMarker = "start-1")

        session.validateTerminal(identity, expectedRoot = root)
        session.terminalProcesses(identity)
        session.setTerminalTitle(identity, "review")
        session.notifyTerminal(identity, "done", "tests passed")
        session.createTerminal(
            cwd = "/tmp/work",
            placement = TerminalCreatePlacement.Workspace,
            focus = true,
            command = listOf("zsh"),
            label = "dev",
        )
        session.createTerminal(
            cwd = "/tmp/work",
            placement = TerminalCreatePlacement.Sibling(identity, expectedRoot = root),
            focus = false,
        )
        session.closeTerminal(identity)
        session.waitTerminalChange(identity, afterRevision = 4, timeoutMs = 1000)
        session.waitTerminalOutput(identity, afterRevision = 4, match = "READY", timeoutMs = 2000)
        session.refreshMission(MissionScope.ALL, workspace = 1)
        session.openMission(workspace = 2)
        session.openDiff(
            path = "src/app.rs",
            layer = DiffLayer.WORKTREE,
            view = DiffOpenView.SPLIT,
            placement = DiffOpenPlacement.TAB,
        )
        session.navigateDiff(DiffNavigateAction.NEXT_HUNK, pane = "7")
        session.applyReviewNotes(
            listOf(
                DiffNoteApplyItem(
                    file = "src/app.rs",
                    line = ReviewLine.New(120),
                    body = "Extract this validation",
                    endLine = 123,
                    kind = ReviewNoteKind.SUGGESTION,
                    layer = DiffLayer.WORKTREE,
                ),
                DiffNoteApplyItem(
                    file = "src/cli.rs",
                    line = ReviewLine.Old(88),
                    body = "Is this fallback still required?",
                    layer = DiffLayer.STAGED,
                ),
            ),
        )
        session.waitEvent(
            "pane.agent_status_changed",
            where = mapOf("pane" to "7"),
            timeoutSeconds = 30,
            afterSequence = 10,
        )
        session.waitOutput("7", "done", timeoutSeconds = 5)
        session.ping()
        session.uhpStats()
        session.getConfig()

        fun params(method: String): JsonObject = seen.single { it.method == method }.params

        val validate = params("terminal.backend.validate")
        assertEquals(GEN, validate.getValue("server_generation").jsonPrimitive.content)
        assertEquals(TID, validate.getValue("terminal_id").jsonPrimitive.content)
        assertEquals("7", validate.getValue("pane_id").jsonPrimitive.content)
        val expectedRoot = validate.getValue("expected_root").jsonObject
        assertEquals("4242", expectedRoot.getValue("pid").jsonPrimitive.content)
        assertEquals("start-1", expectedRoot.getValue("start_marker").jsonPrimitive.content)
        assertEquals(setOf("server_generation", "terminal_id", "pane_id", "expected_root"), validate.keys)

        val processes = params("terminal.backend.processes")
        assertEquals(GEN, processes.getValue("server_generation").jsonPrimitive.content)
        assertEquals(TID, processes.getValue("terminal_id").jsonPrimitive.content)
        assertEquals("7", processes.getValue("pane_id").jsonPrimitive.content)
        assertFalse("expected_root" in processes)
        assertEquals(setOf("server_generation", "terminal_id", "pane_id"), processes.keys)

        val title = params("terminal.backend.set_title")
        assertEquals("review", title.getValue("title").jsonPrimitive.content)
        assertEquals(setOf("server_generation", "terminal_id", "pane_id", "title"), title.keys)

        val notify = params("terminal.backend.notify")
        assertEquals("done", notify.getValue("title").jsonPrimitive.content)
        assertEquals("tests passed", notify.getValue("body").jsonPrimitive.content)
        assertEquals(setOf("server_generation", "terminal_id", "pane_id", "title", "body"), notify.keys)

        val creates = seen.filter { it.method == "terminal.backend.create" }
        assertEquals(2, creates.size)
        val workspaceCreate = creates[0].params
        assertEquals("/tmp/work", workspaceCreate.getValue("cwd").jsonPrimitive.content)
        assertEquals("true", workspaceCreate.getValue("focus").jsonPrimitive.content)
        assertEquals("zsh", workspaceCreate.getValue("command").jsonArray.single().jsonPrimitive.content)
        assertEquals("dev", workspaceCreate.getValue("label").jsonPrimitive.content)
        assertEquals("workspace", workspaceCreate.getValue("placement").jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals(setOf("kind"), workspaceCreate.getValue("placement").jsonObject.keys)
        assertEquals(setOf("cwd", "placement", "focus", "command", "label"), workspaceCreate.keys)
        assertFalse("cols" in workspaceCreate)
        assertFalse("rows" in workspaceCreate)
        assertFalse("winsize" in workspaceCreate)

        val siblingCreate = creates[1].params
        assertEquals("false", siblingCreate.getValue("focus").jsonPrimitive.content)
        assertFalse("command" in siblingCreate)
        assertFalse("label" in siblingCreate)
        val siblingPlacement = siblingCreate.getValue("placement").jsonObject
        assertEquals("sibling", siblingPlacement.getValue("kind").jsonPrimitive.content)
        val ofTerminal = siblingPlacement.getValue("of_terminal").jsonObject
        assertEquals(GEN, ofTerminal.getValue("server_generation").jsonPrimitive.content)
        assertEquals(TID, ofTerminal.getValue("terminal_id").jsonPrimitive.content)
        assertEquals("7", ofTerminal.getValue("pane_id").jsonPrimitive.content)
        assertEquals("4242", ofTerminal.getValue("expected_root").jsonObject.getValue("pid").jsonPrimitive.content)
        assertEquals(setOf("kind", "of_terminal"), siblingPlacement.keys)

        val close = params("terminal.backend.close")
        assertEquals(setOf("server_generation", "terminal_id", "pane_id"), close.keys)

        val waitChange = params("terminal.backend.wait_change")
        assertEquals("4", waitChange.getValue("after_revision").jsonPrimitive.content)
        assertEquals("1000", waitChange.getValue("timeout_ms").jsonPrimitive.content)
        assertFalse("match" in waitChange)
        assertFalse("cols" in waitChange)
        assertEquals(
            setOf("server_generation", "terminal_id", "pane_id", "after_revision", "timeout_ms"),
            waitChange.keys,
        )

        val waitTerminalOut = params("terminal.backend.wait_output")
        assertEquals("READY", waitTerminalOut.getValue("match").jsonPrimitive.content)
        assertEquals("2000", waitTerminalOut.getValue("timeout_ms").jsonPrimitive.content)
        assertEquals(
            setOf("server_generation", "terminal_id", "pane_id", "after_revision", "match", "timeout_ms"),
            waitTerminalOut.keys,
        )

        val refresh = params("mission.refresh")
        assertEquals("all", refresh.getValue("scope").jsonPrimitive.content)
        assertEquals("1", refresh.getValue("workspace").jsonPrimitive.content)
        assertFalse("workspace_id" in refresh)
        assertEquals(setOf("scope", "workspace"), refresh.keys)

        val openMission = params("mission.open")
        assertEquals("2", openMission.getValue("workspace").jsonPrimitive.content)
        assertFalse("scope" in openMission)
        assertEquals(setOf("workspace"), openMission.keys)

        val openDiff = params("diff.open")
        assertEquals("src/app.rs", openDiff.getValue("path").jsonPrimitive.content)
        assertEquals("worktree", openDiff.getValue("layer").jsonPrimitive.content)
        assertEquals("split", openDiff.getValue("view").jsonPrimitive.content)
        assertEquals("tab", openDiff.getValue("placement").jsonPrimitive.content)
        assertEquals(setOf("path", "layer", "view", "placement"), openDiff.keys)

        val navigate = params("diff.navigate")
        assertEquals("7", navigate.getValue("pane").jsonPrimitive.content)
        assertEquals("next_hunk", navigate.getValue("action").jsonPrimitive.content)
        assertEquals(setOf("action", "pane"), navigate.keys)

        val apply = params("diff.note.apply")
        val notes = apply.getValue("notes").jsonArray
        assertEquals(1, apply.size)
        val first = notes[0].jsonObject
        assertEquals("src/app.rs", first.getValue("file").jsonPrimitive.content)
        assertEquals("120", first.getValue("new_line").jsonPrimitive.content)
        assertFalse("old_line" in first)
        assertEquals("123", first.getValue("end_line").jsonPrimitive.content)
        assertEquals("Extract this validation", first.getValue("body").jsonPrimitive.content)
        assertEquals("suggestion", first.getValue("kind").jsonPrimitive.content)
        assertEquals("worktree", first.getValue("layer").jsonPrimitive.content)
        val second = notes[1].jsonObject
        assertEquals("src/cli.rs", second.getValue("file").jsonPrimitive.content)
        assertEquals("88", second.getValue("old_line").jsonPrimitive.content)
        assertFalse("new_line" in second)
        assertFalse("end_line" in second)
        assertEquals("Is this fallback still required?", second.getValue("body").jsonPrimitive.content)
        assertEquals("issue", second.getValue("kind").jsonPrimitive.content)
        assertEquals("staged", second.getValue("layer").jsonPrimitive.content)

        val waitEvent = params("events.wait")
        assertEquals("pane.agent_status_changed", waitEvent.getValue("event").jsonPrimitive.content)
        assertEquals("7", waitEvent.getValue("where").jsonObject.getValue("pane").jsonPrimitive.content)
        assertEquals("30", waitEvent.getValue("timeout_s").jsonPrimitive.content)
        assertEquals("10", waitEvent.getValue("after_sequence").jsonPrimitive.content)
        assertEquals(setOf("event", "where", "timeout_s", "after_sequence"), waitEvent.keys)

        val waitOutput = params("wait.output")
        assertEquals("7", waitOutput.getValue("pane").jsonPrimitive.content)
        assertEquals("done", waitOutput.getValue("match").jsonPrimitive.content)
        assertEquals("5", waitOutput.getValue("timeout_s").jsonPrimitive.content)
        assertEquals(setOf("pane", "match", "timeout_s"), waitOutput.keys)

        assertTrue(params("ping").isEmpty())
        assertTrue(params("uhp.stats").isEmpty())
        assertTrue(params("config.get").isEmpty())
        assertTrue(seen.none { it.method == "terminal.backend.observe" })
        assertTrue(seen.none { it.method == "terminal.backend.control" })
        assertTrue(seen.none { it.method == "terminal.backend.events.subscribe" })

        session.close()
    }

    @Test
    fun mapsRealisticResultFixtures() = runTest {
        val session = openWaitSurface(backgroundScope) { }
        val identity = fixtureIdentity()

        val validate = (session.validateTerminal(identity) as Outcome.Ok).value
        assertEquals("alive", validate.state)

        val processes = (session.terminalProcesses(identity) as Outcome.Ok).value
        assertEquals(GEN, processes.serverGeneration)
        assertEquals(TID, processes.terminalId)
        assertEquals("7", processes.paneId)
        assertEquals(4242L, processes.rootProcess?.pid)
        assertEquals("start-1", processes.rootProcess?.startMarker)
        assertEquals("observed", processes.scan)
        assertEquals(listOf("zsh", "node"), processes.executables)
        assertFalse(processes.argumentsExposed)

        assertIs<Outcome.Ok<Unit>>(session.setTerminalTitle(identity, "review"))
        assertIs<Outcome.Ok<Unit>>(session.notifyTerminal(identity, "done", "ok"))
        assertIs<Outcome.Ok<Unit>>(session.closeTerminal(identity))

        val created =
            (
                session.createTerminal(
                    cwd = "/tmp/work",
                    placement = TerminalCreatePlacement.Workspace,
                    focus = true,
                ) as Outcome.Ok
            ).value
        assertEquals(GEN, created.serverGeneration)
        assertEquals("33333333333333333333333333333333", created.terminalId)
        assertEquals("9", created.paneId)
        assertEquals("/tmp/work", created.cwd)
        assertEquals("workspace", created.placementKind)
        assertEquals(1L, created.workspace)
        assertEquals(1L, created.tab)
        assertEquals(99L, created.rootProcess?.pid)

        val change = (session.waitTerminalChange(identity, afterRevision = 4, timeoutMs = 1000) as Outcome.Ok).value
        assertEquals(12L, change.contentRevision)
        assertFalse(change.outputMatched)

        val output =
            (session.waitTerminalOutput(identity, afterRevision = 4, match = "READY", timeoutMs = 2000) as Outcome.Ok)
                .value
        assertEquals(13L, output.contentRevision)
        assertTrue(output.outputMatched)

        val refresh = (session.refreshMission() as Outcome.Ok).value
        assertEquals("all", refresh.scope)
        assertEquals("1", refresh.workspace)
        assertTrue(refresh.refreshing)

        val opened = (session.openMission() as Outcome.Ok).value
        assertTrue(opened.mission)

        val diff = (session.openDiff("src/app.rs") as Outcome.Ok).value
        assertEquals("7", diff.pane)
        assertEquals("src/app.rs", diff.path)
        assertEquals(DiffLayer.WORKTREE, diff.layer)

        val navigated = (session.navigateDiff(DiffNavigateAction.NEXT_HUNK, pane = "7") as Outcome.Ok).value
        assertEquals("7", navigated.pane)

        val notes =
            (
                session.applyReviewNotes(
                    listOf(DiffNoteApplyItem("src/app.rs", ReviewLine.New(120), body = "Extract this validation")),
                ) as Outcome.Ok
            ).value
        assertEquals("n1", notes.single().id)
        assertEquals(ReviewNoteKind.SUGGESTION, notes.single().kind)
        assertEquals(ReviewNoteState.OPEN, notes.single().state)
        assertEquals("src/app.rs", notes.single().path)

        val waited = (session.waitEvent("pane.agent_status_changed") as Outcome.Ok).value
        assertTrue(waited.matched)
        assertEquals(41L, waited.sequence)
        assertEquals("pane.agent_status_changed", waited.eventName)
        assertEquals(42L, waited.eventSequence)
        assertEquals("7", waited.eventData?.getValue("pane")?.jsonPrimitive?.content)
        assertEquals("done", waited.eventData?.getValue("status")?.jsonPrimitive?.content)

        val paneWait = (session.waitOutput("7", "done") as Outcome.Ok).value
        assertTrue(paneWait.matched)
        assertEquals("7", paneWait.pane)

        val pong = (session.ping() as Outcome.Ok).value
        assertEquals("0.13.4", pong.version)
        assertEquals(1L, pong.protocol)
        assertEquals("default", pong.session)

        val stats = (session.uhpStats() as Outcome.Ok).value
        assertEquals(1000L, stats.uptimeMs)
        assertEquals(1L, stats.connections.active)
        assertEquals(80L, stats.connections.capacity)
        assertEquals(4L, stats.connections.accepted)
        assertEquals(10L, stats.requests.completed)
        assertEquals(50L, stats.requests.meanLatencyUs)
        assertEquals(256L, stats.events.replayCapacity)
        assertEquals(8L, stats.events.terminalStreamCapacity)

        val config = (session.getConfig() as Outcome.Ok).value
        assertEquals("C-a", config.config.getValue("prefix").jsonPrimitive.content)
        assertEquals("10000", config.config.getValue("scrollback").jsonPrimitive.content)

        assertTrue(session.supports("terminal.backend.validate"))
        assertTrue(session.supports("wait.output"))
        assertTrue(session.supports("uhp.stats"))
        assertFalse(session.supports("terminal.backend.events.subscribe"))

        session.close()
    }

    @Test
    fun omitsOptionalKeysWhenUnset() = runTest {
        val seen = mutableListOf<UhpRequest>()
        val session = openWaitSurface(backgroundScope) { seen += it }

        session.refreshMission(MissionScope.WORKSPACE, workspaceId = "workspace_abc")
        session.openMission()
        session.openDiff()
        session.navigateDiff(DiffNavigateAction.TOP)
        session.waitEvent("pane.focused")
        session.waitOutput("7", "ready")

        val refresh = seen.single { it.method == "mission.refresh" }.params
        assertEquals("workspace", refresh.getValue("scope").jsonPrimitive.content)
        assertEquals("workspace_abc", refresh.getValue("workspace_id").jsonPrimitive.content)
        assertFalse("workspace" in refresh)

        assertTrue(seen.single { it.method == "mission.open" }.params.isEmpty())
        assertTrue(seen.single { it.method == "diff.open" }.params.isEmpty())
        assertEquals(setOf("action"), seen.single { it.method == "diff.navigate" }.params.keys)
        assertEquals("top", seen.single { it.method == "diff.navigate" }.params.getValue("action").jsonPrimitive.content)
        assertEquals(setOf("event"), seen.single { it.method == "events.wait" }.params.keys)
        assertEquals(setOf("pane", "match"), seen.single { it.method == "wait.output" }.params.keys)
        assertNull(seen.single { it.method == "wait.output" }.params["timeout_s"])

        session.close()
    }
}

private const val GEN: String = "11111111111111111111111111111111"
private const val TID: String = "22222222222222222222222222222222"

private fun fixtureIdentity(): TerminalIdentity =
    TerminalIdentity(serverGeneration = GEN, terminalId = TID, paneId = "7")

private suspend fun openWaitSurface(
    scope: kotlinx.coroutines.CoroutineScope,
    onRequest: (UhpRequest) -> Unit,
): LuviaSession {
    val factory = ScriptedFactory(scope) { framer -> dispatchWaitSurface(framer, onRequest) }
    return (LuviaClient(factory).open("default") as Outcome.Ok).value
}

private suspend fun dispatchWaitSurface(
    framer: NdjsonFramer,
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
            respondWaitSurface(framer, request)
        }
    }
}

private suspend fun respondWaitSurface(framer: NdjsonFramer, request: UhpRequest) {
    val result: JsonObject =
        when (request.method) {
            "uhp.capabilities" ->
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
                    put("methods", stringArray(WAIT_SURFACE_METHODS))
                    put("event_sequence", 10)
                }
            "terminal.backend.validate" -> parseObject(VALIDATE_RESULT)
            "terminal.backend.processes" -> parseObject(PROCESSES_RESULT)
            "terminal.backend.create" -> parseObject(CREATE_RESULT)
            "terminal.backend.wait_change" -> parseObject(WAIT_CHANGE_RESULT)
            "terminal.backend.wait_output" -> parseObject(WAIT_TERMINAL_OUTPUT_RESULT)
            "mission.refresh" -> parseObject(MISSION_REFRESH_RESULT)
            "mission.open" -> parseObject(MISSION_OPEN_RESULT)
            "diff.open" -> parseObject(DIFF_OPEN_RESULT)
            "diff.navigate" -> parseObject(DIFF_NAVIGATE_RESULT)
            "diff.note.apply" -> parseObject(DIFF_NOTE_APPLY_RESULT)
            "events.wait" -> parseObject(EVENTS_WAIT_RESULT)
            "wait.output" -> parseObject(WAIT_OUTPUT_RESULT)
            "ping" -> parseObject(PING_RESULT)
            "uhp.stats" -> parseObject(UHP_STATS_RESULT)
            "config.get" -> parseObject(CONFIG_GET_RESULT)
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

private val WAIT_SURFACE_METHODS =
    listOf(
        "uhp.capabilities",
        "terminal.backend.validate",
        "terminal.backend.processes",
        "terminal.backend.set_title",
        "terminal.backend.notify",
        "terminal.backend.create",
        "terminal.backend.close",
        "terminal.backend.wait_change",
        "terminal.backend.wait_output",
        "mission.refresh",
        "mission.open",
        "diff.open",
        "diff.navigate",
        "diff.note.apply",
        "events.wait",
        "wait.output",
        "ping",
        "uhp.stats",
        "config.get",
    )

private const val VALIDATE_RESULT: String =
    """{"type":"terminal_backend_validation","state":"alive"}"""

private const val PROCESSES_RESULT: String =
    """{"type":"terminal_backend_processes","server_generation":"11111111111111111111111111111111","terminal_id":"22222222222222222222222222222222","pane_id":"7","root_process":{"pid":4242,"start_marker":"start-1"},"scan":"observed","executables":["zsh","node"],"arguments_exposed":false}"""

private const val CREATE_RESULT: String =
    """{"type":"terminal_backend_created","state":"succeeded","dispatch":"executed","server_generation":"11111111111111111111111111111111","terminal_id":"33333333333333333333333333333333","pane_id":"9","placement":{"kind":"workspace","workspace":1,"tab":1},"cwd":"/tmp/work","root_process":{"pid":99,"start_marker":"boot"}}"""

private const val WAIT_CHANGE_RESULT: String =
    """{"type":"terminal_backend_change","content_revision":12}"""

private const val WAIT_TERMINAL_OUTPUT_RESULT: String =
    """{"type":"terminal_backend_output","content_revision":13}"""

private const val MISSION_REFRESH_RESULT: String =
    """{"type":"mission_refresh","scope":"all","workspace":"1","refreshing":true}"""

private const val MISSION_OPEN_RESULT: String =
    """{"type":"ok","mission":true}"""

private const val DIFF_OPEN_RESULT: String =
    """{"type":"diff_open","pane":"7","path":"src/app.rs","layer":"worktree"}"""

private const val DIFF_NAVIGATE_RESULT: String =
    """{"type":"ok","pane":"7"}"""

private const val DIFF_NOTE_APPLY_RESULT: String =
    """{"type":"diff_notes_applied","notes":[{"id":"n1","review":"rev1","author":"external","kind":"suggestion","body":"Extract this validation","state":"open","path":"src/app.rs","layer":"worktree","side":"new","start_line":120,"end_line":123,"revision":1,"deliveries":[],"created_at_ms":1,"updated_at_ms":1}]}"""

private const val EVENTS_WAIT_RESULT: String =
    """{"type":"event_wait","matched":true,"sequence":41,"event":{"event":"pane.agent_status_changed","sequence":42,"data":{"pane":"7","status":"done"}}}"""

private const val WAIT_OUTPUT_RESULT: String =
    """{"type":"wait","matched":true,"pane":"7"}"""

private const val PING_RESULT: String =
    """{"type":"pong","version":"0.13.4","protocol":1,"session":"default"}"""

private const val UHP_STATS_RESULT: String =
    """{"type":"uhp_stats","uptime_ms":1000,"connections":{"active":1,"capacity":80,"accepted":4,"rejected":0,"initial_frame_timeouts":0},"requests":{"completed":10,"bytes_in":100,"bytes_out":200,"mean_latency_us":50},"events":{"replay_capacity":256,"replay_bytes":1048576,"terminal_streams":1,"terminal_stream_capacity":8}}"""

private const val CONFIG_GET_RESULT: String =
    """{"type":"config","config":{"prefix":"C-a","scrollback":10000}}"""
