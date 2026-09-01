package tech.asahiart.luvia.internal

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.BufferedSink
import okio.BufferedSource
import okio.Path.Companion.toPath
import tech.asahiart.luvia.AgentStatus
import tech.asahiart.luvia.AgentSummary
import tech.asahiart.luvia.CachedTopology
import tech.asahiart.luvia.HostCatalog
import tech.asahiart.luvia.HostProfile
import tech.asahiart.luvia.HostRole
import tech.asahiart.luvia.HostStatus
import tech.asahiart.luvia.HostStore
import tech.asahiart.luvia.TaskSummary
import tech.asahiart.luvia.WorkspaceSummary

private val cacheJson: Json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
        explicitNulls = false
    }

internal const val HOST_CATALOG_VERSION: Int = 2

@Serializable
internal data class HostCatalogFile(
    val version: Int = 1,
    val hosts: List<HostFile> = emptyList(),
    val updatedEpochMs: Long = 0,
)

@Serializable
internal data class HostFile(
    val id: String,
    val alias: String,
    val addresses: List<String> = emptyList(),
    val address: String? = null,
    val sshPort: Int = 22,
    val username: String? = null,
    val hostKeyFingerprints: List<String> = emptyList(),
    val hostKeySha256: String? = null,
    val role: String? = null,
    val lastStatus: String = HostStatus.Unknown.name,
    val lastUpdatedEpochMs: Long = 0,
    val lastConnectedAddress: String? = null,
    val topology: TopologyFile? = null,
)

@Serializable
internal data class TopologyFile(
    val sessionName: String? = null,
    val serverGeneration: String? = null,
    val eventSequence: Long = 0,
    val workspaces: List<WorkspaceFile> = emptyList(),
    val agents: List<AgentFile> = emptyList(),
    val tasks: List<TaskFile> = emptyList(),
    val capturedAtEpochMs: Long = 0,
)

@Serializable
internal data class WorkspaceFile(
    val index: Int,
    val name: String,
    val pinned: Boolean = false,
    val active: Boolean = false,
    val tabCount: Int = 0,
    val branch: String? = null,
    val cwd: String? = null,
)

@Serializable
internal data class AgentFile(
    val paneId: String,
    val name: String? = null,
    val status: String = AgentStatus.Unknown.name,
    val agent: String? = null,
    val authority: String? = null,
    val stateSource: String? = null,
    val session: String? = null,
    val focused: Boolean = false,
    val workspace: String? = null,
    val workspaceName: String? = null,
    val tab: String? = null,
    val cwd: String? = null,
    val branch: String? = null,
    val project: String? = null,
    val repo: String? = null,
    val worktree: Boolean? = null,
)

@Serializable
internal data class TaskFile(
    val id: String,
    val title: String,
    val status: String,
)

internal class DataStoreHostStore(
    filePath: String,
    scope: CoroutineScope?,
) : HostStore {
    private val path = filePath.toPath()
    private val dataStore: DataStore<HostCatalogFile>

    init {
        path.parent?.let { parent ->
            platformFileSystem.createDirectories(parent)
        }
        val storage =
            OkioStorage(
                fileSystem = platformFileSystem,
                serializer = HostCatalogOkioSerializer,
                producePath = { path },
            )
        dataStore =
            if (scope == null) {
                DataStoreFactory.create(storage = storage)
            } else {
                DataStoreFactory.create(storage = storage, scope = scope)
            }
    }

    override val catalog: Flow<HostCatalog> = dataStore.data.map { it.toPublic() }

    override suspend fun current(): HostCatalog = dataStore.data.first().toPublic()

    override suspend fun upsert(host: HostProfile) {
        val sanitized = host.sanitized()
        dataStore.updateData { current ->
            current.copy(
                version = HOST_CATALOG_VERSION,
                hosts = current.hosts.filterNot { it.id == sanitized.id } + sanitized.toFile(),
                updatedEpochMs = sanitized.lastUpdatedEpochMs,
            )
        }
    }

    override suspend fun remove(id: String) {
        dataStore.updateData { current ->
            current.copy(hosts = current.hosts.filterNot { it.id == id })
        }
    }

    override suspend fun rememberTopology(hostId: String, topology: CachedTopology) {
        dataStore.updateData { current ->
            val hosts =
                current.hosts.map { host ->
                    if (host.id == hostId) {
                        host.copy(topology = topology.toFile(), lastUpdatedEpochMs = topology.capturedAtEpochMs)
                    } else {
                        host
                    }
                }
            current.copy(version = HOST_CATALOG_VERSION, hosts = hosts, updatedEpochMs = topology.capturedAtEpochMs)
        }
    }

    override suspend fun setLastConnectedAddress(hostId: String, address: String) {
        dataStore.updateData { current ->
            val hosts =
                current.hosts.map { host ->
                    if (host.id == hostId) host.copy(lastConnectedAddress = address) else host
                }
            current.copy(version = HOST_CATALOG_VERSION, hosts = hosts)
        }
    }

    override suspend fun updateStatus(hostId: String, status: HostStatus, atEpochMs: Long) {
        dataStore.updateData { current ->
            val hosts =
                current.hosts.map { host ->
                    if (host.id == hostId) {
                        host.copy(lastStatus = status.name, lastUpdatedEpochMs = atEpochMs)
                    } else {
                        host
                    }
                }
            current.copy(version = HOST_CATALOG_VERSION, hosts = hosts, updatedEpochMs = atEpochMs)
        }
    }
}

internal object HostCatalogOkioSerializer : OkioSerializer<HostCatalogFile> {
    override val defaultValue: HostCatalogFile = HostCatalogFile()

    override suspend fun readFrom(source: BufferedSource): HostCatalogFile {
        val bytes = source.readByteArray()
        if (bytes.isEmpty()) return defaultValue
        val text =
            try {
                bytes.decodeToString(throwOnInvalidSequence = true)
            } catch (e: Exception) {
                throw CorruptionException("host cache is not UTF-8", e)
            }
        return try {
            cacheJson.decodeFromString(HostCatalogFile.serializer(), text).migrated()
        } catch (e: Exception) {
            throw CorruptionException("host cache is not valid JSON", e)
        }
    }

    override suspend fun writeTo(t: HostCatalogFile, sink: BufferedSink) {
        val text = cacheJson.encodeToString(HostCatalogFile.serializer(), t.migrated())
        sink.writeUtf8(text)
    }
}

internal fun HostProfile.sanitized(): HostProfile =
    copy(
        id = id,
        alias = alias,
        addresses = addresses,
        sshPort = sshPort,
        username = username,
        hostKeyFingerprints = hostKeyFingerprints,
        role = role,
        lastStatus = lastStatus,
        lastUpdatedEpochMs = lastUpdatedEpochMs,
        lastConnectedAddress = lastConnectedAddress,
        topology = topology?.copy(
            sessionName = topology.sessionName,
            serverGeneration = topology.serverGeneration,
            eventSequence = topology.eventSequence,
            workspaces = topology.workspaces,
            agents = topology.agents,
            tasks = topology.tasks,
            capturedAtEpochMs = topology.capturedAtEpochMs,
        ),
    )

internal fun HostCatalogFile.migrated(): HostCatalogFile =
    copy(
        version = HOST_CATALOG_VERSION,
        hosts = hosts.map { it.migrated() },
    )

internal fun HostFile.migrated(): HostFile {
    val migratedAddresses = addresses.ifEmpty { listOfNotNull(address) }
    val migratedFingerprints = hostKeyFingerprints.ifEmpty { listOfNotNull(hostKeySha256) }
    return copy(
        addresses = migratedAddresses,
        address = null,
        username = username ?: "",
        hostKeyFingerprints = migratedFingerprints,
        hostKeySha256 = null,
        role = hostRole(role).name,
    )
}

private fun HostCatalogFile.toPublic(): HostCatalog =
    HostCatalog(
        hosts = hosts.map { it.toPublic() },
        updatedEpochMs = updatedEpochMs,
    )

private fun HostFile.toPublic(): HostProfile {
    val migrated = migrated()
    return HostProfile(
        id = migrated.id,
        alias = migrated.alias,
        addresses = migrated.addresses,
        sshPort = migrated.sshPort,
        username = migrated.username ?: "",
        hostKeyFingerprints = migrated.hostKeyFingerprints,
        role = hostRole(migrated.role),
        lastStatus = hostStatus(migrated.lastStatus),
        lastUpdatedEpochMs = migrated.lastUpdatedEpochMs,
        lastConnectedAddress = migrated.lastConnectedAddress,
        topology = migrated.topology?.toPublic(),
    )
}

private fun HostProfile.toFile(): HostFile =
    HostFile(
        id = id,
        alias = alias,
        addresses = addresses,
        sshPort = sshPort,
        username = username,
        hostKeyFingerprints = hostKeyFingerprints,
        role = role.name,
        lastStatus = lastStatus.name,
        lastUpdatedEpochMs = lastUpdatedEpochMs,
        lastConnectedAddress = lastConnectedAddress,
        topology = topology?.toFile(),
    )

private fun TopologyFile.toPublic(): CachedTopology =
    CachedTopology(
        sessionName = sessionName,
        serverGeneration = serverGeneration,
        eventSequence = eventSequence,
        workspaces = workspaces.map { it.toPublic() },
        agents = agents.map { it.toPublic() },
        tasks = tasks.map { TaskSummary(it.id, it.title, it.status) },
        capturedAtEpochMs = capturedAtEpochMs,
    )

private fun CachedTopology.toFile(): TopologyFile =
    TopologyFile(
        sessionName = sessionName,
        serverGeneration = serverGeneration,
        eventSequence = eventSequence,
        workspaces = workspaces.map { it.toFile() },
        agents = agents.map { it.toFile() },
        tasks = tasks.map { TaskFile(it.id, it.title, it.status) },
        capturedAtEpochMs = capturedAtEpochMs,
    )

private fun WorkspaceFile.toPublic(): WorkspaceSummary =
    WorkspaceSummary(
        index = index,
        name = name,
        pinned = pinned,
        active = active,
        tabCount = tabCount,
        branch = branch,
        cwd = cwd,
    )

private fun WorkspaceSummary.toFile(): WorkspaceFile =
    WorkspaceFile(
        index = index,
        name = name,
        pinned = pinned,
        active = active,
        tabCount = tabCount,
        branch = branch,
        cwd = cwd,
    )

private fun AgentFile.toPublic(): AgentSummary =
    AgentSummary(
        paneId = paneId,
        name = name,
        status = agentStatus(status),
        agent = agent,
        authority = authority,
        stateSource = stateSource,
        session = session,
        focused = focused,
        workspace = workspace,
        workspaceName = workspaceName,
        tab = tab,
        cwd = cwd,
        branch = branch,
        project = project,
        repo = repo,
        worktree = worktree,
    )

private fun AgentSummary.toFile(): AgentFile =
    AgentFile(
        paneId = paneId,
        name = name,
        status = status.name,
        agent = agent,
        authority = authority,
        stateSource = stateSource,
        session = session,
        focused = focused,
        workspace = workspace,
        workspaceName = workspaceName,
        tab = tab,
        cwd = cwd,
        branch = branch,
        project = project,
        repo = repo,
        worktree = worktree,
    )

/**
 * Persisted as an [AgentStatus] name, but caches written before the typed
 * decoders landed hold the raw wire value, so fall back to the wire parser.
 */
private fun agentStatus(raw: String): AgentStatus =
    AgentStatus.entries.firstOrNull { it.name == raw } ?: parseAgentStatus(raw.lowercase())

/**
 * v1 caches predate roles, and those hosts were fully controllable, so an
 * absent role migrates to [HostRole.Controller] rather than silently demoting
 * a host the user already drives. A present but unrecognized role is a
 * corrupt or future value and falls back to least privilege.
 */
private fun hostRole(raw: String?): HostRole =
    when (raw) {
        null -> HostRole.Controller
        else -> HostRole.entries.firstOrNull { it.name == raw } ?: HostRole.Observer
    }

private fun hostStatus(raw: String): HostStatus =
    HostStatus.entries.firstOrNull { it.name == raw } ?: HostStatus.Unknown
