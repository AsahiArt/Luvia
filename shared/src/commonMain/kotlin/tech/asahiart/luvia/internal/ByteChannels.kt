package tech.asahiart.luvia.internal

internal interface ByteChannel {
    suspend fun writeFully(bytes: ByteArray)

    suspend fun readChunk(): ByteArray?

    fun close()
}

internal fun interface ByteChannelFactory {
    suspend fun open(): ByteChannel
}
