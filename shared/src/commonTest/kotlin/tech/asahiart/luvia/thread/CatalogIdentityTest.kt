package tech.asahiart.luvia.thread

import tech.asahiart.luvia.AgentKind
import tech.asahiart.luvia.AgentStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Two hosts may legally use the same local pane id, including the ACP placeholder "acp".
 * The old catalog lookup was `first { it.id == threadId }`, which returns whichever host sorts first.
 */
class CatalogIdentityTest {
    @Test
    fun bothCatalogOrdersOpenTheClickedHost() {
        val a = pane(host = "A", pane = "1", title = "title-A", question = "Q-A")
        val b = pane(host = "B", pane = "1", title = "title-B", question = "Q-B")
        val aFirst = listOf(a, b)
        val bFirst = listOf(b, a)

        assertEquals("A", aFirst.first { it.id == "1" }.hostId)
        assertEquals("B", bFirst.first { it.id == "1" }.hostId)

        for (catalog in listOf(aFirst, bFirst)) {
            val opened = resolveOpenThread(catalog, hostId = "B", threadId = "1")
            assertEquals("B", opened?.hostId)
            assertEquals("1", opened?.paneId)
            assertEquals("title-B", opened?.title)
            assertEquals("Q-B", opened?.ask?.question)
            assertEquals("B", opened?.hostId)
        }
    }

    @Test
    fun sameLocalIdKeepsDistinctQuestionsAndTrails() {
        val a = pane(host = "A", pane = "1", title = "title-A", question = "Q-A")
        val b = pane(host = "B", pane = "1", title = "title-B", question = "Q-B")
        val bare = mutableMapOf<String, String>()
        bare[a.id] = "trail-A"
        bare[b.id] = "trail-B"
        assertEquals("trail-B", bare["1"])

        val trails = mutableMapOf<String, String>()
        trails[routeKey(a)] = "trail-A"
        trails[routeKey(b)] = "trail-B"
        assertEquals("trail-A", trails[routeKey("A", "1")])
        assertEquals("trail-B", trails[routeKey("B", "1")])
        assertEquals("Q-A", resolveOpenThread(listOf(a, b), "A", "1")?.ask?.question)
        assertEquals("Q-B", resolveOpenThread(listOf(a, b), "B", "1")?.ask?.question)
    }

    @Test
    fun searchForHostBDoesNotKeepHostAPending() {
        val a = pane(host = "A", pane = "1", title = "alpha", question = "Q-A")
        val b = pane(host = "B", pane = "1", title = "beta", question = "Q-B")
        val pending = listOf(a, b)
        val shown = searchSessions(listOf(b), "beta")
        assertEquals(listOf("B"), shown.map { it.hostId })

        val bareIds = shown.map { it.id }.toSet()
        assertEquals(listOf("A", "B"), pending.filter { it.id in bareIds }.map { it.hostId })

        val matched = pendingMatchingShown(pending, shown)
        assertEquals(listOf("B"), matched.map { it.hostId })
        assertEquals("Q-B", matched.single().ask?.question)
    }

    @Test
    fun twoAcpPlaceholdersStayDistinct() {
        val a = acp(host = "A", title = "acp-A", question = "ask-A")
        val b = acp(host = "B", title = "acp-B", question = "ask-B")
        assertEquals("acp", a.id)
        assertEquals("acp", b.id)
        assertNotEquals(routeKey(a), routeKey(b))
        val opened = resolveOpenThread(listOf(a, b), hostId = "B", threadId = "acp")
        assertEquals("B", opened?.hostId)
        assertEquals("ask-B", opened?.ask?.question)
        assertEquals("A", listOf(a, b).first { it.id == "acp" }.hostId)
    }

    @Test
    fun terminalOwnerFollowsTheHostOfThePane() {
        val a = pane(host = "A", pane = "1", title = "title-A", question = null)
        val b = pane(host = "B", pane = "1", title = "title-B", question = null)
        val catalog = listOf(a, b)
        assertEquals("A", terminalOwningSession(catalog, "1")?.hostId)
        assertEquals("B", terminalOwningSessionOnHost(catalog, "B", "1")?.hostId)
        assertEquals("A", terminalOwningSessionOnHost(catalog, "A", "1")?.hostId)
        assertNull(terminalOwningSessionOnHost(catalog, "B", "9"))
    }

    @Test
    fun newSessionEntryCarriesTheExactHostAndCwdOrRefuses() {
        val nilCwd = newSessionEntry(hostId = "host-a", cwd = null, controller = true, live = true)
        assertEquals(NewSessionEntry("host-a", null, openComposer = true), nilCwd)
        val path = newSessionEntry(hostId = "host-b", cwd = "/work/app", controller = true, live = true)
        assertEquals(NewSessionEntry("host-b", "/work/app", openComposer = true), path)
        val blank = newSessionEntry(hostId = "host-b", cwd = "  ", controller = true, live = true)
        assertEquals(null, blank?.cwd)
        assertEquals(true, blank?.openComposer)
        assertNull(newSessionEntry(hostId = "host-b", cwd = "/work/app", controller = false, live = true))
        assertNull(newSessionEntry(hostId = "host-b", cwd = "/work/app", controller = true, live = false))
        assertNull(newSessionEntry(hostId = " ", cwd = "/work/app", controller = true, live = true))
    }

    private fun pane(host: String, pane: String, title: String, question: String?) = AgentThread(
        id = pane,
        kind = AgentKind.Pane,
        hostId = host,
        projectKey = null,
        projectLabel = null,
        title = title,
        agentType = null,
        status = if (question == null) AgentStatus.Idle else AgentStatus.Blocked,
        ask = question?.let { Ask(it, emptyList()) },
        summary = null,
        updatedEpochMs = null,
        paneId = pane,
    )

    private fun acp(host: String, title: String, question: String) = AgentThread(
        id = "acp",
        kind = AgentKind.Acp,
        hostId = host,
        projectKey = null,
        projectLabel = null,
        title = title,
        agentType = null,
        status = AgentStatus.Blocked,
        ask = Ask(question, emptyList()),
        summary = null,
        updatedEpochMs = null,
    )
}
