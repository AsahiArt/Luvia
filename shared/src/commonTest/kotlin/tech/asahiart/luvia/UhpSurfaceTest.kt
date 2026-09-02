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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import tech.asahiart.luvia.internal.Methods
import tech.asahiart.luvia.internal.NdjsonFramer
import tech.asahiart.luvia.internal.UhpEvent
import tech.asahiart.luvia.internal.UhpRequest
import tech.asahiart.luvia.internal.decodeUhpRequest
import tech.asahiart.luvia.internal.mapAgentPrompt
import tech.asahiart.luvia.internal.mapAgentRead
import tech.asahiart.luvia.internal.mapAgentSessions
import tech.asahiart.luvia.internal.mapDiffGet
import tech.asahiart.luvia.internal.mapDiffList
import tech.asahiart.luvia.internal.mapGitLog
import tech.asahiart.luvia.internal.mapGitStatus
import tech.asahiart.luvia.internal.mapMissionSnapshot
import tech.asahiart.luvia.internal.mapReviewNoteResult
import tech.asahiart.luvia.internal.mapReviewNotes
import tech.asahiart.luvia.internal.parseBusEvent
import tech.asahiart.luvia.internal.parseObject
import tech.asahiart.luvia.support.CopiedFixtures
import tech.asahiart.luvia.support.ScriptedFactory

class UhpSurfaceTest {
    @Test
    fun encodesEveryNewMethodWithoutIfRevisionOnReads() = runTest {
        val seen = mutableListOf<UhpRequest>()
        val session = openSurface(backgroundScope) { seen += it }

        session.readAgent("7", lines = 50, source = AgentReadSource.VISIBLE)
        session.promptAgent("7", "ship it", wait = true, until = AgentStatus.Idle, timeoutSeconds = 12)
        session.sendAgentKeys("7", listOf(AgentKey.ENTER, AgentKey.Ctrl('c'), AgentKey.Char('y')))
        session.listAgentSessions()
        session.missionSnapshot()
        session.listDiff(DiffLayer.WORKTREE)
        session.getDiff("file.txt", DiffLayer.WORKTREE, includePatch = true)
        session.refreshDiff()
        session.listReviewNotes(ReviewNoteState.OPEN, file = "file.txt")
        session.addReviewNote("file.txt", ReviewLine.New(1), body = "check this", layer = DiffLayer.WORKTREE)
        session.editReviewNote("note1", "updated")
        session.resolveReviewNote("note1")
        session.reopenReviewNote("note1")
        session.removeReviewNote("note1")
        session.sendReviewNotes("pi", ids = listOf("note1"))
        session.gitStatus(workspace = 0)
        session.gitLog(n = 5)

        fun params(method: String): JsonObject = seen.single { it.method == method }.params

        val read = params(Methods.AGENT_READ)
        assertEquals("7", read.getValue("target").jsonPrimitive.content)
        assertEquals("50", read.getValue("lines").jsonPrimitive.content)
        assertEquals("visible", read.getValue("source").jsonPrimitive.content)
        assertFalse("if_revision" in read)

        val prompt = params(Methods.AGENT_PROMPT)
        assertEquals("7", prompt.getValue("target").jsonPrimitive.content)
        assertEquals("ship it", prompt.getValue("text").jsonPrimitive.content)
        assertEquals("true", prompt.getValue("wait").jsonPrimitive.content)
        assertEquals("idle", prompt.getValue("until").jsonArray.single().jsonPrimitive.content)
        assertEquals("12", prompt.getValue("timeout_s").jsonPrimitive.content)

        val keys = params(Methods.AGENT_KEYS)
        assertEquals(
            listOf("enter", "ctrl+c", "y"),
            keys.getValue("keys").jsonArray.map { it.jsonPrimitive.content },
        )

        assertTrue(params(Methods.AGENT_SESSIONS).isEmpty())
        assertFalse("if_revision" in params(Methods.AGENT_SESSIONS))

        val mission = params(Methods.MISSION_SNAPSHOT)
        assertEquals("all", mission.getValue("scope").jsonPrimitive.content)
        assertFalse("if_revision" in mission)

        assertEquals("worktree", params(Methods.DIFF_LIST).getValue("layer").jsonPrimitive.content)
        assertFalse("if_revision" in params(Methods.DIFF_LIST))

        val get = params(Methods.DIFF_GET)
        assertEquals("file.txt", get.getValue("path").jsonPrimitive.content)
        assertEquals("worktree", get.getValue("layer").jsonPrimitive.content)
        assertEquals("true", get.getValue("include_patch").jsonPrimitive.content)
        assertFalse("if_revision" in get)

        assertTrue(params(Methods.DIFF_REFRESH).isEmpty())

        val notes = params(Methods.DIFF_NOTE_LIST)
        assertEquals("open", notes.getValue("state").jsonPrimitive.content)
        assertEquals("file.txt", notes.getValue("file").jsonPrimitive.content)
        assertFalse("if_revision" in notes)

        val add = params(Methods.DIFF_NOTE_ADD)
        assertEquals("file.txt", add.getValue("file").jsonPrimitive.content)
        assertEquals("1", add.getValue("new_line").jsonPrimitive.content)
        assertFalse("old_line" in add)
        assertEquals("check this", add.getValue("body").jsonPrimitive.content)
        assertEquals("issue", add.getValue("kind").jsonPrimitive.content)

        assertEquals("updated", params(Methods.DIFF_NOTE_EDIT).getValue("body").jsonPrimitive.content)
        assertEquals("note1", params(Methods.DIFF_NOTE_RESOLVE).getValue("id").jsonPrimitive.content)
        assertEquals("note1", params(Methods.DIFF_NOTE_REOPEN).getValue("id").jsonPrimitive.content)
        assertEquals("note1", params(Methods.DIFF_NOTE_REMOVE).getValue("id").jsonPrimitive.content)

        val send = params(Methods.DIFF_NOTE_SEND)
        assertEquals("pi", send.getValue("to").jsonPrimitive.content)
        assertEquals("note1", send.getValue("ids").jsonArray.single().jsonPrimitive.content)
        assertFalse("all_open" in send)

        val status = params(Methods.GIT_STATUS)
        assertEquals("0", status.getValue("workspace").jsonPrimitive.content)
        assertFalse("if_revision" in status)
        assertEquals("5", params(Methods.GIT_LOG).getValue("n").jsonPrimitive.content)
        assertFalse("if_revision" in params(Methods.GIT_LOG))

        session.close()
    }

    @Test
    fun reviewLineOldEncodesOnlyOldLine() = runTest {
        val seen = mutableListOf<UhpRequest>()
        val session = openSurface(backgroundScope) { seen += it }
        session.addReviewNote("file.txt", ReviewLine.Old(4), endLine = 6, body = "old side")
        val params = seen.single { it.method == Methods.DIFF_NOTE_ADD }.params
        assertEquals("4", params.getValue("old_line").jsonPrimitive.content)
        assertFalse("new_line" in params)
        assertEquals("6", params.getValue("end_line").jsonPrimitive.content)
        session.close()
    }

    @Test
    fun sendAgentKeysRejectsEmptyListClientSide() = runTest {
        val seen = mutableListOf<UhpRequest>()
        val session = openSurface(backgroundScope) { seen += it }
        val result = session.sendAgentKeys("7", emptyList())
        val failure = (result as Outcome.Err).failure
        assertIs<Failure.InvalidRequest>(failure)
        assertTrue(seen.none { it.method == Methods.AGENT_KEYS })
        session.close()
    }

    @Test
    fun promptAgentMapsBusyFailure() = runTest {
        val session = openSurface(backgroundScope, promptBusy = true) { }
        val result = session.promptAgent("7", "hi", wait = true)
        assertIs<Failure.AgentPromptBusy>((result as Outcome.Err).failure)
        session.close()
    }

    @Test
    fun supportsUsesCapabilitiesMethods() = runTest {
        val session = openSurface(backgroundScope) { }
        assertTrue(session.supports(Methods.AGENT_READ))
        assertTrue(session.supports("mission.snapshot"))
        assertFalse(session.supports("task.next"))
        session.close()
    }

    @Test
    fun mapsCopiedResultFixtures() {
        val read = mapAgentRead(parseObject(CopiedFixtures.AGENT_READ_RESULT))
        assertEquals("7", read.pane)
        assertEquals("hello from agent", read.text)
        assertNull(read.revision)

        val prompt = mapAgentPrompt(parseObject(CopiedFixtures.AGENT_PROMPT_TIMEOUT_RESULT))
        assertTrue(prompt.submitted)
        assertFalse(prompt.matched)
        assertEquals(AgentStatus.Working, prompt.status)
        assertEquals("timeout", prompt.evidence)
        assertEquals(11L, prompt.baselineRevision)
        assertEquals(14L, prompt.contentRevision)

        val sessions = mapAgentSessions(parseObject(CopiedFixtures.AGENT_SESSIONS_RESULT))
        assertEquals("sess_abc", sessions.single().sessionId)
        assertEquals("pi", sessions.single().agent)

        val mission = mapMissionSnapshot(parseObject(CopiedFixtures.MISSION_SNAPSHOT_RESULT))
        assertEquals(MissionRowKind.LIVE, mission.rows.single().kind)
        assertEquals(1L, mission.summary.agents)
        assertEquals(0.25, mission.summary.costUsd)

        val listed = mapDiffList(parseObject(CopiedFixtures.DIFF_LIST_RESULT))
        assertEquals("file.txt", listed.files.single().path)
        assertEquals(DiffLayer.WORKTREE, listed.files.single().layer)

        val diff = mapDiffGet(parseObject(CopiedFixtures.DIFF_GET_RESULT))
        assertEquals("file.txt", diff.path)
        assertEquals(1, diff.hunks.size)
        assertEquals("deletion", diff.hunks.single().lines.first().kind)
        assertEquals(1L, diff.hunks.single().lines.first().oldLine)
        assertNull(diff.hunks.single().lines.first().newLine)

        val notes = mapReviewNotes(parseObject(CopiedFixtures.DIFF_NOTE_LIST_RESULT))
        assertEquals(ReviewNoteKind.ISSUE, notes.single().kind)
        assertEquals(ReviewNoteState.OPEN, notes.single().state)

        val note = mapReviewNoteResult(parseObject(CopiedFixtures.DIFF_NOTE_RESULT))
        assertEquals("note1", note.id)

        val git = mapGitStatus(parseObject(CopiedFixtures.GIT_STATUS_RESULT))
        assertEquals("main", git.branch)
        assertEquals("M", git.staged.single().code)
        assertEquals(listOf("c.kt"), git.untracked)
        assertEquals(listOf("WIP"), git.stashes)

        val log = mapGitLog(parseObject(CopiedFixtures.GIT_LOG_RESULT))
        assertEquals("abc1234", log.single().sha)
        assertEquals("2 hours ago", log.single().whenText)
    }

    @Test
    fun mapsTaskModeAndWorkspaceWorker() {
        val task =
            tech.asahiart.luvia.internal.mapTaskMutation(
                parseObject("""{"type":"task","task":${CopiedFixtures.TASK_WITH_WORKER}}"""),
            ).task
        assertEquals(TaskStatus.Merging, task.status)
        assertEquals("workspace", task.mode)
        assertEquals("workspace-a", task.workspaceWorker?.workspaceId)
        assertEquals("1", task.workspaceWorker?.tabId)
        assertEquals("/tmp/work", task.workspaceWorker?.root)
    }

    @Test
    fun mapsPhoneRelevantEvents() {
        val hook =
            parseBusEvent(
                UhpEvent(
                    "agent.hook",
                    20,
                    buildJsonObject {
                        put("pane", "7")
                        put("agent", "pi")
                        put("kind", "tool")
                        put("message", "ran")
                        put("tool", "bash")
                    },
                ),
            )
        val typedHook = hook as BusEvent.AgentHook
        assertEquals("7", typedHook.pane)
        assertEquals("bash", typedHook.tool)

        val started =
            parseBusEvent(
                UhpEvent(
                    "task.started",
                    21,
                    buildJsonObject {
                        put("id", "t1")
                        put("pane", "7")
                        put("mode", "workspace")
                        put("workspace_id", "ws")
                        put("tab_id", "1")
                        put("cwd", "/tmp")
                    }
                ),
            )
        val typedStarted = started as BusEvent.TaskPayload
        assertEquals("t1", typedStarted.id)
        assertEquals("workspace", typedStarted.mode)
        assertEquals("ws", typedStarted.workspaceId)

        val conflict =
            parseBusEvent(
                UhpEvent(
                    "task.merge_conflict",
                    22,
                    buildJsonObject {
                        put("id", "t1")
                        put("branch", "feat")
                        put("files", buildJsonArray { add(JsonPrimitive("a.kt")) })
                    },
                ),
            )
        val typedConflict = conflict as BusEvent.TaskPayload
        assertEquals(listOf("a.kt"), typedConflict.files)

        val lease =
            parseBusEvent(
                UhpEvent(
                    "lease.acquired",
                    23,
                    buildJsonObject {
                        put("id", "l1")
                        put("pane", 7)
                        put("task", "t1")
                        put("paths", buildJsonArray { add(JsonPrimitive("src/**")) })
                        put("acquired", 99)
                    },
                ),
            )
        val typedLease = lease as BusEvent.LeaseChanged
        assertEquals("l1", typedLease.id)
        assertEquals("7", typedLease.pane)
        assertEquals("t1", typedLease.task)
    }

    @Test
    fun contractMethodsAreCallable() = runTest {
        val session = openSurface(backgroundScope) { }
        assertIs<Outcome.Ok<*>>(session.readAgent("7"))
        assertIs<Outcome.Ok<*>>(session.sendAgentKeys("7", listOf(AgentKey.ENTER)))
        assertIs<Outcome.Ok<*>>(session.promptAgent("7", "hi"))
        assertIs<Outcome.Ok<*>>(session.missionSnapshot())
        assertIs<Outcome.Ok<*>>(session.listDiff())
        assertIs<Outcome.Ok<*>>(session.getDiff("file.txt"))
        assertIs<Outcome.Ok<*>>(session.addReviewNote("file.txt", ReviewLine.New(1), body = "n"))
        assertIs<Outcome.Ok<*>>(session.sendReviewNotes("pi", ids = listOf("note1")))
        assertIs<Outcome.Ok<*>>(session.gitStatus())
        assertTrue(session.supports(Methods.AGENT_READ))
        session.close()
    }
}

private suspend fun openSurface(
    scope: kotlinx.coroutines.CoroutineScope,
    promptBusy: Boolean = false,
    onRequest: (UhpRequest) -> Unit,
): LuviaSession {
    val factory =
        ScriptedFactory(scope) { framer ->
            dispatchSurface(framer, promptBusy, onRequest)
        }
    return (LuviaClient(factory).open("default") as Outcome.Ok).value
}

private suspend fun dispatchSurface(
    framer: NdjsonFramer,
    promptBusy: Boolean,
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
            respondSurface(framer, request, promptBusy)
        }
    }
}

private suspend fun respondSurface(framer: NdjsonFramer, request: UhpRequest, promptBusy: Boolean) {
    if (promptBusy && request.method == Methods.AGENT_PROMPT) {
        framer.writeFrame(
            """{"id":"${request.id}","error":{"code":"agent_prompt_busy","message":"target agent already has a prompt waiting for completion"}}""",
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
                    put("methods", stringArray(SURFACE_METHODS))
                    put("event_sequence", 10)
                }
            Methods.AGENT_READ -> parseObject(CopiedFixtures.AGENT_READ_RESULT)
            Methods.AGENT_PROMPT -> parseObject(CopiedFixtures.AGENT_PROMPT_TIMEOUT_RESULT)
            Methods.AGENT_SESSIONS -> parseObject(CopiedFixtures.AGENT_SESSIONS_RESULT)
            Methods.MISSION_SNAPSHOT -> parseObject(CopiedFixtures.MISSION_SNAPSHOT_RESULT)
            Methods.DIFF_LIST -> parseObject(CopiedFixtures.DIFF_LIST_RESULT)
            Methods.DIFF_GET -> parseObject(CopiedFixtures.DIFF_GET_RESULT)
            Methods.DIFF_NOTE_LIST -> parseObject(CopiedFixtures.DIFF_NOTE_LIST_RESULT)
            Methods.DIFF_NOTE_ADD, Methods.DIFF_NOTE_EDIT, Methods.DIFF_NOTE_RESOLVE, Methods.DIFF_NOTE_REOPEN ->
                parseObject(CopiedFixtures.DIFF_NOTE_RESULT)
            Methods.DIFF_NOTE_SEND -> parseObject(CopiedFixtures.DIFF_NOTE_SEND_RESULT)
            Methods.GIT_STATUS -> parseObject(CopiedFixtures.GIT_STATUS_RESULT)
            Methods.GIT_LOG -> parseObject(CopiedFixtures.GIT_LOG_RESULT)
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

private val SURFACE_METHODS =
    listOf(
        Methods.CAPABILITIES,
        Methods.AGENT_READ,
        Methods.AGENT_PROMPT,
        Methods.AGENT_KEYS,
        Methods.AGENT_SESSIONS,
        Methods.MISSION_SNAPSHOT,
        Methods.DIFF_LIST,
        Methods.DIFF_GET,
        Methods.DIFF_REFRESH,
        Methods.DIFF_NOTE_LIST,
        Methods.DIFF_NOTE_ADD,
        Methods.DIFF_NOTE_EDIT,
        Methods.DIFF_NOTE_RESOLVE,
        Methods.DIFF_NOTE_REOPEN,
        Methods.DIFF_NOTE_REMOVE,
        Methods.DIFF_NOTE_SEND,
        Methods.GIT_STATUS,
        Methods.GIT_LOG,
    )
