package tech.asahiart.luvia.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import tech.asahiart.luvia.ui.theme.LuviaDarkExtended
import tech.asahiart.luvia.ui.theme.LuviaLightExtended

class TerminalOutlinedLineTest {
    @Test
    fun lightOutlineUsesMisakaErrorWashStrokeAndInk() {
        val light = LuviaLightExtended
        val paint = terminalOutlinedLinePaint(light)
        assertEquals(light.failureFill, paint.fill)
        assertEquals(Color(0xFFFFDAD6).red, paint.fill.red)
        assertEquals(Color(0xFFFFDAD6).green, paint.fill.green)
        assertEquals(Color(0xFFFFDAD6).blue, paint.fill.blue)
        assertTrue(paint.fill.alpha in 0.50f..0.60f)
        assertEquals(Color(0xFFBA1A1A), paint.stroke)
        assertEquals(Color(0xFF410002), paint.ink)
        assertEquals(light.failureStroke, paint.stroke)
        assertEquals(light.failureInk, paint.ink)
    }

    @Test
    fun darkOutlineKeepsTheExistingWashAndAddsNoNewStroke() {
        val dark = LuviaDarkExtended
        val paint = terminalOutlinedLinePaint(dark)
        assertEquals(dark.failureWash, paint.fill)
        assertEquals(Color(0xFF5A1A1A).red, paint.fill.red)
        assertEquals(Color(0xFF5A1A1A).green, paint.fill.green)
        assertEquals(Color(0xFF5A1A1A).blue, paint.fill.blue)
        assertTrue(kotlin.math.abs(paint.fill.alpha - 0.62f) < 0.02f)
        assertNull(paint.stroke)
        assertNull(paint.ink)
    }
}
