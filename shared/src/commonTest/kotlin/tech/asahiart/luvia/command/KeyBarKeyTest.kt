package tech.asahiart.luvia.command

import kotlin.test.Test
import kotlin.test.assertEquals
import tech.asahiart.luvia.AgentKey

class KeyBarKeyTest {
    @Test
    fun mapsNamedKeysToAgentKeyWireTokens() {
        assertEquals(listOf("esc"), KeyBarKey.Esc.toAgentKeys().map { it.wire })
        assertEquals(listOf("tab"), KeyBarKey.Tab.toAgentKeys().map { it.wire })
        assertEquals(listOf("ctrl+c"), KeyBarKey.CtrlC.toAgentKeys().map { it.wire })
        assertEquals(listOf("up"), KeyBarKey.Up.toAgentKeys().map { it.wire })
        assertEquals(listOf("down"), KeyBarKey.Down.toAgentKeys().map { it.wire })
        assertEquals(listOf("enter"), KeyBarKey.Enter.toAgentKeys().map { it.wire })
    }

    @Test
    fun shiftTabSendsCsiBackTabSequence() {
        val keys = KeyBarKey.ShiftTab.toAgentKeys()
        assertEquals(3, keys.size)
        assertEquals("\u001B", keys[0].wire)
        assertEquals("[", keys[1].wire)
        assertEquals("Z", keys[2].wire)
        assertEquals(AgentKey.Char('\u001B')::class, keys[0]::class)
    }
}
