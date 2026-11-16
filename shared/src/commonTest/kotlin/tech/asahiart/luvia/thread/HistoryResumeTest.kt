package tech.asahiart.luvia.thread

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HistoryResumeTest {
    @Test
    fun existingSessionOnADoesNotCompleteAResumeOfB() {
        val wait = ResumeWait("B", 4)
        val next =
            reduceHistoryResume(
                ResumeNavigation(wait, "A", "sessions"),
                selectedHostId = "B",
                snapshotHostId = "A",
                snapshotGeneration = 4,
                open = true,
                errorText = null,
                sessionId = "sess-a",
            )
        assertEquals(wait, next.wait)
        assertEquals("A", next.routeHostId)
        assertEquals("sessions", next.stage)
    }

    @Test
    fun noSessionOnAStaysOnBUntilThatGenerationOpens() {
        val wait = ResumeWait("B", 4)
        val start = ResumeNavigation(wait, "A", "sessions")
        val pending =
            reduceHistoryResume(start, "B", "B", 4, open = true, errorText = null, sessionId = "")
        assertEquals(start, pending)
        val opened =
            reduceHistoryResume(pending, "B", "B", 4, open = true, errorText = null, sessionId = "sess-b")
        assertNull(opened.wait)
        assertEquals("B", opened.routeHostId)
        assertEquals("chat", opened.stage)
        val again =
            reduceHistoryResume(opened, "B", "B", 4, open = true, errorText = null, sessionId = "sess-b")
        assertEquals(opened, again)
    }

    @Test
    fun rejectedOrFailedResumeDoesNotOpenA() {
        val wait = ResumeWait("B", 4)
        val start = ResumeNavigation(wait, "A", "sessions")
        assertNull(beginHistoryResume("B", null))
        val failed =
            reduceHistoryResume(start, "B", "B", 4, open = false, errorText = "no grant", sessionId = "")
        assertNull(failed.wait)
        assertEquals("sessions", failed.stage)
        assertEquals("A", failed.routeHostId)
    }

    @Test
    fun lateGenerationOrHostSwitchDoesNotTakeTheRoute() {
        val wait = ResumeWait("B", 4)
        val start = ResumeNavigation(wait, "A", "sessions")
        val switched = reduceHistoryResume(start, "A", "B", 4, open = true, errorText = null, sessionId = "sess-b")
        assertNull(switched.wait)
        assertEquals("sessions", switched.stage)
        val stale =
            reduceHistoryResume(start, "B", "B", 5, open = true, errorText = null, sessionId = "sess-old")
        assertNull(stale.wait)
        assertEquals("sessions", stale.stage)
        val away = start.copy(stage = "terminal")
        val left = reduceHistoryResume(away, "B", "B", 4, open = true, errorText = null, sessionId = "sess-b")
        assertNull(left.wait)
        assertEquals("terminal", left.stage)
    }

    @Test
    fun acceptedReceiptBindsTheResumeGeneration() {
        val wait = beginHistoryResume("B", LaunchReceipt(4))
        assertEquals(ResumeWait("B", 4), wait)
    }
}
