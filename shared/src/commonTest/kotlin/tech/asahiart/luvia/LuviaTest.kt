package tech.asahiart.luvia

import kotlin.test.Test
import kotlin.test.assertEquals

class LuviaTest {
    @Test
    fun exposesSupportedProtocolIdentity() {
        assertEquals("luvus-uhp", Luvia.protocolName)
        assertEquals(1, Luvia.protocolMajor)
    }
}
