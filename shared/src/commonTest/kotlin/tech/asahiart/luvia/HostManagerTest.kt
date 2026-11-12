package tech.asahiart.luvia

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path.Companion.toPath

@OptIn(ExperimentalCoroutinesApi::class)
class HostManagerTest {
    @Test
    fun catalogReadyIsTrueOnceTheStoreHasEmitted() = runTest {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "luvia-hm-ready-${Random.nextLong()}.json"
        val store = HostStore(path.toString(), backgroundScope)
        val manager = HostManager(store, MemoryVault(), backgroundScope)
        store.upsert(sampleProfile())
        advanceUntilIdle()
        assertTrue(manager.catalogReady.value, "catalog ready after the store emits")
        assertEquals(1, manager.hosts.value.size)
        manager.close()
    }

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
        val hostId = "cd".repeat(16)
        val deviceId = "ab".repeat(16)
        val body =
            """{"name":"studio","fqdn":"studio.tailnet","user":"misaka","port":22,"bridge":"/usr/local/bin/luvia-host","hk":["$otherFp"],"hints":["10.0.0.2"],"host_id":"$hostId","device_id":"$deviceId","device_key":"$otherFp","role":"controller"}"""
        val encoded = "luvia2:" + body.encodeUtf8().base64().trimEnd('=').replace('+', '-').replace('/', '_')
        val result = manager.completePairing(draft, encoded)
        val err = result as Outcome.Err
        assertEquals("pairing code is for a different device key", (err.failure as Failure.ProtocolError).reason)
        manager.close()
    }

    @Test
    fun completePairingSurfacesVaultFailure() = runTest {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "luvia-hm-vault-${Random.nextLong()}.json"
        val store = HostStore(path.toString(), backgroundScope)
        val manager = HostManager(store, ThrowingVault(), backgroundScope)
        val fingerprint = "SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU"
        val draft =
            PairingDraft(
                deviceLabel = "phone",
                role = HostRole.Controller,
                authorizedKeysLine = "ssh-ed25519 AAAA",
                deviceKeyFingerprint = fingerprint,
                command = "luvia-host pair",
                privateKeyOpenssh = "SECRET",
            )
        val hostId = "cd".repeat(16)
        val deviceId = "ab".repeat(16)
        val body =
            """{"name":"studio","fqdn":"studio.tailnet","user":"misaka","port":22,"bridge":"/usr/local/bin/luvia-host","hk":["$fingerprint"],"hints":["10.0.0.2"],"host_id":"$hostId","device_id":"$deviceId","device_key":"$fingerprint","role":"controller"}"""
        val encoded = "luvia2:" + body.encodeUtf8().base64().trimEnd('=').replace('+', '-').replace('/', '_')
        val result = manager.completePairing(draft, encoded)
        val err = result as Outcome.Err
        assertEquals("Could not store the device key on this device.", (err.failure as Failure.ProtocolError).reason)
        manager.close()
    }

    @Test
    fun updateConnectionRewritesReachability() = runTest {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "luvia-hm-addr-${Random.nextLong()}.json"
        val store = HostStore(path.toString(), backgroundScope)
        val manager = HostManager(store, MemoryVault(), backgroundScope)
        store.upsert(sampleProfile())
        advanceUntilIdle()
        val result =
            manager.updateConnection(
                hostId = "host-1",
                name = "studio-lan",
                addresses = listOf("192.168.1.20", "studio.local"),
                port = 2222,
                user = "deploy",
            )
        val updated = (result as Outcome.Ok).value
        assertEquals("studio-lan", updated.name)
        assertEquals(listOf("192.168.1.20", "studio.local"), updated.hints)
        assertEquals(2222, updated.port)
        assertEquals("deploy", updated.user)
        val stored = store.current().hosts.single()
        assertEquals(listOf("192.168.1.20", "studio.local"), stored.hints)
        assertEquals(2222, stored.port)
        manager.close()
    }

    @Test
    fun completePairingDropsHostWhenConnectFails() = runTest {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "luvia-hm-rollback-${Random.nextLong()}.json"
        val store = HostStore(path.toString(), backgroundScope)
        val vault = UnreadableVault()
        val manager = HostManager(store, vault, backgroundScope)
        val fingerprint = "SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU"
        val draft =
            PairingDraft(
                deviceLabel = "phone",
                role = HostRole.Controller,
                authorizedKeysLine = "ssh-ed25519 AAAA",
                deviceKeyFingerprint = fingerprint,
                command = "luvia-host pair",
                privateKeyOpenssh = "not-a-key",
            )
        val hostId = "cd".repeat(16)
        val deviceId = "ab".repeat(16)
        val body =
            """{"name":"FailTest","user":"misaka","port":2222,"bridge":"/usr/local/bin/luvia-host","hk":["$fingerprint"],"hints":["192.168.1.70"],"host_id":"$hostId","device_id":"$deviceId","device_key":"$fingerprint","role":"controller"}"""
        val encoded = "luvia2:" + body.encodeUtf8().base64().trimEnd('=').replace('+', '-').replace('/', '_')
        val result = manager.completePairing(draft, encoded)
        advanceUntilIdle()
        val err = result as Outcome.Err
        assertFalse(err.failure.isPairingSetupFailure())
        assertTrue(store.current().hosts.isEmpty(), "failed pairing must not leave a host")
        assertTrue(vault.deviceIds().isEmpty(), "failed pairing must drop the device key")
        assertTrue(manager.hosts.value.none { it.profile.id == "ab".repeat(16) })
        manager.close()
    }

    @Test
    fun deletedGrantIsNotWrittenToLastGood() = runTest {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "luvia-hm-grant-${Random.nextLong()}.json"
        val store = HostStore(path.toString(), backgroundScope)
        val vault = MemoryVault()
        val profile = sampleProfile().copy(lastGood = "10.0.0.2", hints = listOf("192.168.1.9"))
        vault.save(profile.id, "not-a-real-key")
        val manager =
            HostManager(store, vault, backgroundScope) { _, _ ->
                ProfileConnectResult(
                    fail(Failure.ProveRejected("unknown_device", "no pairing grant for ${profile.id}")),
                )
            }
        store.upsert(profile)
        advanceUntilIdle()
        assertEquals(listOf("host-1"), manager.hosts.value.map { it.profile.id })
        manager.connect(profile.id)
        val reached = manager.awaitCurrentConnect(profile.id)
        advanceUntilIdle()
        val err = assertIs<Outcome.Err<Unit>>(reached)
        val failure = assertIs<Failure.ProveRejected>(err.failure)
        assertEquals("unknown_device", failure.code)
        assertEquals("no pairing grant for ${profile.id}", failure.message)
        assertEquals(PROVE_REJECTED_EN, failure.connectMessage())
        assertEquals(PROVE_REJECTED_EN, failure.userMessage())
        assertFalse(failure.connectMessage().contains("no pairing grant"))
        assertFalse(failure.userMessage().contains(profile.id))
        val stored = store.current().hosts.single()
        assertEquals("10.0.0.2", stored.lastGood)
        assertFalse(stored.lastGood == "192.168.1.9")
        val link = assertIs<HostLink.Failed>(manager.hosts.value.single().link)
        assertIs<Failure.ProveRejected>(link.failure)
        manager.close()
    }

    @Test
    fun proveRejectedRaceIsNotRetried() = runTest {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "luvia-hm-race-${Random.nextLong()}.json"
        val store = HostStore(path.toString(), backgroundScope)
        val vault = MemoryVault()
        val profile =
            sampleProfile().copy(
                name = "Yui",
                lastGood = "192.168.1.9",
                hints = listOf("100.89.1.2"),
            )
        vault.save(profile.id, "not-a-real-key")
        var attempts = 0
        val manager =
            HostManager(store, vault, backgroundScope) { current, credential ->
                attempts += 1
                raceConnect(
                    current,
                    credential,
                    object : CandidateDial<ConnectedHost> {
                        override suspend fun dns(address: String): Outcome<Unit> = ok(Unit)

                        override suspend fun tcp(address: String): Outcome<Unit> = ok(Unit)

                        override suspend fun prove(address: String): Outcome<ConnectedHost> {
                            if (address == "192.168.1.9") {
                                return fail(
                                    Failure.ProveRejected(
                                        "unknown_device",
                                        "no pairing grant for ${current.id}",
                                    ),
                                )
                            }
                            delay(PROVE_BUDGET_MS)
                            return fail(Failure.Transport("candidate timeout"))
                        }

                        override suspend fun close(value: ConnectedHost) {
                            value.close()
                        }
                    },
                )
            }
        store.upsert(profile)
        advanceUntilIdle()
        manager.connect(profile.id)
        val reached = manager.awaitCurrentConnect(profile.id)
        advanceUntilIdle()
        val err = assertIs<Outcome.Err<Unit>>(reached)
        val failure = assertIs<Failure.ProveRejected>(err.failure)
        assertEquals("unknown_device", failure.code)
        assertEquals("no pairing grant for ${profile.id}", failure.message)
        assertFalse(failure.connectMessage().contains("candidate timeout"))
        assertFalse(failure.connectMessage().contains("no pairing grant"))
        val link = assertIs<HostLink.Failed>(manager.hosts.value.single().link)
        assertIs<Failure.ProveRejected>(link.failure)
        advanceTimeBy(60_000)
        advanceUntilIdle()
        assertEquals(1, attempts)
        val stayed = assertIs<HostLink.Failed>(manager.hosts.value.single().link)
        assertIs<Failure.ProveRejected>(stayed.failure)
        assertEquals("192.168.1.9", store.current().hosts.single().lastGood)
        manager.close()
    }

    @Test
    fun publicKeyRejectIsNotRetriedAndDoesNotWriteLastGood() = runTest {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "luvia-hm-auth-${Random.nextLong()}.json"
        val store = HostStore(path.toString(), backgroundScope)
        val vault = MemoryVault()
        val profile =
            sampleProfile().copy(
                name = "localhost",
                lastGood = "192.168.1.9",
                hints = emptyList(),
            )
        assertEquals(listOf("192.168.1.9"), candidateAddresses(profile))
        vault.save(profile.id, "not-a-real-key")
        var attempts = 0
        val manager =
            HostManager(store, vault, backgroundScope) { current, credential ->
                attempts += 1
                raceConnect(
                    current,
                    credential,
                    object : CandidateDial<ConnectedHost> {
                        override suspend fun dns(address: String): Outcome<Unit> = ok(Unit)

                        override suspend fun tcp(address: String): Outcome<Unit> = ok(Unit)

                        override suspend fun prove(address: String): Outcome<ConnectedHost> =
                            fail(Failure.AuthRejected("ssh publickey authentication rejected"))

                        override suspend fun close(value: ConnectedHost) {
                            value.close()
                        }
                    },
                )
            }
        store.upsert(profile)
        advanceUntilIdle()
        manager.connect(profile.id)
        val reached = manager.awaitCurrentConnect(profile.id)
        advanceUntilIdle()
        val err = assertIs<Outcome.Err<Unit>>(reached)
        val failure = assertIs<Failure.AuthRejected>(err.failure)
        assertEquals("ssh publickey authentication rejected", failure.reason)
        assertTrue(failure.isPairingRevoked())
        assertEquals(PROVE_REJECTED_EN, failure.connectMessage())
        assertEquals(PROVE_REJECTED_EN, failure.userMessage())
        assertEquals(PROVE_REJECTED_ZH, failure.refusedOutcomeZh())
        val prove = Failure.ProveRejected("unknown_device", "no pairing grant for ${profile.id}")
        assertEquals(prove.connectMessage(), failure.connectMessage())
        assertTrue(failure.raceReason() != prove.raceReason())
        assertTrue(failure.raceReason().startsWith("AuthRejected"))
        assertFalse(failure.connectMessage().contains("publickey"))
        assertFalse(failure.userMessage().contains(failure.reason))
        assertEquals("192.168.1.9", store.current().hosts.single().lastGood)
        val link = assertIs<HostLink.Failed>(manager.hosts.value.single().link)
        assertIs<Failure.AuthRejected>(link.failure)
        advanceTimeBy(60_000)
        advanceUntilIdle()
        assertEquals(1, attempts)
        val stayed = assertIs<HostLink.Failed>(manager.hosts.value.single().link)
        assertIs<Failure.AuthRejected>(stayed.failure)
        assertEquals("192.168.1.9", store.current().hosts.single().lastGood)
        manager.close()
    }

    @Test
    fun parseConnectionAddressesSplitsHostList() {
        assertEquals(
            listOf("192.168.1.8", "studio.local"),
            parseConnectionAddresses("192.168.1.8, studio.local\n"),
        )
        assertEquals(emptyList(), parseConnectionAddresses("  , \n"))
    }

}

private class UnreadableVault : DeviceKeyVault {
    private var saved = false

    override fun save(deviceId: String, privateKeyOpenssh: String) {
        saved = true
    }

    override fun credential(deviceId: String): DeviceCredential? = null

    override fun delete(deviceId: String) {
        saved = false
    }

    override fun deviceIds(): List<String> = if (saved) listOf(deviceIdPlaceholder) else emptyList()

    private companion object {
        const val deviceIdPlaceholder: String = "saved"
    }
}

private class ThrowingVault : DeviceKeyVault {
    override fun save(deviceId: String, privateKeyOpenssh: String) {
        error("keychain status -34018")
    }

    override fun credential(deviceId: String): DeviceCredential? = null

    override fun delete(deviceId: String) {}

    override fun deviceIds(): List<String> = emptyList()
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
        hostId = "ab".repeat(16),
        name = "studio",
        user = "misaka",
        hostKeys = setOf("SHA256:ypeBEsobvcr6wjGzmiPcTaeG7/gUfE5yuYB3ha/uSLs"),
        bridgePath = "/usr/local/bin/luvia-host",
        role = HostRole.Controller,
        lastStatus = HostStatus.Unknown,
        lastUpdatedEpochMs = 1,
        port = 22,
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
