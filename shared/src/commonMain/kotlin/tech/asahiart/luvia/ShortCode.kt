package tech.asahiart.luvia

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tech.asahiart.luvia.internal.StrictJsonException
import tech.asahiart.luvia.internal.parseStrictJson

/** Default TCP port for `POST /pair/redeem`. The host prints the address it actually bound. */
public const val DEFAULT_SHORT_REDEEM_PORT: Int = 8741

public data class ShortCodeEndpoint(
    public val host: String,
    public val port: Int,
)

/**
 * Exactly six digits, after trimming. This is the LAN redeem ticket, not
 * [shortFingerprint].
 */
public fun looksLikeShortCode(raw: String): Boolean {
    val trimmed = raw.trim()
    return trimmed.length == 6 && trimmed.all { it.isDigit() }
}

/**
 * `192.168.1.4`, `192.168.1.4:8741`, `[fe80::1]`, or `[fe80::1]:8741`.
 * A bare IPv6 literal keeps [defaultPort]. Blank input is null.
 */
public fun parseShortCodeHost(raw: String, defaultPort: Int = DEFAULT_SHORT_REDEEM_PORT): ShortCodeEndpoint? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || defaultPort !in 1..65535) return null
    if (trimmed.startsWith("[")) {
        val end = trimmed.indexOf(']')
        if (end <= 1) return null
        val host = trimmed.substring(1, end)
        if (!isHostToken(host)) return null
        val rest = trimmed.substring(end + 1)
        val port =
            when {
                rest.isEmpty() -> defaultPort
                rest.startsWith(":") -> rest.substring(1).toIntOrNull() ?: return null
                else -> return null
            }
        if (port !in 1..65535) return null
        return ShortCodeEndpoint(host, port)
    }
    val colon = trimmed.lastIndexOf(':')
    if (colon > 0 && colon < trimmed.lastIndex) {
        val host = trimmed.substring(0, colon)
        val portText = trimmed.substring(colon + 1)
        if (portText.isNotEmpty() && portText.all { it.isDigit() } && !host.contains(':') && isHostToken(host)) {
            val port = portText.toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return ShortCodeEndpoint(host, port)
        }
    }
    if (!isHostToken(trimmed)) return null
    return ShortCodeEndpoint(trimmed, defaultPort)
}

/**
 * Redeem a six-digit code at a known host. Returns the `luvia2:` payload the
 * phone must pass to `completePairing`. The short code is not itself a pairing
 * code.
 */
public suspend fun redeemShortCode(
    code: String,
    deviceKeyFingerprint: String,
    host: String,
    port: Int = DEFAULT_SHORT_REDEEM_PORT,
): Outcome<String> {
    val endpoint = parseShortCodeHost(host, port) ?: return fail(Failure.ProtocolError("short code needs the computer address"))
    return redeemShortCode(code, deviceKeyFingerprint, listOf(endpoint), platformShortCodePoster())
}

internal suspend fun redeemShortCode(
    code: String,
    deviceKeyFingerprint: String,
    endpoints: List<ShortCodeEndpoint>,
    poster: ShortCodePoster,
): Outcome<String> {
    val trimmed = code.trim()
    if (!looksLikeShortCode(trimmed)) {
        return fail(Failure.ProtocolError("short code was rejected"))
    }
    val dk = deviceKeyFingerprint.trim()
    if (dk.isEmpty()) {
        return fail(Failure.ProtocolError("short code device key does not match"))
    }
    if (endpoints.isEmpty()) {
        return fail(Failure.ProtocolError("short code needs the computer address"))
    }
    val codeJson = jsonString(trimmed) ?: return fail(Failure.ProtocolError("short code was rejected"))
    val dkJson = jsonString(dk) ?: return fail(Failure.ProtocolError("short code device key does not match"))
    val body = """{"code":$codeJson,"dk":$dkJson}"""
    var last = fail<String>(Failure.ProtocolError("short code was rejected"))
    for ((index, endpoint) in endpoints.withIndex()) {
        val http =
            try {
                poster.postJson(shortRedeemUrl(endpoint), body)
            } catch (error: Exception) {
                ShortCodeHttp(0, error.message ?: "short code redeem failed")
            }
        if (http.status in 200..299) {
            val luvia = jsonStringField(http.body, "luvia2")
            if (luvia != null && PairingCodes.looksLikeCode(luvia)) {
                return ok(luvia)
            }
            return fail(Failure.ProtocolError("short code was rejected"))
        }
        last = fail(redeemFailure(http))
        val retry = http.status == 0 || http.status >= 500
        if (!retry || index == endpoints.lastIndex) return last
    }
    return last
}

internal data class ShortCodeHttp(
    val status: Int,
    val body: String,
)

internal fun interface ShortCodePoster {
    suspend fun postJson(url: String, body: String): ShortCodeHttp
}

internal expect fun platformShortCodePoster(): ShortCodePoster

internal fun shortRedeemUrl(endpoint: ShortCodeEndpoint): String {
    val host = endpoint.host.trim()
    val literal = if (host.contains(':')) "[$host]" else host
    return "http://$literal:${endpoint.port}/pair/redeem"
}

private fun isHostToken(value: String): Boolean {
    if (value.isEmpty()) return false
    return value.none { it.isWhitespace() || it == '/' || it == '?' || it == '#' }
}

private fun jsonString(value: String): String? {
    val out = StringBuilder(value.length + 2)
    out.append('"')
    for (ch in value) {
        when (ch) {
            '\\' -> out.append("\\\\")
            '"' -> out.append("\\\"")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            else -> {
                if (ch.code < 0x20) return null
                out.append(ch)
            }
        }
    }
    out.append('"')
    return out.toString()
}

private fun jsonStringField(body: String, key: String): String? {
    val obj =
        try {
            parseStrictJson(body) as? JsonObject
        } catch (_: StrictJsonException) {
            null
        } ?: return null
    val primitive = obj[key] as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return primitive.content
}

private fun redeemFailure(http: ShortCodeHttp): Failure {
    val error = jsonStringField(http.body, "error")
    return when (error) {
        "expired" -> Failure.ProtocolError("short code expired")
        "exhausted" -> Failure.ProtocolError("short code was invalidated after failed attempts")
        "dk_mismatch" -> Failure.ProtocolError("short code device key does not match")
        else ->
            if (http.status == 0) {
                Failure.Transport("short code redeem failed")
            } else {
                Failure.ProtocolError("short code was rejected")
            }
    }
}
