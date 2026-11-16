package tech.asahiart.luvia

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalInputTest {
    @Test
    fun sameIdentitySnapshotStillAcceptsInput() {
        assertTrue(terminalInputCurrent(held()))
    }

    @Test
    fun roleLossDoesNotWriteTheHeldControl() {
        assertFalse(terminalInputCurrent(held().copy(controller = false)))
    }

    @Test
    fun paneSwitchDoesNotWriteTheHeldControl() {
        assertFalse(terminalInputCurrent(held().copy(locatorPaneId = "2")))
    }

    @Test
    fun locatorGenerationOrTerminalChangeDoesNotWrite() {
        assertFalse(terminalInputCurrent(held().copy(locatorGeneration = "next")))
        assertFalse(terminalInputCurrent(held().copy(locatorTerminalId = "term-b")))
    }

    @Test
    fun oldAcquireEpochDoesNotWrite() {
        assertFalse(terminalInputCurrent(held().copy(acquireEpoch = 8)))
    }

    private fun held() = TerminalInputTicket(
        held = true,
        observing = true,
        controller = true,
        selectedHostId = "host-a",
        observingHostId = "host-a",
        locatorPaneId = "1",
        locatorGeneration = "gen",
        locatorTerminalId = "term-a",
        observingPaneId = "1",
        observingGeneration = "gen",
        observingTerminalId = "term-a",
        acquireEpoch = 4,
        installedEpoch = 4,
    )
}
