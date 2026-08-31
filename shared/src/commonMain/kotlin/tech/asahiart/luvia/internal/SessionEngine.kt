package tech.asahiart.luvia.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.asahiart.luvia.Capabilities
import tech.asahiart.luvia.ConnectionFreshness
import tech.asahiart.luvia.DiscoveredSession
import tech.asahiart.luvia.Failure
import tech.asahiart.luvia.LuviaSession
import tech.asahiart.luvia.Outcome
import tech.asahiart.luvia.ResyncReason
import tech.asahiart.luvia.SessionSnapshot
import tech.asahiart.luvia.SessionUpdate
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

internal class SessionEngine(
    private val channels: ByteChannelFactory,
    private val authToken: String?,
) {
    private val mutex = Mutex()
    private val active = LinkedHashSet<ByteChannel>()
    private var sessionName: String? = null
    private var caps: Capabilities? = null
    private var connectionFreshness: ConnectionFreshness = ConnectionFreshness.Offline
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

    fun close() {
        closed = true
        connectionFreshness = ConnectionFreshness.Offline
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

    fun events(): Flow<SessionUpdate> = flow {
        while (currentCoroutineContext().isActive && !closed) {
            val recon = SubscribeSnapshotReconciler()
            val stream = when (val opened = openStream(Methods.EVENTS_SUBSCRIBE, JsonObject(emptyMap()))) {
                is Outcome.Ok -> opened.value
                is Outcome.Err -> {
                    emit(SessionUpdate.Failed(opened.failure))
                    return@flow
                }
            }
            try {
                val snapshot = when (val outcome = snapshot()) {
                    is Outcome.Ok -> outcome.value
                    is Outcome.Err -> {
                        emit(SessionUpdate.Failed(outcome.failure))
                        return@flow
                    }
                }
                var resync: ResyncReason? = null
                for (action in recon.onSnapshot(snapshot)) {
                    when (action) {
                        is ReconcileAction.ApplySnapshot -> emit(SessionUpdate.Snapshot(action.snapshot))
                        is ReconcileAction.ApplyEvent -> emit(SessionUpdate.Event(action.event))
                        is ReconcileAction.Resync -> resync = action.reason
                    }
                }
                while (resync == null && currentCoroutineContext().isActive && !closed) {
                    for (action in recon.onEvent(decodeUhpEvent(stream.framer.readFrame()))) {
                        when (action) {
                            is ReconcileAction.ApplySnapshot -> emit(SessionUpdate.Snapshot(action.snapshot))
                            is ReconcileAction.ApplyEvent -> emit(SessionUpdate.Event(action.event))
                            is ReconcileAction.Resync -> resync = action.reason
                        }
                    }
                }
                if (resync != null) {
                    connectionFreshness = ConnectionFreshness.Stale
                    emit(SessionUpdate.Resyncing(resync!!))
                } else {
                    return@flow
                }
            } catch (e: FrameException) {
                if (e.kind == FrameException.Kind.Eof) {
                    connectionFreshness = ConnectionFreshness.Stale
                    emit(SessionUpdate.Resyncing(ResyncReason.Eof))
                } else {
                    emit(SessionUpdate.Failed(e.toFailure(Methods.EVENTS_SUBSCRIBE, false, true)))
                    return@flow
                }
            } catch (e: CodecException) {
                emit(SessionUpdate.Failed(e.toFailure(Methods.EVENTS_SUBSCRIBE, false, true)))
                return@flow
            } finally {
                stream.close()
            }
        }
    }

    fun observe(identity: TerminalIdentity): Flow<TerminalUpdate> {
        val params = locatorParams(identity)
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

    private suspend fun openStream(method: String, params: JsonObject): Outcome<OpenStream> {
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
            decodeOpenResponse(framer.readFrame(), session)
            val id = allocateId()
            framer.writeFrame(encodeUhpRequest(UhpRequest(id, method, params, authToken)))
            written = true
            when (val response = decodeUhpResponse(framer.readFrame(), id)) {
                is UhpResponse.Success -> ok(OpenStream(framer, channel))
                is UhpResponse.Failure -> {
                    framer.close()
                    fail(Failure.Remote(response.error.code, response.error.message))
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
        var written = false
        return try {
            exchange { framer ->
                framer.writeFrame(encodeOpenRequest(session))
                decodeOpenResponse(framer.readFrame(), session)
                val id = allocateId()
                framer.writeFrame(encodeUhpRequest(UhpRequest(id, method, params, authToken)))
                written = true
                when (val response = decodeUhpResponse(framer.readFrame(), id)) {
                    is UhpResponse.Success -> ok(map(response.result))
                    is UhpResponse.Failure -> fail(response.error.toRemote())
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e.toFailure(method, mutation, written))
        }
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

private fun UhpError.toRemote(): Failure = Failure.Remote(code, message)

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
