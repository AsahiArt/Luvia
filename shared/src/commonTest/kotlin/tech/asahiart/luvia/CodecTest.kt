package tech.asahiart.luvia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import tech.asahiart.luvia.internal.CodecException
import tech.asahiart.luvia.internal.FrameException
import tech.asahiart.luvia.internal.NdjsonFramer
import tech.asahiart.luvia.internal.UhpResponse
import tech.asahiart.luvia.internal.decodeControlFrame
import tech.asahiart.luvia.internal.decodeDiscoverResponse
import tech.asahiart.luvia.internal.decodeOpenResponse
import tech.asahiart.luvia.internal.decodeTerminalFrameEvent
import tech.asahiart.luvia.internal.decodeUhpEvent
import tech.asahiart.luvia.internal.decodeUhpRequest
import tech.asahiart.luvia.internal.decodeUhpResponse
import tech.asahiart.luvia.internal.encodeDiscoverRequest
import tech.asahiart.luvia.internal.encodeOpenRequest
import tech.asahiart.luvia.internal.encodeUhpRequest
import tech.asahiart.luvia.internal.UhpRequest
import tech.asahiart.luvia.support.CopiedFixtures
import tech.asahiart.luvia.internal.ByteChannel
import kotlinx.serialization.json.JsonObject

class CodecTest {
    @Test
    fun validRequestFixturesRoundTrip() {
        for (line in CopiedFixtures.validRequests) {
            val decoded = decodeUhpRequest(line)
            val encoded = encodeUhpRequest(decoded)
            val again = decodeUhpRequest(encoded)
            assertEquals(decoded.id, again.id)
            assertEquals(decoded.method, again.method)
        }
    }

    @Test
    fun validResponseFixturesDecode() {
        val success = decodeUhpResponse(CopiedFixtures.validResponses[0], "1") as UhpResponse.Success
        assertEquals("1", success.id)
        val error = decodeUhpResponse(CopiedFixtures.validResponses[3], "4") as UhpResponse.Failure
        assertEquals("not_found", error.error.code)
    }

    @Test
    fun validEventFixturesDecode() {
        val first = decodeUhpEvent(CopiedFixtures.validEvents[0])
        assertEquals("pane.focused", first.name)
        assertEquals(1, first.sequence)
    }

    @Test
    fun validControlFramesDecode() {
        for (line in CopiedFixtures.validControlFrames) {
            decodeControlFrame(line)
        }
    }

    @Test
    fun rejectsDuplicateKeys() {
        val error =
            assertFailsWith<CodecException> {
                decodeUhpResponse("""{"id":"1","id":"2","result":{}}""", "1")
            }
        assertEquals(CodecException.Kind.DuplicateKey, error.kind)
    }

    @Test
    fun rejectsUnknownEnvelopeFields() {
        val error =
            assertFailsWith<CodecException> {
                decodeUhpRequest(CopiedFixtures.invalidRequests[0])
            }
        assertEquals(CodecException.Kind.UnknownField, error.kind)
    }

    @Test
    fun rejectsMismatchedResponseId() {
        val error =
            assertFailsWith<CodecException> {
                decodeUhpResponse(CopiedFixtures.validResponses[0], "other")
            }
        assertEquals(CodecException.Kind.MismatchedId, error.kind)
    }

    @Test
    fun rejectsResultAndErrorTogether() {
        val error =
            assertFailsWith<CodecException> {
                decodeUhpResponse("""{"id":"1","result":{},"error":{"code":"x","message":"y"}}""", "1")
            }
        assertEquals(CodecException.Kind.ResultErrorExclusivity, error.kind)
    }

    @Test
    fun rejectsInvalidRequestId() {
        assertFailsWith<CodecException> {
            decodeUhpRequest(CopiedFixtures.invalidRequests[1])
        }
    }

    @Test
    fun rejectsOversizedFrame() = runTest {
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        val outgoing = Channel<ByteArray>(Channel.UNLIMITED)
        val channel =
            object : ByteChannel {
                override suspend fun writeFully(bytes: ByteArray) {
                    outgoing.send(bytes)
                }

                override suspend fun readChunk(): ByteArray? = incoming.receiveCatching().getOrNull()

                override fun close() {
                    incoming.close()
                    outgoing.close()
                }
            }
        val oversized = ByteArray(Luvia.maxFrameBytes) { 'a'.code.toByte() }
        incoming.send(oversized)
        val framer = NdjsonFramer(channel)
        val error = assertFailsWith<FrameException> { framer.readFrame() }
        assertEquals(FrameException.Kind.Oversized, error.kind)
    }

    @Test
    fun rejectsTerminalByteAndLineMismatches() {
        for (line in CopiedFixtures.invalidTerminalEvents) {
            val event = decodeUhpEvent(line)
            assertFailsWith<CodecException> { decodeTerminalFrameEvent(event) }
        }
        decodeTerminalFrameEvent(decodeUhpEvent(CopiedFixtures.VALID_TERMINAL_FRAME))
    }

    @Test
    fun bridgePreludeIsClosedAndRejectsSocketPath() {
        val discover = encodeDiscoverRequest()
        assertTrue(discover.contains("\"operation\":\"discover\""))
        decodeDiscoverResponse(
            """{"version":1,"sessions":[{"name":"default","default":true,"running":true,"transport":"unix_socket"}]}""",
        )
        encodeOpenRequest("default")
        decodeOpenResponse("""{"version":1,"status":"ready","session":"default"}""", "default")
        val pathError =
            assertFailsWith<CodecException> {
                decodeOpenResponse(
                    """{"version":1,"status":"ready","session":"default","socket_path":"/tmp/luvus.sock"}""",
                    "default",
                )
            }
        assertEquals(CodecException.Kind.UnknownField, pathError.kind)
        assertFailsWith<CodecException> { encodeOpenRequest("bad session") }
        assertFailsWith<CodecException> {
            decodeDiscoverResponse("""{"version":2,"sessions":[]}""")
        }
    }

    @Test
    fun emptyParamsEncode() {
        val encoded = encodeUhpRequest(UhpRequest("r1", "uhp.capabilities", JsonObject(emptyMap())))
        decodeUhpRequest(encoded)
    }
}
