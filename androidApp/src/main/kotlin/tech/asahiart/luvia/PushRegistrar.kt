package tech.asahiart.luvia

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.MessagingReceiver
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * UnifiedPush registration glue. [registerApp] / [unregisterApp] wrap the
 * connector 3.x [UnifiedPush.register] / [UnifiedPush.unregister] APIs.
 */
object PushRegistrar {
    const val UNIFIEDPUSH_DOCS = "https://unifiedpush.org"

    @Volatile
    var listener: Listener? = null

    interface Listener {
        fun onEndpoint(endpoint: String)
        fun onUnregistered()
    }

    fun hasDistributor(context: Context): Boolean =
        UnifiedPush.getDistributors(context).isNotEmpty()

    fun wantsPush(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WANTED, false)

    fun cachedEndpoint(context: Context): String? =
        prefs(context).getString(KEY_ENDPOINT, null)?.takeIf { it.isNotBlank() }

    fun registerApp(context: Context, onDistributorReady: (Boolean) -> Unit = {}) {
        prefs(context).edit().putBoolean(KEY_WANTED, true).apply()
        if (!hasDistributor(context)) {
            onDistributorReady(false)
            return
        }
        val activity = context.findActivity()
        if (activity != null) {
            UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { ok ->
                if (ok) {
                    UnifiedPush.register(context, messageForDistributor = "Luvia")
                }
                onDistributorReady(ok)
            }
        } else {
            val saved = UnifiedPush.getSavedDistributor(context)
            if (saved == null) {
                val first = UnifiedPush.getDistributors(context).firstOrNull()
                if (first == null) {
                    onDistributorReady(false)
                    return
                }
                UnifiedPush.saveDistributor(context, first)
            }
            UnifiedPush.register(context, messageForDistributor = "Luvia")
            onDistributorReady(true)
        }
    }

    fun unregisterApp(context: Context) {
        prefs(context).edit().putBoolean(KEY_WANTED, false).remove(KEY_ENDPOINT).apply()
        UnifiedPush.unregister(context)
    }

    class Receiver : MessagingReceiver() {
        override fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
            prefs(context).edit().putString(KEY_ENDPOINT, endpoint.url).apply()
            listener?.onEndpoint(endpoint.url)
        }

        override fun onMessage(context: Context, message: PushMessage, instance: String) {
            StatusNotificationController(context).showWake(message.content)
        }

        override fun onRegistrationFailed(context: Context, reason: FailedReason, instance: String) {
            // Distributor declined; the settings switch stays off until a new endpoint arrives.
        }

        override fun onUnregistered(context: Context, instance: String) {
            prefs(context).edit().remove(KEY_ENDPOINT).apply()
            listener?.onUnregistered()
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "luvia_push"
    private const val KEY_WANTED = "wanted"
    private const val KEY_ENDPOINT = "endpoint"
}

internal fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
