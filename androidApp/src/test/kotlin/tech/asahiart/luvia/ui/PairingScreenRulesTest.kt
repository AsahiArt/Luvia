package tech.asahiart.luvia.ui

import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun highlightedIndexes(length: Int): List<Int> =
        (0 until SHORT_CODE_LENGTH).filter { pairingTileHighlighted(it, length) }
}
