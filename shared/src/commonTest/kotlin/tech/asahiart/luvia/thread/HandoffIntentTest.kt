package tech.asahiart.luvia.thread

import kotlin.test.Test
import kotlin.test.assertEquals

class HandoffIntentTest {
    @Test
    fun currentCompletionAdoptsOnceAndARepeatDoesNot() {
        val intent = intent(host = "A", attempt = 1, epoch = 4, baseline = "")
        assertEquals(
            HandoffEffect.Adopt,
            handoffEffect(intent, 1, 4, "A", "A", open = true, errorText = null, sessionId = "a-new"),
        )
        assertEquals(
            HandoffEffect.Ignore,
            handoffEffect(null, 1, 4, "A", "A", open = true, errorText = null, sessionId = "a-new"),
        )
    }

    @Test
    fun returnedNavigationDoesNotAdoptALateAck() {
        val intent = intent(host = "A", attempt = 1, epoch = 4, baseline = "")
        assertEquals(
            HandoffEffect.Ignore,
            handoffEffect(intent, 1, 5, "A", "A", open = true, errorText = null, sessionId = "a-new"),
        )
    }

    @Test
    fun otherHostSessionIsNotThisHandoff() {
        val intent = intent(host = "A", attempt = 1, epoch = 4, baseline = "")
        assertEquals(
            HandoffEffect.Ignore,
            handoffEffect(intent, 1, 4, "B", "B", open = true, errorText = null, sessionId = "b-existing"),
        )
    }

    @Test
    fun failureOnTheOtherHostDoesNotClearThisWait() {
        val intent = intent(host = "B", attempt = 2, epoch = 4, baseline = "b-existing")
        assertEquals(
            HandoffEffect.Ignore,
            handoffEffect(intent, 2, 4, "B", "A", open = false, errorText = "gone", sessionId = ""),
        )
        assertEquals(
            HandoffEffect.ShowFailure,
            handoffEffect(intent, 2, 4, "B", "B", open = false, errorText = "gone", sessionId = "b-existing"),
        )
    }

    @Test
    fun olderAttemptCannotReplaceTheNewerLaunch() {
        val newer = intent(host = "A", attempt = 2, epoch = 4, baseline = "")
        assertEquals(
            HandoffEffect.Adopt,
            handoffEffect(newer, 2, 4, "A", "A", open = true, errorText = null, sessionId = "second"),
        )
        assertEquals(
            HandoffEffect.Ignore,
            handoffEffect(newer, 1, 4, "A", "A", open = true, errorText = null, sessionId = "first"),
        )
    }

    @Test
    fun leavingThePaneDropsTheIntentWithoutASessionClose() {
        val intent = intent(host = "A", attempt = 1, epoch = 4, baseline = "", pane = "shell-1")
        assertEquals(
            HandoffEffect.Ignore,
            handoffEffect(intent, 1, 5, "A", "A", open = true, errorText = null, sessionId = "a-new"),
        )
        assertEquals("shell-1", intent.originPaneId)
    }

    private fun intent(host: String, attempt: Long, epoch: Long, baseline: String, pane: String? = "shell-1") =
        HandoffIntent(host, attempt, pane, epoch, baseline)
}
