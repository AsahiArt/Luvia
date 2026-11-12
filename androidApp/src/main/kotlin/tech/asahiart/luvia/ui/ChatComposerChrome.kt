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
)

internal enum class ChatComposerHint {
    Message,
    Observer,
    Offline,
    PairingRevoked,
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
    return ChatComposerChrome(
        offline = offline,
        inputEnabled = inputEnabled,
        hint = hint,
        sendEnabled = inputEnabled && draftNonBlank,
        decisionEnabled = inputEnabled,
        headerSubtitle = if (pairingRevoked) "配对已失效" else null,
        showPairAgain = pairingRevoked,
    )
}
