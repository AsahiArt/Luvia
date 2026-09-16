package tech.asahiart.luvia

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.asahiart.luvia.internal.CodecException
import tech.asahiart.luvia.internal.FrameException
import tech.asahiart.luvia.internal.Methods
import tech.asahiart.luvia.internal.OpenStream
import tech.asahiart.luvia.internal.SessionEngine
import tech.asahiart.luvia.internal.UhpResponse
import tech.asahiart.luvia.internal.decodeAcpEvent
import tech.asahiart.luvia.internal.decodeUhpEvent
import tech.asahiart.luvia.internal.decodeUhpResponse
import tech.asahiart.luvia.internal.encodeAcpAction
import tech.asahiart.luvia.internal.parseObject
import tech.asahiart.luvia.internal.toFailure
import kotlin.time.Duration.Companion.seconds

public class AcpSession internal constructor(
    private val engine: SessionEngine,
    private val stream: OpenStream,
    public val info: AcpSessionInfo,
) {
    private val gate = Mutex()
    private val pending = mutableMapOf<String, CompletableDeferred<Outcome<Unit>>>()
    private var collectorCount: Int = 0

    public fun events(): Flow<AcpEvent> =
        flow {
            gate.withLock { collectorCount += 1 }
            try {
                while (currentCoroutineContext().isActive) {
                    val text =
                        try {
                            stream.framer.readFrame()
                        } catch (e: FrameException) {
                            if (e.kind == FrameException.Kind.Eof) {
                                emit(AcpEvent.Exited(null, "stream closed"))
                            } else {
                                emit(AcpEvent.Failed(e.toFailure(Methods.ACP_SESSION_OPEN, false, true)))
                            }
                            return@flow
                        }
                    val obj =
                        try {
                            parseObject(text)
                        } catch (e: CodecException) {
                            emit(AcpEvent.Failed(e.toFailure(Methods.ACP_SESSION_OPEN, false, true)))
                            return@flow
                        }
                    if ("id" in obj && "event" !in obj) {
                        routeReply(text)
                        continue
                    }
                    if ("event" !in obj) continue
                    val event =
                        try {
                            decodeAcpEvent(decodeUhpEvent(text))
                        } catch (_: CodecException) {
                            null
                        }
                    if (event == null) continue
                    emit(event)
                    if (event is AcpEvent.Exited || event is AcpEvent.Failed) {
                        return@flow
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(AcpEvent.Failed(e.toFailure(Methods.ACP_SESSION_OPEN, false, true)))
            } finally {
                val leftover =
                    gate.withLock {
                        collectorCount = (collectorCount - 1).coerceAtLeast(0)
                        val waiting = pending.values.toList()
                        pending.clear()
                        waiting
                    }
                leftover.forEach { it.complete(fail(Failure.Closed())) }
            }
        }

    public suspend fun prompt(text: String): Outcome<Unit> =
        writeAction("prompt", buildJsonObject { put("text", text) })

    public suspend fun answerPermission(requestId: String, optionId: String): Outcome<Unit> =
        writeAction(
            "permission",
            buildJsonObject {
                put("request_id", requestId)
                put("option_id", optionId)
            },
        )

    public suspend fun cancel(): Outcome<Unit> = writeAction("cancel", JsonObject(emptyMap()))

    public fun close() {
        stream.close()
    }

    private suspend fun writeAction(action: String, params: JsonObject): Outcome<Unit> {
        val id = engine.nextRequestId()
        val deferred = CompletableDeferred<Outcome<Unit>>()
        val collecting = gate.withLock {
            pending[id] = deferred
            collectorCount > 0
        }
        try {
            stream.framer.writeFrame(encodeAcpAction(id, action, params))
        } catch (e: CancellationException) {
            gate.withLock { pending.remove(id) }
            throw e
        } catch (e: Exception) {
            gate.withLock { pending.remove(id) }
            return fail(e.toFailure(action, true, false))
        }
        return if (collecting) {
            deferred.await()
        } else {
            withTimeoutOrNull(5.seconds) { deferred.await() } ?: ok(Unit)
        }
    }

    private suspend fun routeReply(text: String) {
        val response =
            try {
                decodeUhpResponse(text)
            } catch (_: Exception) {
                return
            }
        val deferred = gate.withLock { pending.remove(response.id) } ?: return
        when (response) {
            is UhpResponse.Success -> deferred.complete(ok(Unit))
            is UhpResponse.Failure -> deferred.complete(fail(response.error.toFailure()))
        }
    }
}
