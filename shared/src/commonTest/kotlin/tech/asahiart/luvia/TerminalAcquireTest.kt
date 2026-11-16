package tech.asahiart.luvia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalAcquireTest {
    @Test
    fun lateSuccessAfterSwitchClosesAAndDoesNotHoldIt() = runTest {
        val closed = mutableListOf<String>()
        val owner = TerminalAcquire<String> { closed += it }
        val gate = CompletableDeferred<String?>()
        owner.show(binding("pane-a"))
        owner.acquire(this) { gate.await() }
        owner.show(binding("pane-b"))
        gate.complete("A")
        advanceUntilIdle()
        assertEquals(listOf("A"), closed)
        assertNull(owner.held())
        assertFalse(owner.send { })
    }

    @Test
    fun lateSuccessAfterHideClosesAndDoesNotRevive() = runTest {
        val closed = mutableListOf<String>()
        val owner = TerminalAcquire<String> { closed += it }
        val gate = CompletableDeferred<String?>()
        owner.show(binding("pane-a"))
        owner.acquire(this) { gate.await() }
        owner.hide()
        gate.complete("A")
        advanceUntilIdle()
        assertEquals(listOf("A"), closed)
        assertNull(owner.held())
        assertNull(owner.binding())
    }

    @Test
    fun sameBindingKeepsTheHeldControl() = runTest {
        val closed = mutableListOf<String>()
        val owner = TerminalAcquire<String> { closed += it }
        val shown = binding("pane-a")
        owner.show(shown)
        owner.acquire(this) { "A" }
        advanceUntilIdle()
        val held = owner.held()
        owner.show(shown)
        assertSame(held, owner.held())
        assertEquals(emptyList(), closed)
        assertEquals(shown, owner.binding())
    }

    @Test
    fun staleControlCannotSendAndTheCurrentOneCan() = runTest {
        val closed = mutableListOf<String>()
        val sent = mutableListOf<String>()
        val owner = TerminalAcquire<String> { closed += it }
        owner.show(binding("pane-a"))
        owner.acquire(this) { "A" }
        advanceUntilIdle()
        owner.show(binding("pane-b"))
        assertFalse(owner.send { sent += it })
        owner.acquire(this) { "B" }
        advanceUntilIdle()
        assertTrue(owner.send { sent += it })
        assertEquals(listOf("B"), sent)
        assertEquals(listOf("A"), closed)
        assertEquals("B", owner.held())
    }

    @Test
    fun hideClosesOnceAndALaterAcquireOfBStaysOpen() = runTest {
        val closed = mutableListOf<String>()
        val owner = TerminalAcquire<String> { closed += it }
        val waiting = CompletableDeferred<String?>()
        owner.show(binding("pane-a"))
        owner.acquire(this) { waiting.await() }
        owner.show(binding("pane-b"))
        owner.acquire(this) { "B" }
        advanceUntilIdle()
        waiting.complete("A")
        advanceUntilIdle()
        owner.hide()
        owner.hide()
        assertEquals(listOf("A", "B"), closed)
        assertEquals(1, closed.count { it == "B" })
        assertNull(owner.held())
    }

    @Test
    fun aFailedOpenDoesNotReplaceTheHeldControl() = runTest {
        val owner = TerminalAcquire<String> { }
        owner.show(binding("pane-a"))
        owner.acquire(this) { "A" }
        advanceUntilIdle()
        owner.acquire(this) { null }
        advanceUntilIdle()
        assertEquals("A", owner.held())
    }

    private fun binding(pane: String) = TerminalBinding(
        hostId = "host",
        serverGeneration = "gen",
        terminalId = "term-$pane",
        paneId = pane,
        viewEpoch = 1,
    )
}
