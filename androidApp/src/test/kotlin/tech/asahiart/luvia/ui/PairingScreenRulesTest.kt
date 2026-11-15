package tech.asahiart.luvia.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import tech.asahiart.luvia.HostRole
import tech.asahiart.luvia.SHORT_CODE_LENGTH

class PairingScreenRulesTest {
    @Test
    fun emptyFieldHighlightsOnlyTheFirstTile() {
        val highlighted = highlightedIndexes(0)
        assertEquals(listOf(0), highlighted)
    }

    @Test
    fun onlyTheLatestEnteredTileIsHighlighted() {
        assertEquals(listOf(0), highlightedIndexes(1))
        assertEquals(listOf(2), highlightedIndexes(3))
        assertEquals(listOf(SHORT_CODE_LENGTH - 1), highlightedIndexes(SHORT_CODE_LENGTH))
    }

    @Test
    fun eachEnteredLengthHighlightsExactlyOneTile() {
        for (length in 0..SHORT_CODE_LENGTH) {
            val highlighted = highlightedIndexes(length)
            assertEquals(1, highlighted.size)
            assertEquals(if (length == 0) 0 else length - 1, highlighted.single())
        }
    }

    @Test
    fun roleSelectionIsExclusive() {
        for (current in listOf(HostRole.Controller, HostRole.Observer)) {
            val selected = listOf(HostRole.Controller, HostRole.Observer).filter { choice ->
                pairingRoleIsSelected(current, choice)
            }
            assertEquals(listOf(current), selected)
        }
        assertTrue(pairingRoleIsSelected(HostRole.Observer, HostRole.Observer))
        assertFalse(pairingRoleIsSelected(HostRole.Observer, HostRole.Controller))
    }

    private fun highlightedIndexes(length: Int): List<Int> =
        (0 until SHORT_CODE_LENGTH).filter { pairingTileHighlighted(it, length) }
}
