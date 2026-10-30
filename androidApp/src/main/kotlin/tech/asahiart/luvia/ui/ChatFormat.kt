package tech.asahiart.luvia.ui

import tech.asahiart.luvia.AgentStatus

internal fun formatRemaining(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

internal fun looksLikeTailscale(address: String): Boolean {
    val host = address.substringBefore('%').substringBefore('/')
    val parts = host.split('.')
    if (parts.size != 4) return false
    val first = parts[0].toIntOrNull() ?: return false
    val second = parts[1].toIntOrNull() ?: return false
    return first == 100 && second in 64..127
}

internal fun AgentStatus.sessionLabel(): String =
    when (this) {
        AgentStatus.Idle -> "Idle"
        AgentStatus.Working -> "Running"
        AgentStatus.Blocked -> "Needs you"
        AgentStatus.Done -> "Done"
        AgentStatus.Unknown -> "Unknown"
    }
