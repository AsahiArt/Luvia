package tech.asahiart.luvia.ui

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DecisionDeckTest {
    private val density = 2.5f

    @Test
    fun peekFollowsCardsBehindAndCapsAtTwo() {
        assertEquals(0, DecisionDeckMath.peekCount(index = 0, count = 0))
        assertEquals(0, DecisionDeckMath.peekCount(index = 0, count = 1))
        assertEquals(1, DecisionDeckMath.peekCount(index = 0, count = 2))
        assertEquals(0, DecisionDeckMath.peekCount(index = 1, count = 2))
        assertEquals(2, DecisionDeckMath.peekCount(index = 0, count = 5))
        assertEquals(1, DecisionDeckMath.peekCount(index = 3, count = 5))
        assertEquals(0, DecisionDeckMath.peekCount(index = 4, count = 5))
    }

    @Test
    fun counterIsHiddenForOneCard() {
        assertNull(DecisionDeckMath.counterLabel(index = 0, count = 1))
        assertEquals("1/2", DecisionDeckMath.counterLabel(index = 0, count = 2))
        assertEquals("2/2", DecisionDeckMath.counterLabel(index = 1, count = 2))
        assertEquals("2/5", DecisionDeckMath.counterLabel(index = 1, count = 5))
    }

    @Test
    fun peekLayersUseHandoffScaleOffsetAndAlpha() {
        assertEquals(6f, DecisionDeckMath.peekOffsetDp(0))
        assertEquals(12f, DecisionDeckMath.peekOffsetDp(1))
        assertEquals(0.96f, DecisionDeckMath.peekScale(0))
        assertEquals(0.92f, DecisionDeckMath.peekScale(1))
        assertEquals(0.72f, DecisionDeckMath.peekAlpha(0))
        assertEquals(0.45f, DecisionDeckMath.peekAlpha(1))
        assertEquals(28f, DecisionDeckMath.CORNER_DP)
        assertEquals(16f, DecisionDeckMath.HORIZONTAL_INSET_DP)
        assertEquals(300, DecisionDeckMath.COLLAPSE_MS)
        assertEquals(220, DecisionDeckMath.FLY_MS)
        assertEquals(200, DecisionDeckMath.ANSWER_MS)
        assertEquals(0.9f, DecisionDeckMath.ANSWER_SCALE)
        assertEquals(150, DecisionDeckMath.CROSSFADE_MS)
        assertEquals(8f, DecisionDeckMath.SLOP_DP)
        assertEquals(20f, DecisionDeckMath.EDGE_DP)
    }

    @Test
    fun rotationClampsAtEightAndIsZeroForPureVertical() {
        assertEquals(0f, DecisionDeckMath.rotationDegrees(0f, cardWidthPx = 300f, allowRotation = true))
        assertEquals(8f, DecisionDeckMath.rotationDegrees(300f, cardWidthPx = 300f, allowRotation = true))
        assertEquals(-8f, DecisionDeckMath.rotationDegrees(-300f, cardWidthPx = 300f, allowRotation = true))
        assertEquals(8f, DecisionDeckMath.rotationDegrees(900f, cardWidthPx = 300f, allowRotation = true))
        assertEquals(0f, DecisionDeckMath.rotationDegrees(40f, cardWidthPx = 300f, allowRotation = false))
        assertEquals(0f, DecisionDeckMath.rotationDegrees(40f, cardWidthPx = 0f, allowRotation = true))
    }

    @Test
    fun singleCardDampsDragAndNeverFlips() {
        assertEquals(3f to 6f, DecisionDeckMath.visualDelta(10f, 20f, singleCard = true))
        assertEquals(10f to 20f, DecisionDeckMath.visualDelta(10f, 20f, singleCard = false))
        assertFalse(
            DecisionDeckMath.shouldFlip(
                dxPx = 500f,
                dyPx = 500f,
                cardWidthPx = 300f,
                cardHeightPx = 150f,
                velocityXPxPerS = 50_000f,
                velocityYPxPerS = 50_000f,
                pxPerDp = density,
                cardCount = 1,
            ),
        )
    }

    @Test
    fun largerAxisMustExceedOneThird() {
        val width = 300f
        val height = 150f
        assertFalse(flip(dx = 100f, dy = 0f, width = width, height = height))
        assertTrue(flip(dx = 101f, dy = 0f, width = width, height = height))
        assertFalse(flip(dx = 0f, dy = 50f, width = width, height = height))
        assertTrue(flip(dx = 0f, dy = 51f, width = width, height = height))
        assertFalse(flip(dx = -100f, dy = 10f, width = width, height = height))
        assertTrue(flip(dx = -101f, dy = 10f, width = width, height = height))
        // Vertical travel would pass, but the larger axis is horizontal and is short of 1/3 width.
        assertFalse(flip(dx = 80f, dy = 51f, width = width, height = height))
        assertTrue(flip(dx = 10f, dy = -51f, width = width, height = height))
    }

    @Test
    fun velocityAbove800DpPerSecondFlipsEvenWhenDisplacementIsSmall() {
        val justUnder = 800f * density
        assertFalse(
            flip(
                dx = 1f,
                dy = 1f,
                velocityX = justUnder,
                velocityY = 0f,
            ),
        )
        assertTrue(
            flip(
                dx = 1f,
                dy = 1f,
                velocityX = justUnder + 1f,
                velocityY = 0f,
            ),
        )
    }

    @Test
    fun anyDirectionAdvancesTheSameWayAndTheLastCardWraps() {
        assertEquals(1, DecisionDeckMath.nextIndex(0, 3))
        assertEquals(0, DecisionDeckMath.nextIndex(2, 3))
        assertEquals(2, DecisionDeckMath.previousIndex(0, 3))
        assertEquals(0, DecisionDeckMath.indexAfterAnswer(index = 0, countAfter = 2))
        assertEquals(1, DecisionDeckMath.indexAfterAnswer(index = 1, countAfter = 2))
        assertEquals(1, DecisionDeckMath.indexAfterAnswer(index = 2, countAfter = 2))
        assertEquals(0, DecisionDeckMath.indexAfterAnswer(index = 0, countAfter = 0))
    }

    @Test
    fun slowReleaseFliesAlongTheDragAndAFastReleaseFollowsVelocity() {
        val alongDrag = DecisionDeckMath.flyVector(
            dxPx = 30f,
            dyPx = 0f,
            velocityXPxPerS = 10f,
            velocityYPxPerS = 0f,
            cardWidthPx = 300f,
            cardHeightPx = 0f,
        )
        assertTrue(alongDrag.first > 0f)
        assertEquals(0f, alongDrag.second)
        val alongVelocity = DecisionDeckMath.flyVector(
            dxPx = 30f,
            dyPx = 0f,
            velocityXPxPerS = 0f,
            velocityYPxPerS = 400f,
            cardWidthPx = 300f,
            cardHeightPx = 150f,
        )
        assertEquals(0f, alongVelocity.first)
        assertTrue(alongVelocity.second > 0f)
    }

    @Test
    fun screenEdgesAreNotClaimedByTheCard() {
        assertFalse(DecisionDeckMath.claimsGesture(windowXPx = 19f, screenWidthPx = 400f, edgePx = 20f))
        assertTrue(DecisionDeckMath.claimsGesture(windowXPx = 20f, screenWidthPx = 400f, edgePx = 20f))
        assertTrue(DecisionDeckMath.claimsGesture(windowXPx = 380f, screenWidthPx = 400f, edgePx = 20f))
        assertFalse(DecisionDeckMath.claimsGesture(windowXPx = 381f, screenWidthPx = 400f, edgePx = 20f))
    }

    @Test
    fun springStiffnessMatchesResponseAndDamping() {
        val omega = (2.0 * PI / DecisionDeckMath.SPRING_RESPONSE_S).toFloat()
        assertEquals(omega * omega, DecisionDeckMath.springStiffness)
        assertEquals(0.85f, DecisionDeckMath.SPRING_DAMPING)
        assertEquals(0.35f, DecisionDeckMath.SPRING_RESPONSE_S)
    }

    private fun flip(
        dx: Float,
        dy: Float,
        width: Float = 300f,
        height: Float = 150f,
        velocityX: Float = 0f,
        velocityY: Float = 0f,
        count: Int = 3,
    ): Boolean = DecisionDeckMath.shouldFlip(
        dxPx = dx,
        dyPx = dy,
        cardWidthPx = width,
        cardHeightPx = height,
        velocityXPxPerS = velocityX,
        velocityYPxPerS = velocityY,
        pxPerDp = density,
        cardCount = count,
    )
}
