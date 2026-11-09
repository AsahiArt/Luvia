package tech.asahiart.luvia.thread

/**
 * Which sessions a host's list is looking at. The three cases are mutually exclusive.
 *
 * [AllOnline] is every session on a host that is online now.
 * [CurrentHost] is every session on the host currently selected at the top of the list,
 * including that host's cached rows when it is offline.
 * [Path] is one working directory on that same current host. It is not a prefix:
 * `/work/luvia/app` does not match `/work/luvia`.
 */
public sealed class SessionScope {
    public data object AllOnline : SessionScope()

    public data object CurrentHost : SessionScope()

    public data class Path(val cwd: String) : SessionScope()
}

/**
 * Scheme ②: one scope and one status, remembered together for a host.
 * Cold start restores both. A host switch does not: see [applyHostSwitch].
 */
public data class HostSessionFilter(
    val scope: SessionScope = SessionScope.AllOnline,
    val status: SessionFilter = SessionFilter.All,
)

/**
 * Per-host memory for [HostSessionFilter].
 *
 * [get] returns [HostSessionFilter] when [hostId] has never been stored.
 * It does not write. [put] replaces that host's scope and status together.
 * A machines-page path row or count block calls [put] with the chosen filter.
 * That entry is what cold start restores; it is not a one-shot overlay.
 * Platform stores (DataStore, UserDefaults) implement this later.
 * [InMemoryHostFilterMemory] is the test double.
 */
public interface HostFilterMemory {
    public fun get(hostId: String): HostSessionFilter

    public fun put(hostId: String, filter: HostSessionFilter)
}

/** Process-local [HostFilterMemory]. Not synchronized; tests and a single caller own it. */
public class InMemoryHostFilterMemory : HostFilterMemory {
    private val stored = HashMap<String, HostSessionFilter>()

    override fun get(hostId: String): HostSessionFilter = stored[hostId] ?: HostSessionFilter()

    override fun put(hostId: String, filter: HostSessionFilter) {
        stored[hostId] = filter
    }
}

/**
 * Filter to show after the selected host changes.
 *
 * Loads [hostId]'s memory, forces the scope back to [SessionScope.AllOnline],
 * and keeps the status remembered for that host ([SessionFilter.All] when there
 * is no entry). Writes that pair back, so the next cold start does not restore
 * the scope from before the switch.
 *
 * Do not call this for a machines-page path or count tap. Those choose a scope
 * on purpose and must [HostFilterMemory.put] it.
 */
public fun HostFilterMemory.applyHostSwitch(hostId: String): HostSessionFilter {
    val next = get(hostId).copy(scope = SessionScope.AllOnline)
    put(hostId, next)
    return next
}

/**
 * Sessions in [filter]'s scope that also match [filter]'s status.
 *
 * Scope and status intersect. [SessionFilter] rules are [filterSessions]:
 * needs-you is a pending ask, running is [tech.asahiart.luvia.AgentStatus.Working],
 * idle is idle or done.
 *
 * Scope counts ignore status. Call this with [SessionFilter.All] and use that
 * size. Status counts are the size of this result for each status inside the
 * same scope. An offline host's sessions stay in [SessionScope.CurrentHost]
 * and [SessionScope.Path] (the cached list) and drop out of [SessionScope.AllOnline].
 *
 * [SessionScope.Path] compares [normalizeSessionCwd] of the scope and the row:
 * trim, then drop trailing slashes. A blank path, or a cwd that is only
 * slashes, matches nothing. Rows on other hosts with the same cwd stay out.
 * A null [hostId] matches nothing for [SessionScope.CurrentHost] and
 * [SessionScope.Path]; [SessionScope.AllOnline] does not use it.
 */
public fun filterSessionsByHost(
    threads: List<AgentThread>,
    hostId: String?,
    filter: HostSessionFilter,
    onlineHostIds: Set<String>,
): List<AgentThread> {
    val inScope = threads.filter { thread -> thread.inScope(hostId, filter.scope, onlineHostIds) }
    return filterSessions(inScope, filter.status)
}

private fun AgentThread.inScope(
    hostId: String?,
    scope: SessionScope,
    onlineHostIds: Set<String>,
): Boolean =
    when (scope) {
        SessionScope.AllOnline -> this.hostId in onlineHostIds
        SessionScope.CurrentHost -> hostId != null && this.hostId == hostId
        is SessionScope.Path -> {
            val wanted = normalizeSessionCwd(scope.cwd)
            wanted != null &&
                hostId != null &&
                this.hostId == hostId &&
                normalizeSessionCwd(cwd) == wanted
        }
    }
