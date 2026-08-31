package tech.asahiart.luvia

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

data class AmbientStatus(
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
    }

    fun show(status: AmbientStatus, allowSensitiveSnippet: Boolean): Boolean {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }

        val openApp = PendingIntent.getActivity(
            appContext,
            0,
            Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
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

    fun dismiss() {
        manager.cancel(NOTIFICATION_ID)
    }

    private companion object {
        const val CHANNEL_ID = "active_session"
        const val NOTIFICATION_ID = 0x4C55
        const val MAX_SNIPPET_CHARS = 160
    }
}
