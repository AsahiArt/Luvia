package tech.asahiart.luvia.internal

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.asahiart.luvia.Luvia
import tech.asahiart.luvia.TerminalCaptureMode
import tech.asahiart.luvia.TerminalFrame
import tech.asahiart.luvia.TerminalIdentity
import tech.asahiart.luvia.TerminalKey

internal data class ControlFrame(
    val id: String,
    val action: Action,
    val params: JsonObject,
) {
    enum class Action {
        TypeLiteral,
        SubmitText,
        SendKey,
    }
}

internal fun encodeControlFrame(frame: ControlFrame): String {
    if (!REQUEST_ID.matches(frame.id)) {
        throw CodecException(CodecException.Kind.InvalidId, "invalid control id")
    }
    val action =
        when (frame.action) {
            ControlFrame.Action.TypeLiteral -> "type_literal"
            ControlFrame.Action.SubmitText -> "submit_text"
            ControlFrame.Action.SendKey -> "send_key"
        }
    val obj =
        buildJsonObject {
            put("id", frame.id)
            put("action", action)
            put("params", frame.params)
        }
    return compactJson.encodeToString(JsonObject.serializer(), obj)
}

internal fun decodeControlFrame(text: String): ControlFrame {
    val obj = parseObject(text)
    rejectUnknown(obj, setOf("id", "action", "params"))
    requireKeys(obj, setOf("id", "action", "params"))
    val id = requireId(obj)
    val actionName = obj.string("action")
    val params = obj.objectField("params")
    val action =
        when (actionName) {
            "type_literal" -> {
                rejectUnknown(params, setOf("text"))
                requireKeys(params, setOf("text"))
                val textValue = params.string("text")
                if (textValue.isEmpty() || textValue.length > 262_144) {
                    throw CodecException(CodecException.Kind.Schema, "invalid type_literal text")
                }
                ControlFrame.Action.TypeLiteral
            }
            "submit_text" -> {
                rejectUnknown(params, setOf("text"))
                requireKeys(params, setOf("text"))
                val textValue = params.string("text")
                if (textValue.isEmpty() || textValue.length > 262_144) {
                    throw CodecException(CodecException.Kind.Schema, "invalid submit_text text")
                }
                ControlFrame.Action.SubmitText
            }
            "send_key" -> {
                rejectUnknown(params, setOf("key"))
                requireKeys(params, setOf("key"))
                parseTerminalKey(params.string("key"))
                ControlFrame.Action.SendKey
            }
            else -> throw CodecException(CodecException.Kind.Schema, "unknown control action")
        }
    return ControlFrame(id = id, action = action, params = params)
}

internal fun decodeTerminalFrameEvent(event: UhpEvent): TerminalFrame {
    if (event.name != "terminal.frame") {
        throw CodecException(CodecException.Kind.Schema, "not a terminal.frame")
    }
    val data = event.data
    val allowed =
        setOf(
            "server_generation",
            "terminal_id",
            "pane_id",
            "content_revision",
            "mode",
            "ansi",
            "text",
            "lines",
            "bytes",
            "truncated",
        )
    rejectUnknown(data, allowed)
    requireKeys(data, allowed)
    val generation = data.string("server_generation")
    val terminalId = data.string("terminal_id")
    val paneId = data.string("pane_id")
    if (!OPAQUE_ID.matches(generation) || !OPAQUE_ID.matches(terminalId) || !PANE_ID.matches(paneId)) {
        throw CodecException(CodecException.Kind.Schema, "invalid terminal identity")
    }
    val text = data.string("text")
    val utf8Bytes = text.encodeToByteArray().size
    if (utf8Bytes > Luvia.maxTerminalUtf8Bytes) {
        throw CodecException(CodecException.Kind.Schema, "terminal text exceeds 65536 UTF-8 bytes")
    }
    val declaredBytes = data.strictLong("bytes")
    if (declaredBytes != utf8Bytes.toLong()) {
        throw CodecException(CodecException.Kind.Schema, "terminal byte count mismatch")
    }
    val declaredLines = data.strictLong("lines")
    if (declaredLines != lineCount(text).toLong()) {
        throw CodecException(CodecException.Kind.Schema, "terminal line count mismatch")
    }
    val mode =
        when (val raw = data.string("mode")) {
            "visible" -> TerminalCaptureMode.Visible
            "recent_unwrapped" -> TerminalCaptureMode.RecentUnwrapped
            else -> throw CodecException(CodecException.Kind.Schema, "unknown capture mode $raw")
        }
    return TerminalFrame(
        identity =
            TerminalIdentity(
                serverGeneration = generation,
                terminalId = terminalId,
                paneId = paneId,
            ),
        contentRevision = data.strictLong("content_revision"),
        mode = mode,
        ansi = data.boolean("ansi"),
        text = text,
        lines = declaredLines.toInt(),
        bytes = utf8Bytes,
        truncated = data.boolean("truncated"),
    )
}

internal fun parseTerminalKey(wire: String): TerminalKey =
    when (wire) {
        "enter" -> TerminalKey.Enter
        "escape" -> TerminalKey.Escape
        "tab" -> TerminalKey.Tab
        "backtab" -> TerminalKey.Backtab
        "up" -> TerminalKey.Up
        "down" -> TerminalKey.Down
        "left" -> TerminalKey.Left
        "right" -> TerminalKey.Right
        "home" -> TerminalKey.Home
        "end" -> TerminalKey.End
        "backspace" -> TerminalKey.Backspace
        "delete" -> TerminalKey.Delete
        "pageup" -> TerminalKey.PageUp
        "pagedown" -> TerminalKey.PageDown
        "ctrl-c" -> TerminalKey.CtrlC
        "ctrl-d" -> TerminalKey.CtrlD
        "ctrl-u" -> TerminalKey.CtrlU
        "ctrl-w" -> TerminalKey.CtrlW
        "space" -> TerminalKey.Space
        "digit-0" -> TerminalKey.Digit0
        "digit-1" -> TerminalKey.Digit1
        "digit-2" -> TerminalKey.Digit2
        "digit-3" -> TerminalKey.Digit3
        "digit-4" -> TerminalKey.Digit4
        "digit-5" -> TerminalKey.Digit5
        "digit-6" -> TerminalKey.Digit6
        "digit-7" -> TerminalKey.Digit7
        "digit-8" -> TerminalKey.Digit8
        "digit-9" -> TerminalKey.Digit9
        else -> throw CodecException(CodecException.Kind.Schema, "unknown key $wire")
    }

internal fun TerminalKey.wireName(): String =
    when (this) {
        TerminalKey.Enter -> "enter"
        TerminalKey.Escape -> "escape"
        TerminalKey.Tab -> "tab"
        TerminalKey.Backtab -> "backtab"
        TerminalKey.Up -> "up"
        TerminalKey.Down -> "down"
        TerminalKey.Left -> "left"
        TerminalKey.Right -> "right"
        TerminalKey.Home -> "home"
        TerminalKey.End -> "end"
        TerminalKey.Backspace -> "backspace"
        TerminalKey.Delete -> "delete"
        TerminalKey.PageUp -> "pageup"
        TerminalKey.PageDown -> "pagedown"
        TerminalKey.CtrlC -> "ctrl-c"
        TerminalKey.CtrlD -> "ctrl-d"
        TerminalKey.CtrlU -> "ctrl-u"
        TerminalKey.CtrlW -> "ctrl-w"
        TerminalKey.Space -> "space"
        TerminalKey.Digit0 -> "digit-0"
        TerminalKey.Digit1 -> "digit-1"
        TerminalKey.Digit2 -> "digit-2"
        TerminalKey.Digit3 -> "digit-3"
        TerminalKey.Digit4 -> "digit-4"
        TerminalKey.Digit5 -> "digit-5"
        TerminalKey.Digit6 -> "digit-6"
        TerminalKey.Digit7 -> "digit-7"
        TerminalKey.Digit8 -> "digit-8"
        TerminalKey.Digit9 -> "digit-9"
    }

internal fun locatorParams(identity: TerminalIdentity): JsonObject =
    buildJsonObject {
        put("server_generation", identity.serverGeneration)
        put("terminal_id", identity.terminalId)
        put("pane_id", identity.paneId)
    }

internal fun lineCount(text: String): Int {
    if (text.isEmpty()) return 0
    var lines = 1
    for (ch in text) {
        if (ch == '\n') lines++
    }
    return lines
}
