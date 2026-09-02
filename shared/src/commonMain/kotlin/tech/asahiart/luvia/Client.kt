package tech.asahiart.luvia

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.asahiart.luvia.internal.ByteChannelFactory
import tech.asahiart.luvia.internal.ControlFrame
import tech.asahiart.luvia.internal.Methods
import tech.asahiart.luvia.internal.SessionEngine
import tech.asahiart.luvia.internal.mapAgentExplain
import tech.asahiart.luvia.internal.mapAgentGet
import tech.asahiart.luvia.internal.mapAgentList
import tech.asahiart.luvia.internal.mapAgentPrompt
import tech.asahiart.luvia.internal.mapAgentRead
import tech.asahiart.luvia.internal.mapAgentSessions
import tech.asahiart.luvia.internal.mapDiffGet
import tech.asahiart.luvia.internal.mapDiffList
import tech.asahiart.luvia.internal.mapGitLog
import tech.asahiart.luvia.internal.mapGitStatus
import tech.asahiart.luvia.internal.mapMissionSnapshot
import tech.asahiart.luvia.internal.mapPaneSplit
import tech.asahiart.luvia.internal.mapReviewNoteResult
import tech.asahiart.luvia.internal.mapReviewNoteSend
import tech.asahiart.luvia.internal.mapReviewNotes
import tech.asahiart.luvia.internal.mapTaskDone
import tech.asahiart.luvia.internal.mapTaskHeartbeat
import tech.asahiart.luvia.internal.mapTaskMutation
import tech.asahiart.luvia.internal.mapTaskNext
import tech.asahiart.luvia.internal.mapTaskStart
import tech.asahiart.luvia.internal.mapTaskSummaries
import tech.asahiart.luvia.internal.mapTerminalBackendSnapshot
import tech.asahiart.luvia.internal.mapTerminalCaptureFrame
import tech.asahiart.luvia.internal.mapTerminalInventory
import tech.asahiart.luvia.internal.mapWorkspaceGet
import tech.asahiart.luvia.internal.mapWorkspaceListAsSummaries
import tech.asahiart.luvia.internal.mapWorkspaceOpen
import tech.asahiart.luvia.internal.wireName
import tech.asahiart.luvia.internal.withIfRevision

public class LuviaClient internal constructor(
    channels: ByteChannelFactory,
    authToken: String? = null,
) {
    private val engine = SessionEngine(channels, authToken)

    public suspend fun discover(): Outcome<List<DiscoveredSession>> = engine.discover()

    public suspend fun open(sessionName: String): Outcome<LuviaSession> = engine.open(sessionName)

    public fun close() {
        engine.close()
    }
}

public class LuviaSession internal constructor(
    private val engine: SessionEngine,
) {
    public val capabilities: Capabilities
        get() = engine.capabilities()

    public val freshness: ConnectionFreshness
        get() = engine.freshness()

    public fun supports(method: String): Boolean = method in capabilities.methods

    public suspend fun snapshot(): Outcome<SessionSnapshot> = engine.snapshot()

    public fun events(): Flow<SessionUpdate> = engine.events()

    internal fun liveUpdates(): Flow<tech.asahiart.luvia.internal.LiveUpdate> = engine.liveUpdates()

    public fun observe(identity: TerminalIdentity): Flow<TerminalUpdate> = engine.observe(identity)

    public suspend fun openControl(identity: TerminalIdentity): Outcome<TerminalControl> = engine.openControl(identity)

    public suspend fun inventory(): Outcome<TerminalInventoryResult> =
        engine.unary(Methods.TERMINAL_INVENTORY, JsonObject(emptyMap()), mutation = false) {
            mapTerminalInventory(it.asObjectOrEmpty())
        }

    public suspend fun terminalSnapshot(): Outcome<TerminalBackendSnapshot> =
        engine.unary(Methods.TERMINAL_SNAPSHOT, JsonObject(emptyMap()), mutation = false) {
            mapTerminalBackendSnapshot(it.asObjectOrEmpty())
        }

    public suspend fun capture(
        identity: TerminalIdentity,
        mode: TerminalCaptureMode,
        lines: Int,
        ansi: Boolean,
    ): Outcome<TerminalFrame> {
        val params =
            buildJsonObject {
                put("server_generation", identity.serverGeneration)
                put("terminal_id", identity.terminalId)
                put("pane_id", identity.paneId)
                put("mode", if (mode == TerminalCaptureMode.Visible) "visible" else "recent_unwrapped")
                put("lines", lines)
                put("ansi", ansi)
            }
        return engine.unary(Methods.TERMINAL_CAPTURE, params, mutation = false) {
            mapTerminalCaptureFrame(it.asObjectOrEmpty(), identity)
        }
    }

    public suspend fun typeLiteral(identity: TerminalIdentity, text: String): Outcome<Unit> =
        engine.unary(Methods.TERMINAL_TYPE, textParams(identity, text), mutation = true) { }

    public suspend fun submitText(identity: TerminalIdentity, text: String): Outcome<Unit> =
        engine.unary(Methods.TERMINAL_SUBMIT, textParams(identity, text), mutation = true) { }

    public suspend fun sendKey(identity: TerminalIdentity, key: TerminalKey): Outcome<Unit> {
        val params =
            buildJsonObject {
                put("server_generation", identity.serverGeneration)
                put("terminal_id", identity.terminalId)
                put("pane_id", identity.paneId)
                put("key", key.wireName())
            }
        return engine.unary(Methods.TERMINAL_KEY, params, mutation = true) { }
    }

    public suspend fun listWorkspaces(): Outcome<List<WorkspaceSummary>> =
        engine.unary(Methods.WORKSPACE_LIST, JsonObject(emptyMap()), mutation = false) {
            mapWorkspaceListAsSummaries(it.asObjectOrEmpty())
        }

    public suspend fun getWorkspace(workspace: Int): Outcome<WorkspaceGetResult> =
        engine.unary(
            Methods.WORKSPACE_GET,
            buildJsonObject { put("workspace", workspace) },
            mutation = false,
        ) { mapWorkspaceGet(it.asObjectOrEmpty()) }

    public suspend fun openWorkspace(path: String, ifRevision: Long? = null): Outcome<WorkspaceOpenResult> =
        engine.unary(
            Methods.WORKSPACE_OPEN,
            withIfRevision(buildJsonObject { put("path", path) }, ifRevision),
            mutation = true,
        ) { mapWorkspaceOpen(it.asObjectOrEmpty()) }

    public suspend fun focusWorkspace(index: Int, ifRevision: Long? = null): Outcome<Unit> =
        engine.unary(
            Methods.WORKSPACE_FOCUS,
            withIfRevision(buildJsonObject { put("workspace", index) }, ifRevision),
            mutation = true,
        ) { }

    public suspend fun splitPane(
        direction: SplitDirection,
        focus: Boolean? = null,
        ifRevision: Long? = null,
    ): Outcome<PaneSplitResult> {
        val params =
            buildJsonObject {
                put("direction", direction.wireName())
                if (focus != null) put("focus", focus)
            }
        return engine.unary(Methods.PANE_SPLIT, withIfRevision(params, ifRevision), mutation = true) {
            mapPaneSplit(it.asObjectOrEmpty())
        }
    }

    public suspend fun listAgents(): Outcome<List<AgentSummary>> =
        engine.unary(Methods.AGENT_LIST, JsonObject(emptyMap()), mutation = false) {
            mapAgentList(it.asObjectOrEmpty()).agents
        }

    public suspend fun getAgent(target: String): Outcome<AgentGetResult> =
        engine.unary(
            Methods.AGENT_GET,
            buildJsonObject { put("target", target) },
            mutation = false,
        ) { mapAgentGet(it.asObjectOrEmpty()) }

    public suspend fun explainAgent(paneId: String): Outcome<AgentExplainResult> =
        engine.unary(
            Methods.AGENT_EXPLAIN,
            buildJsonObject { put("pane", paneId) },
            mutation = false,
        ) { mapAgentExplain(it.asObjectOrEmpty()) }

    public suspend fun explainAgentByTarget(target: String): Outcome<AgentExplainResult> =
        engine.unary(
            Methods.AGENT_EXPLAIN,
            buildJsonObject { put("target", target) },
            mutation = false,
        ) { mapAgentExplain(it.asObjectOrEmpty()) }

    public suspend fun startAgent(name: String, kind: String): Outcome<Unit> =
        engine.unary(
            Methods.AGENT_START,
            buildJsonObject {
                put("name", name)
                put("kind", kind)
            },
            mutation = true,
        ) { }

    /**
     * `agent.prompt` (`dispatch.rs:4736-4803`). Never auto-retried.
     * `until` is sent as a one-element array because the server requires
     * `until` to be 1..4 states (`dispatch.rs:6037-6055`). `timeoutSeconds`
     * maps to `timeout_s`.
     */
    public suspend fun promptAgent(
        target: String,
        text: String,
        wait: Boolean = false,
        until: AgentStatus? = null,
        timeoutSeconds: Int? = null,
    ): Outcome<AgentPromptResult> {
        val params =
            buildJsonObject {
                put("target", target)
                put("text", text)
                if (wait) put("wait", true)
                if (until != null) {
                    put("until", buildJsonArray { add(JsonPrimitive(until.wireName())) })
                }
                if (timeoutSeconds != null) put("timeout_s", timeoutSeconds)
            }
        return engine.unary(Methods.AGENT_PROMPT, params, mutation = true) {
            mapAgentPrompt(it.asObjectOrEmpty())
        }
    }

    public suspend fun readAgent(
        target: String,
        lines: Int = 200,
        source: AgentReadSource = AgentReadSource.RECENT,
    ): Outcome<AgentReadResult> =
        engine.unary(
            Methods.AGENT_READ,
            buildJsonObject {
                put("target", target)
                put("lines", lines)
                put("source", source.wireName())
            },
            mutation = false,
        ) { mapAgentRead(it.asObjectOrEmpty()) }

    /**
     * `agent.keys` (`dispatch.rs:2553-2582`). Empty `keys` is rejected
     * client-side to match the server (`dispatch.rs:2564-2568`).
     */
    public suspend fun sendAgentKeys(target: String, keys: List<AgentKey>): Outcome<Unit> {
        if (keys.isEmpty()) {
            return fail(Failure.InvalidRequest("agent keys needs at least one key"))
        }
        return engine.unary(
            Methods.AGENT_KEYS,
            buildJsonObject {
                put("target", target)
                put("keys", stringArray(keys.map { it.wire }))
            },
            mutation = true,
        ) { }
    }

    public suspend fun listAgentSessions(): Outcome<List<AgentSessionEntry>> =
        engine.unary(Methods.AGENT_SESSIONS, JsonObject(emptyMap()), mutation = false) {
            mapAgentSessions(it.asObjectOrEmpty())
        }

    /**
     * `mission.snapshot` is 0.13.4+ (`dispatch.rs:3851-3887`). Gate with
     * [supports]. Default scope is `all`; the server defaults omitted scope
     * to `workspace`, so this always sends `scope`.
     */
    public suspend fun missionSnapshot(scope: MissionScope = MissionScope.ALL): Outcome<MissionSnapshot> =
        engine.unary(
            Methods.MISSION_SNAPSHOT,
            buildJsonObject { put("scope", scope.wireName()) },
            mutation = false,
        ) { mapMissionSnapshot(it.asObjectOrEmpty()) }

    public suspend fun listDiff(layer: DiffLayer? = null): Outcome<DiffListResult> {
        val params =
            buildJsonObject {
                if (layer != null) put("layer", layer.wireName())
            }
        return engine.unary(Methods.DIFF_LIST, params, mutation = false) {
            mapDiffList(it.asObjectOrEmpty())
        }
    }

    /**
     * `include_patch` defaults to false on the server (`dispatch.rs:3503-3506`);
     * this client defaults to true so Review screens receive hunks.
     */
    public suspend fun getDiff(
        path: String,
        layer: DiffLayer? = null,
        includePatch: Boolean = true,
    ): Outcome<DiffFile> {
        val params =
            buildJsonObject {
                put("path", path)
                if (layer != null) put("layer", layer.wireName())
                put("include_patch", includePatch)
            }
        return engine.unary(Methods.DIFF_GET, params, mutation = false) {
            mapDiffGet(it.asObjectOrEmpty())
        }
    }

    public suspend fun refreshDiff(): Outcome<Unit> =
        engine.unary(Methods.DIFF_REFRESH, JsonObject(emptyMap()), mutation = true) { }

    public suspend fun listReviewNotes(
        state: ReviewNoteState? = null,
        file: String? = null,
    ): Outcome<List<ReviewNote>> {
        val params =
            buildJsonObject {
                if (state != null) put("state", state.wireName())
                if (file != null) put("file", file)
            }
        return engine.unary(Methods.DIFF_NOTE_LIST, params, mutation = false) {
            mapReviewNotes(it.asObjectOrEmpty())
        }
    }

    public suspend fun addReviewNote(
        file: String,
        line: ReviewLine,
        endLine: Int? = null,
        body: String,
        kind: ReviewNoteKind = ReviewNoteKind.ISSUE,
        layer: DiffLayer? = null,
    ): Outcome<ReviewNote> {
        val params =
            buildJsonObject {
                put("file", file)
                putReviewLine(line)
                if (endLine != null) put("end_line", endLine)
                put("body", body)
                put("kind", kind.wireName())
                if (layer != null) put("layer", layer.wireName())
            }
        return engine.unary(Methods.DIFF_NOTE_ADD, params, mutation = true) {
            mapReviewNoteResult(it.asObjectOrEmpty())
        }
    }

    public suspend fun editReviewNote(id: String, body: String): Outcome<ReviewNote> =
        engine.unary(
            Methods.DIFF_NOTE_EDIT,
            buildJsonObject {
                put("id", id)
                put("body", body)
            },
            mutation = true,
        ) { mapReviewNoteResult(it.asObjectOrEmpty()) }

    public suspend fun resolveReviewNote(id: String): Outcome<ReviewNote> =
        engine.unary(
            Methods.DIFF_NOTE_RESOLVE,
            buildJsonObject { put("id", id) },
            mutation = true,
        ) { mapReviewNoteResult(it.asObjectOrEmpty()) }

    public suspend fun reopenReviewNote(id: String): Outcome<ReviewNote> =
        engine.unary(
            Methods.DIFF_NOTE_REOPEN,
            buildJsonObject { put("id", id) },
            mutation = true,
        ) { mapReviewNoteResult(it.asObjectOrEmpty()) }

    public suspend fun removeReviewNote(id: String): Outcome<Unit> =
        engine.unary(
            Methods.DIFF_NOTE_REMOVE,
            buildJsonObject { put("id", id) },
            mutation = true,
        ) { }

    public suspend fun sendReviewNotes(
        to: String,
        ids: List<String>? = null,
        allOpen: Boolean = false,
    ): Outcome<ReviewNoteSendResult> {
        val params =
            buildJsonObject {
                put("to", to)
                if (ids != null) put("ids", stringArray(ids))
                if (allOpen) put("all_open", true)
            }
        return engine.unary(Methods.DIFF_NOTE_SEND, params, mutation = true) {
            mapReviewNoteSend(it.asObjectOrEmpty())
        }
    }

    public suspend fun gitStatus(workspace: Int? = null): Outcome<GitStatus> {
        val params =
            buildJsonObject {
                if (workspace != null) put("workspace", workspace)
            }
        return engine.unary(Methods.GIT_STATUS, params, mutation = false) {
            mapGitStatus(it.asObjectOrEmpty())
        }
    }

    public suspend fun gitLog(n: Int = 30): Outcome<List<GitCommit>> =
        engine.unary(
            Methods.GIT_LOG,
            buildJsonObject { put("n", n) },
            mutation = false,
        ) { mapGitLog(it.asObjectOrEmpty()) }

    public suspend fun listTasks(): Outcome<List<TaskSummary>> =
        engine.unary(Methods.TASK_LIST, JsonObject(emptyMap()), mutation = false) {
            mapTaskSummaries(it.asObjectOrEmpty())
        }

    public suspend fun getTask(id: String): Outcome<TaskMutationResult> =
        engine.unary(
            Methods.TASK_GET,
            buildJsonObject { put("id", id) },
            mutation = false,
        ) { mapTaskMutation(it.asObjectOrEmpty()) }

    public suspend fun addTask(
        title: String,
        paths: List<String> = emptyList(),
        deps: List<String> = emptyList(),
        gate: String? = null,
        ifRevision: Long? = null,
    ): Outcome<TaskMutationResult> {
        val params =
            buildJsonObject {
                put("title", title)
                if (paths.isNotEmpty()) {
                    put("paths", stringArray(paths))
                }
                if (deps.isNotEmpty()) {
                    put("deps", stringArray(deps))
                }
                if (gate != null) put("gate", gate)
            }
        return engine.unary(Methods.TASK_ADD, withIfRevision(params, ifRevision), mutation = true) {
            mapTaskMutation(it.asObjectOrEmpty())
        }
    }

    /**
     * @deprecated `task.next` is declared read-only upstream (`capabilities.rs:250`)
     * but claims/starts (`dispatch.rs:4126-4163`). Do not add new call sites.
     */
    public suspend fun nextTask(): Outcome<TaskNextResult> =
        engine.unary(Methods.TASK_NEXT, JsonObject(emptyMap()), mutation = true) {
            mapTaskNext(it.asObjectOrEmpty())
        }

    public suspend fun startTask(
        id: String,
        branch: String? = null,
        agent: String? = null,
        ifRevision: Long? = null,
    ): Outcome<TaskStartResult> {
        val params =
            buildJsonObject {
                put("id", id)
                if (branch != null) put("branch", branch)
                if (agent != null) put("agent", agent)
            }
        return engine.unary(Methods.TASK_START, withIfRevision(params, ifRevision), mutation = true) {
            mapTaskStart(it.asObjectOrEmpty())
        }
    }

    public suspend fun heartbeatTask(
        id: String,
        context: Double,
        ifRevision: Long? = null,
    ): Outcome<TaskHeartbeatResult> =
        engine.unary(
            Methods.TASK_HEARTBEAT,
            withIfRevision(
                buildJsonObject {
                    put("id", id)
                    put("context", context)
                },
                ifRevision,
            ),
            mutation = true,
        ) { mapTaskHeartbeat(it.asObjectOrEmpty()) }

    public suspend fun completeTask(id: String, ifRevision: Long? = null): Outcome<TaskDoneResult> =
        engine.unary(
            Methods.TASK_DONE,
            withIfRevision(buildJsonObject { put("id", id) }, ifRevision),
            mutation = true,
        ) { mapTaskDone(it.asObjectOrEmpty()) }

    public fun close() {
        engine.close()
    }
}

public class TerminalControl internal constructor(
    private val engine: SessionEngine,
    private val stream: tech.asahiart.luvia.internal.OpenStream,
) {
    public fun frames(): Flow<TerminalUpdate> = engine.controlFrames(stream)

    public suspend fun typeLiteral(text: String): Outcome<Unit> =
        engine.writeControl(
            stream,
            ControlFrame.Action.TypeLiteral,
            buildJsonObject { put("text", text) },
            Methods.TERMINAL_TYPE,
        )

    public suspend fun submitText(text: String): Outcome<Unit> =
        engine.writeControl(
            stream,
            ControlFrame.Action.SubmitText,
            buildJsonObject { put("text", text) },
            Methods.TERMINAL_SUBMIT,
        )

    public suspend fun sendKey(key: TerminalKey): Outcome<Unit> =
        engine.writeControl(
            stream,
            ControlFrame.Action.SendKey,
            buildJsonObject { put("key", key.wireName()) },
            Methods.TERMINAL_KEY,
        )

    public fun close() {
        stream.close()
    }
}

private fun textParams(identity: TerminalIdentity, text: String): JsonObject =
    buildJsonObject {
        put("server_generation", identity.serverGeneration)
        put("terminal_id", identity.terminalId)
        put("pane_id", identity.paneId)
        put("text", text)
    }

private fun stringArray(values: List<String>): JsonArray =
    buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }

private fun kotlinx.serialization.json.JsonObjectBuilder.putReviewLine(line: ReviewLine) {
    when (line) {
        is ReviewLine.Old -> put("old_line", line.line)
        is ReviewLine.New -> put("new_line", line.line)
    }
}

private fun kotlinx.serialization.json.JsonElement.asObjectOrEmpty(): JsonObject =
    this as? JsonObject ?: JsonObject(emptyMap())
