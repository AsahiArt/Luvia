package tech.asahiart.luvia.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatComposerChromeTest {
    @Test
    fun revokedDisablesInputAndCardEvenWhileConnecting() {
        val chrome = chatComposerChrome(
            pairingRevoked = true,
            connection = ConnectionBadge.Connecting,
            isObserver = false,
            sending = false,
            draftNonBlank = true,
        )
        assertFalse(chrome.inputEnabled)
        assertFalse(chrome.sendEnabled)
        assertFalse(chrome.decisionEnabled)
        assertFalse(chrome.offline)
        assertEquals("配对已失效", chrome.headerSubtitle)
        assertEquals(ChatComposerHint.PairingRevoked, chrome.hint)
        assertTrue(chrome.showPairAgain)
    }

    @Test
    fun connectingWithoutRevokeStaysOfflineAndHidesPairAgain() {
        val chrome = chatComposerChrome(
            pairingRevoked = false,
            connection = ConnectionBadge.Connecting,
            isObserver = false,
            sending = false,
            draftNonBlank = true,
        )
        assertTrue(chrome.offline)
        assertFalse(chrome.inputEnabled)
        assertFalse(chrome.sendEnabled)
        assertFalse(chrome.decisionEnabled)
        assertEquals(ChatComposerHint.Offline, chrome.hint)
        assertNull(chrome.headerSubtitle)
        assertFalse(chrome.showPairAgain)
    }
}
