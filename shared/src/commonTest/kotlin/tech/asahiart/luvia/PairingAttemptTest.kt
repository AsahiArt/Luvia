package tech.asahiart.luvia

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path.Companion.toPath
import tech.asahiart.luvia.internal.Methods
import tech.asahiart.luvia.internal.NdjsonFramer
import tech.asahiart.luvia.internal.compactJson
import tech.asahiart.luvia.internal.decodeUhpRequest
import tech.asahiart.luvia.internal.parseObject
import tech.asahiart.luvia.support.ScriptedFactory

@OptIn(ExperimentalCoroutinesApi::class)
class PairingAttemptTest {
    @Test
    fun cancelledAttemptDoesNotPersistOrReplaceAnExistingHost() = runTest {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "luvia-pair-cancel-${Random.nextLong()}.json"
        val store = HostStore(path.toString(), backgroundScope)
        val vault = CountingVault()
        val kept = sample("kept")
        store.upsert(kept)
        val manager = manager(store, vault, this)
        advanceUntilIdle()
        val attempt = manager.openPairingAttempt()
        manager.invalidatePairingAttempt()
        val result = manager.completePairing(draft(), code("new-host", "aa".repeat(16), "bb".repeat(16)), attemptId = attempt)
        runCurrent()
        assertEquals(0, vault.saves)
        assertEquals(listOf(kept.hostId), store.current().hosts.map { it.hostId })
        val err = assertIs<Outcome.Err<HostProfile>>(result)
        assertEquals("pairing attempt is no longer current", (err.failure as Failure.ProtocolError).reason)
        assertFalse(manager.pairingAttempts.wasClaimed(attempt))
        manager.close()
    }

    @Test
    fun oldAttemptCannotReplaceANewerOne() = runTest {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "luvia-pair-order-${Random.nextLong()}.json"
        val store = HostStore(path.toString(), backgroundScope)
        val vault = CountingVault()
        val kept = sample("kept")
        store.upsert(kept)
        val manager = manager(store, vault, this)
        advanceUntilIdle()
        val older = manager.openPairingAttempt()
        val newer = manager.openPairingAttempt()
        val stale = manager.completePairing(draft(), code("stale", "11".repeat(16), "22".repeat(16)), attemptId = older)
        runCurrent()
        assertIs<Outcome.Err<HostProfile>>(stale)
        val fresh = manager.completePairing(draft(), code("fresh", "33".repeat(16), "44".repeat(16)), attemptId = newer)
        runCurrent()
        assertIs<Outcome.Ok<HostProfile>>(fresh)
        assertEquals(listOf(kept.hostId, "33".repeat(16)), store.current().hosts.map { it.hostId })
        assertEquals(1, vault.saves)
        val again = manager.completePairing(draft(), code("fresh", "33".repeat(16), "44".repeat(16)), attemptId = newer)
        runCurrent()
        assertIs<Outcome.Err<HostProfile>>(again)
        assertEquals(1, vault.saves)
        assertEquals(listOf(kept.hostId, "33".repeat(16)), store.current().hosts.map { it.hostId })
        manager.invalidatePairingAttempt()
        assertFalse(manager.pairingAttempts.isCurrent(newer))
        assertTrue(manager.pairingAttempts.wasClaimed(newer))
        assertEquals(listOf(kept.hostId, "33".repeat(16)), store.current().hosts.map { it.hostId })
        manager.close()
    }

    private fun manager(store: HostStore, vault: CountingVault, scope: kotlinx.coroutines.CoroutineScope) =
        HostManager(store, vault, scope) { _, _ ->
            val factory = ScriptedFactory(scope) { framer -> scriptedBridge(framer) }
            ProfileConnectResult(ok(HostConnection(connectedHostForTest(LuviaClient(factory)), "10.0.0.8")))
        }

    private fun draft() = PairingDraft(
        deviceLabel = "phone",
        role = HostRole.Controller,
        authorizedKeysLine = "ssh-ed25519 AAAA",
        deviceKeyFingerprint = FINGERPRINT,
        command = "luvia-host pair",
        privateKeyOpenssh = "SECRET",
    )

    private fun sample(name: String) = HostProfile(
        id = "99".repeat(16),
        hostId = "88".repeat(16),
        name = name,
        user = "misaka",
        port = 22,
        bridgePath = "/usr/local/bin/luvia-host",
        hostKeys = setOf(FINGERPRINT),
        hints = listOf("10.0.0.1"),
        role = HostRole.Controller,
        lastStatus = HostStatus.Unknown,
        lastUpdatedEpochMs = 0L,
        topology = null,
    )

    private fun code(name: String, hostId: String, deviceId: String): String {
        val body =
            """{"name":"$name","user":"misaka","port":2222,"bridge":"/usr/local/bin/luvia-host","hk":["$FINGERPRINT"],"hints":["10.0.0.8"],"host_id":"$hostId","device_id":"$deviceId","device_key":"$FINGERPRINT","role":"controller"}"""
        return "luvia2:" + body.encodeUtf8().base64().trimEnd('=').replace('+', '-').replace('/', '_')
    }

    private class CountingVault : DeviceKeyVault {
        var saves: Int = 0
        private val keys = mutableMapOf<String, String>()

        override fun save(deviceId: String, privateKeyOpenssh: String) {
            saves += 1
            keys[deviceId] = privateKeyOpenssh
        }

        override fun credential(deviceId: String): DeviceCredential? =
            keys[deviceId]?.let { DeviceCredential.SoftwareKey(it) }

        override fun delete(deviceId: String) {
            keys.remove(deviceId)
        }

        override fun deviceIds(): List<String> = keys.keys.toList()
    }

    private suspend fun scriptedBridge(framer: NdjsonFramer) {
        val first = parseObject(framer.readFrame())
        when ((first["operation"] as? JsonPrimitive)?.content) {
            "discover" -> {
                framer.writeFrame(
                    """{"version":1,"sessions":[{"name":"default","default":true,"running":true,"transport":"unix_socket"}]}""",
                )
                return
            }
            "open" -> framer.writeFrame("""{"version":1,"status":"ready","session":"default"}""")
            else -> return
        }
        while (true) {
            val request = try {
                decodeUhpRequest(framer.readFrame())
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return
            }
            val result = when (request.method) {
                Methods.CAPABILITIES ->
                    """{"type":"uhp_capabilities","protocol":{"name":"luvus-uhp","major":1,"minor":0},"methods":["uhp.capabilities","session.snapshot","events.subscribe","agent.list","task.list"],"session":"default","event_sequence":10,"server_generation":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}"""
                Methods.SNAPSHOT ->
                    """{"type":"session_snapshot","protocol":{"name":"luvus-uhp","major":1,"minor":0},"session":"default","server_generation":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","event_sequence":10,"workspaces":[]}"""
                Methods.EVENTS_SUBSCRIBE ->
                    """{"type":"subscription_started","sequence":10,"queue_capacity":256,"loss_behavior":"resync_required_then_close"}"""
                else -> """{"type":"ok"}"""
            }
            framer.writeFrame(
                compactJson.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("id", request.id)
                        put("result", parseObject(result))
                    },
                ),
            )
            if (request.method == Methods.EVENTS_SUBSCRIBE) {
                try {
                    while (true) framer.readFrame()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    return
                }
            }
        }
    }

    private companion object {
        const val FINGERPRINT: String = "SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU"
    }
}
