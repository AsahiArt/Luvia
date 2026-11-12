package tech.asahiart.luvia.internal

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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
        ignoreUnknownKeys = false
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
    val hostId: String,
    val role: String,
    val lastStatus: String = HostStatus.Unknown.name,
    val lastUpdatedEpochMs: Long = 0,
    val topology: TopologyFile? = null,
    val name: String = "",
    val tailnetFqdn: String? = null,
    val user: String = "",
    val port: Int,
    val bridgePath: String,
    val hostKeys: Set<String> = emptySet(),
    val hints: List<String> = emptyList(),
    val lastGood: String? = null,
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
        val corruptionHandler = ReplaceFileCorruptionHandler { HostCatalogFile() }
        dataStore =
            if (scope == null) {
                DataStoreFactory.create(
                    storage = storage,
                    corruptionHandler = corruptionHandler,
                )
            } else {
                DataStoreFactory.create(
                    storage = storage,
                    corruptionHandler = corruptionHandler,
                    scope = scope,
                )
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
        val working = address.trim()
        if (working.isEmpty()) return
        dataStore.updateData { current ->
            val hosts =
                current.hosts.map { host ->
                    if (host.id != hostId) host else host.copy(lastGood = working)
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
            rejectInvalidHostCatalog(text)
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

private fun rejectInvalidHostCatalog(text: String) {
    val root = cacheJson.parseToJsonElement(text) as? JsonObject ?: return
    val hosts = root["hosts"] as? JsonArray ?: return
    for (element in hosts) {
        val host = element as? JsonObject
            ?: throw IllegalArgumentException("host file is not an object")
        if (!host.containsKey("role") || host["role"] == null || host["role"] is JsonNull) {
            throw IllegalArgumentException("host file is missing role")
        }
        val port = (host["port"] as? JsonPrimitive)?.intOrNull
        if (port == 0) {
            throw IllegalArgumentException("host file port is 0")
        }
        val role = (host["role"] as? JsonPrimitive)?.contentOrNull
        if (role != HostRole.Observer.name && role != HostRole.Controller.name) {
            throw IllegalArgumentException("host file role is invalid")
        }
        val hostId = (host["hostId"] as? JsonPrimitive)?.contentOrNull
        if (hostId.isNullOrEmpty()) {
            throw IllegalArgumentException("host file is missing hostId")
        }
        val bridgePath = (host["bridgePath"] as? JsonPrimitive)?.contentOrNull
        if (bridgePath.isNullOrEmpty()) {
            throw IllegalArgumentException("host file is missing bridgePath")
        }
    }
}

internal fun HostProfile.sanitized(): HostProfile =
    copy(
        id = id,
        hostId = hostId,
        role = role,
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
        hostId = hostId,
        role = hostRole(role),
        lastStatus = hostStatus(lastStatus),
        lastUpdatedEpochMs = lastUpdatedEpochMs,
        topology = topology?.toPublic(),
        name = name,
        tailnetFqdn = tailnetFqdn,
        user = user,
        port = port,
        bridgePath = bridgePath,
        hostKeys = hostKeys,
        hints = hints,
        lastGood = lastGood,
    )

private fun HostProfile.toFile(): HostFile =
    HostFile(
        id = id,
        hostId = hostId,
        role = role.name,
        lastStatus = lastStatus.name,
        lastUpdatedEpochMs = lastUpdatedEpochMs,
        topology = topology?.toFile(),
        name = name,
        tailnetFqdn = tailnetFqdn,
        user = user,
        port = port,
        bridgePath = bridgePath,
        hostKeys = hostKeys,
        hints = hints,
        lastGood = lastGood,
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

/** A role other than Observer or Controller is a corrupt cache, not a fallback. */
private fun hostRole(raw: String): HostRole =
    when (raw) {
        HostRole.Observer.name -> HostRole.Observer
        HostRole.Controller.name -> HostRole.Controller
        else -> throw IllegalArgumentException("host file role is invalid")
    }

private fun hostStatus(raw: String): HostStatus =
    HostStatus.entries.firstOrNull { it.name == raw } ?: HostStatus.Unknown
