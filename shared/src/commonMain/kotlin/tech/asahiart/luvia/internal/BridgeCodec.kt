package tech.asahiart.luvia.internal

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.asahiart.luvia.BridgeTransport
import tech.asahiart.luvia.DiscoveredSession

internal const val BRIDGE_VERSION: Int = 1
internal val SESSION_NAME = Regex("^[A-Za-z0-9._-]{1,64}$")
private val FORBIDDEN_PATH_KEYS = setOf("path", "socket", "socket_path", "endpoint", "unix_socket_path")

internal data class BridgeDiscoverResult(
    val sessions: List<DiscoveredSession>,
)

internal fun encodeDiscoverRequest(): String =
    compactJson.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("version", BRIDGE_VERSION)
            put("operation", "discover")
        },
    )

internal fun encodeOpenRequest(session: String): String {
    if (!SESSION_NAME.matches(session)) {
        throw CodecException(CodecException.Kind.Schema, "invalid session name")
    }
    return compactJson.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("version", BRIDGE_VERSION)
            put("operation", "open")
            put("session", session)
        },
    )
}

internal fun decodeDiscoverResponse(text: String): BridgeDiscoverResult {
    val obj = parseObject(text)
    rejectPathKeys(obj)
    if ("error" in obj) {
        throw bridgeError(obj)
    }
    rejectUnknown(obj, setOf("version", "sessions"))
    requireKeys(obj, setOf("version", "sessions"))
    requireVersion1(obj)
    val sessionsEl = obj["sessions"] as? kotlinx.serialization.json.JsonArray
        ?: throw CodecException(CodecException.Kind.Schema, "sessions must be an array")
    val sessions =
        sessionsEl.map { el ->
            val session = el as? JsonObject
                ?: throw CodecException(CodecException.Kind.Schema, "session must be an object")
            rejectPathKeys(session)
            rejectUnknown(session, setOf("name", "default", "running", "transport"))
            requireKeys(session, setOf("name", "default", "running", "transport"))
            val name = session.string("name")
            if (!SESSION_NAME.matches(name)) {
                throw CodecException(CodecException.Kind.Schema, "invalid session name")
            }
            DiscoveredSession(
                name = name,
                isDefault = session.boolean("default"),
                running = session.boolean("running"),
                transport = parseTransport(session.string("transport")),
            )
        }
    return BridgeDiscoverResult(sessions)
}

internal fun decodeOpenResponse(text: String, expectedSession: String) {
    val obj = parseObject(text)
    rejectPathKeys(obj)
    if ("error" in obj) {
        throw bridgeError(obj)
    }
    rejectUnknown(obj, setOf("version", "status", "session"))
    requireKeys(obj, setOf("version", "status", "session"))
    requireVersion1(obj)
    val status = obj.string("status")
    if (status != "ready") {
        throw CodecException(CodecException.Kind.Schema, "bridge not ready")
    }
    val session = obj.string("session")
    if (session != expectedSession) {
        throw CodecException(CodecException.Kind.Schema, "opened session mismatch")
    }
}

private fun requireVersion1(obj: JsonObject) {
    val version = obj.strictLong("version")
    if (version != BRIDGE_VERSION.toLong()) {
        throw CodecException(CodecException.Kind.Schema, "unsupported bridge version $version")
    }
}

private fun parseTransport(value: String): BridgeTransport =
    when (value) {
        "unix_socket" -> BridgeTransport.UnixSocket
        "windows_named_pipe", "named_pipe" -> BridgeTransport.NamedPipe
        else -> throw CodecException(CodecException.Kind.Schema, "unknown bridge transport")
    }

private fun rejectPathKeys(obj: JsonObject) {
    val hit = obj.keys.firstOrNull { it in FORBIDDEN_PATH_KEYS || it.contains("path") }
    if (hit != null) {
        throw CodecException(CodecException.Kind.UnknownField, "remote socket path is not allowed")
    }
}

private fun bridgeError(obj: JsonObject): CodecException {
    rejectUnknown(obj, setOf("version", "error"))
    val error = obj.objectField("error")
    rejectUnknown(error, setOf("code", "message"))
    val code = error.string("code")
    val message = error.string("message")
    return CodecException(CodecException.Kind.Schema, "$code: $message")
}
