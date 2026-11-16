package tech.asahiart.luvia.ui

import tech.asahiart.luvia.thread.Ask
import tech.asahiart.luvia.thread.AskAction
import tech.asahiart.luvia.thread.AskOption
import tech.asahiart.luvia.thread.AskTone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatDecisionSheetTest {
    @Test
    fun handoffHeightsAndMotionStayFixed() {
        assertEquals(208, ChatDecisionMotion.ANDROID_PEEK_DP)
        assertEquals(196, ChatDecisionMotion.IOS_LOW_PT)
        assertEquals(250L, ChatDecisionMotion.EXIT_MS)
        assertEquals(150, ChatDecisionMotion.COMPOSER_FADE_MS)
        assertEquals(150, ChatDecisionMotion.NEXT_CROSSFADE_MS)
        assertEquals(0f, ChatDecisionMotion.SCRIM_ALPHA)
        assertEquals(0.38f, ChatDecisionMotion.REVOKED_INK_ALPHA)
    }

    @Test
    fun askHidesComposerAndScrimWhileLiveActionsFollowChrome() {
        val live = chatComposerChrome(
            pairingRevoked = false,
            connection = ConnectionBadge.Live,
            isObserver = false,
            sending = false,
            draftNonBlank = true,
        )
        val policy = chatDecisionSheetPolicy(hasAsk = true, chrome = live)
        assertTrue(policy.presented)
        assertTrue(policy.hideComposer)
        assertEquals(0f, policy.scrimAlpha)
        assertEquals(208, policy.peekDp)
        assertTrue(policy.actionsEnabled)
        assertEquals(1f, policy.inkAlpha)
        val idle = chatDecisionSheetPolicy(hasAsk = false, chrome = live)
        assertFalse(idle.presented)
        assertFalse(idle.hideComposer)
    }

    @Test
    fun revokedInkIs38AndStaleStatesCannotAct() {
        val revoked = chatDecisionSheetPolicy(
            hasAsk = true,
            chrome = chatComposerChrome(
                pairingRevoked = true,
                connection = ConnectionBadge.Live,
                isObserver = false,
                sending = false,
                draftNonBlank = false,
            ),
        )
        assertFalse(revoked.actionsEnabled)
        assertEquals(0.38f, revoked.inkAlpha)

        listOf(
            chatComposerChrome(false, ConnectionBadge.Stale, false, false, true),
            chatComposerChrome(false, ConnectionBadge.Offline, false, false, true),
            chatComposerChrome(false, ConnectionBadge.Connecting, false, false, true),
            chatComposerChrome(false, ConnectionBadge.Live, true, false, true),
            chatComposerChrome(false, ConnectionBadge.Live, false, true, true),
        ).forEach { chrome ->
            val policy = chatDecisionSheetPolicy(hasAsk = true, chrome = chrome)
            assertFalse(policy.actionsEnabled)
            assertEquals(1f, policy.inkAlpha)
        }
    }

    @Test
    fun emptyOptionsStayTerminalAndDoNotInventYesNo() {
        val ask = Ask("Run ./gradlew :shared:jvmTest?", emptyList())
        assertTrue(decisionOffersTerminalFallback(ask.options.size))
        assertEquals(emptyList(), orderedDecisionOptions(ask))
        assertFalse(decisionShowsNote(ask))
        assertFalse(decisionOffersTerminalFallback(1))
    }

    @Test
    fun noteStaysOnTheFreeTextRowOnly() {
        val choice = AskOption("允许", AskTone.Primary, AskAction.Prompt("1"))
        val withNote = Ask("q?", listOf(choice), freeTextNav = listOf(tech.asahiart.luvia.AgentKey.Char('z')))
        val without = Ask("q?", listOf(choice))
        assertTrue(decisionShowsNote(withNote))
        assertFalse(decisionShowsNote(without))
        assertFalse(decisionShowsNote(Ask("q?", emptyList(), freeTextNav = listOf(tech.asahiart.luvia.AgentKey.Char('z')))))
    }

    @Test
    fun recommendedOptionLeadsThePeekWithoutDroppingRows() {
        val keep = AskOption("先用内存实现，稍后再拆", AskTone.Neutral, AskAction.Prompt("2"))
        val split = AskOption("拆成两个平台实现", AskTone.Primary, AskAction.Prompt("1"), recommended = true)
        val ask = Ask("把 TokenStore 拆开?", listOf(keep, split))
        assertEquals(listOf(split, keep), orderedDecisionOptions(ask))
        val already = ask.copy(options = listOf(split, keep))
        assertEquals(listOf(split, keep), orderedDecisionOptions(already))
    }

    @Test
    fun sameAskTokenDoesNotChangeWhenTheInstanceIsCopied() {
        val ask = Ask(
            "把 TokenStore 拆开?",
            listOf(AskOption("拆", AskTone.Primary, AskAction.Prompt("1"), recommended = true)),
        )
        assertEquals(decisionAskToken(ask), decisionAskToken(ask.copy()))
        assertNull(decisionAskToken(null))
        assertTrue(decisionAskToken(ask) != decisionAskToken(ask.copy(question = "下一题?")))
    }
}
