package tech.asahiart.luvia

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

public sealed class HostLink {
    public object Idle : HostLink()

    public object Connecting : HostLink()

    public data class Online(
        public val address: String,
        public val sessionName: String?,
    ) : HostLink()

    public data class Failed(
        public val failure: Failure,
    ) : HostLink()
}

public data class HostRuntime(
    public val profile: HostProfile,
    public val link: HostLink,
    public val snapshot: SessionSnapshot?,
    public val tasks: List<TaskSummary>,
    public val freshness: ConnectionFreshness,
    public val backend: String = "luvus",
)


public data class PairingDraft(
    public val deviceLabel: String,
    public val role: HostRole,
    public val authorizedKeysLine: String,
    public val deviceKeyFingerprint: String,
    public val command: String,
    internal val privateKeyOpenssh: String,
)

public class HostManager(
    store: HostStore,
    vault: DeviceKeyVault,
    scope: CoroutineScope,
) {
    private val store: HostStore = store
    private val vault: DeviceKeyVault = vault
    private val job: Job = SupervisorJob(scope.coroutineContext[Job])
    private val mgrScope: CoroutineScope = CoroutineScope(scope.coroutineContext + job)
    private val mutex: Mutex = Mutex()
    private val live: MutableMap<String, LiveHost> = mutableMapOf()
    private val links: MutableMap<String, HostLink> = mutableMapOf()
    private val connectJobs: MutableMap<String, Job> = mutableMapOf()
    private var catalogHosts: List<HostProfile> = emptyList()
    private val hostsState: MutableStateFlow<List<HostRuntime>> =
        MutableStateFlow(emptyList())
    private val pushRegistrationState: MutableStateFlow<PushRegistration?> = MutableStateFlow(null)
    private var pushEnabled: Boolean = false
    private val busEventsFlow: MutableSharedFlow<Pair<String, BusEvent>> =
        MutableSharedFlow(extraBufferCapacity = 32)


    public val hosts: StateFlow<List<HostRuntime>> = hostsState.asStateFlow()
    public val pushRegistration: StateFlow<PushRegistration?> = pushRegistrationState.asStateFlow()
    internal val busEvents: SharedFlow<Pair<String, BusEvent>> = busEventsFlow.asSharedFlow()

    public fun setPushRegistration(registration: PushRegistration?) {
        if (registration == null) {
            pushRegistrationState.value = null
            unregisterPush()
        } else {
            registerPush(registration)
        }
    }

    public fun registerPush(registration: PushRegistration) {
        pushRegistrationState.value = registration
        pushEnabled = true
        mgrScope.launch { pushToLiveHosts(registration) }
    }

    public fun unregisterPush() {
        pushEnabled = false
        mgrScope.launch { unregisterLiveHosts() }
    }

    init {
        mgrScope.launch {
            store.catalog.collect { catalog ->
                mutex.withLock {
                    catalogHosts = catalog.hosts
                    publishLocked(catalogHosts)
                }
            }
        }
    }

    public fun beginPairing(deviceLabel: String, role: HostRole): Outcome<PairingDraft> {
        return when (val generated = DeviceKeys.generate()) {
            is Outcome.Err -> fail(generated.failure)
            is Outcome.Ok ->
                ok(
                    PairingDraft(
                        deviceLabel = deviceLabel,
                        role = role,
                        authorizedKeysLine = generated.value.identity.authorizedKeys,
                        deviceKeyFingerprint = generated.value.identity.fingerprint,
                        command = pairCommandFor(deviceLabel, role, generated.value.identity.authorizedKeys),
                        privateKeyOpenssh = generated.value.privateKeyOpenssh,
                    ),
                )
        }
    }

    public suspend fun completePairing(
        draft: PairingDraft,
        rawCode: String,
        addresses: List<String> = emptyList(),
        sshPort: Int? = null,
        username: String? = null,
    ): Outcome<HostProfile> {
        val code =
            when (val decoded = PairingCodes.decode(rawCode)) {
                is Outcome.Err -> return fail(decoded.failure)
                is Outcome.Ok -> decoded.value
            }
        if (code.deviceKeyFingerprint != draft.deviceKeyFingerprint) {
            return fail(Failure.ProtocolError("pairing code is for a different device key"))
        }
        val reachability =
            when (val parsed = parseReachability(addresses, sshPort, username, code.addresses, code.sshPort, code.username)) {
                is Outcome.Err -> return fail(parsed.failure)
                is Outcome.Ok -> parsed.value
            }
        try {
            vault.save(code.deviceId, draft.privateKeyOpenssh)
        } catch (error: Exception) {
            return fail(Failure.ProtocolError("Could not store the device key on this device."))
        }
        val profile =
            HostProfile(
                id = code.deviceId,
                alias = code.hostLabel,
                addresses = reachability.addresses,
                sshPort = reachability.sshPort,
                username = reachability.username,
                hostKeyFingerprints = code.hostKeyFingerprints,
                role = code.role,
                lastStatus = HostStatus.Unknown,
                lastUpdatedEpochMs = nowEpochMs(),
                lastConnectedAddress = null,
                topology = null,
            )
        store.upsert(profile)
        connect(profile.id)
        return ok(profile)
    }

    public suspend fun updateConnection(
        hostId: String,
        alias: String,
        addresses: List<String>,
        sshPort: Int,
        username: String,
    ): Outcome<HostProfile> {
        val current =
            store.current().hosts.firstOrNull { it.id == hostId }
                ?: return fail(Failure.NotFound("host not found"))
        val reachability =
            when (val parsed = parseReachability(addresses, sshPort, username, current.addresses, current.sshPort, current.username)) {
                is Outcome.Err -> return fail(parsed.failure)
                is Outcome.Ok -> parsed.value
            }
        val name = alias.trim().ifEmpty { current.alias }
        val last =
            current.lastConnectedAddress?.takeIf { it in reachability.addresses }
        val updated =
            current.copy(
                alias = name,
                addresses = reachability.addresses,
                sshPort = reachability.sshPort,
                username = reachability.username,
                lastConnectedAddress = last,
                lastUpdatedEpochMs = nowEpochMs(),
            )
        store.upsert(updated)
        val shouldReconnect =
            mutex.withLock {
                catalogHosts = catalogHosts.map { if (it.id == hostId) updated else it }
                publishLocked(catalogHosts)
                val liveHost = live[hostId]
                val connecting = connectJobs.containsKey(hostId)
                val online = links[hostId] is HostLink.Online || links[hostId] is HostLink.Connecting
                Triple(liveHost != null || connecting || online, connectJobs.remove(hostId), live.remove(hostId))
            }
        shouldReconnect.second?.cancel()
        shouldReconnect.third?.close()
        if (shouldReconnect.first) {
            mutex.withLock { links[hostId] = HostLink.Idle }
            connect(hostId)
        }
        return ok(updated)
    }


    public suspend fun unpair(hostId: String) {
        disconnect(hostId)
        vault.delete(hostId)
        store.remove(hostId)
    }
    public fun connect(hostId: String) {
        val missingKey =
            Failure.ProtocolError(
                "Device key for this host is gone. Re-pair the host to restore access.",
            )
        if (vault.credential(hostId) == null) {
            val failed = HostLink.Failed(missingKey)
            links[hostId] = failed
            val current = hostsState.value
            if (current.any { it.profile.id == hostId }) {
                hostsState.value =
                    current.map { runtime ->
                        if (runtime.profile.id == hostId) {
                            runtime.copy(link = failed, freshness = ConnectionFreshness.Offline)
                        } else {
                            runtime
                        }
                    }
            } else {
                mgrScope.launch { setLink(hostId, failed) }
            }
            return
        }
        mgrScope.launch {
            mutex.withLock {
                connectJobs.remove(hostId)?.cancel()
                connectJobs[hostId] = mgrScope.launch { connectLoop(hostId) }
            }
        }
    }

    public fun disconnect(hostId: String) {
        mgrScope.launch {
            val liveHost =
                mutex.withLock {
                    connectJobs.remove(hostId)?.cancel()
                    val removed = live.remove(hostId)
                    links[hostId] = HostLink.Idle
                    removed
                }
            liveHost?.close()
            runCatching {
                store.updateStatus(hostId, HostStatus.Stale, nowEpochMs())
            }
            mutex.withLock {
                publishLocked(catalogHosts)
            }
        }
    }

    public suspend fun refresh(hostId: String): Outcome<Unit> {
        val session =
            mutex.withLock { live[hostId]?.session } ?: return fail(Failure.Closed())
        return pullSessionState(hostId, session)
    }

    public suspend fun openTerminal(hostId: String, identity: TerminalIdentity): Outcome<TerminalControl> {
        val session =
            mutex.withLock { live[hostId]?.session } ?: return fail(Failure.Closed())
        return session.openControl(identity)
    }

    public fun observeTerminal(hostId: String, identity: TerminalIdentity): Flow<TerminalUpdate> {
        val session = live[hostId]?.session ?: return emptyFlow()
        return session.observe(identity)
    }

    /**
     * The live UHP session for a connected Host, or null when not connected.
     * UI layers use it for the structured surface (agents, review, tasks, mission);
     * gate features with [LuviaSession.supports] rather than Host versions.
     */
    public fun session(hostId: String): LuviaSession? = live[hostId]?.session


    public fun close() {
        connectJobs.values.forEach { it.cancel() }
        connectJobs.clear()
        val closing = live.values.toList()
        live.clear()
        links.clear()
        closing.forEach { it.close() }
        job.cancel()
    }

    private suspend fun connectLoop(hostId: String) {
        var attempt = 0
        while (job.isActive) {
            setLink(hostId, HostLink.Connecting)
            val outcome = connectOnce(hostId)
            when (outcome) {
                is Outcome.Ok -> {
                    attempt = 0
                    val session = outcome.value
                    val seed = mutex.withLock { live[hostId]?.snapshot }
                    val collectJob =
                        mgrScope.launch {
                            session
                                .liveUpdates(
                                    afterSequence = seed?.eventSequence,
                                    serverGeneration = seed?.serverGeneration,
                                ).collect { update ->
                                    handleLiveUpdate(hostId, session, update)
                                }
                        }
                    try {
                        collectJob.join()
                    } finally {
                        collectJob.cancel()
                    }
                    if (!job.isActive) return
                    val last = mutex.withLock { live[hostId]?.lastFailure }
                    if (last != null && !isRetryable(last)) {
                        setLink(hostId, HostLink.Failed(last))
                        return
                    }
                }
                is Outcome.Err -> {
                    if (!isRetryable(outcome.failure)) {
                        setLink(hostId, HostLink.Failed(outcome.failure))
                        runCatching {
                            store.updateStatus(hostId, HostStatus.Unreachable, nowEpochMs())
                        }
                        return
                    }
                    attempt += 1
                    setLink(hostId, HostLink.Connecting)
                    delay(backoffMs(attempt))
                }
            }
        }
    }

    private suspend fun connectOnce(hostId: String): Outcome<LuviaSession> {
        val profile =
            store.current().hosts.firstOrNull { it.id == hostId }
                ?: return fail(Failure.NotFound("host not found"))
        val credential =
            vault.credential(hostId)
                ?: return fail(
                    Failure.ProtocolError(
                        "Device key for this host is gone. Re-pair the host to restore access.",
                    ),
                )
        val attempted = connectToProfile(profile, credential)
        if (attempted.deadLiteralAddresses.isNotEmpty()) {
            runCatching { store.forgetLiteralAddresses(hostId, attempted.deadLiteralAddresses) }
        }
        val connected =
            when (val result = attempted.outcome) {
                is Outcome.Err -> return fail(result.failure)
                is Outcome.Ok -> result.value
            }
        store.setLastConnectedAddress(hostId, connected.address)
        val cachedSessionName = profile.topology?.sessionName?.takeIf { it.isNotEmpty() }
        var chosen: DiscoveredSession? = null
        var session: LuviaSession? = null
        var sessionName = cachedSessionName
        if (cachedSessionName != null) {
            when (val result = connected.host.client.open(cachedSessionName)) {
                is Outcome.Ok -> session = result.value
                is Outcome.Err -> {
                    if (!isUnknownSession(result.failure)) {
                        connected.host.close()
                        return fail(result.failure)
                    }
                }
            }
        }
        if (session == null) {
            val discovered =
                when (val result = connected.host.client.discover()) {
                    is Outcome.Err -> {
                        connected.host.close()
                        return fail(result.failure)
                    }
                    is Outcome.Ok -> result.value
                }
            chosen =
                discovered.firstOrNull { it.isDefault && it.running }
                    ?: discovered.firstOrNull { it.running }
                    ?: discovered.firstOrNull { it.isDefault }
                    ?: discovered.firstOrNull()
            sessionName = chosen?.name ?: "default"
            session =
                when (val result = connected.host.client.open(sessionName)) {
                    is Outcome.Err -> {
                        connected.host.close()
                        return fail(result.failure)
                    }
                    is Outcome.Ok -> result.value
                }
        }
        val opened = session!!
        val openedName = sessionName ?: "default"
        val backend =
            opened.backend.takeIf { it == "herdr" } ?: chosen?.backend ?: "luvus"
        mutex.withLock {
            live.remove(hostId)?.close()
            live[hostId] =
                LiveHost(
                    connection = connected,
                    session = opened,
                    address = connected.address,
                    sessionName = openedName,
                    backend = backend,
                )
        }
        val pulled = pullSessionState(hostId, opened)
        if (pulled is Outcome.Err) {
            connected.host.close()
            mutex.withLock { live.remove(hostId) }
            return fail(pulled.failure)
        }

        syncPushAfterConnect(opened)

        store.updateStatus(hostId, HostStatus.Reachable, nowEpochMs())
        setLink(hostId, HostLink.Online(connected.address, openedName))
        return ok(opened)
    }

    private suspend fun pullSessionState(hostId: String, session: LuviaSession): Outcome<Unit> =
        coroutineScope {
            val snapshotDeferred = async { session.snapshot() }
            val tasksDeferred = async { session.listTasks() }
            val agentsDeferred = async { session.listAgents() }
            val snapshot =
                when (val result = snapshotDeferred.await()) {
                    is Outcome.Err -> return@coroutineScope fail(result.failure)
                    is Outcome.Ok -> result.value
                }
            val tasks =
                when (val result = tasksDeferred.await()) {
                    is Outcome.Err -> emptyList()
                    is Outcome.Ok -> result.value
                }
            val agents =
                when (val result = agentsDeferred.await()) {
                    is Outcome.Err -> snapshot.agents
                    is Outcome.Ok -> result.value
                }
            mutex.withLock {
                live[hostId]?.snapshot = snapshot
                live[hostId]?.tasks = tasks
                live[hostId]?.agents = agents
            }
            store.rememberTopology(
                hostId,
                CachedTopology(
                    sessionName = snapshot.sessionName,
                    serverGeneration = snapshot.serverGeneration,
                    eventSequence = snapshot.eventSequence,
                    workspaces = snapshot.workspaces,
                    agents = agents,
                    tasks = tasks,
                    capturedAtEpochMs = nowEpochMs(),
                ),
            )
            mutex.withLock { publishLocked(catalogHosts) }
            ok(Unit)
        }

    private suspend fun handleLiveUpdate(
        hostId: String,
        session: LuviaSession,
        update: tech.asahiart.luvia.internal.LiveUpdate,
    ) {
        when (update) {
            is tech.asahiart.luvia.internal.LiveUpdate.Snapshot -> {
                mutex.withLock {
                    live[hostId]?.snapshot = update.snapshot
                    live[hostId]?.agents = update.snapshot.agents
                }
                val tasks =
                    when (val result = session.listTasks()) {
                        is Outcome.Ok -> result.value
                        is Outcome.Err -> mutex.withLock { live[hostId]?.tasks } ?: emptyList()
                    }
                mutex.withLock { live[hostId]?.tasks = tasks }
                rememberLiveTopology(hostId)
            }
            is tech.asahiart.luvia.internal.LiveUpdate.Event -> {
                applyBusEvent(hostId, session, update.bus)
            }
            is tech.asahiart.luvia.internal.LiveUpdate.Resyncing -> {
                pullSessionState(hostId, session)
            }
            is tech.asahiart.luvia.internal.LiveUpdate.Failed -> {
                mutex.withLock { live[hostId]?.lastFailure = update.failure }
                if (!isRetryable(update.failure)) {
                    setLink(hostId, HostLink.Failed(update.failure))
                }
            }
        }
    }

    private suspend fun applyBusEvent(hostId: String, session: LuviaSession, event: BusEvent) {
        val current = mutex.withLock { live[hostId] } ?: return
        val projection =
            tech.asahiart.luvia.internal.projectBusEvent(
                event = event,
                snapshot = current.snapshot,
                agents = current.agents,
                tasks = current.tasks,
            )
        when (event) {
            is BusEvent.Ignored -> return
            is BusEvent.ResyncRequired -> {
                pullSessionState(hostId, session)
                return
            }
            is BusEvent.AutomationChanged -> {
                busEventsFlow.tryEmit(hostId to event)
                return
            }
            is BusEvent.AgentStatusChanged,
            is BusEvent.TaskPayload,
            is BusEvent.PaneChanged,
            is BusEvent.PaneRenamed,
            is BusEvent.AgentHook,
            is BusEvent.LeaseChanged,
            is BusEvent.WorkspaceChanged,
            is BusEvent.TerminalChanged,
            -> Unit
        }
        if (projection.resync || projection.pullSession) {
            pullSessionState(hostId, session)
            return
        }

        var tasks = projection.tasks
        if (projection.relistTasks) {
            tasks =
                when (val result = session.listTasks()) {
                    is Outcome.Ok -> result.value
                    is Outcome.Err -> return
                }
        }
        mutex.withLock {
            live[hostId]?.snapshot = projection.snapshot
            live[hostId]?.agents = projection.agents
            live[hostId]?.tasks = tasks
        }
        if (projection.terminalOnly) return
        rememberLiveTopology(hostId)
    }

    private suspend fun rememberLiveTopology(hostId: String) {
        val snap = mutex.withLock { live[hostId]?.snapshot } ?: return
        val agents = mutex.withLock { live[hostId]?.agents } ?: snap.agents
        val tasks = mutex.withLock { live[hostId]?.tasks } ?: emptyList()
        store.rememberTopology(
            hostId,
            CachedTopology(
                sessionName = snap.sessionName,
                serverGeneration = snap.serverGeneration,
                eventSequence = snap.eventSequence,
                workspaces = snap.workspaces,
                agents = agents,
                tasks = tasks,
                capturedAtEpochMs = nowEpochMs(),
            ),
        )
        mutex.withLock { publishLocked(catalogHosts) }
    }

    private suspend fun setLink(hostId: String, link: HostLink) {
        mutex.withLock {
            links[hostId] = link
            live[hostId]?.forcedLink = link
            publishLocked(catalogHosts)
        }
    }

    private fun publishLocked(profiles: List<HostProfile>) {
        hostsState.value =
            profiles.map { profile ->
                val session = live[profile.id]
                val topology = profile.topology
                // session.snapshot omits agents on 0.13.4; the agent.list result kept
                // in LiveHost.agents is the authoritative list for the runtime.
                val snapshot =
                    session?.snapshot?.let { snap ->
                        if (session.agents.isNotEmpty()) snap.copy(agents = session.agents) else snap
                    } ?: topology?.toSnapshot()
                val tasks = session?.tasks ?: topology?.tasks ?: emptyList()
                val link = links[profile.id]
                    ?: session?.forcedLink
                    ?: if (session != null) {
                        HostLink.Online(session.address, session.sessionName)
                    } else {
                        HostLink.Idle
                    }
                val freshness =
                    when (link) {
                        is HostLink.Online -> ConnectionFreshness.Live
                        is HostLink.Connecting ->
                            if (topology != null) ConnectionFreshness.Stale else ConnectionFreshness.Offline
                        is HostLink.Failed -> ConnectionFreshness.Offline
                        is HostLink.Idle ->
                            if (topology != null) ConnectionFreshness.Stale else ConnectionFreshness.Offline
                    }
                HostRuntime(
                    profile = profile,
                    link = link,
                    snapshot = snapshot,
                    tasks = tasks,
                    freshness = freshness,
                    backend = session?.backend ?: "luvus",
                )

            }
    }

    private class LiveHost(
        val connection: HostConnection,
        val session: LuviaSession,
        val address: String,
        val sessionName: String?,
        val backend: String = "luvus",
    ) {

        var snapshot: SessionSnapshot? = null
        var tasks: List<TaskSummary> = emptyList()
        var agents: List<AgentSummary> = emptyList()
        var forcedLink: HostLink? = null
        var lastFailure: Failure? = null

        fun close() {
            session.close()
            connection.host.client.close()
        }
    }

    private suspend fun pushToLiveHosts(registration: PushRegistration) {
        val sessions = mutex.withLock { live.values.map { it.session } }
        for (session in sessions) {
            if (!session.supports(UhpMethods.PUSH_REGISTER)) continue
            when (session.registerPush(registration)) {
                is Outcome.Ok -> Unit
                is Outcome.Err -> Unit
            }
        }
    }

    private suspend fun unregisterLiveHosts() {
        val sessions = mutex.withLock { live.values.map { it.session } }
        for (session in sessions) {
            if (!session.supports(UhpMethods.PUSH_UNREGISTER)) continue
            when (session.unregisterPush()) {
                is Outcome.Ok -> Unit
                is Outcome.Err -> Unit
            }
        }
    }

    private suspend fun syncPushAfterConnect(session: LuviaSession) {
        if (!pushEnabled) return
        val registration = pushRegistrationState.value ?: return
        if (!session.supports(UhpMethods.PUSH_REGISTER)) return
        when (session.registerPush(registration)) {
            is Outcome.Ok -> Unit
            is Outcome.Err -> Unit
        }
    }

}

private data class Reachability(
    val addresses: List<String>,
    val sshPort: Int,
    val username: String,
)

private fun parseReachability(
    addresses: List<String>,
    sshPort: Int?,
    username: String?,
    fallbackAddresses: List<String>,
    fallbackPort: Int,
    fallbackUser: String,
): Outcome<Reachability> {
    val resolvedAddresses =
        addresses
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .ifEmpty {
                fallbackAddresses.map { it.trim() }.filter { it.isNotEmpty() }
            }
    val resolvedPort = sshPort ?: fallbackPort
    val resolvedUser = username?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackUser.trim()
    if (resolvedAddresses.isEmpty()) {
        return fail(Failure.Transport("Enter a host address."))
    }
    if (resolvedPort !in 1..65535) {
        return fail(Failure.Transport("SSH port must be between 1 and 65535."))
    }
    if (resolvedUser.isEmpty()) {
        return fail(Failure.Transport("Enter a username."))
    }
    return ok(Reachability(resolvedAddresses, resolvedPort, resolvedUser))
}


private fun CachedTopology.toSnapshot(): SessionSnapshot =
    SessionSnapshot(
        sessionName = sessionName ?: "",
        serverGeneration = serverGeneration ?: "",
        eventSequence = eventSequence,
        workspaces = workspaces,
        panes = emptyList(),
        agents = agents,
    )

private fun isRetryable(failure: Failure): Boolean {
    return when (failure) {
        is Failure.Forbidden -> false
        is Failure.NotFound -> false
        is Failure.ProtocolError -> !failure.reason.contains("Re-pair")
        is Failure.Transport -> !failure.reason.contains("host key mismatch")
        else -> true
    }
}

private fun isUnknownSession(failure: Failure): Boolean =
    when (failure) {
        is Failure.ProtocolError -> failure.reason.contains("unknown_session")
        is Failure.Remote -> failure.code == "unknown_session"
        is Failure.Bridge -> failure.reason.contains("unknown_session")
        else -> false
    }


private fun backoffMs(attempt: Int): Long {
    val shift = attempt.coerceAtMost(6)
    val exp = (500L shl shift).coerceAtMost(30_000L)
    val jitter = kotlin.random.Random.nextLong(0, (exp / 4) + 1)
    return exp + jitter
}

@OptIn(ExperimentalTime::class)
private fun nowEpochMs(): Long = Clock.System.now().toEpochMilliseconds()
