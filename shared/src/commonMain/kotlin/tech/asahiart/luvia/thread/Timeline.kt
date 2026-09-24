package tech.asahiart.luvia.thread

import tech.asahiart.luvia.AcpPlanEntry
import tech.asahiart.luvia.AcpState
import tech.asahiart.luvia.AcpStopReason
import tech.asahiart.luvia.AcpToolCall
import tech.asahiart.luvia.AcpTranscriptItem
import tech.asahiart.luvia.AcpTranscriptRole
import tech.asahiart.luvia.AgentStatus
import tech.asahiart.luvia.stripAnsi

public sealed class TimelineItem {
    public abstract val id: String

    public data class Output(public override val id: String, public val text: String, public val streaming: Boolean = false) : TimelineItem()

    public data class Mine(public override val id: String, public val text: String) : TimelineItem()

    public data class Thought(public override val id: String, public val text: String) : TimelineItem()

    public data class Tool(public override val id: String, public val call: AcpToolCall) : TimelineItem()

    public data class Plan(public override val id: String, public val entries: List<AcpPlanEntry>) : TimelineItem()

    public data class AskCard(public override val id: String, public val ask: Ask) : TimelineItem()

    public data class Status(public override val id: String, public val status: AgentStatus?, public val stopReason: AcpStopReason? = null) : TimelineItem()

    public data class Unconfirmed(public override val id: String, public val text: String) : TimelineItem()
}

/**
 * Timeline for a pane Agent, built from Transcript reads, local sends and status changes.
 * Never infers tools or plans from Transcript text.
 */
public data class PaneTimeline(
    public val items: List<TimelineItem> = emptyList(),
    private val lastLines: List<String> = emptyList(),
    private val lastStatus: AgentStatus? = null,
    private val seq: Long = 0,
) {
    public fun ingestTranscript(text: String): PaneTimeline {
        val lines = stripAnsi(text).lines().map { it.trimEnd() }.dropLastWhile { it.isBlank() }
        val fresh = lines.drop(overlap(lastLines, lines))
        val chunk = fresh.dropWhile { it.isBlank() }.joinToString("\n")
        val next = copy(lastLines = lines)
        if (chunk.isBlank()) return next
        return next.append { TimelineItem.Output(it, chunk) }
    }

    public fun recordMine(text: String): PaneTimeline = append { TimelineItem.Mine(it, text) }

    public fun recordStatus(status: AgentStatus): PaneTimeline {
        if (status == lastStatus) return this
        val first = lastStatus == null
        val next = copy(lastStatus = status)
        return if (first) next else next.append { TimelineItem.Status(it, status) }
    }

    /** Turns the last [TimelineItem.Mine] into [TimelineItem.Unconfirmed]; resolved by re-reading, never resending. */
    public fun markLastUnconfirmed(): PaneTimeline {
        val index = items.indexOfLast { it is TimelineItem.Mine }
        if (index < 0) return this
        val mine = items[index] as TimelineItem.Mine
        return copy(items = items.toMutableList().also { it[index] = TimelineItem.Unconfirmed(mine.id, mine.text) })
    }

    /** Items plus a trailing Ask card when the thread is waiting. */
    public fun render(ask: Ask?): List<TimelineItem> =
        if (ask == null) items else items + TimelineItem.AskCard("ask", ask)

    private fun append(make: (String) -> TimelineItem): PaneTimeline {
        val item = make("p${seq + 1}")
        return copy(items = (items + item).takeLast(MAX_ITEMS), seq = seq + 1)
    }

    public companion object {
        public const val MAX_ITEMS: Int = 500
    }
}

/** Largest k where the last k of [old] equal the first k of [new]. */
internal fun overlap(old: List<String>, new: List<String>): Int {
    if (old.isEmpty()) return 0
    for (k in minOf(old.size, new.size) downTo 1) {
        if (old.subList(old.size - k, old.size) == new.subList(0, k)) return k
    }
    return 0
}

public fun AcpState.timeline(ask: Ask?): List<TimelineItem> =
    buildList {
        if (plan.isNotEmpty()) add(TimelineItem.Plan("plan", plan))
        for (item in transcript) {
            add(
                when (item) {
                    is AcpTranscriptItem.Message -> when (item.role) {
                        AcpTranscriptRole.User -> TimelineItem.Mine(item.id, item.text)
                        AcpTranscriptRole.Agent -> TimelineItem.Output(item.id, item.text, item.streaming)
                        AcpTranscriptRole.Thought -> TimelineItem.Thought(item.id, item.text)
                    }
                    is AcpTranscriptItem.Tool -> TimelineItem.Tool(item.id, item.call)
                    is AcpTranscriptItem.Turn -> TimelineItem.Status(item.id, null, item.stopReason)
                },
            )
        }
        if (ask != null) add(TimelineItem.AskCard("ask", ask))
    }.takeLast(PaneTimeline.MAX_ITEMS)
