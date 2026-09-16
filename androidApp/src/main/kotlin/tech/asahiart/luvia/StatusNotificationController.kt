package tech.asahiart.luvia

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONObject
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

data class AmbientStatus(
    val hostId: String,
    val hostName: String,
    val sessionName: String,
    val connection: String,
    val workingAgents: Int,
    val blockedAgents: Int,
    val completedAgents: Int,
    val sensitiveSnippet: String?,
    val isStale: Boolean,
)

class StatusNotificationController(context: Context) {
    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Active Luvus session",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Status for the Luvus session currently open in Luvia"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
        val wakeChannel = NotificationChannel(
            WAKE_CHANNEL_ID,
            "Approvals and blocked agents",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Wakes when an agent needs you"
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(wakeChannel)
    }

    fun show(status: AmbientStatus, allowSensitiveSnippet: Boolean): Boolean {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }

        val openApp = PendingIntent.getActivity(
            appContext,
            0,
            Intent(appContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_HOST_ID, status.hostId)
                .putExtra(EXTRA_OPEN_BLOCKED, status.blockedAgents > 0),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val safeSummary = buildString {
            append(status.workingAgents).append(" working · ")
            append(status.blockedAgents).append(" blocked · ")
            append(status.completedAgents).append(" done")
            if (status.isStale) append(" · stale")
        }
        val privateText = status.sensitiveSnippet
            ?.takeIf { allowSensitiveSnippet }
            ?.lineSequence()
            ?.lastOrNull()
            ?.take(MAX_SNIPPET_CHARS)
            ?: safeSummary
        val publicVersion = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_luvia_status)
            .setContentTitle(status.hostName)
            .setContentText(safeSummary)
            .build()

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_luvia_status)
            .setContentTitle("${status.hostName} · ${status.sessionName}")
            .setContentText(privateText)
            .setSubText(status.connection)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        return true
    }


    fun showWake(payload: ByteArray): Boolean {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val parsed = runCatching { JSONObject(payload.decodeToString()) }.getOrNull()
        val wake = parsed?.optString("wake").orEmpty()
        val count = parsed?.optInt("count", 1)?.coerceAtLeast(1) ?: 1
        val (title, text) = when (wake) {
            "blocked" ->
                "Agent waiting" to if (count == 1) {
                    "An agent is blocked and needs you."
                } else {
                    "$count agents are blocked and need you."
                }
            "permission" ->
                "Approval needed" to if (count == 1) {
                    "An agent is asking for permission."
                } else {
                    "$count permission requests are waiting."
                }
            "done" ->
                "Work finished" to if (count == 1) {
                    "A task finished on the host."
                } else {
                    "$count tasks finished on the host."
                }
            else -> "Luvia" to "Something on a host needs your attention."
        }
        val openApp = PendingIntent.getActivity(
            appContext,
            WAKE_NOTIFICATION_ID,
            Intent(appContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_OPEN_BLOCKED, wake == "blocked" || wake == "permission"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, WAKE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_luvia_status)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        manager.notify(WAKE_NOTIFICATION_ID, notification)
        return true
    }
    fun dismiss() {
        manager.cancel(NOTIFICATION_ID)
    }

    companion object {
        const val CHANNEL_ID = "active_session"
        const val WAKE_CHANNEL_ID = "luvia.wake"
        const val WAKE_NOTIFICATION_ID = 0x4C57
        const val NOTIFICATION_ID = 0x4C55
        const val MAX_SNIPPET_CHARS = 160
        const val EXTRA_HOST_ID = "tech.asahiart.luvia.HOST_ID"
        const val EXTRA_OPEN_BLOCKED = "tech.asahiart.luvia.OPEN_BLOCKED"
    }
}
