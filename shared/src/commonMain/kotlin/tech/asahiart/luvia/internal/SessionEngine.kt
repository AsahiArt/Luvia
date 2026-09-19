package tech.asahiart.luvia.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.asahiart.luvia.BusEvent
import tech.asahiart.luvia.Capabilities
import tech.asahiart.luvia.ConnectionFreshness
import tech.asahiart.luvia.DiscoveredSession
import tech.asahiart.luvia.Failure
import tech.asahiart.luvia.LuviaSession
import tech.asahiart.luvia.Outcome
import tech.asahiart.luvia.ResyncReason
import tech.asahiart.luvia.SessionEvent
import tech.asahiart.luvia.SessionSnapshot
import tech.asahiart.luvia.SessionUpdate
import tech.asahiart.luvia.TerminalCaptureMode
import tech.asahiart.luvia.TerminalControl
import tech.asahiart.luvia.TerminalIdentity
import tech.asahiart.luvia.TerminalUpdate
import tech.asahiart.luvia.fail
import tech.asahiart.luvia.ok

internal class OpenStream(
    val framer: NdjsonFramer,
    private val channel: ByteChannel,
) {
    fun close() {
        framer.close()
        channel.close()
    }
}

internal class UnaryMux(
    val channel: ByteChannel,
    val framer: NdjsonFramer,
)

private class BridgeOpenException(message: String) : Exception(message)

// Bridge stdin idle-timeout is 300s (host/src/bridge.rs IDLE_TIMEOUT).
// Pooled channels are reopened on the next borrow rather than kept alive
// with pings; a dead channel is dropped and replaced transparently for
// unread requests, never for a mutation that may already have been written.
private const val UNARY_POOL_SIZE = 3

private class UnarySlot {
    val mutex = Mutex()
    var mux: UnaryMux? = null
}


internal sealed class LiveUpdate {
    class Snapshot(val snapshot: SessionSnapshot) : LiveUpdate()

    class Event(
        val sessionEvent: SessionEvent,
        val bus: BusEvent,
    ) : LiveUpdate()

    class Resyncing(val reason: ResyncReason) : LiveUpdate()

    class Failed(val failure: Failure) : LiveUpdate()
}

internal class SessionEngine(
    private val channels: ByteChannelFactory,
    private val authToken: String?,
) {
    private val mutex = Mutex()
    private val unarySlots = Array(UNARY_POOL_SIZE) { UnarySlot() }
    private val active = LinkedHashSet<ByteChannel>()
    private var sessionName: String? = null
    private var caps: Capabilities? = null
    private var connectionFreshness: ConnectionFreshness = ConnectionFreshness.Offline
    private var backendName: String = "luvus"

    private var closed: Boolean = false
    private var nextId: Int = 0

    fun capabilities(): Capabilities = caps ?: Capabilities(
        protocolName = "",
        protocolMajor = 0,
        protocolMinor = 0,
        methods = emptyList(),
        sessionName = null,
        eventSequence = 0,
        serverGeneration = null,
        agentStates = emptyList(),
    )

    fun freshness(): ConnectionFreshness = connectionFreshness
    fun backend(): String = backendName


    fun close() {
        closed = true
        connectionFreshness = ConnectionFreshness.Offline
        for (slot in unarySlots) {
            slot.mux?.framer?.close()
            slot.mux = null
        }
        active.toList().forEach { it.close() }
        active.clear()
    }

    suspend fun discover(): Outcome<List<DiscoveredSession>> {
        if (closed) return fail(Failure.Closed())
        return try {
            exchange { framer ->
                framer.writeFrame(encodeDiscoverRequest())
                ok(decodeDiscoverResponse(framer.readFrame()).sessions)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e.toFailure(null, false, false))
        }
    }

    suspend fun open(sessionName: String): Outcome<LuviaSession> {
        val handshake = unaryInternal(
            method = Methods.CAPABILITIES,
            params = JsonObject(emptyMap()),
            mutation = false,
            session = sessionName,
            gate = false,
        ) { mapCapabilities(it.asObject()) }
        return when (handshake) {
            is Outcome.Ok -> {
                this.sessionName = sessionName
                this.caps = handshake.value
                this.connectionFreshness = ConnectionFreshness.Live
                ok(LuviaSession(this))
            }
            is Outcome.Err -> fail(handshake.failure)
        }
    }

    suspend fun snapshot(): Outcome<SessionSnapshot> =
        unary(Methods.SNAPSHOT, JsonObject(emptyMap()), mutation = false) { mapSnapshot(it.asObject()) }

    suspend fun <T> unary(
        method: String,
        params: JsonObject,
        mutation: Boolean,
        map: (JsonElement) -> T,
    ): Outcome<T> {
        val session = sessionName ?: return fail(Failure.Closed())
        return unaryInternal(method, params, mutation, session, gate = true, map)
    }

    fun events(): Flow<SessionUpdate> =
        liveUpdates().map { update ->
            when (update) {
                is LiveUpdate.Snapshot -> SessionUpdate.Snapshot(update.snapshot)
                is LiveUpdate.Event -> SessionUpdate.Event(update.sessionEvent)
                is LiveUpdate.Resyncing -> SessionUpdate.Resyncing(update.reason)
                is LiveUpdate.Failed -> SessionUpdate.Failed(update.failure)
            }
        }

    fun liveUpdates(
        afterSequence: Long? = null,
        serverGeneration: String? = null,
    ): Flow<LiveUpdate> = flow {
        var cursor = afterSequence
        var generation = serverGeneration
        var useSeed = afterSequence != null
        while (currentCoroutineContext().isActive && !closed) {
            val recon = SubscribeSnapshotReconciler()
            val snapshot: SessionSnapshot?
            if (useSeed && cursor != null) {
                recon.seedFence(cursor, generation)
                snapshot = null
                useSeed = false
            } else {
                snapshot = when (val outcome = snapshot()) {
                    is Outcome.Ok -> outcome.value
                    is Outcome.Err -> {
                        emit(LiveUpdate.Failed(outcome.failure))
                        return@flow
                    }
                }
                cursor = snapshot.eventSequence
                generation = snapshot.serverGeneration
            }
            val stream = when (val opened = openStream(Methods.EVENTS_SUBSCRIBE, subscribeParams(cursor))) {
                is Outcome.Ok -> opened.value
                is Outcome.Err -> {
                    var resyncOnOpen: ResyncReason? = null
                    if (snapshot != null) {
                        for (action in recon.onSnapshot(snapshot)) {
                            when (action) {
                                is ReconcileAction.ApplySnapshot -> emit(LiveUpdate.Snapshot(action.snapshot))
                                is ReconcileAction.ApplyEvent ->
                                    emit(LiveUpdate.Event(action.event, action.bus))
                                is ReconcileAction.Resync -> resyncOnOpen = action.reason
                            }
                        }
                    }
                    when (val failure = opened.failure) {
                        is Failure.ResyncRequired,
                        is Failure.InvalidParams,
                        -> {
                            connectionFreshness = ConnectionFreshness.Stale
                            emit(LiveUpdate.Resyncing(resyncOnOpen ?: ResyncReason.Overflow))
                            cursor = null
                            generation = null
                            continue
                        }
                        else -> {
                            emit(LiveUpdate.Failed(failure))
                            return@flow
                        }
                    }
                }
            }
            try {
                var resync: ResyncReason? = null
                if (snapshot != null) {
                    for (action in recon.onSnapshot(snapshot)) {
                        when (action) {
                            is ReconcileAction.ApplySnapshot -> emit(LiveUpdate.Snapshot(action.snapshot))
                            is ReconcileAction.ApplyEvent ->
                                emit(LiveUpdate.Event(action.event, action.bus))
                            is ReconcileAction.Resync -> resync = action.reason
                        }
                    }
                }
                if (resync != null) {
                    connectionFreshness = ConnectionFreshness.Stale
                    emit(LiveUpdate.Resyncing(resync))
                    cursor = null
                    generation = null
                    continue
                }
                while (resync == null && currentCoroutineContext().isActive && !closed) {
                    val event = decodeUhpEvent(stream.framer.readFrame())
                    cursor = event.sequence
                    for (action in recon.onEvent(event)) {
                        when (action) {
                            is ReconcileAction.ApplySnapshot -> emit(LiveUpdate.Snapshot(action.snapshot))
                            is ReconcileAction.ApplyEvent ->
                                emit(LiveUpdate.Event(action.event, action.bus))
                            is ReconcileAction.Resync -> resync = action.reason
                        }
                    }
                }
                if (resync != null) {
                    connectionFreshness = ConnectionFreshness.Stale
                    emit(LiveUpdate.Resyncing(resync))
                    if (resync == ResyncReason.Overflow) {
                        cursor = null
                        generation = null
                    }
                } else {
                    return@flow
                }
            } catch (e: FrameException) {
                if (e.kind == FrameException.Kind.Eof) {
                    connectionFreshness = ConnectionFreshness.Stale
                    emit(LiveUpdate.Resyncing(ResyncReason.Eof))
                    cursor = null
                    generation = null
                } else {
                    emit(LiveUpdate.Failed(e.toFailure(Methods.EVENTS_SUBSCRIBE, false, true)))
                    return@flow
                }
            } catch (e: CodecException) {
                emit(LiveUpdate.Failed(e.toFailure(Methods.EVENTS_SUBSCRIBE, false, true)))
                return@flow
            } finally {
                stream.close()
            }
        }
    }


    fun observe(
        identity: TerminalIdentity,
        mode: TerminalCaptureMode = TerminalCaptureMode.RecentUnwrapped,
        lines: Int = 200,
    ): Flow<TerminalUpdate> {
        val params = observeParams(identity, mode, lines)
        return flow {
            while (currentCoroutineContext().isActive && !closed) {
                val stream = when (val opened = openStream(Methods.TERMINAL_OBSERVE, params)) {
                    is Outcome.Ok -> opened.value
                    is Outcome.Err -> {
                        emit(TerminalUpdate.Failed(opened.failure))
                        return@flow
                    }
                }
                var resync: ResyncReason? = null
                try {
                    terminalFrames(stream).collect { update ->
                        if (update is TerminalUpdate.Resyncing) {
                            resync = update.reason
                        } else {
                            emit(update)
                        }
                    }
                } finally {
                    stream.close()
                }
                if (resync != null) {
                    connectionFreshness = ConnectionFreshness.Stale
                    emit(TerminalUpdate.Resyncing(resync!!))
                } else {
                    return@flow
                }
            }
        }
    }

    suspend fun openControl(identity: TerminalIdentity): Outcome<TerminalControl> =
        when (val opened = openStream(Methods.TERMINAL_CONTROL, locatorParams(identity))) {
            is Outcome.Ok -> ok(TerminalControl(this, opened.value))
            is Outcome.Err -> fail(opened.failure)
        }

    internal suspend fun openAcp(params: JsonObject): Outcome<Pair<OpenStream, JsonElement>> =
        openStreamWithAck(Methods.ACP_SESSION_OPEN, params)

    internal suspend fun nextRequestId(): String = allocateId()

    fun controlFrames(stream: OpenStream): Flow<TerminalUpdate> = terminalFrames(stream)

    suspend fun writeControl(
        stream: OpenStream,
        action: ControlFrame.Action,
        params: JsonObject,
        method: String,
    ): Outcome<Unit> {
        if (closed) return fail(Failure.Closed())
        return try {
            stream.framer.writeFrame(encodeControlFrame(ControlFrame(allocateId(), action, params)))
            ok(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            fail(Failure.IndeterminateMutation(method))
        }
    }

    private fun terminalFrames(stream: OpenStream): Flow<TerminalUpdate> = flow {
        var lastRevision = -1L
        try {
            while (currentCoroutineContext().isActive && !closed) {
                val event = decodeUhpEvent(stream.framer.readFrame())
                if (isOverflow(event)) {
                    emit(TerminalUpdate.Resyncing(ResyncReason.Overflow))
                    return@flow
                }
                if (event.name != "terminal.frame") continue
                val frame = decodeTerminalFrameEvent(event)
                if (frame.contentRevision < lastRevision) continue
                lastRevision = frame.contentRevision
                emit(TerminalUpdate.Frame(frame))
            }
        } catch (e: FrameException) {
            if (e.kind == FrameException.Kind.Eof) {
                emit(TerminalUpdate.Resyncing(ResyncReason.Eof))
            } else {
                emit(TerminalUpdate.Failed(e.toFailure(Methods.TERMINAL_OBSERVE, false, true)))
            }
        } catch (e: CodecException) {
            emit(TerminalUpdate.Failed(e.toFailure(Methods.TERMINAL_OBSERVE, false, true)))
        }
    }

    private suspend fun openStream(method: String, params: JsonObject): Outcome<OpenStream> =
        when (val opened = openStreamWithAck(method, params)) {
            is Outcome.Ok -> ok(opened.value.first)
            is Outcome.Err -> fail(opened.failure)
        }

    private suspend fun openStreamWithAck(
        method: String,
        params: JsonObject,
    ): Outcome<Pair<OpenStream, JsonElement>> {
        val session = sessionName ?: return fail(Failure.Closed())
        if (method !in capabilities().methods) return fail(Failure.CapabilityMissing(method))
        var written = false
        val channel = try {
            register(channels.open())
        } catch (e: Exception) {
            return fail(Failure.Bridge(e.message ?: "channel open failed"))
        }
        val framer = NdjsonFramer(channel)
        return try {
            framer.writeFrame(encodeOpenRequest(session))
            backendName = decodeOpenResponse(framer.readFrame(), session)

            val id = allocateId()
            framer.writeFrame(encodeUhpRequest(UhpRequest(id, method, params, authToken)))
            written = true
            when (val response = decodeUhpResponse(framer.readFrame(), id)) {
                is UhpResponse.Success -> ok(OpenStream(framer, channel) to response.result)
                is UhpResponse.Failure -> {
                    framer.close()
                    fail(response.error.toFailure())
                }
            }
        } catch (e: CancellationException) {
            framer.close()
            throw e
        } catch (e: Exception) {
            framer.close()
            fail(e.toFailure(method, method in Methods.MUTATIONS, written))
        }
    }

    private suspend fun <T> unaryInternal(
        method: String,
        params: JsonObject,
        mutation: Boolean,
        session: String,
        gate: Boolean,
        map: (JsonElement) -> T,
    ): Outcome<T> {
        if (closed) return fail(Failure.Closed())
        if (gate && method !in (caps?.methods ?: emptyList())) {
            return fail(Failure.CapabilityMissing(method))
        }
        return withUnarySlot { slot ->
            var written = false
            try {
                val first = ensureSlotMux(slot, session)
                try {
                    transactUnary(first, method, params, map) { written = true }
                } catch (e: CancellationException) {
                    dropSlotMux(slot)
                    throw e
                } catch (e: Exception) {
                    dropSlotMux(slot)
                    if (written) {
                        fail(e.toFailure(method, mutation, true))
                    } else if (closed) {
                        fail(Failure.Closed())
                    } else {
                        val retry = ensureSlotMux(slot, session)
                        transactUnary(retry, method, params, map) { written = true }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: BridgeOpenException) {
                dropSlotMux(slot)
                fail(Failure.Bridge(e.message ?: "channel open failed"))
            } catch (e: Exception) {
                dropSlotMux(slot)
                fail(e.toFailure(method, mutation, written))
            }
        }
    }

    private suspend fun <T> transactUnary(
        mux: UnaryMux,
        method: String,
        params: JsonObject,
        map: (JsonElement) -> T,
        onWritten: () -> Unit,
    ): Outcome<T> {
        val id = allocateId()
        mux.framer.writeFrame(encodeUhpRequest(UhpRequest(id, method, params, authToken)))
        onWritten()
        return when (val response = decodeUhpResponse(mux.framer.readFrame(), id)) {
            is UhpResponse.Success -> ok(map(response.result))
            is UhpResponse.Failure -> fail(response.error.toFailure())
        }
    }

    private suspend fun <T> withUnarySlot(block: suspend (UnarySlot) -> T): T {
        for (slot in unarySlots) {
            if (slot.mutex.tryLock()) {
                try {
                    return block(slot)
                } finally {
                    slot.mutex.unlock()
                }
            }
        }
        val fallback = unarySlots[0]
        return fallback.mutex.withLock { block(fallback) }
    }

    private suspend fun ensureSlotMux(slot: UnarySlot, session: String): UnaryMux {
        slot.mux?.let { return it }
        val channel = try {
            register(channels.open())
        } catch (e: Exception) {
            throw BridgeOpenException(e.message ?: "channel open failed")
        }
        val framer = NdjsonFramer(channel)
        try {
            framer.writeFrame(encodeOpenRequest(session))
            backendName = decodeOpenResponse(framer.readFrame(), session)
        } catch (e: Exception) {
            framer.close()
            mutex.withLock { active -= channel }
            throw e
        }
        val mux = UnaryMux(channel, framer)
        slot.mux = mux
        return mux
    }

    private suspend fun dropSlotMux(slot: UnarySlot) {
        val current = slot.mux ?: return
        slot.mux = null
        current.framer.close()
        mutex.withLock { active -= current.channel }
    }



    private suspend fun <T> exchange(block: suspend (NdjsonFramer) -> Outcome<T>): Outcome<T> {
        val channel = try {
            register(channels.open())
        } catch (e: Exception) {
            return fail(Failure.Bridge(e.message ?: "channel open failed"))
        }
        val framer = NdjsonFramer(channel)
        return try {
            block(framer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FrameException) {
            throw e
        } catch (e: CodecException) {
            throw e
        } catch (e: UnknownMajorException) {
            throw e
        } catch (e: Exception) {
            fail(e.toFailure(null, false, false))
        } finally {
            framer.close()
            unregister(channel)
        }
    }

    private suspend fun register(channel: ByteChannel): ByteChannel {
        mutex.withLock { active += channel }
        return channel
    }

    private suspend fun unregister(channel: ByteChannel) {
        mutex.withLock { active -= channel }
        channel.close()
    }

    private suspend fun allocateId(): String = mutex.withLock {
        nextId += 1
        "r$nextId"
    }
}

private fun UhpError.toRemote(): Failure = toFailure()

internal fun Exception.toFailure(method: String?, mutation: Boolean, written: Boolean): Failure {
    if (written && mutation && method != null) {
        if (this is FrameException && kind == FrameException.Kind.Eof) {
            return Failure.IndeterminateMutation(method)
        }
    }
    return when (this) {
        is UnknownMajorException -> Failure.UnknownMajor(name, major)
        is FrameException -> when (kind) {
            FrameException.Kind.Eof -> Failure.Closed()
            FrameException.Kind.Oversized,
            FrameException.Kind.InvalidUtf8,
            FrameException.Kind.Empty,
            -> Failure.Frame(message ?: "invalid frame")
        }
        is CodecException -> when (kind) {
            CodecException.Kind.MismatchedId -> Failure.ProtocolError(message ?: "mismatched id")
            else -> Failure.ProtocolError(message ?: "invalid envelope")
        }
        else -> Failure.ProtocolError(message ?: "request failed")
    }
}
