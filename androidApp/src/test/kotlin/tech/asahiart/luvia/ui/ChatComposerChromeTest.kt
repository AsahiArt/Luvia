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

    @Test
    fun revokedNoteAndComposerStayMutedWhileConnecting() {
        val chrome = chatComposerChrome(
            pairingRevoked = true,
            connection = ConnectionBadge.Connecting,
            isObserver = false,
            sending = false,
            draftNonBlank = false,
        )
        assertEquals(DecisionNoteHint.PairAgain, chrome.decisionNoteHint)
        assertEquals("重新配对后才能作答", chrome.decisionNoteHint.text)
        assertEquals(ChatComposerHint.PairingRevoked, chrome.hint)
        assertTrue(chrome.composerOnSurface38)
        assertFalse(chrome.inputEnabled)
        assertFalse(chrome.decisionEnabled)
        val alsoObserver = chatComposerChrome(
            pairingRevoked = true,
            connection = ConnectionBadge.Connecting,
            isObserver = true,
            sending = true,
            draftNonBlank = true,
        )
        assertEquals("重新配对后才能作答", alsoObserver.decisionNoteHint.text)
        assertEquals(ChatComposerHint.PairingRevoked, alsoObserver.hint)
        assertTrue(alsoObserver.composerOnSurface38)
    }

    @Test
    fun otherDisabledNotesUseObserverCopyAndKeepComposerColors() {
        val observer = chatComposerChrome(
            pairingRevoked = false,
            connection = ConnectionBadge.Live,
            isObserver = true,
            sending = false,
            draftNonBlank = false,
        )
        val offline = chatComposerChrome(
            pairingRevoked = false,
            connection = ConnectionBadge.Connecting,
            isObserver = false,
            sending = false,
            draftNonBlank = true,
        )
        val sending = chatComposerChrome(
            pairingRevoked = false,
            connection = ConnectionBadge.Live,
            isObserver = false,
            sending = true,
            draftNonBlank = true,
        )
        assertEquals(DecisionNoteHint.Observer, observer.decisionNoteHint)
        assertEquals("观察者不能代答", observer.decisionNoteHint.text)
        assertEquals("观察者不能代答", offline.decisionNoteHint.text)
        assertEquals("观察者不能代答", sending.decisionNoteHint.text)
        assertFalse(observer.composerOnSurface38)
        assertFalse(offline.composerOnSurface38)
        assertFalse(sending.composerOnSurface38)
        assertFalse(observer.decisionEnabled)
        assertFalse(offline.decisionEnabled)
        assertFalse(sending.decisionEnabled)
    }

    @Test
    fun enabledNoteKeepsOptionalHint() {
        val chrome = chatComposerChrome(
            pairingRevoked = false,
            connection = ConnectionBadge.Live,
            isObserver = false,
            sending = false,
            draftNonBlank = true,
        )
        assertEquals(DecisionNoteHint.Note, chrome.decisionNoteHint)
        assertEquals("补充说明", chrome.decisionNoteHint.text)
        assertFalse(chrome.composerOnSurface38)
        assertTrue(chrome.decisionEnabled)
        assertTrue(chrome.inputEnabled)
        assertTrue(chrome.sendEnabled)
    }
}
