package tech.asahiart.luvia.thread

/**
 * One handoff from a shell failure into a new ACP session.
 * A late ack may adopt only while the host, the launch attempt, and the navigation epoch still match.
 * Ignoring that ack does not close the session the host already created.
 */
public data class HandoffIntent(
    val hostId: String,
    val attempt: Long,
    val originPaneId: String?,
    val navigationEpoch: Long,
    val baselineSessionId: String,
)

public enum class HandoffEffect {
    Ignore,
    ShowFailure,
    Adopt,
}

public fun handoffEffect(
    intent: HandoffIntent?,
    eventAttempt: Long,
    navigationEpoch: Long,
    selectedHostId: String,
    snapshotHostId: String,
    open: Boolean,
    errorText: String?,
    sessionId: String,
): HandoffEffect {
    val pending = intent ?: return HandoffEffect.Ignore
    if (eventAttempt != pending.attempt) return HandoffEffect.Ignore
    if (navigationEpoch != pending.navigationEpoch) return HandoffEffect.Ignore
    if (selectedHostId != pending.hostId) return HandoffEffect.Ignore
    if (snapshotHostId != pending.hostId) return HandoffEffect.Ignore
    val session = sessionId.trim()
    if (open && session.isNotEmpty() && session != pending.baselineSessionId) return HandoffEffect.Adopt
    if (!open && !errorText.isNullOrBlank()) return HandoffEffect.ShowFailure
    return HandoffEffect.Ignore
}
