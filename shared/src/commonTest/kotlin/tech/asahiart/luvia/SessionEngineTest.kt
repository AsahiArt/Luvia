package tech.asahiart.luvia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.asahiart.luvia.internal.LiveUpdate
import tech.asahiart.luvia.internal.Methods
import tech.asahiart.luvia.internal.NdjsonFramer
import tech.asahiart.luvia.internal.UhpRequest
import tech.asahiart.luvia.internal.decodeUhpRequest
import tech.asahiart.luvia.internal.parseObject
import tech.asahiart.luvia.support.CopiedFixtures
import tech.asahiart.luvia.support.ScriptedFactory

class SessionEngineTest {
    @Test
    fun unaryCallsReuseOneBridgeChannel() = runTest {
        val recorded = mutableListOf<String>()
        val factory =
            ScriptedFactory(backgroundScope) { framer ->
                loopDispatch(framer, recorded)
            }
        val session = (LuviaClient(factory).open("default") as Outcome.Ok).value
        assertIsOk(session.snapshot())
        assertIsOk(session.snapshot())
        assertEquals(1, factory.opens)
        assertEquals(
            listOf(Methods.CAPABILITIES, Methods.SNAPSHOT, Methods.SNAPSHOT),
            recorded,
        )
        session.close()
    }

    @Test
    fun unaryChannelReopensAfterEof() = runTest {
        val recorded = mutableListOf<String>()
        val factory =
            ScriptedFactory(backgroundScope) { framer ->
                loopDispatch(framer, recorded, closeAfter = 2)
            }
        val session = (LuviaClient(factory).open("default") as Outcome.Ok).value
        assertIsOk(session.snapshot())
        assertEquals(1, factory.opens)
        assertIsOk(session.snapshot())
        assertEquals(2, factory.opens)
        assertEquals(
            listOf(Methods.CAPABILITIES, Methods.SNAPSHOT, Methods.SNAPSHOT),
            recorded,
        )
        session.close()
    }

    @Test
    fun writtenMutationIsNotRetriedWhenChannelDies() = runTest {
        val recorded = mutableListOf<String>()
        val factory =
            ScriptedFactory(backgroundScope) { framer ->
                loopDispatch(framer, recorded, dropMutation = true)
            }
        val session = (LuviaClient(factory).open("default") as Outcome.Ok).value
        val opensAfterHandshake = factory.opens
        val identity =
            TerminalIdentity(
                serverGeneration = "11111111111111111111111111111111",
                terminalId = "22222222222222222222222222222222",
                paneId = "7",
            )
        val result = session.typeLiteral(identity, "ls") as Outcome.Err
        assertTrue(result.failure is Failure.IndeterminateMutation)
        assertEquals(opensAfterHandshake, factory.opens)
        assertEquals(1, recorded.count { it == Methods.TERMINAL_TYPE })
        session.close()
    }

    @Test
    fun liveUpdatesWithFenceSkipsSecondSnapshot() = runTest {
        val recorded = mutableListOf<String>()
        val factory =
            ScriptedFactory(backgroundScope) { framer ->
                loopDispatch(framer, recorded, eventSequence = 11)
            }
        val session = (LuviaClient(factory).open("default") as Outcome.Ok).value
        val updates =
            session
                .liveUpdates(
                    afterSequence = 10,
                    serverGeneration = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                ).take(1)
                .toList()
        assertTrue(updates.single() is LiveUpdate.Event)
        assertTrue(Methods.SNAPSHOT !in recorded)
        assertTrue(Methods.EVENTS_SUBSCRIBE in recorded)
        assertEquals(2, factory.opens)
        session.close()
    }

    @Test
    fun concurrentUnaryCallsOpenPooledChannels() = runTest {
        val recorded = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()
        var snapshotsInFlight = 0
        val factory =
            ScriptedFactory(backgroundScope) { framer ->
                val prelude = parseObject(framer.readFrame())
                when (prelude["operation"]?.let { (it as JsonPrimitive).content }) {
                    "open" -> {
                        framer.writeFrame("""{"version":1,"status":"ready","session":"default"}""")
                        try {
                            while (true) {
                                val request = decodeUhpRequest(framer.readFrame())
                                recorded += request.method
                                if (request.method == Methods.SNAPSHOT) {
                                    snapshotsInFlight += 1
                                    if (snapshotsInFlight == 3) {
                                        release.complete(Unit)
                                    }
                                    release.await()
                                }
                                respond(framer, request, eventSequence = 11)
                            }
                        } catch (_: tech.asahiart.luvia.internal.FrameException) {
                        }
                    }
                }
            }
        val session = (LuviaClient(factory).open("default") as Outcome.Ok).value
        coroutineScope {
            val first = async { session.snapshot() }
            val second = async { session.snapshot() }
            val third = async { session.snapshot() }
            assertIsOk(first.await())
            assertIsOk(second.await())
            assertIsOk(third.await())
        }
        assertEquals(3, factory.opens)
        assertEquals(1, recorded.count { it == Methods.CAPABILITIES })
        assertEquals(3, recorded.count { it == Methods.SNAPSHOT })
        session.close()
    }

    @Test
    fun observeSendsRecentUnwrappedModeAndLineCount() = runTest {
        val recorded = mutableListOf<String>()
        val seen = mutableListOf<UhpRequest>()
        val factory =
            ScriptedFactory(backgroundScope) { framer ->
                loopDispatch(framer, recorded, recordedRequests = seen)
            }
        val session = (LuviaClient(factory).open("default") as Outcome.Ok).value
        val identity =
            TerminalIdentity(
                serverGeneration = "11111111111111111111111111111111",
                terminalId = "22222222222222222222222222222222",
                paneId = "7",
            )
        val first = session.observe(identity).first() as TerminalUpdate.Frame
        assertEquals(5, first.frame.contentRevision)
        val observe = seen.single { it.method == Methods.TERMINAL_OBSERVE }
        assertEquals("recent_unwrapped", (observe.params["mode"] as JsonPrimitive).content)
        assertEquals("200", (observe.params["lines"] as JsonPrimitive).content)
        session.close()
    }

    private fun assertIsOk(outcome: Outcome<*>) {
        assertTrue(outcome is Outcome.Ok, "expected Ok, got $outcome")
    }

    private suspend fun loopDispatch(
        framer: NdjsonFramer,
        recorded: MutableList<String>,
        closeAfter: Int? = null,
        dropMutation: Boolean = false,
        eventSequence: Long? = 11,
        recordedRequests: MutableList<UhpRequest>? = null,
    ) {
        val prelude = parseObject(framer.readFrame())
        when (prelude["operation"]?.let { (it as JsonPrimitive).content }) {
            "discover" -> {
                framer.writeFrame(
                    """{"version":1,"sessions":[{"name":"default","default":true,"running":true,"transport":"unix_socket"}]}""",
                )
            }
            "open" -> {
                framer.writeFrame("""{"version":1,"status":"ready","session":"default"}""")
                var served = 0
                try {
                    while (true) {
                        val request = decodeUhpRequest(framer.readFrame())
                        recorded += request.method
                        recordedRequests?.add(request)
                        if (dropMutation && request.method in Methods.MUTATIONS) {
                            return
                        }
                        respond(framer, request, eventSequence)
                        served += 1
                        if (closeAfter != null && served >= closeAfter) {
                            return
                        }
                        if (request.method == Methods.EVENTS_SUBSCRIBE) {
                            return
                        }
                    }
                } catch (_: tech.asahiart.luvia.internal.FrameException) {
                }
            }
        }
    }

    private suspend fun respond(
        framer: NdjsonFramer,
        request: UhpRequest,
        eventSequence: Long?,
    ) {
        val result =
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
                        put("methods", stringArray(DEFAULT_METHODS))
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
                        put("queue_capacity", 256)
                        put("loss_behavior", "resync_required_then_close")
                    }
                else -> buildJsonObject { put("type", "ok") }
            }
        val envelope =
            buildJsonObject {
                put("id", request.id)
                put("result", result)
            }
        framer.writeFrame(
            tech.asahiart.luvia.internal.compactJson.encodeToString(
                JsonObject.serializer(),
                envelope,
            ),
        )
        if (request.method == Methods.EVENTS_SUBSCRIBE && eventSequence != null) {
            framer.writeFrame(
                """{"event":"pane.focused","sequence":$eventSequence,"data":{"pane":"7"}}""",
            )
        }
        if (request.method == Methods.TERMINAL_OBSERVE) {
            framer.writeFrame(CopiedFixtures.VALID_TERMINAL_FRAME)
        }
    }

    private fun stringArray(values: List<String>): JsonArray =
        buildJsonArray {
            values.forEach { add(JsonPrimitive(it)) }
        }

    private companion object {
        val DEFAULT_METHODS =
            listOf(
                Methods.CAPABILITIES,
                Methods.SNAPSHOT,
                Methods.EVENTS_SUBSCRIBE,
                Methods.TERMINAL_TYPE,
                Methods.TERMINAL_OBSERVE,
            )
    }
}
