package tech.asahiart.luvia

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import java.time.Duration
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Drives MainActivity through onCreate, onNewIntent, onSaveInstanceState, and a
 * later onCreate with the original launch intent. The catalog has no session,
 * so a blocked notification stays pending and a refused open is not reached.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi", fontScale = 1.0f)
class MainActivityNotificationTest {
    @Test
    fun pendingBlockedBRestoresOverTheOriginalLaunch() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, launch("host-a", false)).setup()
        idle()
        controller.newIntent(launch("host-b", true))
        idle()
        val live = controller.get()
        assertEquals("host-b", live.notificationLaunch.hostId)
        assertEquals(true, live.notificationLaunch.openBlocked)
        assertEquals(1L, live.notificationLaunch.eventId)
        assertEquals("host-b", live.appliedNotificationEvent?.hostId)
        assertEquals(true, live.appliedNotificationEvent?.openBlocked)
        assertEquals(1L, live.appliedNotificationResult?.cursor?.pendingEventId)
        assertEquals(0L, live.appliedNotificationResult?.cursor?.consumedEventId)
        assertNull(live.appliedNotificationResult?.routeThread)

        val saved = Bundle()
        controller.pause().saveInstanceState(saved).stop().destroy()
        val restored = Robolectric.buildActivity(MainActivity::class.java, launch("host-a", false)).setup(saved)
        idle()
        val activity = restored.get()
        assertEquals("host-b", activity.notificationLaunch.hostId)
        assertEquals(true, activity.notificationLaunch.openBlocked)
        assertEquals(1L, activity.notificationLaunch.eventId)
        assertEquals("host-b", activity.appliedNotificationEvent?.hostId)
        assertEquals(1L, activity.appliedNotificationResult?.cursor?.pendingEventId)
        assertEquals(0L, activity.appliedNotificationResult?.cursor?.consumedEventId)
        assertNull(activity.appliedNotificationResult?.routeThread)
    }

    @Test
    fun onNewIntentReplacementIsWhatRecreateKeeps() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, launch("host-a", true)).setup()
        idle()
        controller.newIntent(launch("host-b", true))
        controller.newIntent(launch("host-c", false))
        idle()
        val recreated = controller.recreate()
        idle()
        val activity = recreated.get()
        assertEquals("host-c", activity.notificationLaunch.hostId)
        assertEquals(false, activity.notificationLaunch.openBlocked)
        assertEquals(2L, activity.notificationLaunch.eventId)
    }

    @Test
    fun consumedLaunchIsNotAppliedAgain() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, launch("host-a", false)).setup()
        idle()
        assertEquals(1, controller.get().notificationApplyCount)
        assertEquals(0L, controller.get().appliedNotificationResult?.cursor?.consumedEventId)
        val saved = Bundle()
        controller.pause().saveInstanceState(saved).stop().destroy()
        val restored = Robolectric.buildActivity(MainActivity::class.java, launch("host-a", false)).setup(saved)
        idle()
        assertEquals(0, restored.get().notificationApplyCount)
    }

    @Test
    fun missingHostDoesNotAttachTheOriginalLaunch() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, launch("host-a", true)).setup()
        idle()
        controller.newIntent(Intent())
        idle()
        assertNull(controller.get().notificationLaunch.hostId)
        val saved = Bundle()
        controller.pause().saveInstanceState(saved).stop().destroy()
        val restored = Robolectric.buildActivity(MainActivity::class.java, launch("host-a", true)).setup(saved)
        idle()
        assertNull(restored.get().notificationLaunch.hostId)
        assertEquals(false, restored.get().notificationLaunch.openBlocked)
        assertNull(restored.get().appliedNotificationEvent)
    }

    private fun launch(host: String, blocked: Boolean): Intent =
        Intent().apply {
            putExtra(StatusNotificationController.EXTRA_HOST_ID, host)
            putExtra(StatusNotificationController.EXTRA_OPEN_BLOCKED, blocked)
        }

    private fun idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
    }
}
