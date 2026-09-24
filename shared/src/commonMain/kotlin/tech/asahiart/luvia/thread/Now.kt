package tech.asahiart.luvia.thread

import tech.asahiart.luvia.AgentStatus
import tech.asahiart.luvia.ConnectionFreshness
import tech.asahiart.luvia.HostUhpState

/** Cached per-Host input for Now. Building Now never issues a UHP call. */
public data class NowHost(
    public val hostId: String,
    public val name: String,
    public val freshness: ConnectionFreshness,
    public val isObserver: Boolean,
    public val state: HostUhpState?,
)

public data class NowGroup(
    public val hostId: String,
    public val hostName: String,
    public val freshness: ConnectionFreshness,
    public val isObserver: Boolean,
    public val threads: List<AgentThread>,
)

public data class NowState(
    public val needsYou: List<NowGroup> = emptyList(),
    public val inProgress: List<NowGroup> = emptyList(),
    public val recentlyDone: List<NowGroup> = emptyList(),
) {
    public val needsYouCount: Int get() = needsYou.sumOf { it.threads.size }
    public val isQuiet: Boolean get() = needsYou.isEmpty() && inProgress.isEmpty() && recentlyDone.isEmpty()
}

/**
 * Remembers when each thread was first seen Done on this device.
 * A done thread leaves Now once viewed here or after [ttlMs].
 */
public data class DoneLedger(
    private val firstSeenMs: Map<String, Long> = emptyMap(),
    private val viewed: Set<String> = emptySet(),
    private val ttlMs: Long = DAY_MS,
) {
    public fun observe(threads: List<AgentThread>, nowMs: Long): DoneLedger {
        val done = threads.filter { it.status == AgentStatus.Done }.map { key(it) }.toSet()
        val seen = firstSeenMs.filterKeys { it in done } + done.filter { it !in firstSeenMs }.associateWith { nowMs }
        return copy(firstSeenMs = seen, viewed = viewed intersect done)
    }

    public fun markViewed(thread: AgentThread): DoneLedger = copy(viewed = viewed + key(thread))

    public fun isRecent(thread: AgentThread, nowMs: Long): Boolean {
        val k = key(thread)
        val since = firstSeenMs[k] ?: return false
        return k !in viewed && nowMs - since < ttlMs
    }

    private fun key(thread: AgentThread) = "${thread.hostId}/${thread.id}"

    public companion object {
        public const val DAY_MS: Long = 24L * 60 * 60 * 1000
    }
}

public fun buildNowState(hosts: List<NowHost>, ledger: DoneLedger, nowMs: Long): NowState {
    fun group(host: NowHost, pick: (AgentThread) -> Boolean): NowGroup? {
        val threads = host.state?.threads(host.hostId).orEmpty().filter(pick)
        if (threads.isEmpty()) return null
        return NowGroup(host.hostId, host.name, host.freshness, host.isObserver, threads)
    }
    val ordered = hosts.sortedBy { it.freshness.ordinal }
    return NowState(
        needsYou = ordered.mapNotNull { h -> group(h) { it.needsYou } },
        inProgress = ordered.mapNotNull { h -> group(h) { !it.needsYou && it.status == AgentStatus.Working } },
        recentlyDone = ordered.mapNotNull { h -> group(h) { it.status == AgentStatus.Done && ledger.isRecent(it, nowMs) } },
    )
}
