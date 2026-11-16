package tech.asahiart.luvia.thread

import tech.asahiart.luvia.AgentKind
import tech.asahiart.luvia.AgentStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    @Test
    fun openPathsAreCurrentHostDirectoriesWithSessions() {
        val threads = listOf(
            thread("old", "mbp", cwd = "/work/luvus/", status = AgentStatus.Idle, updated = 10),
            thread("new", "mbp", cwd = " /work/luvia ", status = AgentStatus.Working, updated = 50),
            thread(
                "ask",
                "mbp",
                cwd = "/work/luvia/",
                status = AgentStatus.Blocked,
                ask = Ask("go?", emptyList()),
                updated = 40,
            ),
            thread("other", "box", cwd = "/work/infra", status = AgentStatus.Idle, updated = 99),
            thread("blank", "mbp", cwd = "///", status = AgentStatus.Idle, updated = 80),
            thread("undated", "mbp", cwd = "/work/notes", status = AgentStatus.Done, updated = null),
        )
        assertEquals(
            listOf(
                SessionPathOption("/work/luvia", 2),
                SessionPathOption("/work/luvus", 1),
                SessionPathOption("/work/notes", 1),
            ),
            openSessionPaths(threads, "mbp"),
        )
        assertEquals(listOf(SessionPathOption("/work/infra", 1)), openSessionPaths(threads, "box"))
        assertEquals(emptyList(), openSessionPaths(threads, null))
    }

    @Test
    fun displayedPathFollowsArrivalsWithoutChangingStored() {
        val stored = HostSessionFilter(SessionScope.Path("/work/luvia"), SessionFilter.NeedsYou)
        val missing = thread("other", "mbp", cwd = "/work/other", status = AgentStatus.Idle)
        assertEquals(
            HostSessionFilter(SessionScope.CurrentHost, SessionFilter.NeedsYou),
            displayedHostSessionFilter(stored, listOf(missing), pinned = false),
        )
        assertEquals(stored, stored.copy())
        val found = thread("luvia", "mbp", cwd = "/work/luvia", status = AgentStatus.Idle)
        assertEquals(stored, displayedHostSessionFilter(stored, listOf(missing, found), pinned = false))
        assertEquals(false, shouldPinStoredPath(stored, listOf(missing)))
        assertEquals(true, shouldPinStoredPath(stored, listOf(found)))
    }

    @Test
    fun pinnedPathStaysWhenItsSessionsClose() {
        val stored = HostSessionFilter(SessionScope.Path("/work/luvia"), SessionFilter.Idle)
        assertEquals(stored, displayedHostSessionFilter(stored, emptyList(), pinned = true))
    }

    @Test
    fun unpinnedMissingPathDisplaysCurrentHost() {
        val stored = HostSessionFilter(SessionScope.Path("/work/luvia"), SessionFilter.Running)
        val shown = displayedHostSessionFilter(
            stored,
            listOf(thread("x", "mbp", cwd = "/tmp/other", status = AgentStatus.Idle)),
            pinned = false,
        )
        assertEquals(HostSessionFilter(SessionScope.CurrentHost, SessionFilter.Running), shown)
        val other = HostSessionFilter()
        assertEquals(other, displayedHostSessionFilter(other, emptyList(), pinned = false))
    }

    @Test
    fun hostSessionListLoadingIsEmptyAndNotLive() {
        assertEquals(true, hostSessionListLoading(0, false))
        assertEquals(false, hostSessionListLoading(0, true))
        assertEquals(false, hostSessionListLoading(1, false))
    }

    @Test
    fun encodedFilterRoundTripsAndRejectsGarbage() {
        val samples = listOf(
            HostSessionFilter(),
            HostSessionFilter(SessionScope.CurrentHost, SessionFilter.NeedsYou),
            HostSessionFilter(SessionScope.Path("/work/luvia|notes"), SessionFilter.Idle),
            HostSessionFilter(SessionScope.Path(" /work/luvia/ "), SessionFilter.Running),
        )
        for (filter in samples) {
            assertEquals(filter, decodeHostSessionFilter(encodeHostSessionFilter(filter)))
        }
        assertEquals(HostSessionFilter(), decodeHostSessionFilter(null))
        assertEquals(HostSessionFilter(), decodeHostSessionFilter(""))
        assertEquals(HostSessionFilter(), decodeHostSessionFilter("nope"))
        assertEquals(HostSessionFilter(), decodeHostSessionFilter("1\u001fall\u001fNotAStatus\u001f"))
        assertEquals(HostSessionFilter(), decodeHostSessionFilter("1\u001fpath\u001fAll"))
        assertEquals(HostSessionFilter(), decodeHostSessionFilter("1\u001fnope\u001fAll\u001f"))
        assertEquals(HostSessionFilter(), decodeHostSessionFilter("1\u001fpath\u001fAll\u001f"))
    }

    @Test
    fun decodingMemoryPersistsEachHost() {
        val entries = MemoryEntries()
        val memory = DecodingHostFilterMemory(entries)
        val path = HostSessionFilter(SessionScope.Path("/work/luvia"), SessionFilter.Idle)
        memory.put("box", path)
        assertEquals(path, memory.get("box"))
        assertEquals(HostSessionFilter(), memory.get("other"))
        assertEquals(path, DecodingHostFilterMemory(entries).get("box"))
    }

    @Test
    fun restoreDropsOnlyAPathWithNoSessions() {
        val stored = HostSessionFilter(SessionScope.Path("/work/luvia/"), SessionFilter.NeedsYou)
        val live = thread("a", "mbp", cwd = "/work/luvia", status = AgentStatus.Idle)
        assertEquals(stored, restoreHostSessionFilter(stored, listOf(live)))
        val other = thread("b", "mbp", cwd = "/other", status = AgentStatus.Idle)
        assertEquals(
            HostSessionFilter(SessionScope.CurrentHost, SessionFilter.NeedsYou),
            restoreHostSessionFilter(stored, listOf(other)),
        )
        assertEquals(
            HostSessionFilter(SessionScope.CurrentHost, SessionFilter.NeedsYou),
            restoreHostSessionFilter(stored, emptyList()),
        )
        val online = HostSessionFilter(SessionScope.AllOnline, SessionFilter.Running)
        assertEquals(online, restoreHostSessionFilter(online, emptyList()))
        val blank = HostSessionFilter(SessionScope.Path("///"), SessionFilter.Idle)
        assertEquals(
            HostSessionFilter(SessionScope.CurrentHost, SessionFilter.Idle),
            restoreHostSessionFilter(blank, listOf(live)),
        )
    }

    @Test
    fun selectedPathStaysListedAtZero() {
        val paths = listOf(SessionPathOption("/work/luvia", 2))
        assertEquals(paths, withSelectedPath(paths, SessionScope.Path("/work/luvia/")))
        assertEquals(
            listOf(SessionPathOption("/work/luvia", 2), SessionPathOption("/tmp/gone", 0)),
            withSelectedPath(paths, SessionScope.Path("/tmp/gone")),
        )
        assertEquals(paths, withSelectedPath(paths, SessionScope.AllOnline))
    }

    @Test
    fun homeRelativeUsesTheHostUsername() {
        val home = hostHomeDirectory("ada", listOf("/Users/ada/luvia", "/work/notes"))
        assertEquals("/Users/ada", home)
        assertEquals("~/luvia", homeRelativePath("/Users/ada/luvia/", home))
        assertEquals("~", homeRelativePath("/Users/ada", home))
        assertEquals("/work/notes", homeRelativePath("/work/notes", home))
        assertNull(hostHomeDirectory("ada", listOf("/work/notes")))
        assertNull(hostHomeDirectory("", listOf("/Users/ada/luvia")))
        assertNull(hostHomeDirectory("ada", emptyList()))
        assertEquals("/home/ada", hostHomeDirectory("ada", listOf("/home/ada/proj")))
    }

    @Test
    fun collidingPathChipsKeepAParent() {
        val home = "/Users/ada"
        val texts = sessionPathTexts(
            listOf(
                "/Users/ada/chezmoi",
                "/Users/ada/Luvia/app",
                "/Users/ada/Other/app",
                "/Users/ada/Developer/Luvia/app",
                "/Users/ada/src/Luvia/app",
            ),
            home,
        )
        assertEquals("chezmoi", texts[0].chip)
        assertEquals("~/chezmoi", texts[0].relative)
        assertEquals("Luvia/app", texts[1].chip)
        assertEquals("~/Luvia/app", texts[1].relative)
        assertEquals("Other/app", texts[2].chip)
        assertEquals("Developer/Luvia/app", texts[3].chip)
        assertEquals("src/Luvia/app", texts[4].chip)
        val absolute = sessionPathTexts(listOf("/work/app"), null).single()
        assertEquals("app", absolute.chip)
        assertEquals("/work/app", absolute.relative)
    }

    private class MemoryEntries : HostFilterEntries {
        val values = mutableMapOf<String, String>()

        override fun read(hostId: String): String? = values[hostId]

        override fun write(hostId: String, encoded: String) {
            values[hostId] = encoded
        }
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
        updated: Long? = null,
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
        updatedEpochMs = updated,
        cwd = cwd,
    )
}
