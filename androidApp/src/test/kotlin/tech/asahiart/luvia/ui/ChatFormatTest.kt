package tech.asahiart.luvia.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import tech.asahiart.luvia.HANDSHAKE_FAILED_EN
import tech.asahiart.luvia.HANDSHAKE_FAILED_ZH
import tech.asahiart.luvia.PROVE_REJECTED_EN
import tech.asahiart.luvia.PROVE_REJECTED_ZH

class ChatFormatTest {
    @Test
    fun proveRejectedCopyHidesTheHostReason() {
        val raw = "no pairing grant for host-1"
        val shown = localizeUiMessage(raw)
        assertEquals(PROVE_REJECTED_ZH, shown)
        assertFalse(shown.contains("no pairing grant"))
        assertFalse(shown.contains("host-1"))
        assertFalse(shown.contains("unknown_device"))
        assertEquals(PROVE_REJECTED_ZH, localizeUiMessage(PROVE_REJECTED_EN))
        assertEquals(HANDSHAKE_FAILED_ZH, localizeUiMessage(HANDSHAKE_FAILED_EN))
    }
}
