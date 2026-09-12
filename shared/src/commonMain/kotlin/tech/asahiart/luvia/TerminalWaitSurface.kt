package tech.asahiart.luvia

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.asahiart.luvia.internal.asObject
import tech.asahiart.luvia.internal.booleanOrFalse
import tech.asahiart.luvia.internal.mapReviewNotes
import tech.asahiart.luvia.internal.optionalObject
import tech.asahiart.luvia.internal.optionalStrictLong
import tech.asahiart.luvia.internal.optionalString
import tech.asahiart.luvia.internal.optionalStringList
import tech.asahiart.luvia.internal.optionalWireString
import tech.asahiart.luvia.internal.wireName

public enum class DiffOpenView {
    AUTO,
    SPLIT,
    STACK,
}

public enum class DiffOpenPlacement {
    PREVIEW,
    PANE,
    TAB,
}

public enum class DiffNavigateAction {
    NEXT,
    NEXT_LINE,
    PREVIOUS,
    PREVIOUS_LINE,
    NEXT_FILE,
    PREVIOUS_FILE,
    NEXT_HUNK,
    PREVIOUS_HUNK,
    NEXT_NOTE,
    PREVIOUS_NOTE,
    TOP,
    BOTTOM,
}

public sealed class TerminalCreatePlacement {
    public data object Workspace : TerminalCreatePlacement()

    public data class Sibling(
        public val ofTerminal: TerminalIdentity,
        public val expectedRoot: ProcessIdentity? = null,
    ) : TerminalCreatePlacement()
}

public data class DiffNoteApplyItem(
    public val file: String,
    public val line: ReviewLine,
    public val body: String,
    public val endLine: Int? = null,
    public val kind: ReviewNoteKind = ReviewNoteKind.ISSUE,
    public val layer: DiffLayer? = null,
)

public data class TerminalValidationResult(
    public val state: String,
)

public data class TerminalProcessesResult(
    public val serverGeneration: String,
    public val terminalId: String,
    public val paneId: String,
    public val rootProcess: ProcessIdentity?,
    public val scan: String,
    public val executables: List<String>,
    public val argumentsExposed: Boolean,
)

public data class TerminalCreatedResult(
    public val serverGeneration: String,
    public val terminalId: String,
    public val paneId: String,
    public val cwd: String,
    public val placementKind: String,
    public val workspace: Long?,
    public val tab: Long?,
    public val rootProcess: ProcessIdentity?,
)

public data class TerminalWaitResult(
    public val contentRevision: Long,
    public val outputMatched: Boolean,
)

public data class MissionRefreshResult(
    public val scope: String?,
    public val workspace: String?,
    public val refreshing: Boolean,
)

public data class MissionOpenResult(
    public val mission: Boolean,
)

public data class DiffOpenResult(
    public val pane: String,
    public val path: String?,
    public val layer: DiffLayer?,
)

public data class DiffNavigateResult(
    public val pane: String?,
)

public data class EventWaitResult(
    public val matched: Boolean,
    public val sequence: Long,
    public val eventName: String?,
    public val eventSequence: Long?,
    public val eventData: JsonObject?,
)

public data class WaitOutputResult(
    public val matched: Boolean,
    public val pane: String?,
)

public data class PingResult(
    public val version: String,
    public val protocol: Long,
    public val session: String?,
)

public data class UhpConnectionStats(
    public val active: Long,
    public val capacity: Long,
    public val accepted: Long,
    public val rejected: Long,
    public val initialFrameTimeouts: Long,
)

public data class UhpRequestStats(
    public val completed: Long,
    public val bytesIn: Long,
    public val bytesOut: Long,
    public val meanLatencyUs: Long,
)

public data class UhpEventStats(
    public val replayCapacity: Long,
    public val replayBytes: Long,
    public val terminalStreams: Long,
    public val terminalStreamCapacity: Long,
)

public data class UhpStatsResult(
    public val uptimeMs: Long,
    public val connections: UhpConnectionStats,
    public val requests: UhpRequestStats,
    public val events: UhpEventStats,
)

public data class ConfigGetResult(
    public val config: JsonObject,
)

public suspend fun LuviaSession.validateTerminal(
    identity: TerminalIdentity,
    expectedRoot: ProcessIdentity? = null,
): Outcome<TerminalValidationResult> =
    engine.unary(TERMINAL_VALIDATE, locatorParams(identity, expectedRoot), mutation = false) {
        mapTerminalValidation(it.asObject())
    }

public suspend fun LuviaSession.terminalProcesses(
    identity: TerminalIdentity,
    expectedRoot: ProcessIdentity? = null,
): Outcome<TerminalProcessesResult> =
    engine.unary(TERMINAL_PROCESSES, locatorParams(identity, expectedRoot), mutation = false) {
        mapTerminalProcesses(it.asObject())
    }

public suspend fun LuviaSession.setTerminalTitle(
    identity: TerminalIdentity,
    title: String,
    expectedRoot: ProcessIdentity? = null,
): Outcome<Unit> =
    engine.unary(
        TERMINAL_SET_TITLE,
        buildJsonObject {
            putLocator(identity, expectedRoot)
            put("title", title)
        },
        mutation = true,
    ) { }

public suspend fun LuviaSession.notifyTerminal(
    identity: TerminalIdentity,
    title: String,
    body: String,
    expectedRoot: ProcessIdentity? = null,
): Outcome<Unit> =
    engine.unary(
        TERMINAL_NOTIFY,
        buildJsonObject {
            putLocator(identity, expectedRoot)
            put("title", title)
            put("body", body)
        },
        mutation = true,
    ) { }

public suspend fun LuviaSession.createTerminal(
    cwd: String,
    placement: TerminalCreatePlacement,
    focus: Boolean,
    command: List<String>? = null,
    label: String? = null,
): Outcome<TerminalCreatedResult> {
    val params =
        buildJsonObject {
            put("cwd", cwd)
            put("placement", placementJson(placement))
            put("focus", focus)
            if (!command.isNullOrEmpty()) put("command", stringArray(command))
            if (!label.isNullOrEmpty()) put("label", label)
        }
    return engine.unary(TERMINAL_CREATE, params, mutation = true) {
        mapTerminalCreated(it.asObject())
    }
}

public suspend fun LuviaSession.closeTerminal(
    identity: TerminalIdentity,
    expectedRoot: ProcessIdentity? = null,
): Outcome<Unit> =
    engine.unary(TERMINAL_CLOSE, locatorParams(identity, expectedRoot), mutation = true) { }

public suspend fun LuviaSession.waitTerminalChange(
    identity: TerminalIdentity,
    afterRevision: Long,
    timeoutMs: Int,
    expectedRoot: ProcessIdentity? = null,
): Outcome<TerminalWaitResult> =
    engine.unary(
        TERMINAL_WAIT_CHANGE,
        buildJsonObject {
            putLocator(identity, expectedRoot)
            put("after_revision", afterRevision)
            put("timeout_ms", timeoutMs)
        },
        mutation = false,
    ) { mapTerminalWait(it.asObject()) }

public suspend fun LuviaSession.waitTerminalOutput(
    identity: TerminalIdentity,
    afterRevision: Long,
    match: String,
    timeoutMs: Int,
    expectedRoot: ProcessIdentity? = null,
): Outcome<TerminalWaitResult> =
    engine.unary(
        TERMINAL_WAIT_OUTPUT,
        buildJsonObject {
            putLocator(identity, expectedRoot)
            put("after_revision", afterRevision)
            put("match", match)
            put("timeout_ms", timeoutMs)
        },
        mutation = false,
    ) { mapTerminalWait(it.asObject()) }

public suspend fun LuviaSession.refreshMission(
    scope: MissionScope = MissionScope.ALL,
    workspace: Int? = null,
    workspaceId: String? = null,
): Outcome<MissionRefreshResult> =
    engine.unary(MISSION_REFRESH, missionParams(scope, workspace, workspaceId), mutation = false) {
        mapMissionRefresh(it.asObject())
    }

public suspend fun LuviaSession.openMission(
    workspace: Int? = null,
    workspaceId: String? = null,
): Outcome<MissionOpenResult> =
    engine.unary(MISSION_OPEN, missionParams(scope = null, workspace, workspaceId), mutation = true) {
        mapMissionOpen(it.asObject())
    }

public suspend fun LuviaSession.openDiff(
    path: String? = null,
    layer: DiffLayer? = null,
    view: DiffOpenView? = null,
    placement: DiffOpenPlacement? = null,
): Outcome<DiffOpenResult> {
    val params =
        buildJsonObject {
            if (!path.isNullOrEmpty()) put("path", path)
            if (layer != null) put("layer", layer.wireName())
            if (view != null) put("view", view.wire())
            if (placement != null) put("placement", placement.wire())
        }
    return engine.unary(DIFF_OPEN, params, mutation = true) { mapDiffOpen(it.asObject()) }
}

public suspend fun LuviaSession.navigateDiff(
    action: DiffNavigateAction,
    pane: String? = null,
): Outcome<DiffNavigateResult> {
    val params =
        buildJsonObject {
            put("action", action.wire())
            if (pane != null) put("pane", pane)
        }
    return engine.unary(DIFF_NAVIGATE, params, mutation = true) { mapDiffNavigate(it.asObject()) }
}

public suspend fun LuviaSession.applyReviewNotes(notes: List<DiffNoteApplyItem>): Outcome<List<ReviewNote>> =
    engine.unary(
        DIFF_NOTE_APPLY,
        buildJsonObject { put("notes", buildJsonArray { notes.forEach { add(noteApplyJson(it)) } }) },
        mutation = true,
    ) { mapReviewNotes(it.asObject()) }

public suspend fun LuviaSession.waitEvent(
    event: String,
    where: Map<String, String>? = null,
    timeoutSeconds: Int? = null,
    afterSequence: Long? = null,
): Outcome<EventWaitResult> {
    val params =
        buildJsonObject {
            put("event", event)
            if (where != null) {
                put(
                    "where",
                    buildJsonObject { where.forEach { (key, value) -> put(key, value) } },
                )
            }
            if (timeoutSeconds != null) put("timeout_s", timeoutSeconds)
            if (afterSequence != null) put("after_sequence", afterSequence)
        }
    return engine.unary(EVENTS_WAIT, params, mutation = false) { mapEventWait(it.asObject()) }
}

public suspend fun LuviaSession.waitOutput(
    pane: String,
    match: String,
    timeoutSeconds: Int? = null,
): Outcome<WaitOutputResult> {
    val params =
        buildJsonObject {
            put("pane", pane)
            put("match", match)
            if (timeoutSeconds != null) put("timeout_s", timeoutSeconds)
        }
    return engine.unary(WAIT_OUTPUT, params, mutation = false) { mapWaitOutput(it.asObject()) }
}

public suspend fun LuviaSession.ping(): Outcome<PingResult> =
    engine.unary(PING, JsonObject(emptyMap()), mutation = false) { mapPing(it.asObject()) }

public suspend fun LuviaSession.uhpStats(): Outcome<UhpStatsResult> =
    engine.unary(UHP_STATS, JsonObject(emptyMap()), mutation = false) { mapUhpStats(it.asObject()) }

public suspend fun LuviaSession.getConfig(): Outcome<ConfigGetResult> =
    engine.unary(CONFIG_GET, JsonObject(emptyMap()), mutation = false) { mapConfigGet(it.asObject()) }

private const val TERMINAL_VALIDATE: String = "terminal.backend.validate"
private const val TERMINAL_PROCESSES: String = "terminal.backend.processes"
private const val TERMINAL_SET_TITLE: String = "terminal.backend.set_title"
private const val TERMINAL_NOTIFY: String = "terminal.backend.notify"
private const val TERMINAL_CREATE: String = "terminal.backend.create"
private const val TERMINAL_CLOSE: String = "terminal.backend.close"
private const val TERMINAL_WAIT_CHANGE: String = "terminal.backend.wait_change"
private const val TERMINAL_WAIT_OUTPUT: String = "terminal.backend.wait_output"
private const val MISSION_REFRESH: String = "mission.refresh"
private const val MISSION_OPEN: String = "mission.open"
private const val DIFF_OPEN: String = "diff.open"
private const val DIFF_NAVIGATE: String = "diff.navigate"
private const val DIFF_NOTE_APPLY: String = "diff.note.apply"
private const val EVENTS_WAIT: String = "events.wait"
private const val WAIT_OUTPUT: String = "wait.output"
private const val PING: String = "ping"
private const val UHP_STATS: String = "uhp.stats"
private const val CONFIG_GET: String = "config.get"

private fun locatorParams(
    identity: TerminalIdentity,
    expectedRoot: ProcessIdentity?,
): JsonObject =
    buildJsonObject { putLocator(identity, expectedRoot) }

private fun JsonObjectBuilder.putLocator(
    identity: TerminalIdentity,
    expectedRoot: ProcessIdentity?,
) {
    put("server_generation", identity.serverGeneration)
    put("terminal_id", identity.terminalId)
    put("pane_id", identity.paneId)
    if (expectedRoot != null) {
        put(
            "expected_root",
            buildJsonObject {
                put("pid", expectedRoot.pid)
                val marker = expectedRoot.startMarker
                if (marker != null) put("start_marker", marker)
            },
        )
    }
}

private fun placementJson(placement: TerminalCreatePlacement): JsonObject =
    when (placement) {
        TerminalCreatePlacement.Workspace -> buildJsonObject { put("kind", "workspace") }
        is TerminalCreatePlacement.Sibling ->
            buildJsonObject {
                put("kind", "sibling")
                put("of_terminal", locatorParams(placement.ofTerminal, placement.expectedRoot))
            }
    }

private fun missionParams(
    scope: MissionScope?,
    workspace: Int?,
    workspaceId: String?,
): JsonObject =
    buildJsonObject {
        if (scope != null) put("scope", scope.wireName())
        if (workspaceId != null) {
            put("workspace_id", workspaceId)
        } else if (workspace != null) {
            put("workspace", workspace)
        }
    }

private fun noteApplyJson(note: DiffNoteApplyItem): JsonObject =
    buildJsonObject {
        put("file", note.file)
        when (val line = note.line) {
            is ReviewLine.Old -> put("old_line", line.line)
            is ReviewLine.New -> put("new_line", line.line)
        }
        if (note.endLine != null) put("end_line", note.endLine)
        put("body", note.body)
        put("kind", note.kind.wireName())
        if (note.layer != null) put("layer", note.layer.wireName())
    }

private fun stringArray(values: List<String>): JsonArray =
    buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }

private fun DiffOpenView.wire(): String =
    when (this) {
        DiffOpenView.AUTO -> "auto"
        DiffOpenView.SPLIT -> "split"
        DiffOpenView.STACK -> "stack"
    }

private fun DiffOpenPlacement.wire(): String =
    when (this) {
        DiffOpenPlacement.PREVIEW -> "preview"
        DiffOpenPlacement.PANE -> "pane"
        DiffOpenPlacement.TAB -> "tab"
    }

private fun DiffNavigateAction.wire(): String =
    when (this) {
        DiffNavigateAction.NEXT -> "next"
        DiffNavigateAction.NEXT_LINE -> "next_line"
        DiffNavigateAction.PREVIOUS -> "previous"
        DiffNavigateAction.PREVIOUS_LINE -> "previous_line"
        DiffNavigateAction.NEXT_FILE -> "next_file"
        DiffNavigateAction.PREVIOUS_FILE -> "previous_file"
        DiffNavigateAction.NEXT_HUNK -> "next_hunk"
        DiffNavigateAction.PREVIOUS_HUNK -> "previous_hunk"
        DiffNavigateAction.NEXT_NOTE -> "next_note"
        DiffNavigateAction.PREVIOUS_NOTE -> "previous_note"
        DiffNavigateAction.TOP -> "top"
        DiffNavigateAction.BOTTOM -> "bottom"
    }

private fun mapTerminalValidation(result: JsonObject): TerminalValidationResult =
    TerminalValidationResult(state = result.optionalString("state") ?: "")

private fun mapTerminalProcesses(result: JsonObject): TerminalProcessesResult =
    TerminalProcessesResult(
        serverGeneration = result.optionalString("server_generation") ?: "",
        terminalId = result.optionalString("terminal_id") ?: "",
        paneId = result.optionalWireString("pane_id") ?: "",
        rootProcess = mapRootProcess(result.optionalObject("root_process")),
        scan = result.optionalString("scan") ?: "",
        executables = result.optionalStringList("executables"),
        argumentsExposed = result.booleanOrFalse("arguments_exposed"),
    )

private fun mapTerminalCreated(result: JsonObject): TerminalCreatedResult {
    val placement = result.optionalObject("placement")
    return TerminalCreatedResult(
        serverGeneration = result.optionalString("server_generation") ?: "",
        terminalId = result.optionalString("terminal_id") ?: "",
        paneId = result.optionalWireString("pane_id") ?: "",
        cwd = result.optionalString("cwd") ?: "",
        placementKind = placement?.optionalString("kind") ?: "",
        workspace = placement?.optionalStrictLong("workspace"),
        tab = placement?.optionalStrictLong("tab"),
        rootProcess = mapRootProcess(result.optionalObject("root_process")),
    )
}

private fun mapTerminalWait(result: JsonObject): TerminalWaitResult =
    TerminalWaitResult(
        contentRevision = result.optionalStrictLong("content_revision") ?: 0L,
        outputMatched = result.optionalString("type") == "terminal_backend_output",
    )

private fun mapMissionRefresh(result: JsonObject): MissionRefreshResult =
    MissionRefreshResult(
        scope = result.optionalString("scope"),
        workspace = result.optionalWireString("workspace"),
        refreshing = result.booleanOrFalse("refreshing"),
    )

private fun mapMissionOpen(result: JsonObject): MissionOpenResult =
    MissionOpenResult(mission = result.booleanOrFalse("mission"))

private fun mapDiffOpen(result: JsonObject): DiffOpenResult =
    DiffOpenResult(
        pane = result.optionalWireString("pane") ?: "",
        path = result.optionalString("path"),
        layer = parseDiffLayer(result.optionalString("layer")),
    )

private fun mapDiffNavigate(result: JsonObject): DiffNavigateResult =
    DiffNavigateResult(pane = result.optionalWireString("pane"))

private fun mapEventWait(result: JsonObject): EventWaitResult {
    val event = result.optionalObject("event")
    return EventWaitResult(
        matched = result.booleanOrFalse("matched"),
        sequence = result.optionalStrictLong("sequence") ?: 0L,
        eventName = event?.optionalString("event"),
        eventSequence = event?.optionalStrictLong("sequence"),
        eventData = event?.optionalObject("data"),
    )
}

private fun mapWaitOutput(result: JsonObject): WaitOutputResult =
    WaitOutputResult(
        matched = result.booleanOrFalse("matched"),
        pane = result.optionalWireString("pane"),
    )

private fun mapPing(result: JsonObject): PingResult =
    PingResult(
        version = result.optionalString("version") ?: "",
        protocol = result.optionalStrictLong("protocol") ?: 0L,
        session = result.optionalString("session"),
    )

private fun mapUhpStats(result: JsonObject): UhpStatsResult {
    val connections = result.optionalObject("connections")
    val requests = result.optionalObject("requests")
    val events = result.optionalObject("events")
    return UhpStatsResult(
        uptimeMs = result.optionalStrictLong("uptime_ms") ?: 0L,
        connections =
            UhpConnectionStats(
                active = connections?.optionalStrictLong("active") ?: 0L,
                capacity = connections?.optionalStrictLong("capacity") ?: 0L,
                accepted = connections?.optionalStrictLong("accepted") ?: 0L,
                rejected = connections?.optionalStrictLong("rejected") ?: 0L,
                initialFrameTimeouts = connections?.optionalStrictLong("initial_frame_timeouts") ?: 0L,
            ),
        requests =
            UhpRequestStats(
                completed = requests?.optionalStrictLong("completed") ?: 0L,
                bytesIn = requests?.optionalStrictLong("bytes_in") ?: 0L,
                bytesOut = requests?.optionalStrictLong("bytes_out") ?: 0L,
                meanLatencyUs = requests?.optionalStrictLong("mean_latency_us") ?: 0L,
            ),
        events =
            UhpEventStats(
                replayCapacity = events?.optionalStrictLong("replay_capacity") ?: 0L,
                replayBytes = events?.optionalStrictLong("replay_bytes") ?: 0L,
                terminalStreams = events?.optionalStrictLong("terminal_streams") ?: 0L,
                terminalStreamCapacity = events?.optionalStrictLong("terminal_stream_capacity") ?: 0L,
            ),
    )
}

private fun mapConfigGet(result: JsonObject): ConfigGetResult =
    ConfigGetResult(config = result.optionalObject("config") ?: JsonObject(emptyMap()))

private fun mapRootProcess(obj: JsonObject?): ProcessIdentity? {
    val pid = obj?.optionalStrictLong("pid") ?: return null
    return ProcessIdentity(pid = pid, startMarker = obj.optionalString("start_marker"))
}

private fun parseDiffLayer(raw: String?): DiffLayer? =
    when (raw) {
        "staged" -> DiffLayer.STAGED
        "worktree", "unstaged" -> DiffLayer.WORKTREE
        "untracked" -> DiffLayer.UNTRACKED
        "conflict" -> DiffLayer.CONFLICT
        else -> null
    }
