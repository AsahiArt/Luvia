package tech.asahiart.luvia.internal

import tech.asahiart.luvia.ResyncReason
import tech.asahiart.luvia.SessionEvent
import tech.asahiart.luvia.SessionSnapshot

internal sealed class ReconcileAction {
    data class ApplyEvent(val event: SessionEvent) : ReconcileAction()

    data class ApplySnapshot(val snapshot: SessionSnapshot) : ReconcileAction()

    data class Resync(val reason: ResyncReason) : ReconcileAction()
}

internal class SubscribeSnapshotReconciler {
    private val buffer = ArrayDeque<UhpEvent>()
    private var snapshotSequence: Long? = null
    private var generation: String? = null
    private var lastApplied: Long? = null

    fun reset() {
        buffer.clear()
        snapshotSequence = null
        generation = null
        lastApplied = null
    }

    fun onEvent(event: UhpEvent): List<ReconcileAction> {
        if (isOverflow(event)) {
            return listOf(ReconcileAction.Resync(ResyncReason.Overflow))
        }
        val fence = snapshotSequence
        if (fence == null) {
            buffer.addLast(event)
            return emptyList()
        }
        return applyLive(event, fence)
    }

    fun onSnapshot(snapshot: SessionSnapshot): List<ReconcileAction> {
        val currentGeneration = generation
        if (currentGeneration != null && snapshot.serverGeneration != currentGeneration) {
            return listOf(ReconcileAction.Resync(ResyncReason.GenerationChange))
        }
        val applied = lastApplied
        if (applied != null && snapshot.eventSequence < applied) {
            return listOf(ReconcileAction.Resync(ResyncReason.StaleSnapshot))
        }
        generation = snapshot.serverGeneration
        snapshotSequence = snapshot.eventSequence
        lastApplied = snapshot.eventSequence
        val actions = ArrayList<ReconcileAction>()
        actions += ReconcileAction.ApplySnapshot(snapshot)
        val pending = ArrayList<UhpEvent>()
        while (buffer.isNotEmpty()) {
            pending += buffer.removeFirst()
        }
        for (event in pending) {
            if (event.sequence <= snapshot.eventSequence) {
                continue
            }
            val next = applyLive(event, snapshot.eventSequence)
            actions += next
            if (next.lastOrNull() is ReconcileAction.Resync) {
                return actions
            }
        }
        return actions
    }

    fun onEof(): ReconcileAction = ReconcileAction.Resync(ResyncReason.Eof)

    private fun applyLive(event: UhpEvent, fence: Long): List<ReconcileAction> {
        if (isOverflow(event)) {
            return listOf(ReconcileAction.Resync(ResyncReason.Overflow))
        }
        val eventGeneration = event.data.optionalString("server_generation")
        val knownGeneration = generation
        if (eventGeneration != null && knownGeneration != null && eventGeneration != knownGeneration) {
            return listOf(ReconcileAction.Resync(ResyncReason.GenerationChange))
        }
        if (event.sequence <= fence) {
            return emptyList()
        }
        val previous = lastApplied ?: fence
        if (event.sequence > previous + 1) {
            return listOf(ReconcileAction.Resync(ResyncReason.Gap))
        }
        if (event.sequence <= previous) {
            return emptyList()
        }
        if (requiresFreshSnapshot(event.name) && event.sequence > fence) {
            lastApplied = event.sequence
            return listOf(ReconcileAction.Resync(ResyncReason.Gap))
        }
        lastApplied = event.sequence
        return listOf(ReconcileAction.ApplyEvent(toSessionEvent(event)))
    }
}

internal fun isOverflow(event: UhpEvent): Boolean =
    event.name == "events.resync_required" ||
        event.name == "terminal.resync_required" ||
        event.data.optionalString("reason") == "subscriber_overflow"

internal fun requiresFreshSnapshot(name: String): Boolean =
    name == "terminal.created" || name == "terminal.moved" || name == "terminal.exited"

internal fun toSessionEvent(event: UhpEvent): SessionEvent =
    SessionEvent(
        name = event.name,
        sequence = event.sequence,
        paneId = event.data.optionalString("pane") ?: event.data.optionalString("pane_id"),
    )
