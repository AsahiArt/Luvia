package tech.asahiart.luvia

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import tech.asahiart.luvia.internal.DataStoreHostStore

public interface HostStore {
    public val catalog: Flow<HostCatalog>

    public suspend fun current(): HostCatalog

    public suspend fun upsert(host: HostProfile)

    public suspend fun remove(id: String)

    public suspend fun rememberTopology(hostId: String, topology: CachedTopology)
}

public fun HostStore(filePath: String): HostStore = DataStoreHostStore(filePath, scope = null)

internal fun HostStore(filePath: String, scope: CoroutineScope): HostStore = DataStoreHostStore(filePath, scope)
