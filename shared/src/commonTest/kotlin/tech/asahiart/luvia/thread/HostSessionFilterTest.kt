package tech.asahiart.luvia.thread

import tech.asahiart.luvia.AgentKind
import tech.asahiart.luvia.AgentStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostSessionFilterTest {
    @Test
    fun allOnlineKeepsSessionsOnOnlineHosts() {
        val online = setOf("mbp", "box")
        val shown = filterSessionsByHost(
            sample(),
            hostId = null,
            filter = HostSessionFilter(SessionScope.AllOnline, SessionFilter.All),
            onlineHostIds = online,
        )
        assertEquals(listOf("mbp-luvia", "mbp-luvus", "mbp-done", "box-infra"), shown.map { it.id })
        assertEquals(
            emptyList(),
            filterSessionsByHost(sample(), "mbp", HostSessionFilter(), emptySet()).map { it.id },
        )
    }

    @Test
    fun currentHostKeepsThatHostEvenWhenOffline() {
        val shown = filterSessionsByHost(
            sample(),
            hostId = "nas",
            filter = HostSessionFilter(SessionScope.CurrentHost, SessionFilter.All),
            onlineHostIds = setOf("mbp", "box"),
        )
        assertEquals(listOf("nas-cache"), shown.map { it.id })
        assertEquals(
            emptyList(),
            filterSessionsByHost(
                sample(),
                hostId = null,
                filter = HostSessionFilter(scope = SessionScope.CurrentHost),
                onlineHostIds = setOf("mbp"),
            ).map { it.id },
        )
    }

    @Test
    fun pathMatchesNormalizedCwdOnTheCurrentHost() {
        val filter = HostSessionFilter(SessionScope.Path(" /work/luvia/ "), SessionFilter.All)
        val boxSame = thread("box-luvia", "box", cwd = "/work/luvia", status = AgentStatus.Working)
        val threads = sample() + boxSame
        val shown = filterSessionsByHost(threads, "mbp", filter, onlineHostIds = emptySet())
        assertEquals(listOf("mbp-luvia"), shown.map { it.id })
        val sameTextOnTheOtherHost = filterSessionsByHost(threads, "box", filter, onlineHostIds = emptySet())
        assertEquals(listOf("box-luvia"), sameTextOnTheOtherHost.map { it.id })
        val nested = thread("nested", "mbp", cwd = "/work/luvia/app", status = AgentStatus.Idle)
        assertEquals(
            listOf("mbp-luvia"),
            filterSessionsByHost(threads + nested, "mbp", filter, emptySet()).map { it.id },
        )
        val slashOnly = HostSessionFilter(SessionScope.Path("/"), SessionFilter.All)
        val root = thread("root", "mbp", cwd = "/", status = AgentStatus.Idle)
        assertEquals(
            emptyList(),
            filterSessionsByHost(listOf(root), "mbp", slashOnly, setOf("mbp")).map { it.id },
        )
        assertEquals(
            emptyList(),
            filterSessionsByHost(
                sample(),
                hostId = null,
                filter = HostSessionFilter(SessionScope.Path("/work/luvia")),
                onlineHostIds = setOf("mbp"),
            ).map { it.id },
        )
    }

    @Test
    fun statusIntersectsScope() {
        val online = setOf("mbp", "box")
        val running = filterSessionsByHost(
            sample(),
            "mbp",
            HostSessionFilter(SessionScope.AllOnline, SessionFilter.Running),
            online,
        )
        assertEquals(listOf("mbp-luvus"), running.map { it.id })
        val waiting = filterSessionsByHost(
            sample(),
            "mbp",
            HostSessionFilter(SessionScope.CurrentHost, SessionFilter.NeedsYou),
            online,
        )
        assertEquals(listOf("mbp-luvia"), waiting.map { it.id })
        val idleOnPath = filterSessionsByHost(
            sample(),
            "box",
            HostSessionFilter(SessionScope.Path("/work/infra/"), SessionFilter.Idle),
            online,
        )
        assertEquals(listOf("box-infra"), idleOnPath.map { it.id })
        val doneCountsAsIdle = filterSessionsByHost(
            sample(),
            "mbp",
            HostSessionFilter(SessionScope.CurrentHost, SessionFilter.Idle),
            onlineHostIds = emptySet(),
        )
        assertEquals(listOf("mbp-done"), doneCountsAsIdle.map { it.id })
    }

    @Test
    fun scopePopulationIgnoresStatus() {
        val online = setOf("mbp", "box")
        val scope = filterSessionsByHost(
            sample(),
            "mbp",
            HostSessionFilter(SessionScope.AllOnline, SessionFilter.All),
            online,
        )
        val waiting = filterSessionsByHost(
            sample(),
            "mbp",
            HostSessionFilter(SessionScope.AllOnline, SessionFilter.NeedsYou),
            online,
        )
        assertEquals(scope.filter { it.needsYou }.map { it.id }, waiting.map { it.id })
        assertTrue(scope.any { it.id == "box-infra" })
        assertTrue(waiting.none { it.id == "nas-cache" })
    }

    @Test
    fun hostSwitchResetsScopeAndKeepsStatus() {
        val memory = InMemoryHostFilterMemory()
        memory.put(
            "mbp",
            HostSessionFilter(SessionScope.Path("/work/luvia"), SessionFilter.NeedsYou),
        )
        memory.put(
            "box",
            HostSessionFilter(SessionScope.Path("/work/infra"), SessionFilter.Running),
        )
        val shown = memory.applyHostSwitch("mbp")
        assertEquals(HostSessionFilter(SessionScope.AllOnline, SessionFilter.NeedsYou), shown)
        assertEquals(shown, memory.get("mbp"))
        assertEquals(
            HostSessionFilter(SessionScope.Path("/work/infra"), SessionFilter.Running),
            memory.get("box"),
        )
        val fresh = InMemoryHostFilterMemory()
        assertEquals(HostSessionFilter(), fresh.applyHostSwitch("nas"))
        assertEquals(HostSessionFilter(), fresh.get("nas"))
    }

    @Test
    fun memoryStoresScopeAndStatusPerHost() {
        val memory = InMemoryHostFilterMemory()
        assertEquals(HostSessionFilter(), memory.get("mbp"))
        val path = HostSessionFilter(SessionScope.Path(" /work/luvia/ "), SessionFilter.Idle)
        memory.put("mbp", path)
        memory.put("box", HostSessionFilter(SessionScope.CurrentHost, SessionFilter.Running))
        assertEquals(path, memory.get("mbp"))
        assertEquals(SessionScope.Path(" /work/luvia/ "), memory.get("mbp").scope)
        assertEquals(
            HostSessionFilter(SessionScope.CurrentHost, SessionFilter.Running),
            memory.get("box"),
        )
        assertEquals(HostSessionFilter(), memory.get("nas"))
        val replaced = HostSessionFilter(SessionScope.AllOnline, SessionFilter.NeedsYou)
        memory.put("mbp", replaced)
        assertEquals(replaced, memory.get("mbp"))
        assertEquals(
            HostSessionFilter(SessionScope.CurrentHost, SessionFilter.Running),
            memory.get("box"),
        )
    }

    private fun sample(): List<AgentThread> = listOf(
        thread(
            "mbp-luvia",
            "mbp",
            cwd = "/work/luvia/",
            status = AgentStatus.Blocked,
            ask = Ask("go?", emptyList()),
        ),
        thread("mbp-luvus", "mbp", cwd = "/work/luvus", status = AgentStatus.Working),
        thread("mbp-done", "mbp", cwd = "/work/perler", status = AgentStatus.Done),
        thread("box-infra", "box", cwd = "/work/infra", status = AgentStatus.Idle),
        thread(
            "nas-cache",
            "nas",
            cwd = "/work/share",
            status = AgentStatus.Blocked,
            ask = Ask("still there?", emptyList()),
        ),
    )

    private fun thread(
        id: String,
        hostId: String,
        cwd: String?,
        status: AgentStatus,
        ask: Ask? = null,
    ) = AgentThread(
        id = id,
        kind = AgentKind.Pane,
        hostId = hostId,
        projectKey = cwd,
        projectLabel = null,
        title = id,
        agentType = null,
        status = status,
        ask = ask,
        summary = null,
        updatedEpochMs = null,
        cwd = cwd,
    )
}
