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
                hostId = "ab".repeat(16),
                name = "studio",
                user = "misaka",
                hostKeys = setOf("SHA256:ypeBEsobvcr6wjGzmiPcTaeG7/gUfE5yuYB3ha/uSLs"),
                bridgePath = "/usr/local/bin/luvia-host",
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
        store.setLastConnectedAddress("ab".repeat(16), "10.0.0.2")
        store.updateStatus("ab".repeat(16), HostStatus.Stale, 200)
        val loaded = store.current().hosts.single()
        assertEquals("studio", loaded.name)
        assertEquals(listOf("studio.tailnet", "10.0.0.2"), loaded.hints)
        assertEquals("misaka", loaded.user)
        assertEquals(setOf("SHA256:ypeBEsobvcr6wjGzmiPcTaeG7/gUfE5yuYB3ha/uSLs"), loaded.hostKeys)
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
        store.remove("ab".repeat(16))
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
                hostId = "ab".repeat(16),
                name = "Yui.local",
                user = "misaka",
                hostKeys = setOf("SHA256:ypeBEsobvcr6wjGzmiPcTaeG7/gUfE5yuYB3ha/uSLs"),
                bridgePath = "/usr/local/bin/luvia-host",
                role = HostRole.Controller,
                lastStatus = HostStatus.Unknown,
                lastUpdatedEpochMs = 1,
                topology = null,
                hints = hints,
                port = 22,
                lastGood = "Yui.local",
            ),
        )
        store.setLastConnectedAddress("ab".repeat(16), "192.168.1.33")
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

    @Test
    fun hostFileInvalidRoleFailsToParse() = runTest {
        val thrown =
            assertFailsWith<CorruptionException> {
                HostCatalogOkioSerializer.readFrom(
                    Buffer().writeUtf8(
                        """{"version":2,"hosts":[{"id":"host-1","hostId":"ab","role":"Admin","name":"Yui","user":"misaka","port":22,"bridgePath":"/usr/local/bin/luvia-host","hostKeys":["SHA256:ypeBEsobvcr6wjGzmiPcTaeG7/gUfE5yuYB3ha/uSLs"]}],"updatedEpochMs":1}""",
                    ),
                )
            }
        assertTrue(thrown.cause?.message?.contains("role is invalid") == true, thrown.toString())
    }

    @Test
    fun hostFileUnknownKeyIsCorrupt() = runTest {
        assertFailsWith<CorruptionException> {
            HostCatalogOkioSerializer.readFrom(
                Buffer().writeUtf8(
                    """{"version":2,"hosts":[{"id":"host-1","hostId":"ab","role":"Observer","alias":"old","name":"Yui","user":"misaka","port":22,"bridgePath":"/usr/local/bin/luvia-host","hostKeys":["SHA256:ypeBEsobvcr6wjGzmiPcTaeG7/gUfE5yuYB3ha/uSLs"]}],"updatedEpochMs":1}""",
                ),
            )
        }
    }

    @Test
    fun mainEraHostsFileOpensAsEmptyCatalogAndAcceptsANewHost() = runTest {
        val mainEra =
            """
            {"version":2,"updatedEpochMs":10,"hosts":[{
              "id":"host-1",
              "alias":"Yui",
              "addresses":["192.168.1.8","100.89.1.2"],
              "address":"192.168.1.8",
              "sshPort":22,
              "username":"misaka",
              "hostKeyFingerprints":["SHA256:ypeBEsobvcr6wjGzmiPcTaeG7/gUfE5yuYB3ha/uSLs"],
              "hostKeySha256":"SHA256:ypeBEsobvcr6wjGzmiPcTaeG7/gUfE5yuYB3ha/uSLs",
              "role":"Controller",
              "lastStatus":"Reachable",
              "lastUpdatedEpochMs":10,
              "lastConnectedAddress":"192.168.1.8"
            }]}
            """.trimIndent()
        assertFailsWith<CorruptionException> {
            HostCatalogOkioSerializer.readFrom(Buffer().writeUtf8(mainEra))
        }
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "luvia-host-main-${Random.nextLong()}.json"
        FileSystem.SYSTEM.write(path) { writeUtf8(mainEra) }
        val store = HostStore(path.toString(), backgroundScope)
        val opened = store.current()
        assertTrue(opened.hosts.isEmpty(), "main-era catalog must not crash or surface old hosts")
        assertEquals(0, opened.updatedEpochMs)
        store.upsert(
            HostProfile(
                id = "host-2",
                hostId = "cd".repeat(16),
                name = "studio",
                user = "misaka",
                hostKeys = setOf("SHA256:ypeBEsobvcr6wjGzmiPcTaeG7/gUfE5yuYB3ha/uSLs"),
                bridgePath = "/usr/local/bin/luvia-host",
                role = HostRole.Controller,
                lastStatus = HostStatus.Unknown,
                lastUpdatedEpochMs = 20,
                topology = null,
                hints = listOf("10.0.0.2"),
                port = 22,
            ),
        )
        val loaded = store.current().hosts.single()
        assertEquals("host-2", loaded.id)
        assertEquals("studio", loaded.name)
        assertEquals(listOf("10.0.0.2"), loaded.hints)
        assertEquals(22, loaded.port)
    }

    @Test
    fun upsertReplacesTheSameHostIdInPlace() = runTest {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "luvia-host-replace-${Random.nextLong()}.json"
        val store = HostStore(path.toString(), backgroundScope)
        val hostId = "cd".repeat(16)
        store.upsert(
            hostFile("other", "ee".repeat(16), "Other"),
        )
        store.upsert(hostFile("dev-old", hostId, "Yui"))
        store.upsert(hostFile("dev-new", hostId, "Yui.local").copy(lastGood = "10.0.0.8"))
        val hosts = store.current().hosts
        assertEquals(listOf("ee".repeat(16), hostId), hosts.map { it.hostId })
        assertEquals("dev-new", hosts[1].id)
        assertEquals("Yui.local", hosts[1].name)
    }

    @Test
    fun duplicateHostIdsInAnOldCatalogCollapseToTheNewest() = runTest {
        val hostId = "cd".repeat(16)
        val otherId = "ee".repeat(16)
        val catalog =
            """
            {"version":2,"updatedEpochMs":9,"hosts":[
              {"id":"other","hostId":"$otherId","role":"Controller","lastStatus":"Unknown","lastUpdatedEpochMs":1,"name":"Other","user":"misaka","port":22,"bridgePath":"/usr/local/bin/luvia-host","hostKeys":["SHA256:ypeBEsobvcr6wjGzmiPcTaeG7/gUfE5yuYB3ha/uSLs"],"hints":["10.0.0.1"]},
              {"id":"dev-old","hostId":"$hostId","role":"Controller","lastStatus":"Unknown","lastUpdatedEpochMs":1,"name":"Old","user":"misaka","port":22,"bridgePath":"/usr/local/bin/luvia-host","hostKeys":["SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU"],"hints":["10.0.0.1"],"lastGood":"10.0.0.1"},
              {"id":"dev-new","hostId":"$hostId","role":"Controller","lastStatus":"Reachable","lastUpdatedEpochMs":9,"name":"New","user":"misaka","port":2222,"bridgePath":"/usr/local/bin/luvia-host","hostKeys":["SHA256:ypeBEsobvcr6wjGzmiPcTaeG7/gUfE5yuYB3ha/uSLs"],"hints":["10.0.0.8"],"lastGood":"10.0.0.8"}
            ]}
            """.trimIndent()
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "luvia-host-dup-${Random.nextLong()}.json"
        FileSystem.SYSTEM.write(path) { writeUtf8(catalog) }
        val hosts = HostStore(path.toString(), backgroundScope).current().hosts
        assertEquals(2, hosts.size)
        assertEquals(otherId, hosts[0].hostId)
        assertEquals("other", hosts[0].id)
        assertEquals("dev-new", hosts[1].id)
        assertEquals("New", hosts[1].name)
        assertEquals(9, hosts[1].lastUpdatedEpochMs)
        assertEquals("10.0.0.8", hosts[1].lastGood)
    }
}

private fun hostFile(id: String, hostId: String, name: String): HostProfile =
    HostProfile(
        id = id,
        hostId = hostId,
        name = name,
        user = "misaka",
        hostKeys = setOf("SHA256:ypeBEsobvcr6wjGzmiPcTaeG7/gUfE5yuYB3ha/uSLs"),
        bridgePath = "/usr/local/bin/luvia-host",
        role = HostRole.Controller,
        lastStatus = HostStatus.Unknown,
        lastUpdatedEpochMs = 1,
        topology = null,
        hints = listOf("10.0.0.2"),
        port = 22,
    )
