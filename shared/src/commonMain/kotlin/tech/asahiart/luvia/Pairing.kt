package tech.asahiart.luvia

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okio.ByteString.Companion.decodeBase64
import tech.asahiart.luvia.internal.StrictJsonException
import tech.asahiart.luvia.internal.parseStrictJson

public data class PairingCode(
    public val name: String,
    public val tailnetFqdn: String?,
    public val user: String,
    public val port: Int,
    public val bridgePath: String,
    public val hostKeys: List<String>,
    public val hints: List<String>,
    public val hostId: String,
    public val deviceId: String,
    public val deviceKey: String,
    public val role: HostRole,
)

public object PairingCodes {
    public fun decode(raw: String): Outcome<PairingCode> {
        val trimmed = raw.trim()
        if (hasPrefix(trimmed, LEGACY_PREFIX)) {
            return fail(Failure.ProtocolError("luvia1: pairing codes are not accepted; use luvia2:"))
        }
        if (!hasPrefix(trimmed, PREFIX)) {
            return fail(Failure.ProtocolError("pairing code must start with luvia2:"))
        }
        val body = trimmed.substring(PREFIX.length)
        val jsonBytes = decodeBase64UrlUnpadded(body)
            ?: return fail(Failure.ProtocolError("pairing code body is not valid base64url"))
        val jsonText =
            try {
                jsonBytes.decodeToString(throwOnInvalidSequence = true)
            } catch (_: Exception) {
                return fail(Failure.ProtocolError("pairing code is not valid JSON"))
            }
        val element =
            try {
                parseStrictJson(jsonText)
            } catch (_: StrictJsonException) {
                return fail(Failure.ProtocolError("pairing code is not valid JSON"))
            }
        val obj = element as? JsonObject
            ?: return fail(Failure.ProtocolError("pairing code is not valid JSON"))
        return decodeObject(obj)
    }

    public fun looksLikeCode(raw: String): Boolean {
        val trimmed = raw.trim()
        return hasPrefix(trimmed, PREFIX)
    }
}

/**
 * Grouped prefix of an OpenSSH SHA256 fingerprint for the pairing row.
 * Six bytes, three groups: `E3B0 C442 98FC`. Not a second fingerprint.
 */
public fun shortFingerprint(raw: String): String {
    val body = raw.trim().substringAfter(':')
    val bytes = decodeBase64UrlUnpadded(body.replace('+', '-').replace('/', '_').trimEnd('='))
    if (bytes != null && bytes.size >= 6) {
        val hex = buildString(12) {
            for (index in 0 until 6) {
                append((bytes[index].toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
            }
        }
        return hex.chunked(4).joinToString(" ")
    }
    val compact = body.filter { it.isLetterOrDigit() }.uppercase()
    if (compact.isEmpty()) return raw.trim()
    return compact.take(12).chunked(4).joinToString(" ")
}

public fun pairCommandFor(deviceLabel: String, role: HostRole, authorizedKeysLine: String): String {
    val roleFlag =
        when (role) {
            HostRole.Observer -> "observer"
            HostRole.Controller -> "controller"
        }
    return "luvia-host pair --name ${posixSingleQuote(deviceLabel)} --role $roleFlag --key ${posixSingleQuote(authorizedKeysLine)} --short"
}

private const val PREFIX: String = "luvia2:"
private const val LEGACY_PREFIX: String = "luvia1:"
private val FINGERPRINT: Regex = Regex("^SHA256:[A-Za-z0-9+/]{43}$")
private val HOST_ID: Regex = Regex("^[0-9a-f]{32}$")
private val REQUIRED_KEYS: Set<String> =
    setOf("name", "user", "port", "bridge", "hk", "hints", "host_id", "device_id", "device_key", "role")
private val OPTIONAL_KEYS: Set<String> = setOf("fqdn")

private fun hasPrefix(value: String, prefix: String): Boolean =
    value.length >= prefix.length && value.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)

private fun decodeObject(obj: JsonObject): Outcome<PairingCode> {
    val extra = obj.keys - REQUIRED_KEYS - OPTIONAL_KEYS
    if (extra.isNotEmpty()) {
        return fail(Failure.ProtocolError("pairing code contains unknown field '${extra.first()}'"))
    }
    for (key in REQUIRED_KEYS) {
        if (key !in obj) {
            return fail(Failure.ProtocolError("pairing code is missing field '$key'"))
        }
    }

    val name = stringField(obj, "name") ?: return typeError("name")
    val user = stringField(obj, "user") ?: return typeError("user")
    val port = intField(obj, "port")
        ?: return fail(Failure.ProtocolError("pairing code port is out of range"))
    val bridgePath = stringField(obj, "bridge") ?: return typeError("bridge")
    val hostKeys = stringList(obj, "hk") ?: return typeError("hk")
    val hints = stringList(obj, "hints") ?: return typeError("hints")
    val hostId = stringField(obj, "host_id") ?: return typeError("host_id")
    val deviceId = stringField(obj, "device_id") ?: return typeError("device_id")
    val deviceKey = stringField(obj, "device_key") ?: return typeError("device_key")
    val roleRaw = stringField(obj, "role") ?: return typeError("role")
    val tailnetFqdn =
        if ("fqdn" !in obj || obj["fqdn"] == null || obj["fqdn"] is JsonNull) {
            null
        } else {
            stringField(obj, "fqdn") ?: return typeError("fqdn")
        }

    if (name.isBlank()) {
        return fail(Failure.ProtocolError("pairing code name is blank"))
    }
    if (user.isBlank()) {
        return fail(Failure.ProtocolError("pairing code username is blank"))
    }
    if (port !in 1..65535) {
        return fail(Failure.ProtocolError("pairing code port is out of range"))
    }
    if (!bridgePath.startsWith("/") || bridgePath.contains('\n') || bridgePath.contains('\u0000')) {
        return fail(Failure.ProtocolError("pairing code bridge path must be absolute"))
    }
    if (hints.isEmpty() && tailnetFqdn.isNullOrBlank()) {
        return fail(Failure.ProtocolError("pairing code has no hints or tailnet name"))
    }
    if (tailnetFqdn != null && tailnetFqdn.isBlank()) {
        return fail(Failure.ProtocolError("pairing code tailnet name is blank"))
    }
    if (hostKeys.isEmpty()) {
        return fail(Failure.ProtocolError("pairing code has no host key fingerprints"))
    }
    if (!HOST_ID.matches(hostId)) {
        return fail(Failure.ProtocolError("pairing code host_id must be 32 lowercase hex characters"))
    }
    if (!HOST_ID.matches(deviceId)) {
        return fail(Failure.ProtocolError("pairing code device_id must be 32 lowercase hex characters"))
    }
    if (!isOpensshSha256Fingerprint(deviceKey)) {
        return fail(Failure.ProtocolError("pairing code has an invalid host key fingerprint"))
    }
    for (fingerprint in hostKeys) {
        if (!isOpensshSha256Fingerprint(fingerprint)) {
            return fail(Failure.ProtocolError("pairing code has an invalid host key fingerprint"))
        }
    }
    val role =
        when (roleRaw) {
            "observer" -> HostRole.Observer
            "controller" -> HostRole.Controller
            else -> return fail(Failure.ProtocolError("pairing code role must be observer or controller"))
        }
    return ok(
        PairingCode(
            name = name,
            tailnetFqdn = tailnetFqdn?.takeIf { it.isNotEmpty() },
            user = user,
            port = port,
            bridgePath = bridgePath,
            hostKeys = hostKeys,
            hints = hints,
            hostId = hostId,
            deviceId = deviceId,
            deviceKey = deviceKey,
            role = role,
        ),
    )
}

private fun typeError(field: String): Outcome<PairingCode> =
    fail(Failure.ProtocolError("pairing code field '$field' has the wrong type"))

private fun stringField(obj: JsonObject, key: String): String? {
    val primitive = obj[key] as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return primitive.content
}

private fun intField(obj: JsonObject, key: String): Int? {
    val primitive = obj[key] as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return primitive.intOrNull
}

private fun stringList(obj: JsonObject, key: String): List<String>? {
    val array = obj[key] as? JsonArray ?: return null
    val out = ArrayList<String>(array.size)
    for (item in array) {
        val primitive = item as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        out.add(primitive.content)
    }
    return out
}

private fun isOpensshSha256Fingerprint(value: String): Boolean {
    if (!FINGERPRINT.matches(value)) return false
    val padded = value.substring(7) + "="
    val decoded = padded.decodeBase64() ?: return false
    return decoded.size == 32
}

private fun decodeBase64UrlUnpadded(body: String): ByteArray? {
    if (body.isEmpty()) return null
    for (ch in body) {
        val ok =
            ch in 'A'..'Z' ||
                ch in 'a'..'z' ||
                ch in '0'..'9' ||
                ch == '-' ||
                ch == '_'
        if (!ok) return null
    }
    if (body.length % 4 == 1) return null
    val standard = body.replace('-', '+').replace('_', '/')
    val padded =
        when (standard.length % 4) {
            0 -> standard
            2 -> "$standard=="
            3 -> "$standard="
            else -> return null
        }
    return padded.decodeBase64()?.toByteArray()
}

private fun posixSingleQuote(value: String): String =
    "'" + value.replace("'", "'\\''") + "'"
