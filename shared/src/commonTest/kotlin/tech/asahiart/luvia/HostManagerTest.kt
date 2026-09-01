package tech.asahiart.luvia

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path.Companion.toPath

@OptIn(ExperimentalCoroutinesApi::class)
class HostManagerTest {
    @Test
    fun hostsFollowStoreEvenWhenNeverConnected() = runTest {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "luvia-hm-${Random.nextLong()}.json"
        val store = HostStore(path.toString(), backgroundScope)
        val vault = MemoryVault()
        val manager = HostManager(store, vault, backgroundScope)
        store.upsert(sampleProfile())
        advanceUntilIdle()
        val runtime = manager.hosts.value.single()
        assertEquals("host-1", runtime.profile.id)
        assertIs<HostLink.Idle>(runtime.link)
        assertEquals(ConnectionFreshness.Stale, runtime.freshness)
        assertEquals("default", runtime.snapshot?.sessionName)
        manager.close()
    }

    @Test
    fun connectWithoutCredentialFailsWithRepairMessage() = runTest {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "luvia-hm-key-${Random.nextLong()}.json"
        val store = HostStore(path.toString(), backgroundScope)
        val vault = MemoryVault()
        val manager = HostManager(store, vault, backgroundScope)
        store.upsert(sampleProfile())
        advanceUntilIdle()
        manager.connect("host-1")
        advanceUntilIdle()
        val link = manager.hosts.value.single().link as HostLink.Failed
        val failure = link.failure as Failure.ProtocolError
        assertTrue(failure.reason.contains("Re-pair"))
        manager.close()
    }

    @Test
    fun completePairingRejectsMismatchedDeviceKey() = runTest {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "luvia-hm-pair-${Random.nextLong()}.json"
        val store = HostStore(path.toString(), backgroundScope)
        val manager = HostManager(store, MemoryVault(), backgroundScope)
        val draft =
            PairingDraft(
                deviceLabel = "phone",
                role = HostRole.Controller,
                authorizedKeysLine = "ssh-ed25519 AAAA",
                deviceKeyFingerprint = "SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU",
                command = "luvia-host pair",
                privateKeyOpenssh = "SECRET",
            )
        val otherFp = "SHA256:ypeBEsobvcr6wjGzmiPcTaeG7/gUfE5yuYB3ha/uSLs"
        val body =
            """{"v":1,"id":"grant-1","dk":"$otherFp","name":"studio","user":"misaka","port":22,"addrs":["studio.tailnet"],"hk":["$otherFp"],"role":"controller"}"""
        val encoded = "luvia1:" + body.encodeUtf8().base64().trimEnd('=').replace('+', '-').replace('/', '_')
        val result = manager.completePairing(draft, encoded)
        val err = result as Outcome.Err
        assertEquals("pairing code is for a different device key", (err.failure as Failure.ProtocolError).reason)
        manager.close()
    }
}

private class MemoryVault : DeviceKeyVault {
    private val keys = mutableMapOf<String, String>()

    override fun save(deviceId: String, privateKeyOpenssh: String) {
        keys[deviceId] = privateKeyOpenssh
    }

    override fun credential(deviceId: String): DeviceCredential? =
        keys[deviceId]?.let { DeviceCredential.SoftwareKey(it) }

    override fun delete(deviceId: String) {
        keys.remove(deviceId)
    }

    override fun deviceIds(): List<String> = keys.keys.toList()
}

private fun sampleProfile(): HostProfile =
    HostProfile(
        id = "host-1",
        alias = "studio",
        addresses = listOf("studio.tailnet"),
        sshPort = 22,
        username = "misaka",
        hostKeyFingerprints = listOf("SHA256:ypeBEsobvcr6wjGzmiPcTaeG7/gUfE5yuYB3ha/uSLs"),
        role = HostRole.Controller,
        lastStatus = HostStatus.Unknown,
        lastUpdatedEpochMs = 1,
        lastConnectedAddress = null,
        topology =
            CachedTopology(
                sessionName = "default",
                serverGeneration = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                eventSequence = 4,
                workspaces = listOf(WorkspaceSummary(1, "work", false, true, 1)),
                agents = emptyList(),
                tasks = emptyList(),
                capturedAtEpochMs = 1,
            ),
    )
