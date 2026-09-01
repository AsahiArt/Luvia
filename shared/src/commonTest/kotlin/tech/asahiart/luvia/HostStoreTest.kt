package tech.asahiart.luvia

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath

class HostStoreTest {
    @Test
    fun persistsApprovedFieldsOnly() = runTest {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "luvia-host-${Random.nextLong()}.json"
        val store = HostStore(path.toString(), backgroundScope)
        store.upsert(
            HostProfile(
                id = "host-1",
                alias = "studio",
                addresses = listOf("studio.tailnet", "10.0.0.2"),
                sshPort = 22,
                username = "misaka",
                hostKeyFingerprints = listOf("SHA256:ypeBEsobvcr6wjGzmiPcTaeG7/gUfE5yuYB3ha/uSLs"),
                role = HostRole.Observer,
                lastStatus = HostStatus.Reachable,
                lastUpdatedEpochMs = 100,
                lastConnectedAddress = null,
                topology =
                    CachedTopology(
                        sessionName = "default",
                        serverGeneration = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        eventSequence = 4,
                        workspaces = listOf(WorkspaceSummary(1, "work", false, true, 1)),
                        agents = listOf(AgentSummary("7", "codex", AgentStatus.Idle)),
                        tasks = listOf(TaskSummary("t1", "wire-up", "open")),
                        capturedAtEpochMs = 100,
                    ),
            ),
        )
        store.setLastConnectedAddress("host-1", "10.0.0.2")
        store.updateStatus("host-1", HostStatus.Stale, 200)
        val loaded = store.current().hosts.single()
        assertEquals("studio", loaded.alias)
        assertEquals(listOf("studio.tailnet", "10.0.0.2"), loaded.addresses)
        assertEquals("misaka", loaded.username)
        assertEquals(listOf("SHA256:ypeBEsobvcr6wjGzmiPcTaeG7/gUfE5yuYB3ha/uSLs"), loaded.hostKeyFingerprints)
        assertEquals(HostRole.Observer, loaded.role)
        assertEquals(HostStatus.Stale, loaded.lastStatus)
        assertEquals(200, loaded.lastUpdatedEpochMs)
        assertEquals("10.0.0.2", loaded.lastConnectedAddress)
        assertEquals("default", loaded.topology?.sessionName)
        assertNull(loaded.topology?.workspaces?.first()?.name?.takeIf { it == "secret-prompt" })
        val bytes = FileSystem.SYSTEM.read(path) { readUtf8() }
        assertFalse(bytes.contains("luv_tok"))
        assertFalse(bytes.contains("BEGIN OPENSSH"))
        assertFalse(bytes.contains("prompt"))
        assertTrue(bytes.contains("studio"))
        assertTrue(bytes.contains("\"addresses\""))
        assertTrue(bytes.contains("\"hostKeyFingerprints\""))
        store.remove("host-1")
        assertTrue(store.current().hosts.isEmpty())
    }

    @Test
    fun migratesLegacyHostJson() = runTest {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "luvia-host-legacy-${Random.nextLong()}.json"
        FileSystem.SYSTEM.write(path) {
            writeUtf8(
                """
                {"version":1,"hosts":[{"id":"host-1","alias":"studio","address":"studio.tailnet","sshPort":22,"username":"misaka","hostKeySha256":"SHA256:ypeBEsobvcr6wjGzmiPcTaeG7/gUfE5yuYB3ha/uSLs","lastStatus":"Reachable","lastUpdatedEpochMs":100},{"id":"host-2","alias":"spare","address":"spare.local","sshPort":2222,"lastStatus":"Unknown","lastUpdatedEpochMs":0}],"updatedEpochMs":100}
                """.trimIndent(),
            )
        }
        val store = HostStore(path.toString(), backgroundScope)
        val hosts = store.current().hosts.associateBy { it.id }
        val studio = hosts.getValue("host-1")
        assertEquals("studio", studio.alias)
        assertEquals(listOf("studio.tailnet"), studio.addresses)
        assertEquals("misaka", studio.username)
        assertEquals(listOf("SHA256:ypeBEsobvcr6wjGzmiPcTaeG7/gUfE5yuYB3ha/uSLs"), studio.hostKeyFingerprints)
        assertEquals(HostRole.Controller, studio.role)
        assertEquals(HostStatus.Reachable, studio.lastStatus)
        assertNull(studio.lastConnectedAddress)
        val spare = hosts.getValue("host-2")
        assertEquals(listOf("spare.local"), spare.addresses)
        assertEquals("", spare.username)
        assertEquals(emptyList(), spare.hostKeyFingerprints)
        assertEquals(HostRole.Controller, spare.role)
        assertEquals(2222, spare.sshPort)
    }
}
