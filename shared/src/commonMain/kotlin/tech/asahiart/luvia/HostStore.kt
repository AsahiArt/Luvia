package tech.asahiart.luvia

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import tech.asahiart.luvia.internal.DataStoreHostStore

/**
 * One catalog entry per computer. [HostProfile.hostId] is the key;
 * [HostProfile.id] is the current device credential and is replaced on re-pair.
 */
public interface HostStore {
    public val catalog: Flow<HostCatalog>

    public suspend fun current(): HostCatalog

    public suspend fun upsert(host: HostProfile)

    public suspend fun remove(hostId: String)

    public suspend fun rememberTopology(hostId: String, topology: CachedTopology)

    public suspend fun setLastConnectedAddress(hostId: String, address: String)

    public suspend fun updateStatus(hostId: String, status: HostStatus, atEpochMs: Long)
}

public fun HostStore(filePath: String): HostStore = DataStoreHostStore(filePath, scope = null)

internal fun HostStore(filePath: String, scope: CoroutineScope): HostStore = DataStoreHostStore(filePath, scope)
