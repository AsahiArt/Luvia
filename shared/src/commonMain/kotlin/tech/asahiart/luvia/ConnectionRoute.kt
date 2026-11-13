package tech.asahiart.luvia

/**
 * Destination-address category of the IP that was actually dialed.
 * Shown only for [HostLink.Online]. This is not evidence of a direct or relay path.
 * Reconnecting, offline, a rejected pairing, and an in-progress race have no route.
 */
public enum class ConnectionRoute {
    Lan,
    Tailnet,
    Unknown,
}

/**
 * Category of the winner's dialed IP. Null for every other link, so the chip
 * stays hidden until a candidate has actually won.
 *
 * [tailnetFqdn] is ignored. A name the user can edit, including `*.ts.net`,
 * does not select the category. Tailnet is the dialed address in 100.64.0.0/10
 * or fd7a:115c:a1e0::/48, after unwrapping an IPv4-mapped IPv6 form. LAN is a
 * private or link-local dialed address. Anything else that is online, including
 * a public IP or an unresolved name, stays [ConnectionRoute.Unknown].
 */
@Suppress("UNUSED_PARAMETER")
public fun connectionRouteOf(link: HostLink, tailnetFqdn: String?): ConnectionRoute? {
    val online = link as? HostLink.Online ?: return null
    val dialed = online.dialedAddress.trim().ifEmpty { online.address }
    return categoryOfDialed(dialed)
}

public val HostRuntime.connectionRoute: ConnectionRoute?
    get() = connectionRouteOf(link, profile.tailnetFqdn)

private fun categoryOfDialed(raw: String): ConnectionRoute {
    val groups = expandDialedIp(raw) ?: return ConnectionRoute.Unknown
    val v4 = mappedV4(groups)
    if (v4 != null) return categoryOfV4(v4)
    if (isTailnetV6(groups)) return ConnectionRoute.Tailnet
    if (isLanV6(groups)) return ConnectionRoute.Lan
    return ConnectionRoute.Unknown
}

private fun categoryOfV4(parts: IntArray): ConnectionRoute {
    val a = parts[0]
    val b = parts[1]
    if (a == 100 && b in 64..127) return ConnectionRoute.Tailnet
    val lan =
        a == 10 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254)
    return if (lan) ConnectionRoute.Lan else ConnectionRoute.Unknown
}

private fun isTailnetV6(groups: IntArray): Boolean =
    groups[0] == 0xfd7a && groups[1] == 0x115c && groups[2] == 0xa1e0

private fun isLanV6(groups: IntArray): Boolean {
    val linkLocal = groups[0] and 0xffc0 == 0xfe80
    val ula = groups[0] and 0xfe00 == 0xfc00
    return linkLocal || ula
}

/** Eight hextets, or null when [raw] is not an IP. IPv4-mapped addresses stay wrapped so [mappedV4] can see them. */
private fun expandDialedIp(raw: String): IntArray? {
    var text = raw.trim().removePrefix("[").removeSuffix("]").substringBefore('%').trim().lowercase()
    if (text.isEmpty()) return null
    val dotted = text.lastIndexOf(':').let { colon ->
        if (colon < 0 || !text.contains('.')) {
            null
        } else {
            val v4 = parseV4(text.substring(colon + 1)) ?: return null
            val high = ((v4[0] shl 8) or v4[1]).toString(16)
            val low = ((v4[2] shl 8) or v4[3]).toString(16)
            text = text.substring(0, colon) + ":$high:$low"
            v4
        }
    }
    if (!text.contains(':')) {
        val v4 = dotted ?: parseV4(text) ?: return null
        return intArrayOf(0, 0, 0, 0, 0, 0xffff, (v4[0] shl 8) or v4[1], (v4[2] shl 8) or v4[3])
    }
    val halves = text.split("::")
    if (halves.size > 2) return null
    fun side(part: String): IntArray? {
        if (part.isEmpty()) return IntArray(0)
        val groups = part.split(':')
        val out = IntArray(groups.size)
        for ((index, group) in groups.withIndex()) {
            if (group.length !in 1..4) return null
            out[index] = group.toIntOrNull(16) ?: return null
        }
        return out
    }
    val left = side(halves[0]) ?: return null
    if (halves.size == 1) {
        if (left.size != 8) return null
        return left
    }
    val right = side(halves[1]) ?: return null
    if (left.size + right.size > 7) return null
    val out = IntArray(8)
    left.copyInto(out, 0)
    right.copyInto(out, 8 - right.size)
    return out
}

private fun mappedV4(groups: IntArray): IntArray? {
    for (index in 0..4) {
        if (groups[index] != 0) return null
    }
    if (groups[5] != 0xffff) return null
    return intArrayOf(groups[6] shr 8, groups[6] and 0xff, groups[7] shr 8, groups[7] and 0xff)
}

private fun parseV4(text: String): IntArray? {
    val parts = text.split('.')
    if (parts.size != 4) return null
    val out = IntArray(4)
    for ((index, part) in parts.withIndex()) {
        val value = part.toIntOrNull() ?: return null
        if (value !in 0..255) return null
        out[index] = value
    }
    return out
}
