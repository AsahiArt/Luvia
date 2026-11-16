package tech.asahiart.luvia.thread

/** The history resume the list accepted, bound to that host and that launch generation. */
public data class ResumeWait(
    val hostId: String,
    val generation: Long,
)

public data class ResumeNavigation(
    val wait: ResumeWait?,
    val routeHostId: String?,
    val stage: String,
)

/**
 * What the session list does while a history resume is in flight.
 * The caller passes the snapshot of [ResumeWait.hostId], not whichever host is selected.
 */
public fun reduceHistoryResume(
    navigation: ResumeNavigation,
    selectedHostId: String?,
    snapshotHostId: String,
    snapshotGeneration: Long,
    open: Boolean,
    errorText: String?,
    sessionId: String,
): ResumeNavigation {
    val wait = navigation.wait ?: return navigation
    if (snapshotGeneration < 0) return navigation
    if (selectedHostId != wait.hostId || navigation.stage != "sessions") {
        return navigation.copy(wait = null)
    }
    if (snapshotHostId != wait.hostId) return navigation
    if (snapshotGeneration != wait.generation) {
        return navigation.copy(wait = null)
    }
    if (!open && !errorText.isNullOrBlank()) {
        return navigation.copy(wait = null)
    }
    val session = sessionId.trim()
    if (open && session.isNotEmpty()) {
        return navigation.copy(wait = null, routeHostId = wait.hostId, stage = "chat")
    }
    return navigation
}

/** A resume that did not return a receipt does not become a wait and does not change host. */
public fun beginHistoryResume(hostId: String, receipt: LaunchReceipt?): ResumeWait? {
    if (hostId.isBlank()) return null
    val generation = receipt?.generation ?: return null
    return ResumeWait(hostId, generation)
}
