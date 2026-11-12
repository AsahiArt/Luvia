package tech.asahiart.luvia

import kotlin.random.Random
import androidx.datastore.core.CorruptionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toPath
import tech.asahiart.luvia.internal.HostCatalogOkioSerializer

class HostStoreTest {
    @Test
    fun persistsApprovedFieldsOnly() = runTest {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "luvia-host-${Random.nextLong()}.json"
        val store = HostStore(path.toString(), backgroundScope)
        store.upsert(
            HostProfile(
                id = "host-1",
                name = "studio",
                user = "misaka",
                hostKeys = listOf("SHA256:ypeBEsobvcr6wjGzmiPcTaeG7/gUfE5yuYB3ha/uSLs"),
                role = HostRole.Observer,
                lastStatus = HostStatus.Reachable,
                lastUpdatedEpochMs = 100,
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
                hints = listOf("studio.tailnet", "10.0.0.2"),
                port = 22,
            ),
        )
        store.setLastConnectedAddress("host-1", "10.0.0.2")
        store.updateStatus("host-1", HostStatus.Stale, 200)
        val loaded = store.current().hosts.single()
        assertEquals("studio", loaded.name)
        assertEquals(listOf("studio.tailnet", "10.0.0.2"), loaded.hints)
        assertEquals("misaka", loaded.user)
        assertEquals(listOf("SHA256:ypeBEsobvcr6wjGzmiPcTaeG7/gUfE5yuYB3ha/uSLs"), loaded.hostKeys)
        assertEquals(HostRole.Observer, loaded.role)
        assertEquals(HostStatus.Stale, loaded.lastStatus)
        assertEquals(200, loaded.lastUpdatedEpochMs)
        assertEquals("10.0.0.2", loaded.lastGood)
        assertEquals("default", loaded.topology?.sessionName)
        assertNull(loaded.topology?.workspaces?.first()?.name?.takeIf { it == "secret-prompt" })
        val bytes = FileSystem.SYSTEM.read(path) { readUtf8() }
        assertFalse(bytes.contains("luv_tok"))
        assertFalse(bytes.contains("BEGIN OPENSSH"))
        assertFalse(bytes.contains("prompt"))
        assertTrue(bytes.contains("studio"))
        assertTrue(bytes.contains("\"hints\""))
        assertTrue(bytes.contains("\"hostKeys\""))
        assertFalse(bytes.contains("\"hostKeyFingerprints\""))
        assertFalse(bytes.contains("\"alias\""))
        assertFalse(bytes.contains("\"username\""))
        store.remove("host-1")
        assertTrue(store.current().hosts.isEmpty())
    }

    @Test
    fun rememberSuccessfulAddressSetsLastGoodWithoutRewritingHints() = runTest {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "luvia-host-addr-${Random.nextLong()}.json"
        val store = HostStore(path.toString(), backgroundScope)
        val hints = listOf("192.168.1.16", "Yui.local", "172.18.0.1")
        store.upsert(
            HostProfile(
                id = "host-1",
                name = "Yui.local",
                user = "misaka",
                hostKeys = listOf("SHA256:ypeBEsobvcr6wjGzmiPcTaeG7/gUfE5yuYB3ha/uSLs"),
                role = HostRole.Controller,
                lastStatus = HostStatus.Unknown,
                lastUpdatedEpochMs = 1,
                topology = null,
                hints = hints,
                port = 22,
                lastGood = "Yui.local",
            ),
        )
        store.setLastConnectedAddress("host-1", "192.168.1.33")
        val afterOk = store.current().hosts.single()
        assertEquals(hints, afterOk.hints)
        assertEquals("192.168.1.33", afterOk.lastGood)
    }

    @Test
    fun hostFileMissingRoleFailsToParse() = runTest {
        val thrown =
            assertFailsWith<CorruptionException> {
                HostCatalogOkioSerializer.readFrom(
                    Buffer().writeUtf8(
                        """{"version":2,"hosts":[{"id":"host-1","name":"Yui","user":"misaka","port":22,"hostKeys":["SHA256:ypeBEsobvcr6wjGzmiPcTaeG7/gUfE5yuYB3ha/uSLs"]}],"updatedEpochMs":1}""",
                    ),
                )
            }
        assertTrue(thrown.cause?.message?.contains("missing role") == true, thrown.toString())
    }

    @Test
    fun hostFilePortZeroFailsToParse() = runTest {
        val thrown =
            assertFailsWith<CorruptionException> {
                HostCatalogOkioSerializer.readFrom(
                    Buffer().writeUtf8(
                        """{"version":2,"hosts":[{"id":"host-1","role":"Controller","name":"Yui","user":"misaka","port":0,"hostKeys":["SHA256:ypeBEsobvcr6wjGzmiPcTaeG7/gUfE5yuYB3ha/uSLs"]}],"updatedEpochMs":1}""",
                    ),
                )
            }
        assertTrue(thrown.cause?.message?.contains("port is 0") == true, thrown.toString())
    }
}
