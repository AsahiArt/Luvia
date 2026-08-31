package tech.asahiart.luvia.support

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import tech.asahiart.luvia.internal.ByteChannel
import tech.asahiart.luvia.internal.ByteChannelFactory
import tech.asahiart.luvia.internal.NdjsonFramer

internal class MemoryPipePair {
    private val leftToRight = Channel<ByteArray>(Channel.UNLIMITED)
    private val rightToLeft = Channel<ByteArray>(Channel.UNLIMITED)
    val client: ByteChannel = End(leftToRight, rightToLeft)
    val server: ByteChannel = End(rightToLeft, leftToRight)

    private class End(
        private val writes: Channel<ByteArray>,
        private val reads: Channel<ByteArray>,
    ) : ByteChannel {
        override suspend fun writeFully(bytes: ByteArray) {
            writes.send(bytes.copyOf())
        }

        override suspend fun readChunk(): ByteArray? = reads.receiveCatching().getOrNull()

        override fun close() {
            writes.close()
            reads.close()
        }
    }
}

internal class ScriptedFactory(
    private val scope: CoroutineScope,
    private val handle: suspend (NdjsonFramer) -> Unit,
) : ByteChannelFactory {
    var opens: Int = 0
        private set
    val methods: MutableList<String> = mutableListOf()

    override suspend fun open(): ByteChannel {
        opens += 1
        val pair = MemoryPipePair()
        scope.launch {
            val framer = NdjsonFramer(pair.server)
            try {
                handle(framer)
            } finally {
                pair.server.close()
            }
        }
        return pair.client
    }
}
