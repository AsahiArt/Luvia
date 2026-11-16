package tech.asahiart.luvia.thread

/** One external notification navigation. [eventId] distinguishes repeats on the same Activity. */
public data class NotificationEvent(
    val eventId: Long,
    val hostId: String,
    val openBlocked: Boolean,
)

/** [consumedEventId] and [pendingEventId] use -1 for none. */
public data class NotificationCursor(
    val consumedEventId: Long = -1L,
    val pendingEventId: Long = -1L,
)

public data class NotificationResult(
    val cursor: NotificationCursor,
    val routeThread: AgentThread? = null,
    val showSessions: Boolean = false,
)

/**
 * Decides what one notification event may do.
 * A missing blocked thread stays pending. A refused open consumes the event and does not change route.
 * An older event cannot take the route after a newer one is pending.
 */
public fun reduceNotification(
    cursor: NotificationCursor,
    event: NotificationEvent,
    threads: List<AgentThread>,
    accept: (AgentThread) -> Boolean,
): NotificationResult {
    if (event.eventId == cursor.consumedEventId) return NotificationResult(cursor)
    if (cursor.pendingEventId >= 0 && event.eventId < cursor.pendingEventId) return NotificationResult(cursor)
    if (!event.openBlocked) {
        return NotificationResult(
            cursor = NotificationCursor(consumedEventId = event.eventId),
            showSessions = true,
        )
    }
    val thread = threads.firstOrNull { it.hostId == event.hostId && it.needsYou }
        ?: return NotificationResult(cursor.copy(pendingEventId = event.eventId))
    val consumed = NotificationCursor(consumedEventId = event.eventId)
    return if (accept(thread)) {
        NotificationResult(cursor = consumed, routeThread = thread)
    } else {
        NotificationResult(cursor = consumed)
    }
}
