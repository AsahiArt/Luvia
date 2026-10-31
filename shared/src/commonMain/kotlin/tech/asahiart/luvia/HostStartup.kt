package tech.asahiart.luvia

/**
 * What the host chrome should say while a computer is or isn't reachable.
 * Cached topology is not a live session: [Offline] and [Reconnecting] must not
 * look like an open chat or terminal.
 */
public enum class HostChrome {
    Live,
    Reconnecting,
    Offline,
}

public fun hostChrome(link: HostLink, freshness: ConnectionFreshness): HostChrome =
    when (link) {
        is HostLink.Online ->
            if (freshness == ConnectionFreshness.Live) HostChrome.Live else HostChrome.Reconnecting
        is HostLink.Connecting -> HostChrome.Reconnecting
        is HostLink.Failed, is HostLink.Idle -> HostChrome.Offline
    }

/** Session rows drawn from a saved topology, not from a live host. */
public fun sessionsAreCached(link: HostLink): Boolean = link !is HostLink.Online

/**
 * Host to select on a cold start.
 * Last successful connection wins. A stale duplicate with a similar name
 * (Yui vs Yui.local) loses to the host that actually connected.
 */
public fun preferredStartupHost(profiles: List<HostProfile>): HostProfile? {
    if (profiles.isEmpty()) return null
    return profiles.maxWithOrNull(
        compareBy<HostProfile>({ startupRank(it) }, { it.lastUpdatedEpochMs }, { it.id }),
    )
}

/**
 * Cold-start selection. A saved id is kept when it names a different computer.
 * A similar alias (Yui vs Yui.local) yields the last-good host instead.
 */
public fun resolveStartupHost(profiles: List<HostProfile>, selectedId: String?): HostProfile? {
    val preferred = preferredStartupHost(profiles) ?: return null
    val selected = profiles.firstOrNull { it.id == selectedId } ?: return preferred
    if (selected.id == preferred.id) return selected
    val stem = aliasStem(selected.alias)
    if (stem.isNotEmpty() && stem == aliasStem(preferred.alias)) {
        return preferred
    }
    return selected
}

internal fun aliasStem(alias: String): String {
    val head = alias.trim().substringBefore('.').trim()
    return head.lowercase()
}

private fun startupRank(profile: HostProfile): Int {
    val everConnected = !profile.lastConnectedAddress.isNullOrBlank()
    return when (profile.lastStatus) {
        HostStatus.Reachable -> 3
        HostStatus.Stale -> if (everConnected) 2 else 1
        HostStatus.Unknown -> if (everConnected) 2 else 0
        HostStatus.Unreachable -> if (everConnected) 1 else 0
    }
}
