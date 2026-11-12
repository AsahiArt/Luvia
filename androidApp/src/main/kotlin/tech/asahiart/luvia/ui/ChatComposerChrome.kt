package tech.asahiart.luvia.ui

/**
 * Pure chat chrome for the composer, decision card, and pairing-lost header.
 * [headerSubtitle] / [ChatComposerHint.PairingRevoked] stay in lockstep with
 * `R.string.pairing_revoked` ("配对已失效").
 */
internal data class ChatComposerChrome(
    val offline: Boolean,
    val inputEnabled: Boolean,
    val hint: ChatComposerHint,
    val sendEnabled: Boolean,
    val decisionEnabled: Boolean,
    /** Short header line when pairing is revoked; null keeps the session / offline subtitle. */
    val headerSubtitle: String?,
    val showPairAgain: Boolean,
    /**
     * Decision-card note placeholder. Revoked is pair-again copy; every other
     * disabled reason (observer, offline, sending) is the observer copy;
     * enabled keeps [R.string.note_hint] ("补充说明").
     */
    val decisionNoteHint: DecisionNoteHint,
    /** Revoked composer placeholder and send icon use onSurface at 38%. */
    val composerOnSurface38: Boolean,
)

internal enum class ChatComposerHint {
    Message,
    Observer,
    Offline,
    PairingRevoked,
}

/** Copy stays in lockstep with the matching `R.string` in `values/strings.xml`. */
internal enum class DecisionNoteHint(val text: String) {
    /** [R.string.note_hint] */
    Note("补充说明"),
    /** [R.string.observer_cannot_answer] */
    Observer("观察者不能代答"),
    /** [R.string.pair_again_to_answer] — "Pair again to answer". */
    PairAgain("重新配对后才能作答"),
}

internal fun chatComposerChrome(
    pairingRevoked: Boolean,
    connection: ConnectionBadge?,
    isObserver: Boolean,
    sending: Boolean,
    draftNonBlank: Boolean,
): ChatComposerChrome {
    val offline = !pairingRevoked && connection != null && connection != ConnectionBadge.Live
    val inputEnabled = !pairingRevoked && !isObserver && !sending && !offline
    val hint = when {
        pairingRevoked -> ChatComposerHint.PairingRevoked
        offline -> ChatComposerHint.Offline
        isObserver -> ChatComposerHint.Observer
        else -> ChatComposerHint.Message
    }
    val decisionNoteHint = when {
        pairingRevoked -> DecisionNoteHint.PairAgain
        !inputEnabled -> DecisionNoteHint.Observer
        else -> DecisionNoteHint.Note
    }
    return ChatComposerChrome(
        offline = offline,
        inputEnabled = inputEnabled,
        hint = hint,
        sendEnabled = inputEnabled && draftNonBlank,
        decisionEnabled = inputEnabled,
        headerSubtitle = if (pairingRevoked) "配对已失效" else null,
        showPairAgain = pairingRevoked,
        decisionNoteHint = decisionNoteHint,
        composerOnSurface38 = pairingRevoked,
    )
}
