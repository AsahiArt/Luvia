package tech.asahiart.luvia

/** Six decimal digits. The LAN redeem ticket, not a fingerprint. */
public const val SHORT_CODE_LENGTH: Int = 6

/**
 * Accept a proposed short-code edit.
 *
 * The proposal must be entirely decimal digits. Letters, dots, and other
 * characters are refused and [current] is kept, so a computer address such as
 * `192.168.1.83` cannot be rewritten into `192168`. A longer digit string is
 * clamped to [SHORT_CODE_LENGTH].
 */
public fun applyShortCodeInput(current: String, proposed: String): String {
    if (proposed.any { it !in '0'..'9' }) return current
    return if (proposed.length <= SHORT_CODE_LENGTH) proposed else proposed.take(SHORT_CODE_LENGTH)
}

/**
 * Manual pairing fields. The six-digit code and the computer address are
 * independent: [updateHost] never writes [shortCode], and [updateShortCode]
 * never writes [host].
 *
 * Short-code edits apply only while [shortFieldFocused] is true, so keystrokes
 * delivered to this buffer while the address field is the one being edited are
 * dropped.
 */
public data class PairingManualEntry(
    public val shortCode: String = "",
    public val host: String = "",
    public val shortFieldFocused: Boolean = false,
) {
    public fun focusShortField(focused: Boolean): PairingManualEntry =
        if (focused == shortFieldFocused) this else copy(shortFieldFocused = focused)

    public fun updateShortCode(proposed: String): PairingManualEntry {
        if (!shortFieldFocused) return this
        val next = applyShortCodeInput(shortCode, proposed)
        return if (next == shortCode) this else copy(shortCode = next)
    }

    public fun updateHost(proposed: String): PairingManualEntry =
        if (proposed == host) this else copy(host = proposed)
}
