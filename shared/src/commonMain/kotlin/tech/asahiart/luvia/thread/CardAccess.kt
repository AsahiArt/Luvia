package tech.asahiart.luvia.thread

/** Live role of the host that owns one card. Selected host is not an input. */
public data class CardHostState(
    val connected: Boolean,
    val controller: Boolean,
    val pairingRevoked: Boolean,
)

/** A card can be answered only from its own host: live, controller, and still paired. */
public fun cardCanOperate(host: CardHostState?): Boolean =
    host != null && host.connected && host.controller && !host.pairingRevoked
