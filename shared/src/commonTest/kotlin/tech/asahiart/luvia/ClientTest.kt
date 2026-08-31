package tech.asahiart.luvia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
import tech.asahiart.luvia.internal.Methods
import tech.asahiart.luvia.internal.NdjsonFramer
import tech.asahiart.luvia.internal.UhpRequest
import tech.asahiart.luvia.internal.decodeUhpRequest
import tech.asahiart.luvia.internal.parseObject
import tech.asahiart.luvia.support.CopiedFixtures
import tech.asahiart.luvia.support.ScriptedFactory

class ClientTest {
    @Test
    fun handshakeAndDiscover() = runTest {
        val factory = ScriptedFactory(backgroundScope) { framer -> dispatch(framer) }
        val client = LuviaClient(factory)
        val sessions = (client.discover() as Outcome.Ok).value
        assertEquals("default", sessions.single().name)
        val session = (client.open("default") as Outcome.Ok).value
        assertEquals(Luvia.protocolName, session.capabilities.protocolName)
        assertEquals(Luvia.protocolMajor, session.capabilities.protocolMajor)
        session.close()
    }

    @Test
    fun rejectsUnknownMajor() = runTest {
        val factory = ScriptedFactory(backgroundScope) { framer -> dispatch(framer, major = 2) }
        val result = LuviaClient(factory).open("default") as Outcome.Err
        assertTrue(result.failure is Failure.UnknownMajor)
    }

    @Test
    fun rejectsMissingCapability() = runTest {
        val factory =
            ScriptedFactory(backgroundScope) { framer ->
                dispatch(framer, methods = listOf(Methods.CAPABILITIES))
            }
        val session = (LuviaClient(factory).open("default") as Outcome.Ok).value
        val result = session.snapshot() as Outcome.Err
        assertTrue(result.failure is Failure.CapabilityMissing)
        session.close()
    }

    @Test
    fun mismatchedResponseIdIsProtocolFailure() = runTest {
        val factory =
            ScriptedFactory(backgroundScope) { framer ->
                dispatch(framer, mismatchId = true)
            }
        val result = LuviaClient(factory).open("default") as Outcome.Err
        assertTrue(result.failure is Failure.ProtocolError)
    }

    @Test
    fun mutationDisconnectIsIndeterminateAndNotRetried() = runTest {
        val factory = ScriptedFactory(backgroundScope) { framer -> dispatch(framer, dropMutation = true) }
        val client = LuviaClient(factory)
        val session = (client.open("default") as Outcome.Ok).value
        val opensAfterHandshake = factory.opens
        val identity =
            TerminalIdentity(
                serverGeneration = "11111111111111111111111111111111",
                terminalId = "22222222222222222222222222222222",
                paneId = "7",
            )
        val result = session.typeLiteral(identity, "ls") as Outcome.Err
        assertTrue(result.failure is Failure.IndeterminateMutation)
        assertEquals(opensAfterHandshake + 1, factory.opens)
        session.close()
    }

    @Test
    fun eventsResyncOnSequenceGap() = runTest {
        val factory =
            ScriptedFactory(backgroundScope) { framer ->
                dispatch(framer, eventSequence = 12)
            }
        val session = (LuviaClient(factory).open("default") as Outcome.Ok).value
        val updates = session.events().take(2).toList()
        assertTrue(updates.any { it is SessionUpdate.Snapshot })
        assertTrue(updates.any { it is SessionUpdate.Resyncing && it.reason == ResyncReason.Gap })
        session.close()
    }

    @Test
    fun observeReplacesFramesByRevision() = runTest {
        val factory = ScriptedFactory(backgroundScope) { framer -> dispatch(framer) }
        val session = (LuviaClient(factory).open("default") as Outcome.Ok).value
        val identity =
            TerminalIdentity(
                serverGeneration = "11111111111111111111111111111111",
                terminalId = "22222222222222222222222222222222",
                paneId = "7",
            )
        val first = session.observe(identity).first() as TerminalUpdate.Frame
        assertEquals(5, first.frame.contentRevision)
        assertEquals("ready", first.frame.text)
        session.close()
    }

    private suspend fun dispatch(
        framer: NdjsonFramer,
        major: Int = 1,
        methods: List<String> = DEFAULT_METHODS,
        mismatchId: Boolean = false,
        dropMutation: Boolean = false,
        eventSequence: Long? = null,
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
                val request = decodeUhpRequest(framer.readFrame())
                respond(framer, request, major, methods, mismatchId, dropMutation, eventSequence)
            }
        }
    }

    private suspend fun respond(
        framer: NdjsonFramer,
        request: UhpRequest,
        major: Int,
        methods: List<String>,
        mismatchId: Boolean,
        dropMutation: Boolean,
        eventSequence: Long?,
    ) {
        if (dropMutation && request.method in Methods.MUTATIONS) {
            return
        }
        val id = if (mismatchId) "other" else request.id
        val result =
            when (request.method) {
                Methods.CAPABILITIES ->
                    buildJsonObject {
                        put("type", "uhp_capabilities")
                        put(
                            "protocol",
                            buildJsonObject {
                                put("name", "luvus-uhp")
                                put("major", major)
                                put("minor", 0)
                            },
                        )
                        put("methods", stringArray(methods))
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
                Methods.TERMINAL_OBSERVE, Methods.TERMINAL_CONTROL ->
                    buildJsonObject {
                        put("type", "terminal_backend_stream")
                        put("mode", "observe")
                    }
                else -> buildJsonObject { put("type", "ok") }
            }
        val envelope = buildJsonObject {
            put("id", id)
            put("result", result)
        }
        framer.writeFrame(tech.asahiart.luvia.internal.compactJson.encodeToString(JsonObject.serializer(), envelope))
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
                Methods.TERMINAL_CONTROL,
            )
    }
}
