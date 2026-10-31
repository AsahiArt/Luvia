package tech.asahiart.luvia

/** `host:port`, with brackets around IPv6 so the port stays unambiguous. */
public fun formatConnectEndpoint(address: String, port: Int): String {
    val trimmed = address.trim()
    if (trimmed.isEmpty()) return ":$port"
    val bare = trimmed.removePrefix("[").removeSuffix("]")
    return if (bare.contains(':')) "[$bare]:$port" else "$bare:$port"
}

/**
 * Shown when confirm-pair cannot open SSH.
 * Includes every attempted endpoint and the Wi-Fi / Tailscale hint.
 */
public fun pairingUnreachableCopy(addresses: List<String>, port: Int): String {
    val endpoints =
        addresses
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { formatConnectEndpoint(it, port) }
    val where =
        if (endpoints.isEmpty()) formatConnectEndpoint("未知地址", port) else endpoints.joinToString("、")
    return "连不上 $where。试试同一 Wi-Fi 或 Tailscale。"
}

/** Transport failures that mean the host:port never accepted SSH. Auth and host-key mismatches are not this. */
public fun Failure.isUnreachableTransport(): Boolean {
    val reason =
        when (this) {
            is Failure.Transport -> reason
            is Failure.Bridge -> reason
            else -> return false
        }.lowercase()
    if (reason.contains("host key")) return false
    if (
        reason.contains("authentication") ||
        reason.contains("permission denied") ||
        reason.contains("public-key")
    ) {
        return false
    }
    return reason.contains("timeout") ||
        reason.contains("timed out") ||
        reason.contains("no route") ||
        reason.contains("connection refused") ||
        reason.contains("unreachable") ||
        reason.contains("network is down") ||
        reason.contains("failed to resolve") ||
        reason.contains("unknown host") ||
        reason.contains("host is down") ||
        reason.contains("could not connect") ||
        reason.contains("no address") ||
        reason.contains("dns failed") ||
        reason.contains("ssh connection failed") ||
        reason.contains("ssh transport failure") ||
        reason.contains("incomplete host endpoint")
}

/** Copy for a confirm-pair connect failure. Unreachable hosts name the address and port. */
public fun pairingConnectMessage(failure: Failure, addresses: List<String>, port: Int): String =
    if (failure.isUnreachableTransport()) {
        pairingUnreachableCopy(addresses, port)
    } else {
        failure.connectMessage()
    }
