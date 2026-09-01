package tech.asahiart.luvia

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okio.ByteString.Companion.decodeBase64
import tech.asahiart.luvia.internal.StrictJsonException
import tech.asahiart.luvia.internal.parseStrictJson

public data class PairingCode(
    public val version: Int,
    public val deviceId: String,
    public val deviceKeyFingerprint: String,
    public val hostLabel: String,
    public val username: String,
    public val sshPort: Int,
    public val addresses: List<String>,
    public val hostKeyFingerprints: List<String>,
    public val role: HostRole,
)

public object PairingCodes {
    public fun decode(raw: String): Outcome<PairingCode> {
        val trimmed = raw.trim()
        if (!hasLuviaPrefix(trimmed)) {
            return fail(Failure.ProtocolError("pairing code must start with luvia1:"))
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
}

public fun pairCommandFor(deviceLabel: String, role: HostRole, authorizedKeysLine: String): String {
    val roleFlag =
        when (role) {
            HostRole.Observer -> "observer"
            HostRole.Controller -> "controller"
        }
    return "luvia-host pair --name ${posixSingleQuote(deviceLabel)} --role $roleFlag --key ${posixSingleQuote(authorizedKeysLine)}"
}

private const val PREFIX: String = "luvia1:"
private val FINGERPRINT: Regex = Regex("^SHA256:[A-Za-z0-9+/]{43}$")
private val REQUIRED_KEYS: Set<String> =
    setOf("v", "id", "dk", "name", "user", "port", "addrs", "hk", "role")

private fun hasLuviaPrefix(value: String): Boolean =
    value.length >= PREFIX.length && value.regionMatches(0, PREFIX, 0, PREFIX.length, ignoreCase = true)

private fun decodeObject(obj: JsonObject): Outcome<PairingCode> {
    val extra = obj.keys - REQUIRED_KEYS
    if (extra.isNotEmpty()) {
        return fail(Failure.ProtocolError("pairing code contains unknown field '${extra.first()}'"))
    }
    for (key in REQUIRED_KEYS) {
        if (key !in obj) {
            return fail(Failure.ProtocolError("pairing code is missing field '$key'"))
        }
    }

    val version = intField(obj, "v") ?: return fail(Failure.ProtocolError("pairing code version must be 1"))
    if (version != 1) {
        return fail(Failure.ProtocolError("pairing code version must be 1"))
    }
    val deviceId = stringField(obj, "id") ?: return typeError("id")
    val deviceKeyFingerprint = stringField(obj, "dk") ?: return typeError("dk")
    val hostLabel = stringField(obj, "name") ?: return typeError("name")
    val username = stringField(obj, "user") ?: return typeError("user")
    val port = intField(obj, "port")
        ?: return fail(Failure.ProtocolError("pairing code port is out of range"))
    val addresses = stringList(obj, "addrs") ?: return typeError("addrs")
    val hostKeyFingerprints = stringList(obj, "hk") ?: return typeError("hk")
    val roleRaw = stringField(obj, "role") ?: return typeError("role")

    if (username.isBlank()) {
        return fail(Failure.ProtocolError("pairing code username is blank"))
    }
    if (port !in 1..65535) {
        return fail(Failure.ProtocolError("pairing code port is out of range"))
    }
    if (addresses.isEmpty()) {
        return fail(Failure.ProtocolError("pairing code has no addresses"))
    }
    if (hostKeyFingerprints.isEmpty()) {
        return fail(Failure.ProtocolError("pairing code has no host key fingerprints"))
    }
    if (!isOpensshSha256Fingerprint(deviceKeyFingerprint)) {
        return fail(Failure.ProtocolError("pairing code has an invalid host key fingerprint"))
    }
    for (fingerprint in hostKeyFingerprints) {
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
            version = version,
            deviceId = deviceId,
            deviceKeyFingerprint = deviceKeyFingerprint,
            hostLabel = hostLabel,
            username = username,
            sshPort = port,
            addresses = addresses,
            hostKeyFingerprints = hostKeyFingerprints,
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
