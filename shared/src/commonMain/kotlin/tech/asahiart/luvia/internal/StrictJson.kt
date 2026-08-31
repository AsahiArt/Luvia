package tech.asahiart.luvia.internal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class StrictJsonException(message: String) : Exception(message)

internal fun parseStrictJson(text: String): JsonElement {
    val parser = StrictJsonParser(text)
    val value = parser.parseValue()
    parser.skipWs()
    if (!parser.eof) {
        throw StrictJsonException("trailing data after JSON value")
    }
    return value
}

private class StrictJsonParser(private val text: String) {
    private var i: Int = 0

    val eof: Boolean
        get() = i >= text.length

    fun skipWs() {
        while (i < text.length) {
            when (text[i]) {
                ' ', '\t', '\n', '\r' -> i++
                else -> return
            }
        }
    }

    fun parseValue(): JsonElement {
        skipWs()
        if (eof) throw StrictJsonException("unexpected end of JSON")
        return when (val c = text[i]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonPrimitive(parseString())
            't' -> parseKeyword("true", JsonPrimitive(true))
            'f' -> parseKeyword("false", JsonPrimitive(false))
            'n' -> parseKeyword("null", JsonNull)
            '-', in '0'..'9' -> parseNumber()
            else -> throw StrictJsonException("unexpected character '$c'")
        }
    }

    private fun parseObject(): JsonObject {
        expect('{')
        skipWs()
        if (peek() == '}') {
            i++
            return JsonObject(emptyMap())
        }
        val map = LinkedHashMap<String, JsonElement>()
        while (true) {
            skipWs()
            if (peek() != '"') throw StrictJsonException("object key must be a string")
            val key = parseString()
            if (map.containsKey(key)) {
                throw StrictJsonException("duplicate object key: $key")
            }
            skipWs()
            expect(':')
            map[key] = parseValue()
            skipWs()
            when (peek()) {
                ',' -> i++
                '}' -> {
                    i++
                    return JsonObject(map)
                }
                else -> throw StrictJsonException("expected comma or end of object")
            }
        }
    }

    private fun parseArray(): JsonArray {
        expect('[')
        skipWs()
        if (peek() == ']') {
            i++
            return JsonArray(emptyList())
        }
        val items = ArrayList<JsonElement>()
        while (true) {
            items += parseValue()
            skipWs()
            when (peek()) {
                ',' -> i++
                ']' -> {
                    i++
                    return JsonArray(items)
                }
                else -> throw StrictJsonException("expected comma or end of array")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val out = StringBuilder()
        while (!eof) {
            when (val c = text[i++]) {
                '"' -> return out.toString()
                '\\' -> out.append(parseEscape())
                in '\u0000'..'\u001F' -> throw StrictJsonException("unescaped control character in string")
                else -> out.append(c)
            }
        }
        throw StrictJsonException("unterminated string")
    }

    private fun parseEscape(): Char {
        if (eof) throw StrictJsonException("unterminated escape")
        return when (val c = text[i++]) {
            '"', '\\', '/' -> c
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                if (i + 4 > text.length) throw StrictJsonException("truncated unicode escape")
                val hex = text.substring(i, i + 4)
                i += 4
                hex.toIntOrNull(16)?.toChar() ?: throw StrictJsonException("invalid unicode escape")
            }
            else -> throw StrictJsonException("invalid escape")
        }
    }

    private fun parseNumber(): JsonPrimitive {
        val start = i
        if (peek() == '-') i++
        if (eof) throw StrictJsonException("invalid number")
        if (peek() == '0') {
            i++
            if (!eof && text[i] in '0'..'9') throw StrictJsonException("leading zero")
        } else if (peek() in '1'..'9') {
            i++
            while (!eof && text[i] in '0'..'9') i++
        } else {
            throw StrictJsonException("invalid number")
        }
        var fractional = false
        if (!eof && peek() == '.') {
            fractional = true
            i++
            if (eof || text[i] !in '0'..'9') throw StrictJsonException("invalid fraction")
            while (!eof && text[i] in '0'..'9') i++
        }
        if (!eof && (peek() == 'e' || peek() == 'E')) {
            fractional = true
            i++
            if (!eof && (peek() == '+' || peek() == '-')) i++
            if (eof || text[i] !in '0'..'9') throw StrictJsonException("invalid exponent")
            while (!eof && text[i] in '0'..'9') i++
        }
        val raw = text.substring(start, i)
        return if (fractional) {
            JsonPrimitive(raw.toDouble())
        } else {
            JsonPrimitive(raw.toLong())
        }
    }

    private fun parseKeyword(keyword: String, value: JsonElement): JsonElement {
        if (i + keyword.length > text.length || text.substring(i, i + keyword.length) != keyword) {
            throw StrictJsonException("expected $keyword")
        }
        i += keyword.length
        return value
    }

    private fun peek(): Char {
        if (eof) throw StrictJsonException("unexpected end of JSON")
        return text[i]
    }

    private fun expect(c: Char) {
        if (peek() != c) throw StrictJsonException("expected '$c'")
        i++
    }
}
