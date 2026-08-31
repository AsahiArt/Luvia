package tech.asahiart.luvia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.asahiart.luvia.internal.ReconcileAction
import tech.asahiart.luvia.internal.SubscribeSnapshotReconciler
import tech.asahiart.luvia.internal.UhpEvent

class ReconcilerTest {
    @Test
    fun dropsEventsThroughSnapshotInRace() {
        val recon = SubscribeSnapshotReconciler()
        assertTrue(recon.onEvent(event("pane.focused", 8)).isEmpty())
        assertTrue(recon.onEvent(event("pane.focused", 9)).isEmpty())
        assertTrue(recon.onEvent(event("pane.focused", 11)).isEmpty())
        val actions = recon.onSnapshot(snapshot(10, "gen-a"))
        assertTrue(actions.first() is ReconcileAction.ApplySnapshot)
        assertTrue(actions.drop(1).single() is ReconcileAction.ApplyEvent)
        assertEquals(11, (actions.last() as ReconcileAction.ApplyEvent).event.sequence)
    }

    @Test
    fun resyncsOnSequenceGap() {
        val recon = SubscribeSnapshotReconciler()
        recon.onSnapshot(snapshot(10, "gen-a"))
        val actions = recon.onEvent(event("pane.focused", 12))
        assertEquals(ResyncReason.Gap, (actions.single() as ReconcileAction.Resync).reason)
    }

    @Test
    fun resyncsOnOverflow() {
        val recon = SubscribeSnapshotReconciler()
        recon.onSnapshot(snapshot(10, "gen-a"))
        val overflow =
            UhpEvent(
                name = "events.resync_required",
                sequence = 11,
                data = buildJsonObject { put("reason", "subscriber_overflow") },
            )
        val actions = recon.onEvent(overflow)
        assertEquals(ResyncReason.Overflow, (actions.single() as ReconcileAction.Resync).reason)
    }

    @Test
    fun resyncsOnEof() {
        val recon = SubscribeSnapshotReconciler()
        recon.onSnapshot(snapshot(10, "gen-a"))
        assertEquals(ResyncReason.Eof, (recon.onEof() as ReconcileAction.Resync).reason)
    }

    @Test
    fun resyncsOnGenerationChange() {
        val recon = SubscribeSnapshotReconciler()
        recon.onSnapshot(snapshot(10, "gen-a"))
        val actions = recon.onSnapshot(snapshot(11, "gen-b"))
        assertEquals(ResyncReason.GenerationChange, (actions.single() as ReconcileAction.Resync).reason)
    }

    @Test
    fun resyncsOnStaleSnapshot() {
        val recon = SubscribeSnapshotReconciler()
        recon.onSnapshot(snapshot(10, "gen-a"))
        recon.onEvent(event("pane.focused", 11))
        val actions = recon.onSnapshot(snapshot(9, "gen-a"))
        assertEquals(ResyncReason.StaleSnapshot, (actions.single() as ReconcileAction.Resync).reason)
    }

    private fun event(name: String, sequence: Long): UhpEvent =
        UhpEvent(name, sequence, buildJsonObject { put("pane", "7") })

    private fun snapshot(sequence: Long, generation: String): SessionSnapshot =
        SessionSnapshot(
            sessionName = "default",
            serverGeneration = generation,
            eventSequence = sequence,
            workspaces = emptyList(),
            panes = emptyList(),
            agents = emptyList(),
        )
}
