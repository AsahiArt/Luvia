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
): Outcome<HostConnection> {
    if (profile.sshPort !in 1..65535) {
        return fail(Failure.Transport("invalid SSH port"))
    }
    if (profile.username.isBlank() || profile.hostKeyFingerprints.isEmpty()) {
        return fail(Failure.Transport("incomplete host endpoint"))
    }
    val ordered = orderedAddresses(profile)
    if (ordered.isEmpty()) {
        return fail(Failure.Transport("incomplete host endpoint"))
    }
    var lastFailure: Failure? = null
    for (address in ordered) {
        if (address.isBlank()) continue
        when (
            val result = connectToHost(
                HostEndpoint(
                    address = address,
                    port = profile.sshPort,
                    username = profile.username,
                    hostKeyFingerprints = profile.hostKeyFingerprints,
                ),
                credential,
            )
        ) {
            is Outcome.Ok -> return ok(HostConnection(result.value, address))
            is Outcome.Err -> {
                lastFailure = result.failure
                if (!isRetryableTransportFailure(result.failure)) {
                    return fail(result.failure)
                }
            }
        }
    }
    return fail(lastFailure ?: Failure.Transport("incomplete host endpoint"))
}

private fun orderedAddresses(profile: HostProfile): List<String> {
    val last = profile.lastConnectedAddress
    if (last.isNullOrBlank() || last !in profile.addresses) {
        return profile.addresses
    }
    return listOf(last) + profile.addresses.filter { it != last }
}

private fun isRetryableTransportFailure(failure: Failure): Boolean {
    val reason = (failure as? Failure.Transport)?.reason ?: return false
    return !reason.contains("host key mismatch")
}
