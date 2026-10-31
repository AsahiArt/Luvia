package tech.asahiart.luvia

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContrastTest {
    @Test
    fun lightCapsulePrefersDarkInk() {
        assertTrue(prefersDarkInk(0xE3 / 255f, 0xEA / 255f, 0xE7 / 255f))
        assertTrue(prefersDarkInk(0xE4 / 255f, 0xEE / 255f, 0xEB / 255f))
    }

    @Test
    fun darkFillAndPrimaryPreferLightInk() {
        assertFalse(prefersDarkInk(0x25 / 255f, 0x2B / 255f, 0x2A / 255f))
        assertFalse(prefersDarkInk(0x10 / 255f, 0x16 / 255f, 0x15 / 255f))
        assertFalse(prefersDarkInk(0f, 0x6A / 255f, 0x62 / 255f))
    }
}
