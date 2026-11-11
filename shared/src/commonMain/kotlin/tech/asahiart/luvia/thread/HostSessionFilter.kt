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
 *
 * Cold start reads both from [HostFilterMemory] and does not write them back.
 * [displayedHostSessionFilter] may show [SessionScope.CurrentHost] until that
 * path is pinned. A host switch does not restore the previous scope: see
 * [applyHostSwitch]. A pinned path stays selected at count zero.
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
 * Platform stores persist [encodeHostSessionFilter].
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

/**
 * Working directories on [hostId] that have at least one session.
 * Newest activity first. A missing timestamp sorts as older than any real one.
 * Count is every session in that directory, not a status slice: pass the
 * unfiltered catalog. Blank cwds and slash-only cwds are dropped.
 * Other hosts are not included.
 */
public data class SessionPathOption(
    public val cwd: String,
    public val count: Int,
)

public fun openSessionPaths(threads: List<AgentThread>, hostId: String?): List<SessionPathOption> {
    if (hostId == null) return emptyList()
    val counts = LinkedHashMap<String, Int>()
    val latest = HashMap<String, Long>()
    for (thread in threads) {
        if (thread.hostId != hostId) continue
        val cwd = normalizeSessionCwd(thread.cwd) ?: continue
        counts[cwd] = (counts[cwd] ?: 0) + 1
        val stamp = thread.updatedEpochMs ?: 0L
        if (stamp > (latest[cwd] ?: 0L)) latest[cwd] = stamp
    }
    return counts.keys
        .sortedWith(compareByDescending<String> { latest[it] ?: 0L }.thenBy { it })
        .map { cwd -> SessionPathOption(cwd, counts.getValue(cwd)) }
}

/** String form of a filter. Undecodable text is not a stored filter. */
public fun encodeHostSessionFilter(filter: HostSessionFilter): String {
    val kind = when (filter.scope) {
        SessionScope.AllOnline -> "all"
        SessionScope.CurrentHost -> "host"
        is SessionScope.Path -> "path"
    }
    val cwd = (filter.scope as? SessionScope.Path)?.cwd.orEmpty()
    return listOf(FILTER_ENCODING, kind, filter.status.name, cwd).joinToString(FILTER_SEPARATOR)
}

/** [HostSessionFilter] when [raw] is not a value [encodeHostSessionFilter] wrote. */
public fun decodeHostSessionFilter(raw: String?): HostSessionFilter {
    if (raw.isNullOrEmpty()) return HostSessionFilter()
    val parts = raw.split(FILTER_SEPARATOR)
    if (parts.size != 4 || parts[0] != FILTER_ENCODING) return HostSessionFilter()
    val status = runCatching { SessionFilter.valueOf(parts[2]) }.getOrNull() ?: return HostSessionFilter()
    val scope = when (parts[1]) {
        "all" -> SessionScope.AllOnline
        "host" -> SessionScope.CurrentHost
        "path" -> {
            if (parts[3].isEmpty()) return HostSessionFilter()
            SessionScope.Path(parts[3])
        }
        else -> return HostSessionFilter()
    }
    return HostSessionFilter(scope, status)
}

/** [HostFilterMemory] over the string [encodeHostSessionFilter] writes. */
public interface HostFilterEntries {
    public fun read(hostId: String): String?

    public fun write(hostId: String, encoded: String)
}

public class DecodingHostFilterMemory(
    private val entries: HostFilterEntries,
) : HostFilterMemory {
    override fun get(hostId: String): HostSessionFilter = decodeHostSessionFilter(entries.read(hostId))

    override fun put(hostId: String, filter: HostSessionFilter) {
        entries.write(hostId, encodeHostSessionFilter(filter))
    }
}

/**
 * Display-only. A path with no matching session becomes [SessionScope.CurrentHost].
 * Status stays. Does not write.
 */
public fun restoreHostSessionFilter(
    stored: HostSessionFilter,
    threadsForHost: List<AgentThread>,
): HostSessionFilter {
    val path = stored.scope as? SessionScope.Path ?: return stored
    val wanted = normalizeSessionCwd(path.cwd) ?: return stored.copy(scope = SessionScope.CurrentHost)
    val live = threadsForHost.any { thread -> normalizeSessionCwd(thread.cwd) == wanted }
    return if (live) stored else stored.copy(scope = SessionScope.CurrentHost)
}

/**
 * What the session list shows. Does not write.
 *
 * A pinned path stays, even at count zero. An unpinned path is [restoreHostSessionFilter].
 * Any other scope is [stored].
 */
public fun displayedHostSessionFilter(
    stored: HostSessionFilter,
    arrivedThreads: List<AgentThread>,
    pinned: Boolean,
): HostSessionFilter {
    if (stored.scope !is SessionScope.Path) return stored
    if (pinned) return stored
    return restoreHostSessionFilter(stored, arrivedThreads)
}

/** True when [stored]'s path has at least one session in [arrivedThreads]. */
public fun shouldPinStoredPath(
    stored: HostSessionFilter,
    arrivedThreads: List<AgentThread>,
): Boolean {
    val path = stored.scope as? SessionScope.Path ?: return false
    val wanted = normalizeSessionCwd(path.cwd) ?: return false
    return arrivedThreads.any { thread -> normalizeSessionCwd(thread.cwd) == wanted }
}

/** This host has not delivered a session yet, and it is not live. */
public fun hostSessionListLoading(sessionCount: Int, connectionLive: Boolean): Boolean =
    sessionCount == 0 && !connectionLive

/**
 * Keep a selected path in the menu after its last session closes.
 * The added row has count 0. A path that is already listed is unchanged.
 */
public fun withSelectedPath(
    paths: List<SessionPathOption>,
    scope: SessionScope,
): List<SessionPathOption> {
    val path = scope as? SessionScope.Path ?: return paths
    val wanted = normalizeSessionCwd(path.cwd) ?: return paths
    if (paths.any { normalizeSessionCwd(it.cwd) == wanted }) return paths
    return paths + SessionPathOption(wanted, 0)
}

/**
 * Home directory for [username] when one of [paths] is that home or a child of it.
 * Checks `/Users/<name>` and `/home/<name>` only. A blank name, or a name that
 * matches nothing, returns null. The caller passes the host's own username.
 */
public fun hostHomeDirectory(username: String?, paths: List<String>): String? {
    val name = username?.trim()?.takeIf { candidate ->
        candidate.isNotEmpty() && !candidate.contains('/') && candidate != "." && candidate != ".."
    } ?: return null
    return listOf("/Users/$name", "/home/$name").firstOrNull { home ->
        paths.any { raw ->
            val path = normalizeSessionCwd(raw) ?: return@any false
            path == home || path.startsWith("$home/")
        }
    }
}

/** `~` when [cwd] is [homeDir] or a child of it. Otherwise the cwd unchanged. */
public fun homeRelativePath(cwd: String, homeDir: String?): String {
    val path = normalizeSessionCwd(cwd) ?: return cwd.trim()
    val home = normalizeSessionCwd(homeDir) ?: return path
    return when {
        path == home -> "~"
        path.startsWith("$home/") -> "~/" + path.removePrefix("$home/")
        else -> path
    }
}

/** Chip is the last path component. Colliding chips grow a parent prefix. */
public data class SessionPathText(
    val cwd: String,
    val chip: String,
    val relative: String,
)

public fun sessionPathTexts(paths: List<String>, homeDir: String?): List<SessionPathText> {
    val relatives = paths.map { homeRelativePath(it, homeDir) }
    val parts = relatives.map(::relativeParts)
    val depth = IntArray(paths.size) { index -> if (parts[index].isEmpty()) 0 else 1 }
    var grew = true
    while (grew) {
        grew = false
        val groups = paths.indices.groupBy { index -> chipLabel(parts[index], depth[index]) }
        for (indexes in groups.values) {
            if (indexes.size < 2) continue
            for (index in indexes) {
                if (depth[index] < parts[index].size) {
                    depth[index] += 1
                    grew = true
                }
            }
        }
    }
    return paths.indices.map { index ->
        SessionPathText(
            cwd = paths[index],
            chip = chipLabel(parts[index], depth[index]),
            relative = relatives[index],
        )
    }
}

private const val FILTER_ENCODING: String = "1"
private const val FILTER_SEPARATOR: String = "\u001f"

private fun relativeParts(relative: String): List<String> {
    val body = when {
        relative == "~" -> return emptyList()
        relative.startsWith("~/") -> relative.removePrefix("~/")
        relative.startsWith("/") -> relative.removePrefix("/")
        else -> relative
    }
    return body.split('/').filter { it.isNotEmpty() }
}

private fun chipLabel(parts: List<String>, depth: Int): String {
    if (parts.isEmpty() || depth <= 0) return "~"
    return parts.takeLast(depth).joinToString("/")
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
