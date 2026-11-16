package tech.asahiart.luvia

/**
 * One pairing submission. Cancel, a new draft, or leaving the screen advances the generation.
 * [claim] is the only door into key and profile persistence, and it opens once per id.
 */
public class PairingAttemptGate {
    private var generation: Long = 0
    private val claimed: MutableSet<Long> = mutableSetOf()

    public fun open(): Long {
        generation += 1
        return generation
    }

    public fun invalidate() {
        generation += 1
    }

    public fun isCurrent(id: Long): Boolean = id != 0L && id == generation

    /** True only the first time [id] is still the open attempt. */
    public fun claim(id: Long): Boolean {
        if (!isCurrent(id) || id in claimed) return false
        claimed += id
        return true
    }

    public fun wasClaimed(id: Long): Boolean = id in claimed
}
