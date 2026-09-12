package tech.asahiart.luvia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import tech.asahiart.luvia.internal.Methods
import tech.asahiart.luvia.internal.NdjsonFramer
import tech.asahiart.luvia.internal.UhpRequest
import tech.asahiart.luvia.internal.decodeUhpRequest
import tech.asahiart.luvia.internal.parseObject
import tech.asahiart.luvia.support.ScriptedFactory

class OrchSurfaceTest {
    @Test
    fun encodesDocumentedKeysAndIfRevisionOnlyOnMutations() = runTest {
        val seen = mutableListOf<UhpRequest>()
        val session = openOrch(backgroundScope) { seen += it }

        session.claimTask("t1", pane = "7", ifRevision = 9)
        session.updateTask(
            "t1",
            status = TaskStatus.Blocked,
            output = "log",
            note = "60%",
            prompt = "new briefing",
            ifRevision = 9,
        )
        session.mergeTask("t1", ifRevision = 9)
        session.releaseTask("t1", ifRevision = 9)
        session.deleteTask("t1", ifRevision = 9)
        session.listWorktrees()
        session.createWorktree("feature/x", ifRevision = 9)
        session.openWorktree("/tmp/wt", ifRevision = 9)
        session.removeWorktree("/tmp/wt", ifRevision = 9)
        session.acquireLease("t1", listOf("src/**"), pane = "7", ifRevision = 9)
        session.listLeases()
        session.releaseLease("L2", ifRevision = 9)
        session.createAutomation(
            name = "Morning review",
            enabled = true,
            trigger = WEEKLY_TRIGGER,
            target = AutomationTarget.NewWorker,
            task = REVIEW_TASK,
            policy = REVIEW_POLICY,
            idempotencyKey = "morning-review-v1",
            ifRevision = 9,
        )
        session.listAutomations()
        session.getAutomation("a1")
        session.updateAutomation(
            id = "a1",
            name = "Morning review",
            enabled = false,
            trigger = WEEKLY_TRIGGER,
            target = AutomationTarget.ActiveAgent(
                paneId = "7",
                terminalId = "0123456789abcdef0123456789abcdef",
                ifBusy = "skip",
            ),
            task = REVIEW_TASK,
            policy = REVIEW_POLICY,
            ifRevision = 9,
        )
        session.enableAutomation("a1", ifRevision = 9)
        session.disableAutomation("a1", ifRevision = 9)
        session.rebindAutomation(
            "a1",
            pane = "9",
            terminalId = "0123456789abcdef0123456789abcdef",
            ifRevision = 9,
        )
        session.deleteAutomation("a1", ifRevision = 9)
        session.runAutomation("a1", idempotencyKey = "run-1", ifRevision = 9)
        session.listAutomationHistory(id = "a1", limit = 50)
        session.previewAutomation(WEEKLY_TRIGGER, fromUtc = 1788300000)
        session.automationHealth()

        fun params(method: String): JsonObject = seen.single { it.method == method }.params

        val claim = params(OrchMethods.TASK_CLAIM)
        assertEquals("t1", claim.getValue("id").jsonPrimitive.content)
        assertEquals("7", claim.getValue("pane").jsonPrimitive.content)
        assertEquals("9", claim.getValue("if_revision").jsonPrimitive.content)
        assertEquals(setOf("id", "pane", "if_revision"), claim.keys)

        val update = params(OrchMethods.TASK_UPDATE)
        assertEquals("t1", update.getValue("id").jsonPrimitive.content)
        assertEquals("blocked", update.getValue("status").jsonPrimitive.content)
        assertEquals("log", update.getValue("output").jsonPrimitive.content)
        assertEquals("60%", update.getValue("note").jsonPrimitive.content)
        assertEquals("new briefing", update.getValue("prompt").jsonPrimitive.content)
        assertEquals("9", update.getValue("if_revision").jsonPrimitive.content)
        assertEquals(setOf("id", "status", "output", "note", "prompt", "if_revision"), update.keys)

        assertEquals(setOf("id", "if_revision"), params(OrchMethods.TASK_MERGE).keys)
        assertEquals("t1", params(OrchMethods.TASK_MERGE).getValue("id").jsonPrimitive.content)
        assertEquals("t1", params(OrchMethods.TASK_RELEASE).getValue("id").jsonPrimitive.content)
        assertEquals("t1", params(OrchMethods.TASK_DELETE).getValue("id").jsonPrimitive.content)

        assertTrue(params(OrchMethods.WORKTREE_LIST).isEmpty())
        assertFalse("if_revision" in params(OrchMethods.WORKTREE_LIST))
        assertEquals("feature/x", params(OrchMethods.WORKTREE_CREATE).getValue("branch").jsonPrimitive.content)
        assertEquals(setOf("branch", "if_revision"), params(OrchMethods.WORKTREE_CREATE).keys)
        assertEquals("/tmp/wt", params(OrchMethods.WORKTREE_OPEN).getValue("path").jsonPrimitive.content)
        assertEquals("/tmp/wt", params(OrchMethods.WORKTREE_REMOVE).getValue("path").jsonPrimitive.content)

        val acquire = params(OrchMethods.LEASE_ACQUIRE)
        assertEquals("t1", acquire.getValue("task").jsonPrimitive.content)
        assertEquals(listOf("src/**"), acquire.getValue("paths").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("7", acquire.getValue("pane").jsonPrimitive.content)
        assertEquals(setOf("task", "paths", "pane", "if_revision"), acquire.keys)
        assertTrue(params(OrchMethods.LEASE_LIST).isEmpty())
        assertFalse("if_revision" in params(OrchMethods.LEASE_LIST))
        assertEquals("L2", params(OrchMethods.LEASE_RELEASE).getValue("id").jsonPrimitive.content)

        val create = params(OrchMethods.AUTOMATION_CREATE)
        assertEquals("Morning review", create.getValue("name").jsonPrimitive.content)
        assertEquals("true", create.getValue("enabled").jsonPrimitive.content)
        assertEquals("weekly", create.getValue("trigger").jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals("Asia/Makassar", create.getValue("trigger").jsonObject.getValue("timezone").jsonPrimitive.content)
        assertEquals(
            listOf("1", "2", "3", "4", "5"),
            create.getValue("trigger").jsonObject.getValue("weekdays").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("28800", create.getValue("trigger").jsonObject.getValue("second_of_day").jsonPrimitive.content)
        assertEquals("new_worker", create.getValue("target").jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals(setOf("kind"), create.getValue("target").jsonObject.keys)
        val createdTask = create.getValue("task").jsonObject
        assertEquals("Review changes", createdTask.getValue("title").jsonPrimitive.content)
        assertEquals("Review the workspace and report risks.", createdTask.getValue("prompt").jsonPrimitive.content)
        assertEquals("codex", createdTask.getValue("agent_id").jsonPrimitive.content)
        assertEquals("workspace_example", createdTask.getValue("workspace_id").jsonPrimitive.content)
        assertEquals("workspace", createdTask.getValue("mode").jsonPrimitive.content)
        assertEquals("workspace", createdTask.getValue("access").jsonPrimitive.content)
        assertEquals("morning-review-v1", create.getValue("idempotency_key").jsonPrimitive.content)
        assertEquals("9", create.getValue("if_revision").jsonPrimitive.content)
        assertEquals(
            setOf("name", "enabled", "trigger", "target", "task", "policy", "idempotency_key", "if_revision"),
            create.keys,
        )

        assertTrue(params(OrchMethods.AUTOMATION_LIST).isEmpty())
        assertFalse("if_revision" in params(OrchMethods.AUTOMATION_LIST))
        assertEquals("a1", params(OrchMethods.AUTOMATION_GET).getValue("id").jsonPrimitive.content)
        assertEquals(setOf("id"), params(OrchMethods.AUTOMATION_GET).keys)
        assertFalse("if_revision" in params(OrchMethods.AUTOMATION_GET))

        val updated = params(OrchMethods.AUTOMATION_UPDATE)
        assertEquals("a1", updated.getValue("id").jsonPrimitive.content)
        assertEquals("false", updated.getValue("enabled").jsonPrimitive.content)
        assertEquals("active_agent", updated.getValue("target").jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals("7", updated.getValue("target").jsonObject.getValue("pane_id").jsonPrimitive.content)
        assertEquals(
            "0123456789abcdef0123456789abcdef",
            updated.getValue("target").jsonObject.getValue("terminal_id").jsonPrimitive.content,
        )
        assertEquals("skip", updated.getValue("target").jsonObject.getValue("if_busy").jsonPrimitive.content)
        assertFalse("idempotency_key" in updated)
        assertFalse("binding" in updated.getValue("target").jsonObject)
        assertEquals(
            setOf("id", "name", "enabled", "trigger", "target", "task", "policy", "if_revision"),
            updated.keys,
        )

        assertEquals("a1", params(OrchMethods.AUTOMATION_ENABLE).getValue("id").jsonPrimitive.content)
        assertEquals("a1", params(OrchMethods.AUTOMATION_DISABLE).getValue("id").jsonPrimitive.content)
        val rebind = params(OrchMethods.AUTOMATION_REBIND)
        assertEquals("a1", rebind.getValue("id").jsonPrimitive.content)
        assertEquals("9", rebind.getValue("pane").jsonPrimitive.content)
        assertEquals(
            "0123456789abcdef0123456789abcdef",
            rebind.getValue("terminal_id").jsonPrimitive.content,
        )
        assertEquals(setOf("id", "pane", "terminal_id", "if_revision"), rebind.keys)
        assertEquals("a1", params(OrchMethods.AUTOMATION_DELETE).getValue("id").jsonPrimitive.content)

        val run = params(OrchMethods.AUTOMATION_RUN)
        assertEquals("a1", run.getValue("id").jsonPrimitive.content)
        assertEquals("run-1", run.getValue("idempotency_key").jsonPrimitive.content)
        assertEquals(setOf("id", "idempotency_key", "if_revision"), run.keys)

        val history = params(OrchMethods.AUTOMATION_HISTORY)
        assertEquals("a1", history.getValue("id").jsonPrimitive.content)
        assertEquals("50", history.getValue("limit").jsonPrimitive.content)
        assertFalse("if_revision" in history)
        val preview = params(OrchMethods.AUTOMATION_PREVIEW)
        assertEquals("weekly", preview.getValue("trigger").jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals("1788300000", preview.getValue("from_utc").jsonPrimitive.content)
        assertFalse("if_revision" in preview)
        assertTrue(params(OrchMethods.AUTOMATION_HEALTH).isEmpty())
        assertFalse("if_revision" in params(OrchMethods.AUTOMATION_HEALTH))

        session.close()
    }

    @Test
    fun omitsOptionalMutationKeysWhenUnset() = runTest {
        val seen = mutableListOf<UhpRequest>()
        val session = openOrch(backgroundScope) { seen += it }

        session.claimTask("t1")
        session.updateTask("t1", clearPrompt = true)
        session.acquireLease("t1", listOf("src/**"))
        session.createAutomation(name = "once", trigger = ONCE_TRIGGER, task = REVIEW_TASK)
        session.runAutomation("a1")
        session.listAutomationHistory()
        session.previewAutomation(ONCE_TRIGGER)

        assertEquals(setOf("id"), seen.single { it.method == OrchMethods.TASK_CLAIM }.params.keys)
        val update = seen.single { it.method == OrchMethods.TASK_UPDATE }.params
        assertEquals(JsonNull, update.getValue("prompt"))
        assertEquals(setOf("id", "prompt"), update.keys)
        assertEquals(setOf("task", "paths"), seen.single { it.method == OrchMethods.LEASE_ACQUIRE }.params.keys)
        val create = seen.single { it.method == OrchMethods.AUTOMATION_CREATE }.params
        assertEquals(setOf("name", "trigger", "task"), create.keys)
        assertFalse("idempotency_key" in create)
        assertEquals(setOf("id"), seen.single { it.method == OrchMethods.AUTOMATION_RUN }.params.keys)
        assertTrue(seen.single { it.method == OrchMethods.AUTOMATION_HISTORY }.params.isEmpty())
        assertEquals(setOf("trigger"), seen.single { it.method == OrchMethods.AUTOMATION_PREVIEW }.params.keys)
        session.close()
    }

    @Test
    fun supportsUsesAdvertisedMethods() = runTest {
        val session = openOrch(backgroundScope) { }
        assertTrue(session.supports(OrchMethods.TASK_CLAIM))
        assertTrue(session.supports(OrchMethods.WORKTREE_LIST))
        assertTrue(session.supports(OrchMethods.LEASE_ACQUIRE))
        assertTrue(session.supports(OrchMethods.AUTOMATION_CREATE))
        assertFalse(session.supports("task.next"))
        session.close()
    }

    @Test
    fun mapsInlineResultFixtures() = runTest {
        val session = openOrch(backgroundScope) { }

        val claimed = (session.claimTask("t1") as Outcome.Ok).value.task
        assertEquals("t1", claimed.id)
        assertEquals(TaskStatus.Claimed, claimed.status)
        assertEquals(7L, claimed.assignee)

        val released = (session.releaseTask("t1") as Outcome.Ok).value
        assertEquals(TaskStatus.Queued, released.task.status)
        assertEquals(listOf("L2"), released.releasedLeases)

        val merged = (session.mergeTask("t1") as Outcome.Ok).value
        assertEquals("merged", merged.outcome)
        assertEquals("t1", merged.task)
        assertEquals("luvus/t1", merged.branch)
        assertEquals("luvus/integration", merged.into)
        assertEquals("abc123def456789", merged.commit)

        val worktrees = (session.listWorktrees() as Outcome.Ok).value
        assertEquals("/tmp/repo", worktrees.first().path)
        assertEquals("main", worktrees.first().branch)
        assertTrue(worktrees.first().main)
        assertEquals("feature/x", worktrees.last().branch)
        assertFalse(worktrees.last().main)
        assertEquals("/tmp/wt", (session.createWorktree("feature/x") as Outcome.Ok).value)

        val lease = (session.acquireLease("t1", listOf("src/**")) as Outcome.Ok).value
        assertEquals("L2", lease.id)
        assertEquals("7", lease.pane)
        assertEquals("t1", lease.task)
        assertEquals(listOf("src/**"), lease.paths)
        assertEquals(1788146509L, lease.acquired)
        assertEquals("L2", (session.listLeases() as Outcome.Ok).value.single().id)

        val automation = (session.getAutomation("a1") as Outcome.Ok).value
        assertEquals("a1", automation.id)
        assertEquals("Morning review", automation.name)
        assertTrue(automation.enabled)
        val weekly = automation.trigger as AutomationTrigger.Weekly
        assertEquals("Asia/Makassar", weekly.timezone)
        assertEquals(listOf(1, 2, 3, 4, 5), weekly.weekdays)
        assertEquals(28800, weekly.secondOfDay)
        assertEquals(AutomationTarget.NewWorker, automation.target)
        assertEquals("codex", automation.task?.agentId)
        assertEquals("workspace", automation.task?.mode)
        assertEquals("run_latest", automation.policy?.misfire)
        assertEquals(1788393600L, automation.nextRunAt)

        val listed = (session.listAutomations() as Outcome.Ok).value
        assertEquals("a1", listed.single().id)

        val run = (session.runAutomation("a1") as Outcome.Ok).value
        assertEquals("r1", run.id)
        assertEquals("a1", run.automationId)
        assertEquals("running", run.status)
        assertEquals("t9", run.taskId)
        assertEquals(1L, run.attempt)
        assertNull(run.error)

        val history = (session.listAutomationHistory("a1") as Outcome.Ok).value
        assertEquals("r1", history.single().id)

        val preview = (session.previewAutomation(WEEKLY_TRIGGER) as Outcome.Ok).value
        assertEquals(
            listOf(1788393600L, 1788480000L, 1788566400L, 1788652800L, 1788739200L),
            preview.occurrencesUtc,
        )

        val health = (session.automationHealth() as Outcome.Ok).value
        assertEquals(1L, health.summary.definitions)
        assertEquals(1L, health.summary.enabled)
        assertEquals(1788393600L, health.summary.nextRunAt)
        assertEquals("scheduled", health.automations.single().state)
        assertEquals("codex", health.automations.single().agentId)

        session.close()
    }

    @Test
    fun contractMethodsAreCallable() = runTest {
        val session = openOrch(backgroundScope) { }
        assertIs<Outcome.Ok<*>>(session.claimTask("t1"))
        assertIs<Outcome.Ok<*>>(session.updateTask("t1", note = "n"))
        assertIs<Outcome.Ok<*>>(session.mergeTask("t1"))
        assertIs<Outcome.Ok<*>>(session.releaseTask("t1"))
        assertIs<Outcome.Ok<*>>(session.deleteTask("t1"))
        assertIs<Outcome.Ok<*>>(session.listWorktrees())
        assertIs<Outcome.Ok<*>>(session.createWorktree("feature/x"))
        assertIs<Outcome.Ok<*>>(session.openWorktree("/tmp/wt"))
        assertIs<Outcome.Ok<*>>(session.removeWorktree("/tmp/wt"))
        assertIs<Outcome.Ok<*>>(session.acquireLease("t1", listOf("src/**")))
        assertIs<Outcome.Ok<*>>(session.listLeases())
        assertIs<Outcome.Ok<*>>(session.releaseLease("L2"))
        assertIs<Outcome.Ok<*>>(session.createAutomation("once", ONCE_TRIGGER, REVIEW_TASK))
        assertIs<Outcome.Ok<*>>(session.listAutomations())
        assertIs<Outcome.Ok<*>>(session.getAutomation("a1"))
        assertIs<Outcome.Ok<*>>(session.updateAutomation("a1", "once", ONCE_TRIGGER, REVIEW_TASK))
        assertIs<Outcome.Ok<*>>(session.enableAutomation("a1"))
        assertIs<Outcome.Ok<*>>(session.disableAutomation("a1"))
        assertIs<Outcome.Ok<*>>(session.rebindAutomation("a1", pane = "9"))
        assertIs<Outcome.Ok<*>>(session.deleteAutomation("a1"))
        assertIs<Outcome.Ok<*>>(session.runAutomation("a1"))
        assertIs<Outcome.Ok<*>>(session.listAutomationHistory())
        assertIs<Outcome.Ok<*>>(session.previewAutomation(ONCE_TRIGGER))
        assertIs<Outcome.Ok<*>>(session.automationHealth())
        session.close()
    }
}

private val WEEKLY_TRIGGER =
    AutomationTrigger.Weekly(
        timezone = "Asia/Makassar",
        weekdays = listOf(1, 2, 3, 4, 5),
        secondOfDay = 28800,
    )

private val ONCE_TRIGGER = AutomationTrigger.Once(atUtc = 2000000000)

private val REVIEW_TASK =
    AutomationTaskSpec(
        title = "Review changes",
        prompt = "Review the workspace and report risks.",
        agentId = "codex",
        workspaceId = "workspace_example",
        mode = "workspace",
        access = "workspace",
    )

private val REVIEW_POLICY =
    AutomationPolicySpec(
        misfire = "run_latest",
        overlap = "skip",
        misfireGraceSeconds = 3600,
    )

private const val TASK_CLAIMED: String =
    """{"type":"task","task":{"id":"t1","title":"wire","status":"claimed","assignee":7,"deps":[],"paths":["src/**"],"gate":null,"outputs":[],"notes":[],"worktree":null,"branch":null,"context":null,"created":1,"updated":2}}"""

private const val TASK_RELEASED: String =
    """{"type":"task","task":{"id":"t1","title":"wire","status":"queued","assignee":null,"deps":[],"paths":["src/**"],"gate":null,"outputs":[],"notes":[],"worktree":null,"branch":null,"context":null,"created":1,"updated":3},"released_leases":["L2"]}"""

private const val TASK_MERGED: String =
    """{"type":"merge","outcome":"merged","task":"t1","branch":"luvus/t1","into":"luvus/integration","commit":"abc123def456789","revision":12}"""

private const val WORKTREE_LIST: String =
    """{"type":"worktree_list","worktrees":[{"path":"/tmp/repo","branch":"main","head":"abc1234","main":true},{"path":"/tmp/wt","branch":"feature/x","head":"def5678","main":false}]}"""

private const val WORKTREE_CREATED: String =
    """{"type":"ok","path":"/tmp/wt"}"""

private const val LEASE: String =
    """{"type":"lease","lease":{"id":"L2","pane":7,"task":"t1","paths":["src/**"],"acquired":1788146509}}"""

private const val LEASE_LIST: String =
    """{"type":"lease_list","leases":[{"id":"L2","pane":7,"task":"t1","paths":["src/**"],"acquired":1788146509}]}"""

private const val AUTOMATION: String =
    """{"type":"automation","automation":{"id":"a1","name":"Morning review","enabled":true,"trigger":{"kind":"weekly","timezone":"Asia/Makassar","weekdays":[1,2,3,4,5],"second_of_day":28800},"target":{"kind":"new_worker"},"target_state":null,"task":{"title":"Review changes","prompt":"Review the workspace and report risks.","agent_id":"codex","workspace_id":"workspace_example","mode":"workspace","access":"workspace","paths":[],"gate":null},"policy":{"misfire":"run_latest","overlap":"skip","misfire_grace_seconds":3600},"next_run_at":1788393600,"created_at":1788300000,"updated_at":1788300000}}"""

private const val AUTOMATION_LIST: String =
    """{"type":"automation_list","automations":[{"id":"a1","name":"Morning review","enabled":true,"trigger":{"kind":"weekly","timezone":"Asia/Makassar","weekdays":[1,2,3,4,5],"second_of_day":28800},"target":{"kind":"new_worker"},"task":{"title":"Review changes","prompt":"Review the workspace and report risks.","agent_id":"codex","workspace_id":"workspace_example","mode":"workspace","access":"workspace"},"policy":{"misfire":"run_latest","overlap":"skip","misfire_grace_seconds":3600},"next_run_at":1788393600,"created_at":1788300000,"updated_at":1788300000}]}"""

private const val AUTOMATION_RUN: String =
    """{"type":"automation_run","run":{"id":"r1","automation_id":"a1","scheduled_at":1788393600,"created_at":1788393600,"started_at":1788393601,"finished_at":null,"task_id":"t9","status":"running","attempt":1,"error":null,"trigger":{"kind":"weekly","timezone":"Asia/Makassar","weekdays":[1,2,3,4,5],"second_of_day":28800},"policy":{"misfire":"run_latest","overlap":"skip","misfire_grace_seconds":3600},"target":{"kind":"new_worker"},"task":{"title":"Review changes","prompt":"Review the workspace and report risks.","agent_id":"codex","workspace_id":"workspace_example","mode":"workspace","access":"workspace"}}}"""

private const val AUTOMATION_HISTORY: String =
    """{"type":"automation_history","runs":[{"id":"r1","automation_id":"a1","scheduled_at":1788393600,"created_at":1788393600,"started_at":1788393601,"finished_at":null,"task_id":"t9","status":"running","attempt":1,"error":null}]}"""

private const val AUTOMATION_PREVIEW: String =
    """{"type":"automation_preview","occurrences_utc":[1788393600,1788480000,1788566400,1788652800,1788739200]}"""

private const val AUTOMATION_HEALTH: String =
    """{"type":"automation_health","summary":{"definitions":1,"enabled":1,"scheduled":1,"running":0,"review":0,"failed":0,"next_run_at":1788393600},"automations":[{"id":"a1","name":"Morning review","state":"scheduled","next_run_at":1788393600,"current_run_id":null,"latest_run_id":null,"latest_status":null,"latest_error":null,"agent_id":"codex","workspace_id":"workspace_example"}]}"""

private suspend fun openOrch(
    scope: kotlinx.coroutines.CoroutineScope,
    onRequest: (UhpRequest) -> Unit,
): LuviaSession {
    val factory =
        ScriptedFactory(scope) { framer ->
            dispatchOrch(framer, onRequest)
        }
    return (LuviaClient(factory).open("default") as Outcome.Ok).value
}

private suspend fun dispatchOrch(
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
            respondOrch(framer, request)
        }
    }
}

private suspend fun respondOrch(framer: NdjsonFramer, request: UhpRequest) {
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
                    put("methods", stringArray(ORCH_METHODS))
                    put("event_sequence", 10)
                }
            OrchMethods.TASK_CLAIM, OrchMethods.TASK_UPDATE, OrchMethods.TASK_DELETE -> parseObject(TASK_CLAIMED)
            OrchMethods.TASK_RELEASE -> parseObject(TASK_RELEASED)
            OrchMethods.TASK_MERGE -> parseObject(TASK_MERGED)
            OrchMethods.WORKTREE_LIST -> parseObject(WORKTREE_LIST)
            OrchMethods.WORKTREE_CREATE -> parseObject(WORKTREE_CREATED)
            OrchMethods.LEASE_ACQUIRE -> parseObject(LEASE)
            OrchMethods.LEASE_LIST -> parseObject(LEASE_LIST)
            OrchMethods.AUTOMATION_CREATE,
            OrchMethods.AUTOMATION_GET,
            OrchMethods.AUTOMATION_UPDATE,
            OrchMethods.AUTOMATION_ENABLE,
            OrchMethods.AUTOMATION_DISABLE,
            OrchMethods.AUTOMATION_REBIND,
            OrchMethods.AUTOMATION_DELETE,
            -> parseObject(AUTOMATION)
            OrchMethods.AUTOMATION_LIST -> parseObject(AUTOMATION_LIST)
            OrchMethods.AUTOMATION_RUN -> parseObject(AUTOMATION_RUN)
            OrchMethods.AUTOMATION_HISTORY -> parseObject(AUTOMATION_HISTORY)
            OrchMethods.AUTOMATION_PREVIEW -> parseObject(AUTOMATION_PREVIEW)
            OrchMethods.AUTOMATION_HEALTH -> parseObject(AUTOMATION_HEALTH)
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

private val ORCH_METHODS =
    listOf(
        Methods.CAPABILITIES,
        OrchMethods.TASK_CLAIM,
        OrchMethods.TASK_UPDATE,
        OrchMethods.TASK_MERGE,
        OrchMethods.TASK_RELEASE,
        OrchMethods.TASK_DELETE,
        OrchMethods.WORKTREE_LIST,
        OrchMethods.WORKTREE_CREATE,
        OrchMethods.WORKTREE_OPEN,
        OrchMethods.WORKTREE_REMOVE,
        OrchMethods.LEASE_ACQUIRE,
        OrchMethods.LEASE_LIST,
        OrchMethods.LEASE_RELEASE,
        OrchMethods.AUTOMATION_CREATE,
        OrchMethods.AUTOMATION_LIST,
        OrchMethods.AUTOMATION_GET,
        OrchMethods.AUTOMATION_UPDATE,
        OrchMethods.AUTOMATION_ENABLE,
        OrchMethods.AUTOMATION_DISABLE,
        OrchMethods.AUTOMATION_REBIND,
        OrchMethods.AUTOMATION_DELETE,
        OrchMethods.AUTOMATION_RUN,
        OrchMethods.AUTOMATION_HISTORY,
        OrchMethods.AUTOMATION_PREVIEW,
        OrchMethods.AUTOMATION_HEALTH,
    )
