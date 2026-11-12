package tech.asahiart.luvia

import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tech.asahiart.luvia.transport.resolveCandidate

internal const val CANDIDATE_STAGGER_MS: Long = 250L
internal const val DNS_TIMEOUT_MS: Long = 1_500L
internal const val TCP_TIMEOUT_MS: Long = 3_000L
internal const val PROVE_BUDGET_MS: Long = 10_000L

/**
 * One candidate's DNS, TCP, and Prove steps. Tests supply a fake; production
 * dials through `luvia-transport`.
 */
internal interface CandidateDial<T> {
    suspend fun dns(address: String): Outcome<Unit>

    suspend fun tcp(address: String): Outcome<Unit>

    suspend fun prove(address: String): Outcome<T>

    suspend fun close(value: T)
}

/**
 * Candidates, in order, deduped: lastGood, `name.local`, LAN hints, tailnet
 * FQDN, then 100.x (and other tailnet IP) hints.
 */
internal fun candidateAddresses(profile: HostProfile): List<String> {
    val seen = LinkedHashSet<String>()
    val out = ArrayList<String>()
    fun add(raw: String?) {
        val address = raw?.trim().orEmpty()
        if (address.isEmpty()) return
        if (!seen.add(address.lowercase())) return
        out += address
    }
    add(profile.lastGood?.trim()?.ifEmpty { null } ?: profile.lastConnectedAddress)
    add(mdnsCandidate(profile))
    val hints = profile.hints.ifEmpty { profile.addresses }
    val lan = ArrayList<String>()
    val tailnetNames = ArrayList<String>()
    val tailnetIps = ArrayList<String>()
    for (hint in hints) {
        val address = hint.trim()
        if (address.isEmpty()) continue
        when {
            isTailnetIpHint(address) -> tailnetIps += address
            isTailnetAddress(address) -> tailnetNames += address
            else -> lan += address
        }
    }
    lan.forEach(::add)
    add(profile.tailnetFqdn)
    tailnetNames.forEach(::add)
    tailnetIps.forEach(::add)
    return out
}

internal suspend fun raceConnect(
    profile: HostProfile,
    credential: DeviceCredential,
    dial: CandidateDial<ConnectedHost>? = null,
    log: (String) -> Unit = { line -> println("luvia-connect: $line") },
): ProfileConnectResult {
    val sample = profile.raceEndpoint("candidate")
    if (sample.port !in 1..65535) {
        return ProfileConnectResult(fail(Failure.Transport("invalid SSH port")))
    }
    if (sample.username.isBlank() || sample.hostKeyFingerprints.isEmpty()) {
        return ProfileConnectResult(fail(Failure.Transport("incomplete host endpoint")))
    }
    val addresses = candidateAddresses(profile)
    if (addresses.isEmpty()) {
        return ProfileConnectResult(fail(Failure.Transport("incomplete host endpoint")))
    }
    val used = dial ?: SshCandidateDial(credential) { address -> profile.raceEndpoint(address) }
    return when (val raced = raceCandidates(addresses, used, log)) {
        is Outcome.Ok -> ProfileConnectResult(ok(HostConnection(raced.value.second, raced.value.first)))
        is Outcome.Err -> ProfileConnectResult(fail(raced.failure))
    }
}

internal suspend fun <T> raceCandidates(
    addresses: List<String>,
    dial: CandidateDial<T>,
    log: (String) -> Unit = { line -> println("luvia-connect: $line") },
): Outcome<Pair<String, T>> {
    if (addresses.isEmpty()) {
        return fail(Failure.Transport("incomplete host endpoint"))
    }
    return coroutineScope {
        val winner = CompletableDeferred<Pair<String, T>>()
        val security = CompletableDeferred<Failure.HostKeyMismatch>()
        val state = Mutex()
        val loserLines = ArrayList<String>()
        var lastFailure: Failure = Failure.Transport("incomplete host endpoint")

        suspend fun addLoser(address: String, reason: String) {
            state.withLock { loserLines += "loser $address: $reason" }
        }

        suspend fun noteFailure(failure: Failure) {
            state.withLock { lastFailure = failure }
        }

        val jobs = ArrayList<Job>(addresses.size)
        for ((index, address) in addresses.withIndex()) {
            jobs += launch {
                try {
                    delay(index * CANDIDATE_STAGGER_MS)
                    when (val outcome = runCandidate(address, dial)) {
                        is Outcome.Ok -> {
                            if (!winner.complete(address to outcome.value)) {
                                dial.close(outcome.value)
                            }
                        }
                        is Outcome.Err -> {
                            val failure = outcome.failure
                            if (failure is Failure.HostKeyMismatch) {
                                addLoser(address, failure.raceReason())
                                security.complete(failure)
                            } else {
                                noteFailure(failure)
                                addLoser(address, failure.raceReason())
                            }
                        }
                    }
                } catch (error: CancellationException) {
                    val wonThis = winner.isCompleted && winner.getCompleted().first == address
                    if (!wonThis) {
                        withContext(NonCancellable) { addLoser(address, "cancelled") }
                    }
                    throw error
                }
            }
        }

        val allDone: Deferred<Unit> = async {
            jobs.forEach { it.join() }
        }
        select<Unit> {
            winner.onAwait { }
            security.onAwait { }
            allDone.onAwait { }
        }
        coroutineContext.cancelChildren()
        jobs.forEach { it.join() }

        val mismatch = if (security.isCompleted) security.getCompleted() else null
        if (mismatch != null) {
            if (winner.isCompleted) dial.close(winner.getCompleted().second)
            state.withLock { loserLines.toList() }.forEach(log)
            return@coroutineScope fail(mismatch)
        }
        if (winner.isCompleted) {
            val (address, value) = winner.getCompleted()
            log("winner $address")
            state.withLock { loserLines.toList() }.forEach(log)
            return@coroutineScope ok(address to value)
        }
        val failure = state.withLock { lastFailure }
        state.withLock { loserLines.toList() }.forEach(log)
        fail(failure)
    }
}

private suspend fun <T> runCandidate(address: String, dial: CandidateDial<T>): Outcome<T> {
    val dns = withTimeoutOrNull(DNS_TIMEOUT_MS) { dial.dns(address) }
        ?: return fail(Failure.Transport("dns timeout"))
    if (dns is Outcome.Err) return fail(dns.failure)
    val proved =
        withTimeoutOrNull(PROVE_BUDGET_MS) {
            val tcp = withTimeoutOrNull(TCP_TIMEOUT_MS) { dial.tcp(address) }
                ?: return@withTimeoutOrNull fail(Failure.Transport("connect timeout"))
            if (tcp is Outcome.Err) return@withTimeoutOrNull fail(tcp.failure)
            dial.prove(address)
        }
    return proved ?: fail(Failure.Transport("candidate timeout"))
}

internal class SshCandidateDial(
    private val credential: DeviceCredential,
    private val endpointOf: (String) -> HostEndpoint,
) : CandidateDial<ConnectedHost> {
    private val resolved = HashMap<String, String>()
    private val gate = Mutex()

    override suspend fun dns(address: String): Outcome<Unit> {
        val endpoint = endpointOf(address)
        return try {
            val ip = resolveCandidate(address, endpoint.port.toUShort())
            gate.withLock { resolved[address] = ip }
            ok(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            fail(mapTransport(error))
        }
    }

    override suspend fun tcp(address: String): Outcome<Unit> = ok(Unit)

    override suspend fun prove(address: String): Outcome<ConnectedHost> {
        val endpoint = endpointOf(address)
        val ip = gate.withLock { resolved[address] } ?: address
        return connectAndProve(endpoint.copy(address = ip), credential)
    }

    override suspend fun close(value: ConnectedHost) {
        value.close()
    }
}

internal fun HostProfile.raceEndpoint(address: String): HostEndpoint {
    val resolvedPort = if (port in 1..65535) port else sshPort
    val resolvedUser = user.trim().ifEmpty { username.trim() }
    val keys = hostKeys.ifEmpty { hostKeyFingerprints }
    return HostEndpoint(
        address = address,
        port = resolvedPort,
        username = resolvedUser,
        hostKeyFingerprints = keys,
        hostId = hostId,
        deviceId = id,
    )
}

internal fun Failure.raceReason(): String =
    when (this) {
        is Failure.HostKeyMismatch -> "host key mismatch: expected $expected, actual $actual"
        is Failure.Transport -> reason
        else -> userMessage()
    }

private fun mdnsCandidate(profile: HostProfile): String? {
    val raw = profile.name.trim().ifEmpty { profile.alias.trim() }
    if (raw.isEmpty() || raw.equals("localhost", ignoreCase = true)) return null
    if (isLiteralIp(raw)) return null
    if (raw.endsWith(".local", ignoreCase = true)) return raw
    if (raw.contains('.') || raw.contains(':')) return null
    return "$raw.local"
}

private fun isTailnetIpHint(address: String): Boolean {
    val trimmed = address.trim().removePrefix("[").removeSuffix("]")
    return isLiteralIp(trimmed) && isTailnetAddress(trimmed)
}
