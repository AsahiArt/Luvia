package tech.asahiart.luvia

import tech.asahiart.luvia.internal.SshChannelFactory
import tech.asahiart.luvia.transport.SshConnection
import tech.asahiart.luvia.transport.generateDeviceKey
import tech.asahiart.luvia.transport.importDeviceKey

public data class HostEndpoint(
    public val address: String,
    public val port: Int,
    public val username: String,
    public val hostKeyFingerprints: List<String>,
)

public data class DevicePublicIdentity(
    public val authorizedKeys: String,
    public val fingerprint: String,
)

public data class GeneratedDeviceKey(
    public val identity: DevicePublicIdentity,
    public val privateKeyOpenssh: String,
)

public data class HostConnection(
    public val host: ConnectedHost,
    public val address: String,
)

public data class ProfileConnectResult(
    public val outcome: Outcome<HostConnection>,
    public val deadLiteralAddresses: List<String> = emptyList(),
)

public class ConnectedHost internal constructor(
    public val client: LuviaClient,
    private val connection: SshConnection,
) {
    public suspend fun close() {
        client.close()
        runCatching { connection.shutdown() }
    }
}

public object DeviceKeys {
    public fun generate(): Outcome<GeneratedDeviceKey> =
        try {
            val key = generateDeviceKey()
            ok(
                GeneratedDeviceKey(
                    identity = DevicePublicIdentity(
                        authorizedKeys = key.publicKey.authorizedKeys,
                        fingerprint = key.publicKey.fingerprint,
                    ),
                    privateKeyOpenssh = key.privateKeyOpenssh,
                ),
            )
        } catch (error: Exception) {
            fail(Failure.Transport(error.message ?: "invalid device key"))
        }

    public fun inspect(privateKeyOpenssh: String): Outcome<DevicePublicIdentity> =
        try {
            val publicKey = importDeviceKey(privateKeyOpenssh)
            ok(
                DevicePublicIdentity(
                    authorizedKeys = publicKey.authorizedKeys,
                    fingerprint = publicKey.fingerprint,
                ),
            )
        } catch (error: Exception) {
            fail(Failure.Transport(error.message ?: "invalid device key"))
        }
}

public suspend fun connectToHost(
    endpoint: HostEndpoint,
    credential: DeviceCredential,
): Outcome<ConnectedHost> {
    if (endpoint.port !in 1..65535) {
        return fail(Failure.Transport("invalid SSH port"))
    }
    if (endpoint.username.isBlank() ||
        endpoint.address.isBlank() ||
        endpoint.hostKeyFingerprints.isEmpty()
    ) {
        return fail(Failure.Transport("incomplete host endpoint"))
    }
    val privateKeyOpenssh =
        when (credential) {
            is DeviceCredential.SoftwareKey -> credential.privateKeyOpenssh
        }
    return try {
        val connection = SshConnection.connect(
            endpoint.address,
            endpoint.port.toUShort(),
            endpoint.username,
            endpoint.hostKeyFingerprints,
            privateKeyOpenssh,
        )
        ok(ConnectedHost(LuviaClient(SshChannelFactory(connection)), connection))
    } catch (error: Exception) {
        fail(Failure.Transport(error.message ?: "ssh connection failed"))
    }
}

public suspend fun connectToProfile(
    profile: HostProfile,
    credential: DeviceCredential,
): ProfileConnectResult = connectToProfile(profile, credential, ::connectToHost)

internal suspend fun connectToProfile(
    profile: HostProfile,
    credential: DeviceCredential,
    attempt: suspend (HostEndpoint, DeviceCredential) -> Outcome<ConnectedHost>,
): ProfileConnectResult {
    if (profile.sshPort !in 1..65535) {
        return ProfileConnectResult(fail(Failure.Transport("invalid SSH port")))
    }
    if (profile.username.isBlank() || profile.hostKeyFingerprints.isEmpty()) {
        return ProfileConnectResult(fail(Failure.Transport("incomplete host endpoint")))
    }
    val ordered = orderedAddresses(profile)
    if (ordered.isEmpty()) {
        return ProfileConnectResult(fail(Failure.Transport("incomplete host endpoint")))
    }
    val dead = ArrayList<String>()
    var lastFailure: Failure? = null
    for (address in ordered) {
        if (address.isBlank()) continue
        when (
            val result =
                attempt(
                    HostEndpoint(
                        address = address,
                        port = profile.sshPort,
                        username = profile.username,
                        hostKeyFingerprints = profile.hostKeyFingerprints,
                    ),
                    credential,
                )
        ) {
            is Outcome.Ok ->
                return ProfileConnectResult(ok(HostConnection(result.value, address)), dead)
            is Outcome.Err -> {
                lastFailure = result.failure
                if (isLiteralIp(address) && isDeadLiteralFailure(result.failure)) {
                    dead += address
                }
                if (!isRetryableTransportFailure(result.failure)) {
                    return ProfileConnectResult(fail(result.failure), dead)
                }
            }
        }
    }
    return ProfileConnectResult(
        fail(lastFailure ?: Failure.Transport("incomplete host endpoint")),
        dead,
    )
}

public fun parseConnectionAddresses(raw: String): List<String> {
    val seen = LinkedHashSet<String>()
    for (part in raw.split(',', '\n', ';')) {
        val address = part.trim()
        if (address.isNotEmpty()) seen += address
    }
    return seen.toList()
}


internal fun orderedAddresses(profile: HostProfile): List<String> {
    val seen = LinkedHashSet<String>()
    val literals = ArrayList<String>()
    val names = ArrayList<String>()
    for (raw in profile.addresses) {
        val address = raw.trim()
        if (address.isEmpty() || !seen.add(address)) continue
        if (isLiteralIp(address)) literals += address else names += address
    }
    val last = profile.lastConnectedAddress?.trim().orEmpty()
    if (isLiteralIp(last)) prefer(literals, last) else prefer(names, last)
    return expandUnqualifiedHostnames(literals + names)
}

internal fun expandUnqualifiedHostnames(addresses: List<String>): List<String> {
    val seen = LinkedHashSet<String>()
    val out = ArrayList<String>()
    for (address in addresses) {
        if (seen.add(address)) out += address
        val local = mdnsLocalAlias(address) ?: continue
        if (seen.add(local)) out += local
    }
    return out
}

private fun mdnsLocalAlias(address: String): String? {
    if (isLiteralIp(address) || address.contains('.') || address.contains(':')) return null
    if (address.equals("localhost", ignoreCase = true)) return null
    return "$address.local"
}


internal fun isLiteralIp(address: String): Boolean {
    val trimmed = address.trim().removePrefix("[").removeSuffix("]")
    if (trimmed.contains(':')) {
        return trimmed.any { it == ':' } &&
            trimmed.all { it.isDigit() || it in "abcdefABCDEF:." }
    }
    val parts = trimmed.split('.')
    if (parts.size != 4) return false
    return parts.all { part ->
        val value = part.toIntOrNull() ?: return false
        value in 0..255
    }
}

internal fun isDeadLiteralFailure(failure: Failure): Boolean {
    val reason = (failure as? Failure.Transport)?.reason ?: return false
    return reason.contains("connect timeout") || reason.contains("no route")
}

internal fun isRetryableTransportFailure(failure: Failure): Boolean {
    val reason = (failure as? Failure.Transport)?.reason ?: return false
    return !reason.contains("host key mismatch")
}

private fun prefer(list: MutableList<String>, preferred: String) {
    if (preferred.isEmpty() || preferred !in list) return
    list.remove(preferred)
    list.add(0, preferred)
}
