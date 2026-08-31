package tech.asahiart.luvia.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tech.asahiart.luvia.transport.BridgeChannel
import tech.asahiart.luvia.transport.SshConnection
import tech.asahiart.luvia.transport.maxReadBytes

internal class SshChannelFactory(
    private val connection: SshConnection,
) : ByteChannelFactory {
    override suspend fun open(): ByteChannel = SshByteChannel(connection.openBridge())
}

internal class SshByteChannel(
    private val channel: BridgeChannel,
) : ByteChannel {
    override suspend fun writeFully(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        channel.write(bytes)
    }

    override suspend fun readChunk(): ByteArray? {
        while (true) {
            val result = channel.read(maxReadBytes())
            if (result.data.isNotEmpty()) return result.data
            if (result.eof) return null
        }
    }

    override fun close() {
        closer.launch {
            runCatching { channel.shutdown() }
        }
    }

    private companion object {
        val closer = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
