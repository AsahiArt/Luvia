package tech.asahiart.luvia.thread

import tech.asahiart.luvia.AgentKind
import tech.asahiart.luvia.AgentStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationRouteTest {
    @Test
    fun secondHostEventIsNotSwallowedByTheFirstConsume() {
        val first = reduceNotification(NotificationCursor(), event(1, "host-a", false), emptyList()) { false }
        assertTrue(first.showSessions)
        val second = reduceNotification(first.cursor, event(2, "host-b", true), listOf(blocked("host-b", "1"))) {
            true
        }
        assertEquals("host-b", second.routeThread?.hostId)
        assertEquals("1", second.routeThread?.paneId)
        assertEquals(2L, second.cursor.consumedEventId)
    }

    @Test
    fun sameHostSecondEventOpensAgain() {
        val firstThread = blocked("host-a", "1")
        val secondThread = blocked("host-a", "2")
        val first = reduceNotification(
            NotificationCursor(),
            event(1, "host-a", true),
            listOf(firstThread, secondThread),
        ) { true }
        assertEquals("1", first.routeThread?.paneId)
        val afterFirst = listOf(firstThread.copy(ask = null, status = AgentStatus.Idle), secondThread)
        val second = reduceNotification(first.cursor, event(2, "host-a", true), afterFirst) { true }
        assertEquals("2", second.routeThread?.paneId)
        assertEquals(2L, second.cursor.consumedEventId)
    }

    @Test
    fun refusedOpenDoesNotRouteAndFreshOpenDoes() {
        val thread = blocked("host-a", "8")
        var calls = 0
        val refused = reduceNotification(NotificationCursor(), event(1, "host-a", true), listOf(thread)) {
            calls += 1
            false
        }
        assertEquals(1, calls)
        assertNull(refused.routeThread)
        assertEquals(1L, refused.cursor.consumedEventId)
        val opened = reduceNotification(NotificationCursor(), event(1, "host-a", true), listOf(thread)) { true }
        assertEquals(thread, opened.routeThread)
    }

    @Test
    fun catalogArrivesLaterAndANewerEventReplacesThePendingOne() {
        val waiting = reduceNotification(NotificationCursor(), event(1, "host-a", true), emptyList()) { true }
        assertNull(waiting.routeThread)
        assertEquals(-1L, waiting.cursor.consumedEventId)
        assertEquals(1L, waiting.cursor.pendingEventId)
        val replaced = reduceNotification(waiting.cursor, event(2, "host-b", true), emptyList()) { true }
        assertEquals(2L, replaced.cursor.pendingEventId)
        val late = reduceNotification(
            replaced.cursor,
            event(1, "host-a", true),
            listOf(blocked("host-a", "1"), blocked("host-b", "1")),
        ) { true }
        assertNull(late.routeThread)
        assertEquals(replaced.cursor, late.cursor)
        val ready = reduceNotification(
            replaced.cursor,
            event(2, "host-b", true),
            listOf(blocked("host-a", "1"), blocked("host-b", "1")),
        ) { it.hostId == "host-b" }
        assertEquals("host-b", ready.routeThread?.hostId)
        assertEquals(2L, ready.cursor.consumedEventId)
    }

    @Test
    fun theSameEventDoesNotOpenTwice() {
        val thread = blocked("host-a", "1")
        var calls = 0
        val first = reduceNotification(NotificationCursor(), event(4, "host-a", true), listOf(thread)) {
            calls += 1
            true
        }
        val again = reduceNotification(first.cursor, event(4, "host-a", true), listOf(thread)) {
            calls += 1
            true
        }
        assertEquals(1, calls)
        assertNull(again.routeThread)
        assertEquals(first.cursor, again.cursor)
    }

    private fun event(id: Long, host: String, blocked: Boolean) =
        NotificationEvent(id, host, blocked)

    private fun blocked(host: String, pane: String) = AgentThread(
        id = pane,
        kind = AgentKind.Pane,
        hostId = host,
        projectKey = null,
        projectLabel = null,
        title = "title-$host-$pane",
        agentType = null,
        status = AgentStatus.Blocked,
        ask = Ask("Q-$pane", emptyList()),
        summary = null,
        updatedEpochMs = null,
        paneId = pane,
    )
}
