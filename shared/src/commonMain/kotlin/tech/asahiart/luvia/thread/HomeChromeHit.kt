package tech.asahiart.luvia.thread

/**
 * Hit regions for the floating home bar (会话 / 机器 / 设置, plus search).
 *
 * The bar is drawn over the session list. Glass only hit-tests the glyph, so a
 * tap on the rest of a tab falls through to the row underneath and opens a chat.
 * Points in the bar's band belong to the bar, even when a row frame contains them.
 *
 * SwiftUI reads these insets so the hit map and the laid-out bar stay the same.
 */
public object HomeChromeLayout {
    public val horizontalInset: Double = 20.0
    public val clusterGap: Double = 12.0
    public val searchDiameter: Double = 48.0
    public val capsuleInset: Double = 6.0
    public val barBottomInset: Double = 6.0
}

public enum class HomeChromeSlot {
    Sessions,
    Machines,
    Settings,
    Search,
    /** Inside the bar, but not on a control. Still not a session row. */
    Absorb,
}

public enum class HomeTapWinner {
    Sessions,
    Machines,
    Settings,
    Search,
    Chrome,
    Row,
    Miss,
}

/**
 * Which control owns [x] in the bar's local coordinates.
 * [width] is the full bar, including the horizontal insets.
 * The capsule's leading and trailing padding count as the end tabs, so the whole pill is live.
 */
public fun homeChromeSlot(x: Double, width: Double): HomeChromeSlot {
    if (!x.isFinite() || !width.isFinite() || width <= 0.0 || x < 0.0 || x >= width) {
        return HomeChromeSlot.Absorb
    }
    val inset = HomeChromeLayout.horizontalInset
    val searchLeft = width - inset - HomeChromeLayout.searchDiameter
    val searchRight = width - inset
    // A circle that doesn't fit between the insets is not a control.
    if (searchLeft >= inset && x >= searchLeft && x < searchRight) return HomeChromeSlot.Search
    val capsuleLeft = inset
    val capsuleRight = searchLeft - HomeChromeLayout.clusterGap
    if (x < capsuleLeft || x >= capsuleRight || capsuleRight <= capsuleLeft) {
        return HomeChromeSlot.Absorb
    }
    val innerLeft = capsuleLeft + HomeChromeLayout.capsuleInset
    val innerRight = capsuleRight - HomeChromeLayout.capsuleInset
    val inner = innerRight - innerLeft
    if (inner <= 0.0) return HomeChromeSlot.Absorb
    val button = inner / 3.0
    val sessionsEnd = innerLeft + button
    val machinesEnd = sessionsEnd + button
    return when {
        x < sessionsEnd -> HomeChromeSlot.Sessions
        x < machinesEnd -> HomeChromeSlot.Machines
        else -> HomeChromeSlot.Settings
    }
}

/**
 * A tap inside the chrome band never opens the session row under the bar.
 * [rowContainsPoint] is the list's claim; it only wins outside the band.
 */
public fun resolveOverlappingTap(
    slot: HomeChromeSlot,
    inChromeBand: Boolean,
    rowContainsPoint: Boolean,
): HomeTapWinner {
    if (inChromeBand) {
        return when (slot) {
            HomeChromeSlot.Sessions -> HomeTapWinner.Sessions
            HomeChromeSlot.Machines -> HomeTapWinner.Machines
            HomeChromeSlot.Settings -> HomeTapWinner.Settings
            HomeChromeSlot.Search -> HomeTapWinner.Search
            HomeChromeSlot.Absorb -> HomeTapWinner.Chrome
        }
    }
    return if (rowContainsPoint) HomeTapWinner.Row else HomeTapWinner.Miss
}
