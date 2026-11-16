package tech.asahiart.luvia

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import android.os.Build
import tech.asahiart.luvia.thread.NotificationEvent
import tech.asahiart.luvia.thread.NotificationResult
import tech.asahiart.luvia.ui.theme.LuviaTheme

/** The notification a recreated activity must keep together: id, host, and blocked flag. */
internal data class NotificationLaunch(
    val eventId: Long,
    val hostId: String?,
    val openBlocked: Boolean,
) {
    fun advance(intent: Intent): NotificationLaunch = fromIntent(intent, eventId + 1)

    fun asIntent(): Intent =
        Intent().apply {
            hostId?.let { putExtra(StatusNotificationController.EXTRA_HOST_ID, it) }
            putExtra(StatusNotificationController.EXTRA_OPEN_BLOCKED, openBlocked)
        }

    fun write(out: Bundle) {
        out.putBundle(
            KEY,
            Bundle().apply {
                putLong(EVENT, eventId)
                putString(HOST, hostId)
                putBoolean(BLOCKED, openBlocked)
            },
        )
    }

    companion object {
        private const val KEY = "tech.asahiart.luvia.notification.launch"
        private const val EVENT = "eventId"
        private const val HOST = "hostId"
        private const val BLOCKED = "openBlocked"

        fun fromIntent(intent: Intent?, eventId: Long): NotificationLaunch =
            NotificationLaunch(
                eventId = eventId,
                hostId = intent?.getStringExtra(StatusNotificationController.EXTRA_HOST_ID)?.takeIf { it.isNotEmpty() },
                openBlocked = intent?.getBooleanExtra(StatusNotificationController.EXTRA_OPEN_BLOCKED, false) == true,
            )

        fun fromSaved(saved: Bundle?): NotificationLaunch? {
            val nested = saved?.getBundle(KEY) ?: return null
            return NotificationLaunch(
                eventId = nested.getLong(EVENT),
                hostId = nested.getString(HOST)?.takeIf { it.isNotEmpty() },
                openBlocked = nested.getBoolean(BLOCKED),
            )
        }
    }
}

class MainActivity : ComponentActivity() {
    internal val notificationLaunchState = mutableStateOf(NotificationLaunch(0L, null, false))
    internal val notificationLaunch: NotificationLaunch get() = notificationLaunchState.value
    internal var appliedNotificationEvent: NotificationEvent? = null
    internal var appliedNotificationResult: NotificationResult? = null
    internal var notificationApplyCount: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        applyLuviaEdgeToEdge()
        notificationLaunchState.value = NotificationLaunch.fromSaved(savedInstanceState)
            ?: NotificationLaunch.fromIntent(intent, 0L)
        super.onCreate(savedInstanceState)
        setContent {
            val launch = notificationLaunchState.value
            LuviaTheme {
                Surface {
                    LuviaApp(launchIntent = launch.asIntent(), launchEventId = launch.eventId)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationLaunchState.value = notificationLaunch.advance(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        notificationLaunch.write(outState)
    }

    internal fun recordNotification(event: NotificationEvent, result: NotificationResult) {
        appliedNotificationEvent = event
        appliedNotificationResult = result
        notificationApplyCount += 1
    }
}

/** Edge-to-edge, then drop the platform nav-bar scrim [enableEdgeToEdge] turns on. */
internal fun ComponentActivity.applyLuviaEdgeToEdge() {
    enableEdgeToEdge()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isNavigationBarContrastEnforced = false
    }
}

