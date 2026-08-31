package tech.asahiart.luvia.internal

import okio.Buffer
import tech.asahiart.luvia.Luvia

internal class FrameException(val kind: Kind, override val message: String) : Exception(message) {
    enum class Kind {
        Oversized,
        InvalidUtf8,
        Eof,
        Empty,
    }
}

internal class NdjsonFramer(private val channel: ByteChannel) {
    private val incoming = Buffer()

    suspend fun writeFrame(utf8Text: String) {
        val payload = utf8Text.encodeToByteArray()
        val total = payload.size + 1
        if (total > Luvia.maxFrameBytes) {
            throw FrameException(FrameException.Kind.Oversized, "frame exceeds 1 MiB")
        }
        val framed = ByteArray(total)
        payload.copyInto(framed)
        framed[payload.size] = LF
        channel.writeFully(framed)
    }

    suspend fun readFrame(): String {
        while (true) {
            val nl = incoming.indexOf(LF)
            if (nl >= 0) {
                val includingLf = nl + 1
                if (includingLf > Luvia.maxFrameBytes) {
                    throw FrameException(FrameException.Kind.Oversized, "frame exceeds 1 MiB")
                }
                val payload = incoming.readByteArray(nl)
                incoming.readByte()
                if (payload.isEmpty()) {
                    throw FrameException(FrameException.Kind.Empty, "empty frame")
                }
                return decodeUtf8(payload)
            }
            if (incoming.size >= Luvia.maxFrameBytes) {
                throw FrameException(FrameException.Kind.Oversized, "frame exceeds 1 MiB")
            }
            val chunk = channel.readChunk()
                ?: throw FrameException(FrameException.Kind.Eof, "unexpected end of stream")
            incoming.write(chunk)
        }
    }

    fun close() {
        channel.close()
        incoming.clear()
    }
}

private const val LF: Byte = 0x0A

private fun decodeUtf8(bytes: ByteArray): String {
    val text = bytes.decodeToString(throwOnInvalidSequence = true)
    if (text.isNotEmpty() && text.last() == '\r') {
        return text.substring(0, text.length - 1)
    }
    return text
}
