package tech.asahiart.luvia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.time.Duration.Companion.seconds

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray

import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import tech.asahiart.luvia.internal.Methods
import tech.asahiart.luvia.internal.NdjsonFramer
import tech.asahiart.luvia.internal.UhpEvent
import tech.asahiart.luvia.internal.UhpRequest
import tech.asahiart.luvia.internal.decodeUhpRequest
import tech.asahiart.luvia.internal.parseBusEvent
import tech.asahiart.luvia.internal.parseObject
import tech.asahiart.luvia.support.ScriptedFactory

@OptIn(ExperimentalCoroutinesApi::class)
class NextSurfaceTest {
    @Test
    fun taskStatusIsRetryableOnlyForFailed() {
        val expected =
            mapOf(
                TaskStatus.Queued to false,
                TaskStatus.Claimed to false,
                TaskStatus.Running to false,
                TaskStatus.Blocked to false,
                TaskStatus.Review to false,
                TaskStatus.Done to false,
                TaskStatus.Merging to false,
                TaskStatus.Merged to false,
                TaskStatus.Failed to true,
                TaskStatus.Unknown to false,
            )
        for (status in TaskStatus.entries) {
            assertEquals(expected[status], status.isRetryable, status.name)
        }
    }

    @Test
    fun capabilitiesFlagTaskRetryAndPush() = runTest {
        val session = openNext(backgroundScope) { }
        val caps = session.toCapabilities()
        assertTrue(caps.taskRetry)
        assertTrue(caps.push)
        assertTrue(session.supports(UhpMethods.TASK_RETRY))
        assertTrue(session.supports(UhpMethods.PUSH_REGISTER))
        session.close()
    }

    @Test
    fun retryTaskSendsIdAndWorkspace() = runTest {
        val seen = mutableListOf<UhpRequest>()
        val session = openNext(backgroundScope) { seen += it }
        val result = session.retryTask("t1", workspaceId = "ws_1")
        assertIs<Outcome.Ok<*>>(result)
        val params = seen.single { it.method == UhpMethods.TASK_RETRY }.params
        assertEquals("t1", params.getValue("id").jsonPrimitive.content)
        assertEquals("ws_1", params.getValue("workspace_id").jsonPrimitive.content)
        assertEquals(setOf("id", "workspace_id"), params.keys)
        session.close()
    }

    @Test
    fun registerPushSendsKindTokenAndEnvironment() = runTest {
        val seen = mutableListOf<UhpRequest>()
        val session = openNext(backgroundScope) { seen += it }
        val registered =
            session.registerPush(
                PushRegistration(kind = "apns", token = "deadbeef", environment = "sandbox"),
            )
        assertIs<Outcome.Ok<*>>(registered)
        val params = seen.single { it.method == UhpMethods.PUSH_REGISTER }.params
        assertEquals("apns", params.getValue("kind").jsonPrimitive.content)
        assertEquals("deadbeef", params.getValue("token").jsonPrimitive.content)
        assertEquals("sandbox", params.getValue("environment").jsonPrimitive.content)
        val unregistered = session.unregisterPush()
        assertIs<Outcome.Ok<*>>(unregistered)
        assertTrue(seen.single { it.method == UhpMethods.PUSH_UNREGISTER }.params.isEmpty())
        session.close()
    }

    @Test
    fun createAutomationDraftMapsTriggerTargetPolicyAndIdempotency() = runTest {
        val seen = mutableListOf<UhpRequest>()
        val session = openNext(backgroundScope) { seen += it }
        session.createAutomation(
            name = "Morning review",
            enabled = true,
            trigger = WEEKLY,
            target = AutomationTarget.NewWorker,
            task = TASK,
            policy = POLICY,
            idempotencyKey = "k-morning",
        )
        val create = seen.single { it.method == UhpMethods.AUTOMATION_CREATE }.params
        assertEquals("Morning review", create.getValue("name").jsonPrimitive.content)
        assertEquals("true", create.getValue("enabled").jsonPrimitive.content)
        val trigger = create.getValue("trigger").jsonObject
        assertEquals("weekly", trigger.getValue("kind").jsonPrimitive.content)
        assertEquals("Asia/Tokyo", trigger.getValue("timezone").jsonPrimitive.content)
        assertEquals(
            listOf("1", "3", "5"),
            trigger.getValue("weekdays").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("new_worker", create.getValue("target").jsonObject.getValue("kind").jsonPrimitive.content)
        val task = create.getValue("task").jsonObject
        assertEquals("Review", task.getValue("title").jsonPrimitive.content)
        assertEquals("codex", task.getValue("agent_id").jsonPrimitive.content)
        assertEquals("ws_1", task.getValue("workspace_id").jsonPrimitive.content)
        val policy = create.getValue("policy").jsonObject
        assertEquals("skip", policy.getValue("misfire").jsonPrimitive.content)
        assertEquals("queue", policy.getValue("overlap").jsonPrimitive.content)
        assertEquals("k-morning", create.getValue("idempotency_key").jsonPrimitive.content)
        session.close()
    }

    @Test
    fun createAutomationDraftMapsIntervalDailyOnceAndActiveAgent() = runTest {
        val seen = mutableListOf<UhpRequest>()
        val session = openNext(backgroundScope) { seen += it }
        session.createAutomation(name = "once", trigger = AutomationTrigger.Once(atUtc = 100), task = TASK)
        session.createAutomation(
            name = "interval",
            trigger = AutomationTrigger.Interval(everySeconds = 1800, anchorUtc = 50),
            task = TASK,
        )
        session.createAutomation(
            name = "daily",
            trigger = AutomationTrigger.Daily(timezone = "UTC", secondOfDay = 3600),
            target =
                AutomationTarget.ActiveAgent(
                    paneId = "7",
                    terminalId = "term",
                    ifBusy = "wait",
                ),
            task = TASK,
        )
        val creates = seen.filter { it.method == UhpMethods.AUTOMATION_CREATE }
        assertEquals(3, creates.size)
        assertEquals("once", creates[0].params.getValue("trigger").jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals("100", creates[0].params.getValue("trigger").jsonObject.getValue("at_utc").jsonPrimitive.content)
        assertEquals(
            "interval",
            creates[1].params.getValue("trigger").jsonObject.getValue("kind").jsonPrimitive.content,
        )
        assertEquals(
            "1800",
            creates[1].params.getValue("trigger").jsonObject.getValue("every_seconds").jsonPrimitive.content,
        )
        val daily = creates[2].params
        assertEquals("daily", daily.getValue("trigger").jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals("active_agent", daily.getValue("target").jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals("7", daily.getValue("target").jsonObject.getValue("pane_id").jsonPrimitive.content)
        assertEquals("wait", daily.getValue("target").jsonObject.getValue("if_busy").jsonPrimitive.content)
        session.close()
    }

    @Test
    fun hostUhpCreateAutomationAddsIdempotencyKey() = runTest {
        val seen = mutableListOf<UhpRequest>()
        val session = openNext(backgroundScope, loop = true) { seen += it }
        assertTrue(session.supports(UhpMethods.AUTOMATION_CREATE))
        val runtime = sampleRuntime()
        val uhp = HostUhp(session = { session }, runtime = { runtime }, scope = backgroundScope)
        uhp.applyRuntime(runtime)
        assertTrue(uhp.state.value.canMutate)
        uhp.createAutomation(
            AutomationDraft(
                name = "Morning review",
                trigger = WEEKLY,
                task = TASK,
                policy = POLICY,
            ),
        )
        val create =
            kotlinx.coroutines.withTimeout(2.seconds) {
                while (seen.none { it.method == UhpMethods.AUTOMATION_CREATE }) {
                    kotlinx.coroutines.yield()
                }
                seen.single { it.method == UhpMethods.AUTOMATION_CREATE }.params
            }
        val key = create.getValue("idempotency_key").jsonPrimitive.content
        assertTrue(key.isNotEmpty())
        assertTrue(key.length <= 128)
        session.close()
        uhp.close()
    }

    @Test
    fun automationBusEventReloadsBoardWhenSectionShowing() = runTest {
        val seen = mutableListOf<UhpRequest>()
        val session = openNext(backgroundScope, loop = true) { seen += it }
        assertTrue(session.supports(UhpMethods.AUTOMATION_LIST))
        val runtime = sampleRuntime()
        val uhp = HostUhp(session = { session }, runtime = { runtime }, scope = backgroundScope)
        uhp.applyRuntime(runtime)
        uhp.setSection(HostSection.Automations)
        uhp.show(HostSection.Automations)
        kotlinx.coroutines.withTimeout(2.seconds) {
            while (seen.none { it.method == UhpMethods.AUTOMATION_LIST }) {
                kotlinx.coroutines.yield()
            }
        }
        val listsBefore = seen.count { it.method == UhpMethods.AUTOMATION_LIST }
        assertTrue(listsBefore >= 1)
        uhp.applyBusEvent(
            parseBusEvent(UhpEvent("automation.enabled", 3, buildJsonObject { put("id", "a1") })),
        )
        kotlinx.coroutines.withTimeout(2.seconds) {
            while (seen.count { it.method == UhpMethods.AUTOMATION_LIST } <= listsBefore) {
                kotlinx.coroutines.yield()
            }
        }
        session.close()
        uhp.close()
    }

    @Test
    fun automationRunEventRefreshesLoadedHistory() = runTest {
        val seen = mutableListOf<UhpRequest>()
        val session = openNext(backgroundScope, loop = true) { seen += it }
        val runtime = sampleRuntime()
        val uhp = HostUhp(session = { session }, runtime = { runtime }, scope = backgroundScope)
        uhp.applyRuntime(runtime)
        uhp.setSection(HostSection.Automations)
        uhp.loadAutomationHistory("a1")
        kotlinx.coroutines.withTimeout(2.seconds) {
            while (seen.none { it.method == UhpMethods.AUTOMATION_HISTORY }) {
                kotlinx.coroutines.yield()
            }
        }
        val historyBefore = seen.count { it.method == UhpMethods.AUTOMATION_HISTORY }
        assertEquals(1, historyBefore)
        uhp.applyBusEvent(
            parseBusEvent(
                UhpEvent("automation.run_finished", 4, buildJsonObject { put("automation_id", "a1") }),
            ),
        )
        kotlinx.coroutines.withTimeout(2.seconds) {
            while (seen.count { it.method == UhpMethods.AUTOMATION_HISTORY } <= historyBefore) {
                kotlinx.coroutines.yield()
            }
        }
        session.close()
        uhp.close()
    }

}

private val WEEKLY =
    AutomationTrigger.Weekly(timezone = "Asia/Tokyo", weekdays = listOf(1, 3, 5), secondOfDay = 32400)

private val TASK =
    AutomationTaskSpec(
        title = "Review",
        prompt = "Look it over.",
        agentId = "codex",
        workspaceId = "ws_1",
    )

private val POLICY =
    AutomationPolicySpec(misfire = "skip", overlap = "queue", misfireGraceSeconds = 60)

private fun sampleRuntime(): HostRuntime {
    val agent =
        AgentSummary(
            paneId = "7",
            name = "opus",
            status = AgentStatus.Idle,
            focused = true,
            workspaceId = "ws_1",
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
        backend = "luvus",
    )
}

private suspend fun openNext(
    scope: kotlinx.coroutines.CoroutineScope,
    loop: Boolean = false,
    onRequest: (UhpRequest) -> Unit,
): LuviaSession {
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
                        if (loop) {
                            while (true) {
                                val request = decodeUhpRequest(framer.readFrame())
                                onRequest(request)
                                respondNext(framer, request)
                            }
                        } else {
                            val request = decodeUhpRequest(framer.readFrame())
                            onRequest(request)
                            respondNext(framer, request)
                        }
                    } catch (_: tech.asahiart.luvia.internal.FrameException) {
                    }
                }
            }
        }
    return (LuviaClient(factory).open("default") as Outcome.Ok).value
}

private suspend fun respondNext(framer: NdjsonFramer, request: UhpRequest) {
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
                    put("methods", stringArray(NEXT_METHODS))
                    put("event_sequence", 10)
                }
            UhpMethods.TASK_RETRY ->
                parseObject(
                    """{"type":"task","task":{"id":"t1","title":"wire","status":"queued","assignee":null,"deps":[],"paths":[],"gate":null,"outputs":[],"notes":[],"worktree":null,"branch":null,"context":null,"created":1,"updated":2},"revision":3}""",
                )
            UhpMethods.PUSH_REGISTER -> buildJsonObject { put("registered", true) }
            UhpMethods.PUSH_UNREGISTER -> buildJsonObject { put("registered", false) }
            UhpMethods.AUTOMATION_CREATE,
            UhpMethods.AUTOMATION_UPDATE,
            UhpMethods.AUTOMATION_DELETE,
            UhpMethods.AUTOMATION_ENABLE,
            UhpMethods.AUTOMATION_DISABLE,
            UhpMethods.AUTOMATION_REBIND,
            -> parseObject(AUTOMATION)
            UhpMethods.AUTOMATION_LIST -> parseObject(AUTOMATION_LIST)
            UhpMethods.AUTOMATION_RUN -> parseObject(AUTOMATION_RUN)
            UhpMethods.AUTOMATION_HISTORY -> parseObject(AUTOMATION_HISTORY)
            UhpMethods.AUTOMATION_PREVIEW -> parseObject(AUTOMATION_PREVIEW)
            UhpMethods.AUTOMATION_HEALTH -> parseObject(AUTOMATION_HEALTH)
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

private val NEXT_METHODS =
    listOf(
        Methods.CAPABILITIES,
        UhpMethods.TASK_RETRY,
        UhpMethods.PUSH_REGISTER,
        UhpMethods.PUSH_UNREGISTER,
        UhpMethods.AUTOMATION_LIST,
        UhpMethods.AUTOMATION_CREATE,
        UhpMethods.AUTOMATION_UPDATE,
        UhpMethods.AUTOMATION_DELETE,
        UhpMethods.AUTOMATION_REBIND,
        UhpMethods.AUTOMATION_ENABLE,
        UhpMethods.AUTOMATION_DISABLE,
        UhpMethods.AUTOMATION_RUN,
        UhpMethods.AUTOMATION_HISTORY,
        UhpMethods.AUTOMATION_PREVIEW,
        UhpMethods.AUTOMATION_HEALTH,
    )

private const val AUTOMATION: String =
    """{"type":"automation","automation":{"id":"a1","name":"Morning review","enabled":true}}"""

private const val AUTOMATION_LIST: String =
    """{"type":"automation_list","automations":[{"id":"a1","name":"Morning review","enabled":true}]}"""

private const val AUTOMATION_RUN: String =
    """{"type":"automation_run","run":{"id":"r1","automation_id":"a1","status":"running"}}"""

private const val AUTOMATION_HISTORY: String =
    """{"type":"automation_history","runs":[{"id":"r1","automation_id":"a1","status":"succeeded"}]}"""

private const val AUTOMATION_PREVIEW: String =
    """{"type":"automation_preview","occurrences_utc":[1,2,3]}"""

private const val AUTOMATION_HEALTH: String =
    """{"type":"automation_health","summary":{"definitions":1,"enabled":1,"scheduled":1,"running":0,"review":0,"failed":0},"automations":[]}"""
