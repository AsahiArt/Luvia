package tech.asahiart.luvia.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal val compactJson: Json =
    Json {
        prettyPrint = false
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = false
        isLenient = false
        allowSpecialFloatingPointValues = false
        useAlternativeNames = false
    }

internal val REQUEST_ID = Regex("^[A-Za-z0-9._:-]{1,128}$")
internal val AUTH_TOKEN = Regex("^[!-~]{1,256}$")
internal val OPAQUE_ID = Regex("^[0-9a-f]{32}$")
internal val PANE_ID = Regex("^[1-9][0-9]{0,9}$")

internal class CodecException(val kind: Kind, override val message: String) : Exception(message) {
    enum class Kind {
        DuplicateKey,
        UnknownField,
        InvalidId,
        ResultErrorExclusivity,
        MismatchedId,
        Syntax,
        Schema,
    }
}

internal data class UhpRequest(
    val id: String,
    val method: String,
    val params: JsonObject = JsonObject(emptyMap()),
    val auth: String? = null,
)

internal data class UhpError(
    val code: String,
    val message: String,
    val expected: Long? = null,
    val actual: Long? = null,
    val sequence: Long? = null,
    val retryable: Boolean? = null,
)

internal sealed class UhpResponse {
    abstract val id: String

    data class Success(override val id: String, val result: JsonElement) : UhpResponse()

    data class Failure(override val id: String, val error: UhpError) : UhpResponse()
}

internal data class UhpEvent(
    val name: String,
    val sequence: Long,
    val data: JsonObject,
)

internal fun encodeUhpRequest(request: UhpRequest): String {
    if (!REQUEST_ID.matches(request.id)) {
        throw CodecException(CodecException.Kind.InvalidId, "invalid request id")
    }
    if (request.method.isEmpty()) {
        throw CodecException(CodecException.Kind.Schema, "missing method")
    }
    request.auth?.let { token ->
        if (!AUTH_TOKEN.matches(token)) {
            throw CodecException(CodecException.Kind.Schema, "invalid auth token")
        }
    }
    val obj =
        buildJsonObject {
            put("id", request.id)
            request.auth?.let { put("auth", it) }
            put("method", request.method)
            put("params", request.params)
        }
    return compactJson.encodeToString(JsonObject.serializer(), obj)
}

internal fun decodeUhpRequest(text: String): UhpRequest {
    val obj = parseObject(text)
    rejectUnknown(obj, setOf("id", "method", "params", "auth"))
    requireKeys(obj, setOf("id", "method", "params"))
    val id = requireId(obj)
    val method = obj.string("method")
    val params = obj.objectField("params")
    val auth = obj.optionalString("auth")
    if (auth != null && !AUTH_TOKEN.matches(auth)) {
        throw CodecException(CodecException.Kind.Schema, "invalid auth token")
    }
    return UhpRequest(id = id, method = method, params = params, auth = auth)
}

internal fun decodeUhpResponse(text: String, expectedId: String? = null): UhpResponse {
    val obj = parseObject(text)
    rejectUnknown(obj, setOf("id", "result", "error"))
    val id = requireId(obj)
    if (expectedId != null && id != expectedId) {
        throw CodecException(CodecException.Kind.MismatchedId, "response id $id does not match $expectedId")
    }
    val hasResult = "result" in obj
    val hasError = "error" in obj
    if (hasResult == hasError) {
        throw CodecException(
            CodecException.Kind.ResultErrorExclusivity,
            "response must contain exactly one of result or error",
        )
    }
    return if (hasResult) {
        UhpResponse.Success(id, obj.getValue("result"))
    } else {
        val error = obj.objectField("error")
        UhpResponse.Failure(
            id,
            UhpError(
                code = error.string("code"),
                message = error.string("message"),
                expected = error.optionalStrictLong("expected"),
                actual = error.optionalStrictLong("actual"),
                sequence = error.optionalStrictLong("sequence"),
                retryable = error.optionalBoolean("retryable"),
            ),
        )
    }
}

internal fun decodeUhpEvent(text: String): UhpEvent {
    val obj = parseObject(text)
    rejectUnknown(obj, setOf("event", "sequence", "data"))
    requireKeys(obj, setOf("event", "sequence", "data"))
    val name = obj.string("event")
    if (name.isEmpty() || name.length > 128) {
        throw CodecException(CodecException.Kind.Schema, "invalid event name")
    }
    val sequence = obj.strictLong("sequence")
    if (sequence < 1) {
        throw CodecException(CodecException.Kind.Schema, "event sequence must be >= 1")
    }
    val data = obj.objectField("data")
    return UhpEvent(name = name, sequence = sequence, data = data)
}

internal fun parseObject(text: String): JsonObject {
    val element =
        try {
            parseStrictJson(text)
        } catch (e: StrictJsonException) {
            val kind =
                if (e.message?.startsWith("duplicate object key") == true) {
                    CodecException.Kind.DuplicateKey
                } else {
                    CodecException.Kind.Syntax
                }
            throw CodecException(kind, e.message ?: "invalid JSON")
        }
    return element as? JsonObject
        ?: throw CodecException(CodecException.Kind.Syntax, "expected JSON object")
}

internal fun rejectUnknown(obj: JsonObject, allowed: Set<String>) {
    val extra = obj.keys - allowed
    if (extra.isNotEmpty()) {
        throw CodecException(CodecException.Kind.UnknownField, "unknown field: ${extra.first()}")
    }
}

internal fun requireKeys(obj: JsonObject, required: Set<String>) {
    if (!obj.keys.containsAll(required)) {
        throw CodecException(CodecException.Kind.Schema, "missing required field")
    }
}

internal fun requireId(obj: JsonObject): String {
    val id = obj.string("id")
    if (!REQUEST_ID.matches(id)) {
        throw CodecException(CodecException.Kind.InvalidId, "invalid id")
    }
    return id
}

internal fun JsonObject.string(key: String): String {
    val value = this[key] as? JsonPrimitive ?: throw CodecException(CodecException.Kind.Schema, "expected string $key")
    if (!value.isString) throw CodecException(CodecException.Kind.Schema, "expected string $key")
    return value.content
}

internal fun JsonObject.optionalString(key: String): String? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive ?: throw CodecException(CodecException.Kind.Schema, "expected string $key")
    if (!primitive.isString) throw CodecException(CodecException.Kind.Schema, "expected string $key")
    return primitive.content
}

internal fun JsonObject.objectField(key: String): JsonObject {
    val value = this[key] ?: throw CodecException(CodecException.Kind.Schema, "missing $key")
    return value as? JsonObject ?: throw CodecException(CodecException.Kind.Schema, "expected object $key")
}

internal fun JsonObject.optionalObject(key: String): JsonObject? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    return value as? JsonObject ?: throw CodecException(CodecException.Kind.Schema, "expected object $key")
}

internal fun JsonObject.strictLong(key: String): Long {
    val value = this[key] as? JsonPrimitive ?: throw CodecException(CodecException.Kind.Schema, "expected integer $key")
    if (value.isString) throw CodecException(CodecException.Kind.Schema, "expected integer $key")
    val raw = value.content
    if (raw.contains('.') || raw.contains('e') || raw.contains('E')) {
        throw CodecException(CodecException.Kind.Schema, "expected integer $key")
    }
    return raw.toLongOrNull() ?: throw CodecException(CodecException.Kind.Schema, "invalid integer $key")
}

internal fun JsonObject.optionalStrictLong(key: String): Long? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    return strictLong(key)
}

internal fun JsonObject.boolean(key: String): Boolean {
    val value = this[key] as? JsonPrimitive ?: throw CodecException(CodecException.Kind.Schema, "expected boolean $key")
    if (value.isString) throw CodecException(CodecException.Kind.Schema, "expected boolean $key")
    return value.content.toBooleanStrictOrNull()
        ?: throw CodecException(CodecException.Kind.Schema, "expected boolean $key")
}

internal fun JsonObject.stringList(key: String): List<String> {
    val value = this[key] as? kotlinx.serialization.json.JsonArray
        ?: throw CodecException(CodecException.Kind.Schema, "expected array $key")
    return value.map { el ->
        val p = el as? JsonPrimitive ?: throw CodecException(CodecException.Kind.Schema, "expected string in $key")
        if (!p.isString) throw CodecException(CodecException.Kind.Schema, "expected string in $key")
        p.content
    }
}

internal fun JsonElement.asObject(): JsonObject =
    this as? JsonObject ?: throw CodecException(CodecException.Kind.Schema, "expected object result")
internal fun JsonObject.optionalBoolean(key: String): Boolean? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return primitive.content.toBooleanStrictOrNull()
}

internal fun JsonObject.optionalDouble(key: String): Double? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return primitive.content.toDoubleOrNull()
}

internal fun JsonObject.optionalWireString(key: String): String? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive ?: return null
    return primitive.content
}

internal fun JsonObject.optionalStringList(key: String): List<String> {
    val value = this[key] ?: return emptyList()
    if (value is JsonNull) return emptyList()
    val array = value as? kotlinx.serialization.json.JsonArray ?: return emptyList()
    return array.mapNotNull { el ->
        val primitive = el as? JsonPrimitive ?: return@mapNotNull null
        if (!primitive.isString) return@mapNotNull null
        primitive.content
    }
}

internal fun JsonObject.optionalObjectList(key: String): List<JsonObject> {
    val value = this[key] ?: return emptyList()
    if (value is JsonNull) return emptyList()
    val array = value as? kotlinx.serialization.json.JsonArray ?: return emptyList()
    return array.mapNotNull { it as? JsonObject }
}

internal fun JsonObject.booleanOrFalse(key: String): Boolean = optionalBoolean(key) ?: false

internal fun UhpError.toFailure(): tech.asahiart.luvia.Failure =
    when (code) {
        "revision_conflict" ->
            tech.asahiart.luvia.Failure.RevisionConflict(
                expected = expected ?: 0L,
                actual = actual ?: 0L,
                message = message,
            )
        "forbidden" -> tech.asahiart.luvia.Failure.Forbidden(message)
        "not_found" -> tech.asahiart.luvia.Failure.NotFound(message)
        "invalid_params" -> tech.asahiart.luvia.Failure.InvalidParams(message, sequence)
        "invalid_request" -> tech.asahiart.luvia.Failure.InvalidRequest(message)
        "stale_server" -> tech.asahiart.luvia.Failure.StaleServer(message)
        "stale_route" -> tech.asahiart.luvia.Failure.StaleRoute(message)
        "terminal_gone" -> tech.asahiart.luvia.Failure.TerminalGone(message)
        "resync_required" -> tech.asahiart.luvia.Failure.ResyncRequired(message, sequence)
        "control_conflict" -> tech.asahiart.luvia.Failure.ControlConflict(message)
        "frame_too_large" -> tech.asahiart.luvia.Failure.FrameTooLarge(message)
        "server_busy" -> tech.asahiart.luvia.Failure.ServerBusy(message, retryable ?: true)
        else -> tech.asahiart.luvia.Failure.Remote(code, message)
    }
