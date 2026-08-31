package tech.asahiart.luvia

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tech.asahiart.luvia.internal.SshChannelFactory
import tech.asahiart.luvia.transport.SshConnection
import tech.asahiart.luvia.transport.generateDeviceKey
import tech.asahiart.luvia.transport.importDeviceKey

public data class HostEndpoint(
    public val address: String,
    public val port: Int,
    public val username: String,
    public val hostKeySha256: String,
)

public data class DevicePublicIdentity(
    public val authorizedKeys: String,
    public val fingerprint: String,
)

public data class GeneratedDeviceKey(
    public val identity: DevicePublicIdentity,
    public val privateKeyOpenssh: String,
)

public class ConnectedHost internal constructor(
    public val client: LuviaClient,
    private val connection: SshConnection,
) {
    public fun close() {
        client.close()
        closer.launch {
            runCatching { connection.shutdown() }
        }
    }

    private companion object {
        val closer = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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
    privateKeyOpenssh: String,
): Outcome<ConnectedHost> {
    if (endpoint.port !in 1..65535) {
        return fail(Failure.Transport("invalid SSH port"))
    }
    if (endpoint.username.isBlank() || endpoint.address.isBlank() || endpoint.hostKeySha256.isBlank()) {
        return fail(Failure.Transport("incomplete host endpoint"))
    }
    return try {
        val connection = SshConnection.connect(
            endpoint.address,
            endpoint.port.toUShort(),
            endpoint.username,
            endpoint.hostKeySha256,
            privateKeyOpenssh,
        )
        ok(ConnectedHost(LuviaClient(SshChannelFactory(connection)), connection))
    } catch (error: Exception) {
        fail(Failure.Transport(error.message ?: "ssh connection failed"))
    }
}
