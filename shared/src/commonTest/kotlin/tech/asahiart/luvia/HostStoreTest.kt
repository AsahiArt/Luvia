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
                address = "studio.tailnet",
                sshPort = 22,
                username = "misaka",
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
            ),
        )
        val loaded = store.current().hosts.single()
        assertEquals("studio", loaded.alias)
        assertEquals("studio.tailnet", loaded.address)
        assertEquals("misaka", loaded.username)
        assertEquals(HostStatus.Reachable, loaded.lastStatus)
        assertEquals("default", loaded.topology?.sessionName)
        assertNull(loaded.topology?.workspaces?.first()?.name?.takeIf { it == "secret-prompt" })
        val bytes = FileSystem.SYSTEM.read(path) { readUtf8() }
        assertFalse(bytes.contains("luv_tok"))
        assertFalse(bytes.contains("BEGIN OPENSSH"))
        assertFalse(bytes.contains("prompt"))
        assertTrue(bytes.contains("studio"))
        store.remove("host-1")
        assertTrue(store.current().hosts.isEmpty())
    }
}
