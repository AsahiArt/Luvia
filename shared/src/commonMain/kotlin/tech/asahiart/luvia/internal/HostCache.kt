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
import okio.FileSystem
import okio.Path.Companion.toPath
import tech.asahiart.luvia.AgentStatus
import tech.asahiart.luvia.AgentSummary
import tech.asahiart.luvia.CachedTopology
import tech.asahiart.luvia.HostCatalog
import tech.asahiart.luvia.HostProfile
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
    val address: String,
    val sshPort: Int = 22,
    val username: String? = null,
    val hostKeySha256: String? = null,
    val lastStatus: String = HostStatus.Unknown.name,
    val lastUpdatedEpochMs: Long = 0,
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
)

@Serializable
internal data class AgentFile(
    val paneId: String,
    val name: String? = null,
    val status: String = AgentStatus.Unknown.name,
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
            FileSystem.SYSTEM.createDirectories(parent)
        }
        val storage =
            OkioStorage(
                fileSystem = FileSystem.SYSTEM,
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
                version = 1,
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
            current.copy(hosts = hosts, updatedEpochMs = topology.capturedAtEpochMs)
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
            cacheJson.decodeFromString(HostCatalogFile.serializer(), text)
        } catch (e: Exception) {
            throw CorruptionException("host cache is not valid JSON", e)
        }
    }

    override suspend fun writeTo(t: HostCatalogFile, sink: BufferedSink) {
        val text = cacheJson.encodeToString(HostCatalogFile.serializer(), t)
        sink.writeUtf8(text)
    }
}

internal fun HostProfile.sanitized(): HostProfile =
    copy(
        id = id,
        alias = alias,
        address = address,
        sshPort = sshPort,
        username = username,
        hostKeySha256 = hostKeySha256,
        lastStatus = lastStatus,
        lastUpdatedEpochMs = lastUpdatedEpochMs,
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

private fun HostCatalogFile.toPublic(): HostCatalog =
    HostCatalog(
        hosts = hosts.map { it.toPublic() },
        updatedEpochMs = updatedEpochMs,
    )

private fun HostFile.toPublic(): HostProfile =
    HostProfile(
        id = id,
        alias = alias,
        address = address,
        sshPort = sshPort,
        username = username,
        hostKeySha256 = hostKeySha256,
        lastStatus = hostStatus(lastStatus),
        lastUpdatedEpochMs = lastUpdatedEpochMs,
        topology = topology?.toPublic(),
    )

private fun HostProfile.toFile(): HostFile =
    HostFile(
        id = id,
        alias = alias,
        address = address,
        sshPort = sshPort,
        username = username,
        hostKeySha256 = hostKeySha256,
        lastStatus = lastStatus.name,
        lastUpdatedEpochMs = lastUpdatedEpochMs,
        topology = topology?.toFile(),
    )

private fun TopologyFile.toPublic(): CachedTopology =
    CachedTopology(
        sessionName = sessionName,
        serverGeneration = serverGeneration,
        eventSequence = eventSequence,
        workspaces =
            workspaces.map {
                WorkspaceSummary(it.index, it.name, it.pinned, it.active, it.tabCount)
            },
        agents =
            agents.map {
                AgentSummary(it.paneId, it.name, parseAgentStatus(it.status.lowercase()))
            },
        tasks = tasks.map { TaskSummary(it.id, it.title, it.status) },
        capturedAtEpochMs = capturedAtEpochMs,
    )

private fun CachedTopology.toFile(): TopologyFile =
    TopologyFile(
        sessionName = sessionName,
        serverGeneration = serverGeneration,
        eventSequence = eventSequence,
        workspaces =
            workspaces.map {
                WorkspaceFile(it.index, it.name, it.pinned, it.active, it.tabCount)
            },
        agents =
            agents.map {
                AgentFile(it.paneId, it.name, it.status.name)
            },
        tasks = tasks.map { TaskFile(it.id, it.title, it.status) },
        capturedAtEpochMs = capturedAtEpochMs,
    )

private fun hostStatus(raw: String): HostStatus =
    HostStatus.entries.firstOrNull { it.name == raw } ?: HostStatus.Unknown
