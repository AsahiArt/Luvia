package tech.asahiart.luvia

/**
 * How the address that won the candidate race is reached.
 * Shown only for [HostLink.Online]. Reconnecting, offline, a rejected pairing,
 * and an in-progress race have no route.
 */
public enum class ConnectionRoute {
    Lan,
    Tailnet,
}

/**
 * Route of [HostLink.Online.address]. Null for every other link, so the chip
 * stays hidden until a candidate has actually won.
 *
 * Tailnet is 100.64.0.0/10, fd7a:115c:a1e0::/48, a `*.ts.net` name, or an
 * address equal to [tailnetFqdn]. Everything else that is online is LAN,
 * including 192.168.0.0/16 and link-local.
 */
public fun connectionRouteOf(link: HostLink, tailnetFqdn: String?): ConnectionRoute? {
    val online = link as? HostLink.Online ?: return null
    return if (isOnlineTailnet(online.address, tailnetFqdn)) ConnectionRoute.Tailnet else ConnectionRoute.Lan
}

public val HostRuntime.connectionRoute: ConnectionRoute?
    get() = connectionRouteOf(link, profile.tailnetFqdn)

private fun isOnlineTailnet(address: String, tailnetFqdn: String?): Boolean {
    if (sameRouteName(address, tailnetFqdn)) return true
    return isTailnetAddress(address)
}

private fun sameRouteName(address: String, tailnetFqdn: String?): Boolean {
    val expected = tailnetFqdn?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: return false
    val actual = address.trim().removePrefix("[").removeSuffix("]").trim().trimEnd('.')
    return actual.equals(expected, ignoreCase = true)
}
