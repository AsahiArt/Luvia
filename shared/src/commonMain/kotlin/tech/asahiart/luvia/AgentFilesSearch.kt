package tech.asahiart.luvia

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.asahiart.luvia.internal.booleanOrFalse
import tech.asahiart.luvia.internal.optionalBoolean
import tech.asahiart.luvia.internal.optionalObject
import tech.asahiart.luvia.internal.optionalObjectList
import tech.asahiart.luvia.internal.optionalStrictLong
import tech.asahiart.luvia.internal.optionalString
import tech.asahiart.luvia.internal.optionalStringList
import tech.asahiart.luvia.internal.optionalWireString
import tech.asahiart.luvia.internal.parseAgentStatus
import tech.asahiart.luvia.internal.wireName

public data class AgentReportResult(
    public val pane: String,
    public val agent: String?,
    public val status: AgentStatus,
    public val source: String?,
    public val sequence: Long?,
    public val ttlSeconds: Long?,
)

public data class AgentReleaseResult(
    public val pane: String,
)

public data class AgentWaitResult(
    public val matched: Boolean,
    public val pane: String?,
    public val status: AgentStatus?,
)

public data class AgentNameResult(
    public val pane: String,
    public val name: String?,
)

public data class AgentForkResult(
    public val from: String,
    public val pane: String,
    public val agent: String?,
    public val name: String?,
    public val workspace: String?,
    public val tab: String?,
    public val focused: Boolean,
)

public data class FileTreeRow(
    public val path: String,
    public val name: String,
    public val depth: Int,
    public val dir: Boolean,
    public val expanded: Boolean,
)

public data class FileTreeResult(
    public val root: String,
    public val rows: List<FileTreeRow>,
)

public enum class FileOpenTarget {
    PREVIEW,
    PANE,
    TAB,
}

public enum class SearchScope {
    ALL,
    NAVIGATE,
    FILES,
    OUTPUT,
}

public enum class SearchKind {
    SESSION,
    FOLDER,
    TAB,
    PANE,
    AGENT,
    FILE,
    OUTPUT,
}

public data class SearchTarget(
    public val session: String? = null,
    public val running: Boolean? = null,
    public val current: Boolean? = null,
    public val workspace: Int? = null,
    public val workspacePath: String? = null,
    public val tab: Int? = null,
    public val tabPanes: List<String> = emptyList(),
    public val pane: String? = null,
    public val path: String? = null,
    public val row: Long? = null,
    public val lineOffset: Long? = null,
    public val above: Long? = null,
    public val line: String? = null,
)

public data class SearchCapabilities(
    public val version: Long?,
    public val methods: List<String>,
    public val scopes: List<String>,
    public val maxResults: Long?,
    public val maxResponseBytes: Long?,
)

public data class SearchMatch(
    public val id: String,
    public val kind: String,
    public val label: String,
    public val detail: String?,
    public val score: Long?,
    public val target: SearchTarget,
)

public data class SearchQueryResult(
    public val query: String,
    public val scope: String?,
    public val total: Long,
    public val shown: Long,
    public val partial: Boolean,
    public val matches: List<SearchMatch>,
)

public data class SearchActivateResult(
    public val activated: Boolean,
)

public data class GitBranch(
    public val name: String,
    public val head: Boolean,
    public val ahead: Long?,
    public val behind: Long?,
    public val subject: String?,
)

public data class GitOpenResult(
    public val git: Boolean,
)

/**
 * `agent.report` (`agents.rs`). Sequence must increase for this source.
 * `ttl_s` defaults on the host when omitted.
 */
public suspend fun LuviaSession.reportAgent(
    pane: String,
    source: String,
    agent: String,
    status: AgentStatus,
    message: String? = null,
    sessionId: String? = null,
    sequence: Long? = null,
    ttlSeconds: Int? = null,
): Outcome<AgentReportResult> {
    val params =
        buildJsonObject {
            put("pane", pane)
            put("source", source)
            put("agent", agent)
            put("status", status.wireName())
            if (message != null) put("message", message)
            if (sessionId != null) put("session_id", sessionId)
            if (sequence != null) put("sequence", sequence)
            if (ttlSeconds != null) put("ttl_s", ttlSeconds)
        }
    return engine.unary("agent.report", params, mutation = true) {
        mapAgentReport(it.asObjectOrEmpty())
    }
}

/** `agent.release` (`agents.rs`). [source] must own the pane. */
public suspend fun LuviaSession.releaseAgent(
    pane: String,
    source: String,
): Outcome<AgentReleaseResult> =
    engine.unary(
        "agent.release",
        buildJsonObject {
            put("pane", pane)
            put("source", source)
        },
        mutation = true,
    ) { mapAgentRelease(it.asObjectOrEmpty()) }

/**
 * `agent.wait` (`ipc/api.rs`). Send `status` or `statuses`, never both.
 * Empty [statuses] is rejected client-side to match the host.
 */
public suspend fun LuviaSession.waitForAgent(
    pane: String,
    status: AgentStatus? = null,
    statuses: List<AgentStatus>? = null,
    timeoutSeconds: Int? = null,
): Outcome<AgentWaitResult> {
    if (status != null && statuses != null) {
        return fail(Failure.InvalidRequest("agent.wait accepts status or statuses, not both"))
    }
    if (status == null && statuses == null) {
        return fail(Failure.InvalidRequest("agent.wait needs status or statuses"))
    }
    if (statuses != null && (statuses.isEmpty() || statuses.size > 4)) {
        return fail(Failure.InvalidRequest("statuses must contain between 1 and 4 states"))
    }
    val params =
        buildJsonObject {
            put("pane", pane)
            if (status != null) put("status", status.wireName())
            if (statuses != null) put("statuses", stringArray(statuses.map { it.wireName() }))
            if (timeoutSeconds != null) put("timeout_s", timeoutSeconds)
        }
    return engine.unary("agent.wait", params, mutation = false) {
        mapAgentWait(it.asObjectOrEmpty())
    }
}

/**
 * `agent.name` (`agents.rs`). Omitting [pane] names the focused pane.
 * [clear] drops the alias; do not send [name] with it.
 */
public suspend fun LuviaSession.nameAgent(
    pane: String? = null,
    name: String? = null,
    clear: Boolean = false,
): Outcome<AgentNameResult> {
    val params =
        buildJsonObject {
            if (pane != null) put("pane", pane)
            if (clear) {
                put("clear", true)
            } else if (name != null) {
                put("name", name)
            }
        }
    return engine.unary("agent.name", params, mutation = true) {
        mapAgentName(it.asObjectOrEmpty())
    }
}

/**
 * `agent.fork` (`agents.rs`). [target] is a live alias, pane id, or unique kind.
 * [focus] defaults to true on the host when omitted.
 */
public suspend fun LuviaSession.forkAgent(
    target: String,
    name: String? = null,
    focus: Boolean? = null,
): Outcome<AgentForkResult> {
    val params =
        buildJsonObject {
            put("target", target)
            if (name != null) put("name", name)
            if (focus != null) put("focus", focus)
        }
    return engine.unary("agent.fork", params, mutation = true) {
        mapAgentFork(it.asObjectOrEmpty())
    }
}

/** `agent.resume` (`agents.rs`). [sessionId] is a resumable session from `agent.sessions`. */
public suspend fun LuviaSession.resumeAgent(sessionId: String): Outcome<Unit> =
    engine.unary(
        "agent.resume",
        buildJsonObject { put("session_id", sessionId) },
        mutation = true,
    ) { }

/** `files.tree` (`content.rs`). */
public suspend fun LuviaSession.fileTree(): Outcome<FileTreeResult> =
    engine.unary("files.tree", JsonObject(emptyMap()), mutation = false) {
        mapFileTree(it.asObjectOrEmpty())
    }

/** `files.open` (`content.rs`). Host default [target] is preview. */
public suspend fun LuviaSession.openFile(
    path: String,
    target: FileOpenTarget? = null,
): Outcome<Unit> {
    val params =
        buildJsonObject {
            put("path", path)
            if (target != null) put("target", target.wireName())
        }
    return engine.unary("files.open", params, mutation = true) { }
}

/** `files.reveal` (`content.rs`). */
public suspend fun LuviaSession.revealFile(path: String): Outcome<Unit> =
    engine.unary(
        "files.reveal",
        buildJsonObject { put("path", path) },
        mutation = true,
    ) { }

/** `files.refresh` (`content.rs`). Invalidates the cached listing. */
public suspend fun LuviaSession.refreshFiles(): Outcome<Unit> =
    engine.unary("files.refresh", JsonObject(emptyMap()), mutation = true) { }

/** `search.capabilities` (`content.rs`). */
public suspend fun LuviaSession.searchCapabilities(): Outcome<SearchCapabilities> =
    engine.unary("search.capabilities", JsonObject(emptyMap()), mutation = false) {
        mapSearchCapabilities(it.asObjectOrEmpty())
    }

/**
 * `search.query` (`search.rs`). [scope] is `all`, `navigate`, `files`, or `output`.
 * Bare `search` is the legacy exact-output alias and is not wrapped.
 */
public suspend fun LuviaSession.querySearch(
    query: String,
    scope: SearchScope? = null,
    caseSensitive: Boolean? = null,
    allSessions: Boolean? = null,
    limit: Int? = null,
): Outcome<SearchQueryResult> {
    val params =
        buildJsonObject {
            put("query", query)
            if (scope != null) put("scope", scope.wireName())
            if (caseSensitive != null) put("case_sensitive", caseSensitive)
            if (allSessions != null) put("all_sessions", allSessions)
            if (limit != null) put("limit", limit)
        }
    return engine.unary("search.query", params, mutation = false) {
        mapSearchQuery(it.asObjectOrEmpty())
    }
}

/** `search.activate` (`search.rs`). [target] must be a result from the same session. */
public suspend fun LuviaSession.activateSearch(
    kind: SearchKind,
    target: SearchTarget,
): Outcome<SearchActivateResult> {
    val params =
        buildJsonObject {
            put("kind", kind.wireName())
            put("target", target.toJson())
        }
    return engine.unary("search.activate", params, mutation = true) {
        mapSearchActivate(it.asObjectOrEmpty())
    }
}

/** `git.branches` (`content.rs`). Optional [workspace] selects the git cwd. */
public suspend fun LuviaSession.gitBranches(workspace: Int? = null): Outcome<List<GitBranch>> {
    val params =
        buildJsonObject {
            if (workspace != null) put("workspace", workspace)
        }
    return engine.unary("git.branches", params, mutation = false) {
        mapGitBranches(it.asObjectOrEmpty())
    }
}

/** `git.open` (`content.rs`). Opens the git tab; optional [workspace] picks the node. */
public suspend fun LuviaSession.gitOpen(workspace: Int? = null): Outcome<GitOpenResult> {
    val params =
        buildJsonObject {
            if (workspace != null) put("workspace", workspace)
        }
    return engine.unary("git.open", params, mutation = true) {
        mapGitOpen(it.asObjectOrEmpty())
    }
}

private fun FileOpenTarget.wireName(): String =
    when (this) {
        FileOpenTarget.PREVIEW -> "preview"
        FileOpenTarget.PANE -> "pane"
        FileOpenTarget.TAB -> "tab"
    }

private fun SearchScope.wireName(): String =
    when (this) {
        SearchScope.ALL -> "all"
        SearchScope.NAVIGATE -> "navigate"
        SearchScope.FILES -> "files"
        SearchScope.OUTPUT -> "output"
    }

private fun SearchKind.wireName(): String =
    when (this) {
        SearchKind.SESSION -> "session"
        SearchKind.FOLDER -> "folder"
        SearchKind.TAB -> "tab"
        SearchKind.PANE -> "pane"
        SearchKind.AGENT -> "agent"
        SearchKind.FILE -> "file"
        SearchKind.OUTPUT -> "output"
    }

private fun SearchTarget.toJson(): JsonObject =
    buildJsonObject {
        if (session != null) put("session", session)
        if (running != null) put("running", running)
        if (current != null) put("current", current)
        if (workspace != null) put("workspace", workspace)
        if (workspacePath != null) put("workspace_path", workspacePath)
        if (tab != null) put("tab", tab)
        if (tabPanes.isNotEmpty()) put("tab_panes", stringArray(tabPanes))
        if (pane != null) put("pane", pane)
        if (path != null) put("path", path)
        if (row != null) put("row", row)
        if (lineOffset != null) put("line_offset", lineOffset)
        if (above != null) put("above", above)
        if (line != null) put("line", line)
    }

private fun mapAgentReport(result: JsonObject): AgentReportResult =
    AgentReportResult(
        pane = result.optionalWireString("pane") ?: "",
        agent = result.optionalString("agent"),
        status = parseAgentStatus(result.optionalString("status")),
        source = result.optionalString("source"),
        sequence = result.optionalStrictLong("sequence"),
        ttlSeconds = result.optionalStrictLong("ttl_s"),
    )

private fun mapAgentRelease(result: JsonObject): AgentReleaseResult =
    AgentReleaseResult(pane = result.optionalWireString("pane") ?: "")

private fun mapAgentWait(result: JsonObject): AgentWaitResult =
    AgentWaitResult(
        matched = result.booleanOrFalse("matched"),
        pane = result.optionalWireString("pane"),
        status = result.optionalString("status")?.let { parseAgentStatus(it) },
    )

private fun mapAgentName(result: JsonObject): AgentNameResult =
    AgentNameResult(
        pane = result.optionalWireString("pane") ?: "",
        name = result.optionalString("name"),
    )

private fun mapAgentFork(result: JsonObject): AgentForkResult =
    AgentForkResult(
        from = result.optionalWireString("from") ?: "",
        pane = result.optionalWireString("pane") ?: "",
        agent = result.optionalString("agent"),
        name = result.optionalString("name"),
        workspace = result.optionalWireString("workspace"),
        tab = result.optionalWireString("tab"),
        focused = result.booleanOrFalse("focused"),
    )

private fun mapFileTree(result: JsonObject): FileTreeResult =
    FileTreeResult(
        root = result.optionalString("root") ?: "",
        rows =
            result.optionalObjectList("rows").map { row ->
                FileTreeRow(
                    path = row.optionalString("path") ?: "",
                    name = row.optionalString("name") ?: "",
                    depth = row.optionalStrictLong("depth")?.toInt() ?: 0,
                    dir = row.booleanOrFalse("dir"),
                    expanded = row.booleanOrFalse("expanded"),
                )
            },
    )

private fun mapSearchCapabilities(result: JsonObject): SearchCapabilities =
    SearchCapabilities(
        version = result.optionalStrictLong("version"),
        methods = result.optionalStringList("methods"),
        scopes = result.optionalStringList("scopes"),
        maxResults = result.optionalStrictLong("max_results"),
        maxResponseBytes = result.optionalStrictLong("max_response_bytes"),
    )

private fun mapSearchQuery(result: JsonObject): SearchQueryResult =
    SearchQueryResult(
        query = result.optionalString("query") ?: "",
        scope = result.optionalString("scope"),
        total = result.optionalStrictLong("total") ?: 0L,
        shown = result.optionalStrictLong("shown") ?: 0L,
        partial = result.booleanOrFalse("partial"),
        matches = result.optionalObjectList("matches").map { mapSearchMatch(it) },
    )

private fun mapSearchMatch(obj: JsonObject): SearchMatch =
    SearchMatch(
        id = obj.optionalString("id") ?: "",
        kind = obj.optionalString("kind") ?: "",
        label = obj.optionalString("label") ?: "",
        detail = obj.optionalString("detail"),
        score = obj.optionalStrictLong("score"),
        target = obj.optionalObject("target")?.let { mapSearchTarget(it) } ?: SearchTarget(),
    )

private fun mapSearchTarget(obj: JsonObject): SearchTarget =
    SearchTarget(
        session = obj.optionalString("session"),
        running = obj.optionalBoolean("running"),
        current = obj.optionalBoolean("current"),
        workspace = obj.optionalStrictLong("workspace")?.toInt(),
        workspacePath = obj.optionalString("workspace_path"),
        tab = obj.optionalStrictLong("tab")?.toInt(),
        tabPanes = obj.optionalStringList("tab_panes"),
        pane = obj.optionalWireString("pane"),
        path = obj.optionalString("path"),
        row = obj.optionalStrictLong("row"),
        lineOffset = obj.optionalStrictLong("line_offset"),
        above = obj.optionalStrictLong("above"),
        line = obj.optionalString("line"),
    )

private fun mapSearchActivate(result: JsonObject): SearchActivateResult =
    SearchActivateResult(activated = result.booleanOrFalse("activated"))

private fun mapGitBranches(result: JsonObject): List<GitBranch> =
    result.optionalObjectList("branches").mapNotNull { obj ->
        val name = obj.optionalString("name") ?: return@mapNotNull null
        GitBranch(
            name = name,
            head = obj.booleanOrFalse("head"),
            ahead = obj.optionalStrictLong("ahead"),
            behind = obj.optionalStrictLong("behind"),
            subject = obj.optionalString("subject"),
        )
    }

private fun mapGitOpen(result: JsonObject): GitOpenResult =
    GitOpenResult(git = result.booleanOrFalse("git"))

private fun stringArray(values: List<String>): JsonArray =
    buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }

private fun JsonElement.asObjectOrEmpty(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
