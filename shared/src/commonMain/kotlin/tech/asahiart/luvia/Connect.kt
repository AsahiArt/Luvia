package tech.asahiart.luvia

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import tech.asahiart.luvia.internal.SshChannelFactory
import tech.asahiart.luvia.transport.SshConnection
import tech.asahiart.luvia.transport.TransportException
import tech.asahiart.luvia.transport.generateDeviceKey
import tech.asahiart.luvia.transport.importDeviceKey

public data class HostEndpoint(
    public val address: String,
    public val port: Int,
    public val username: String,
    public val hostKeyFingerprints: List<String>,
    public val hostId: String = "",
    public val deviceId: String = "",
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
        val connection = openSession(endpoint, privateKeyOpenssh)
        ok(ConnectedHost(LuviaClient(SshChannelFactory(connection)), connection))
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        fail(mapTransport(error))
    }
}

/**
 * SSH publickey auth plus the bridge signature proof. The returned host keeps
 * the session; the probe channel is closed after Prove so a loser can drop it.
 */
internal suspend fun connectAndProve(
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
    var connection: SshConnection? = null
    return try {
        connection = openSession(endpoint, privateKeyOpenssh)
        val channel = connection.openAuthenticatedBridge()
        try {
            channel.shutdown()
        } catch (_: Exception) {
        }
        val host = ConnectedHost(LuviaClient(SshChannelFactory(connection)), connection)
        connection = null
        ok(host)
    } catch (error: CancellationException) {
        connection?.let { closeQuietly(it) }
        throw error
    } catch (error: Exception) {
        connection?.let { closeQuietly(it) }
        fail(mapTransport(error))
    }
}

public suspend fun connectToProfile(
    profile: HostProfile,
    credential: DeviceCredential,
): ProfileConnectResult = raceConnect(profile, credential)

private suspend fun openSession(
    endpoint: HostEndpoint,
    privateKeyOpenssh: String,
): SshConnection =
    SshConnection.connect(
        endpoint.address,
        endpoint.port.toUShort(),
        endpoint.username,
        endpoint.hostKeyFingerprints,
        privateKeyOpenssh,
        endpoint.hostId,
        endpoint.deviceId,
    )

internal fun mapTransport(error: Throwable): Failure {
    var current: Throwable? = error
    while (current != null) {
        if (current is TransportException.HostKeyMismatch) {
            return Failure.HostKeyMismatch(current.expected, current.actual)
        }
        current = current.cause
    }
    return Failure.Transport(error.message ?: "ssh connection failed")
}

private suspend fun closeQuietly(connection: SshConnection) {
    withContext(NonCancellable) {
        try {
            connection.shutdown()
        } catch (_: Exception) {
        }
    }
}

public fun parseConnectionAddresses(raw: String): List<String> {
    val seen = LinkedHashSet<String>()
    for (part in raw.split(',', '\n', ';')) {
        val address = part.trim()
        if (address.isNotEmpty()) seen += address
    }
    return seen.toList()
}


/**
 * True for addresses that only route over a Tailscale tailnet: the CGNAT range
 * 100.64.0.0/10, the ULA prefix fd7a:115c:a1e0::/48, or a MagicDNS `*.ts.net` name.
 * Used to label reachability in the UI; never affects pinning.
 */
public fun isTailnetAddress(address: String): Boolean {
    val trimmed = address.trim().removePrefix("[").removeSuffix("]")
    if (trimmed.endsWith(".ts.net", ignoreCase = true)) return true
    if (!isLiteralIp(trimmed)) return false
    if (trimmed.contains(':')) {
        return trimmed.lowercase().startsWith("fd7a:115c:a1e0:")
    }
    val parts = trimmed.split('.')
    val a = parts.getOrNull(0)?.toIntOrNull() ?: return false
    val b = parts.getOrNull(1)?.toIntOrNull() ?: return false
    return a == 100 && b in 64..127
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

