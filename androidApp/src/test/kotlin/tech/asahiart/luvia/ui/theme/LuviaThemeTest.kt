package tech.asahiart.luvia.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import tech.asahiart.luvia.prefersDarkInk

class LuviaThemeTest {
    @Test
    fun lightTerminalUsesMisakaBoard() {
        val light = LuviaLightExtended
        assertEquals(Color(0xFFEEF5F2), light.terminalBg)
        assertEquals(Color(0xFF161D1C), light.terminalFg)
        assertEquals(Color(0xFF3F4947), light.terminalDim)
        assertTrue(light.lightTerminalBoard())
        assertTrue(prefersDarkInk(light.terminalBg.red, light.terminalBg.green, light.terminalBg.blue))
        assertTrue(contrastRatio(light.terminalFg, light.terminalBg) >= 13.0)
        assertEquals(Color(0xFF161D1C), light.composerInk(Color(0xFFE3EAE7)))
        assertEquals(Color(0xFF3F4947), light.inactivePaneInk())
    }

    @Test
    fun lightFailureLineUsesErrorWash() {
        val chrome = LuviaLightExtended.failureLineChrome(Color(0xFFABCDEF))
        assertEquals(Color(0xFFBA1A1A), chrome.stroke)
        assertEquals(Color(0xFF410002), chrome.text)
        assertEquals(Color(0xFFFFDAD6).red, chrome.fill.red)
        assertEquals(Color(0xFFFFDAD6).green, chrome.fill.green)
        assertEquals(Color(0xFFFFDAD6).blue, chrome.fill.blue)
        assertTrue(chrome.fill.alpha in 0.50f..0.60f)
    }

    @Test
    fun darkTerminalForegroundStaysAboveLogFloor() {
        val dark = LuviaDarkExtended
        assertEquals(Color(0xFF101615), dark.terminalBg)
        assertEquals(Color(0xFFE4EEEB), dark.terminalFg)
        assertFalse(dark.lightTerminalBoard())
        assertAtLeast(dark.terminalFg, Color(0xFFC8CDD2))
        assertEquals(Color(0xFFE4EEEB), dark.inactivePaneInk())
        assertEquals(Color(0xFFE4EEEB), dark.composerInk(Color(0xFF252B2A)))
        assertEquals(Color(0xFF14201E), dark.composerInk(Color(0xFFE3EAE7)))
    }

    @Test
    fun failureWashAndCardFollowTheTerminalWow() {
        assertEquals(Color(0xFFF7E7E2), LuviaLightExtended.failureWash)
        assertEquals(Color(0xFFBA1A1A), LuviaLightExtended.failureCard)
        assertEquals(Color(0xFFFFFFFF), LuviaLightExtended.failureCardInk)
        assertEquals(Color(0xFF5A1A1A).red, LuviaDarkExtended.failureWash.red)
        assertEquals(Color(0xFF5A1A1A).green, LuviaDarkExtended.failureWash.green)
        assertEquals(Color(0xFF5A1A1A).blue, LuviaDarkExtended.failureWash.blue)
        assertTrue(kotlin.math.abs(LuviaDarkExtended.failureWash.alpha - 0.62f) < 0.02f)
        assertEquals(Color(0xFF8E1A16), LuviaDarkExtended.failureCard)
        assertEquals(Color(0xFFFFFFFF), LuviaDarkExtended.failureCardInk)
    }

    @Test
    fun darkFailureChromeFollowsMaterialError() {
        val material = Color(0xFFABCDEF)
        val chrome = LuviaDarkExtended.failureLineChrome(material)
        assertEquals(material, chrome.stroke)
        assertEquals(material.red, chrome.fill.red)
        assertEquals(material.green, chrome.fill.green)
        assertEquals(material.blue, chrome.fill.blue)
        assertTrue(kotlin.math.abs(chrome.fill.alpha - 0.16f) < 0.02f)
        assertEquals(Color(0xFFE4EEEB), chrome.text)
    }

    private fun assertAtLeast(color: Color, floor: Color) {
        assertTrue(color.red + 1e-4f >= floor.red)
        assertTrue(color.green + 1e-4f >= floor.green)
        assertTrue(color.blue + 1e-4f >= floor.blue)
        assertTrue(color.alpha + 1e-4f >= floor.alpha)
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val lighter = maxOf(relativeLuminance(foreground), relativeLuminance(background))
        val darker = minOf(relativeLuminance(foreground), relativeLuminance(background))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return (0.2126 * channel(color.red)) + (0.7152 * channel(color.green)) + (0.0722 * channel(color.blue))
    }
}
