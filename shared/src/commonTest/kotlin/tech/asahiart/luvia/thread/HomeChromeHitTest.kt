package tech.asahiart.luvia.thread

import kotlin.test.Test
import kotlin.test.assertEquals

class HomeChromeHitTest {
    @Test
    fun playtestTapOnMachinesDoesNotOpenTheRowUnderIt() {
        // iPhone 13 Pro points. Agent-device tapped 机器 at x=165, the middle of that tab,
        // and the session row underneath opened.
        val slot = homeChromeSlot(x = 165.0, width = 390.0)
        assertEquals(HomeChromeSlot.Machines, slot)
        assertEquals(
            HomeTapWinner.Machines,
            resolveOverlappingTap(slot = slot, inChromeBand = true, rowContainsPoint = true),
        )
    }

    @Test
    fun capsuleThirdsMatchTheFloatingPill() {
        val third = 278.0 / 3.0
        val sessionsEnd = 26.0 + third
        val machinesEnd = sessionsEnd + third
        assertEquals(HomeChromeSlot.Sessions, homeChromeSlot(sessionsEnd - 0.01, 390.0))
        assertEquals(HomeChromeSlot.Machines, homeChromeSlot(sessionsEnd, 390.0))
        assertEquals(HomeChromeSlot.Machines, homeChromeSlot(machinesEnd - 0.01, 390.0))
        assertEquals(HomeChromeSlot.Settings, homeChromeSlot(machinesEnd, 390.0))
        assertEquals(HomeChromeSlot.Settings, homeChromeSlot(309.9, 390.0))
    }

    @Test
    fun searchCircleAndEmptyPaddingStayInTheBar() {
        assertEquals(HomeChromeSlot.Search, homeChromeSlot(346.0, 390.0))
        assertEquals(HomeChromeSlot.Search, homeChromeSlot(322.0, 390.0))
        assertEquals(HomeChromeSlot.Search, homeChromeSlot(369.9, 390.0))
        assertEquals(HomeChromeSlot.Absorb, homeChromeSlot(316.0, 390.0))
        assertEquals(HomeChromeSlot.Absorb, homeChromeSlot(10.0, 390.0))
        assertEquals(HomeChromeSlot.Absorb, homeChromeSlot(370.0, 390.0))
        assertEquals(
            HomeTapWinner.Chrome,
            resolveOverlappingTap(
                slot = HomeChromeSlot.Absorb,
                inChromeBand = true,
                rowContainsPoint = true,
            ),
        )
        assertEquals(
            HomeTapWinner.Search,
            resolveOverlappingTap(
                slot = homeChromeSlot(346.0, 390.0),
                inChromeBand = true,
                rowContainsPoint = true,
            ),
        )
    }

    @Test
    fun rowWinsOnlyOutsideTheChromeBand() {
        assertEquals(
            HomeTapWinner.Row,
            resolveOverlappingTap(
                slot = HomeChromeSlot.Machines,
                inChromeBand = false,
                rowContainsPoint = true,
            ),
        )
        assertEquals(
            HomeTapWinner.Miss,
            resolveOverlappingTap(
                slot = HomeChromeSlot.Machines,
                inChromeBand = false,
                rowContainsPoint = false,
            ),
        )
    }

    @Test
    fun collapsedBarDoesNotInventATab() {
        assertEquals(HomeChromeSlot.Absorb, homeChromeSlot(10.0, 40.0))
        assertEquals(HomeChromeSlot.Absorb, homeChromeSlot(Double.NaN, 390.0))
        assertEquals(HomeChromeSlot.Absorb, homeChromeSlot(10.0, Double.POSITIVE_INFINITY))
        assertEquals(HomeChromeSlot.Absorb, homeChromeSlot(-1.0, 390.0))
    }
}
