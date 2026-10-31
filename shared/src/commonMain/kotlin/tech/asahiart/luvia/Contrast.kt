package tech.asahiart.luvia

/**
 * Whether [red], [green], and [blue] (0..1) need dark label ink.
 * Terminal chips sit on a dark canvas but borrow Material surface fills, which are
 * light in the light theme — light-on-light was the invisible pane and key text.
 */
public fun prefersDarkInk(red: Float, green: Float, blue: Float): Boolean {
    val luminance = (0.2126f * red) + (0.7152f * green) + (0.0722f * blue)
    return luminance >= 0.62f
}
