package tech.asahiart.luvia.ui

import tech.asahiart.luvia.thread.Ask
import tech.asahiart.luvia.thread.AskOption
import tech.asahiart.luvia.thread.askHasFreeText
import tech.asahiart.luvia.thread.recommendedOption

/**
 * HANDOFF §2 numbers. Android has two resting heights: [ANDROID_PEEK_DP] and the
 * content height. The keyboard over the expanded sheet is the high state.
 * iOS adds an explicit large detent; [IOS_LOW_PT] is the low one.
 */
internal object ChatDecisionMotion {
    const val ANDROID_PEEK_DP = 208
    const val IOS_LOW_PT = 196
    const val EXIT_MS = 250L
    const val COMPOSER_FADE_MS = 150
    const val NEXT_CROSSFADE_MS = 150
    const val SCRIM_ALPHA = 0f
    const val REVOKED_INK_ALPHA = 0.38f
}

internal data class ChatDecisionSheetPolicy(
    val presented: Boolean,
    val hideComposer: Boolean,
    val scrimAlpha: Float,
    val actionsEnabled: Boolean,
    /** 0.38 only while pairing is revoked. Observer, offline, and sending stay at 1. */
    val inkAlpha: Float,
    val peekDp: Int,
)

internal fun chatDecisionSheetPolicy(hasAsk: Boolean, chrome: ChatComposerChrome): ChatDecisionSheetPolicy =
    ChatDecisionSheetPolicy(
        presented = hasAsk,
        hideComposer = hasAsk,
        scrimAlpha = ChatDecisionMotion.SCRIM_ALPHA,
        actionsEnabled = chrome.decisionEnabled,
        inkAlpha = if (chrome.composerOnSurface38) ChatDecisionMotion.REVOKED_INK_ALPHA else 1f,
        peekDp = ChatDecisionMotion.ANDROID_PEEK_DP,
    )

/** Identity for the crossfade. A new host snapshot of the same ask does not restart it. */
internal fun decisionAskToken(ask: Ask?): String? {
    if (ask == null) return null
    return buildString {
        append(ask.question.orEmpty())
        append('\u0000')
        ask.options.forEach { option ->
            append(option.label)
            append('\u0001')
            append(option.recommended)
            append('\u0001')
        }
        append('\u0000')
        append(ask.freeTextNav?.size ?: -1)
    }
}

/**
 * Recognized empty option list stays empty. The sheet sends the user to the
 * terminal instead of inventing Yes/No.
 */
internal fun decisionOffersTerminalFallback(optionCount: Int): Boolean = optionCount == 0

/** Note field only for a free-text row on an ask that already has choices. */
internal fun decisionShowsNote(ask: Ask): Boolean =
    ask.options.isNotEmpty() && askHasFreeText(ask)

/** Recommended (or the shared primary fallback) sits first so the 208dp peek shows it. */
internal fun orderedDecisionOptions(ask: Ask): List<AskOption> {
    val lead = recommendedOption(ask) ?: return ask.options
    if (ask.options.firstOrNull() == lead) return ask.options
    return listOf(lead) + ask.options.filter { it != lead }
}
