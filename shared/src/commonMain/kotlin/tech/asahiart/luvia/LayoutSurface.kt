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
import tech.asahiart.luvia.internal.optionalDouble
import tech.asahiart.luvia.internal.optionalObject
import tech.asahiart.luvia.internal.optionalObjectList
import tech.asahiart.luvia.internal.optionalStrictLong
import tech.asahiart.luvia.internal.optionalString
import tech.asahiart.luvia.internal.optionalStringList
import tech.asahiart.luvia.internal.optionalWireString
import tech.asahiart.luvia.internal.parseAgentStatus
import tech.asahiart.luvia.internal.withIfRevision

public enum class PaneDirection {
    Left,
    Right,
    Up,
    Down,
}

public enum class TabMoveDirection {
    Left,
    Right,
}

public enum class LayoutSplitStep {
    A,
    B,
}

public sealed class LayoutTree {
    public data class Leaf(public val pane: Long) : LayoutTree()

    public data class Split(
        public val axis: Int,
        public val ratio: Double,
        public val a: LayoutTree,
        public val b: LayoutTree,
    ) : LayoutTree()
}

public data class WorkspaceRenameResult(
    public val workspace: String,
    public val name: String,
    public val cwd: String?,
    public val pinned: Boolean,
    public val displayPosition: String?,
    public val revision: Long?,
)

public data class WorkspacePinResult(
    public val workspace: String,
    public val name: String,
    public val cwd: String?,
    public val pinned: Boolean,
    public val displayPosition: String?,
    public val revision: Long?,
)

public data class WorkspaceMoveResult(
    public val workspace: String,
    public val to: String,
    public val revision: Long?,
)

public data class WorkspaceMoveBlockResult(
    public val workspaces: List<Int>,
    public val positions: List<Int>,
    public val revision: Long?,
)

public data class TabListEntry(
    public val tab: String,
    public val tabId: String?,
    public val active: Boolean,
    public val name: String?,
    public val kind: String?,
)

public data class TabListResult(
    public val tabs: List<TabListEntry>,
    public val revision: Long?,
)

public data class TabGetResult(
    public val workspace: String,
    public val workspaceId: String?,
    public val tab: String,
    public val tabId: String?,
    public val active: Boolean,
    public val name: String?,
    public val kind: String?,
    public val focus: String?,
    public val panes: List<String>,
    public val revision: Long?,
)

public data class TabNewResult(
    public val tab: String,
    public val revision: Long?,
)

public data class TabMoveResult(
    public val from: String,
    public val to: String,
    public val active: String,
    public val revision: Long?,
)

public data class TabSwapResult(
    public val tab: String,
    public val with: String,
    public val active: String,
    public val revision: Long?,
)

public data class PaneModuleRef(
    public val id: String,
    public val entrypoint: String?,
)

public data class PaneListEntry(
    public val pane: String,
    public val agent: String?,
    public val status: AgentStatus,
    public val focused: Boolean,
    public val cwd: String?,
    public val module: PaneModuleRef?,
    public val scrollOffset: Long?,
    public val historyRows: Long?,
    public val historyBudgetBytes: Long?,
    public val historyBytes: Long?,
    public val historyExact: Boolean?,
    public val historyBytesKind: String?,
)

public data class PaneListResult(
    public val panes: List<PaneListEntry>,
    public val revision: Long?,
)

public data class PaneGetResult(
    public val pane: String,
    public val workspace: String?,
    public val workspaceId: String?,
    public val tab: String?,
    public val tabId: String?,
    public val terminalId: String?,
    public val focused: Boolean,
    public val name: String?,
    public val cwd: String?,
    public val command: String?,
    public val agent: String?,
    public val status: AgentStatus,
    public val module: PaneModuleRef?,
    public val revision: Long?,
)

public data class PaneSize(
    public val width: Long?,
    public val height: Long?,
)

public data class PaneRect(
    public val x: Long?,
    public val y: Long?,
    public val width: Long?,
    public val height: Long?,
)

public data class PaneLayoutResult(
    public val pane: String,
    public val workspace: String?,
    public val tab: String?,
    public val logicalSize: PaneSize?,
    public val rect: PaneRect?,
    public val tree: LayoutTree?,
    public val revision: Long?,
)

public data class PaneNeighborResult(
    public val pane: String,
    public val neighbor: String?,
    public val revision: Long?,
)

public data class PaneEdges(
    public val left: Boolean,
    public val right: Boolean,
    public val top: Boolean,
    public val bottom: Boolean,
)

public data class PaneEdgesResult(
    public val pane: String,
    public val edges: PaneEdges,
    public val revision: Long?,
)

public data class PaneMoveResult(
    public val pane: String,
    public val workspace: String?,
    public val tab: String?,
    public val revision: Long?,
)

public data class PaneSwapResult(
    public val pane: String,
    public val with: String,
    public val revision: Long?,
)

public data class PaneFocusResult(
    public val pane: String,
    public val revision: Long?,
)

public data class PaneResizeResult(
    public val pane: String,
    public val revision: Long?,
)

public data class PaneZoomResult(
    public val pane: String,
    public val enabled: Boolean,
    public val revision: Long?,
)

public data class PaneRenameResult(
    public val pane: String,
    public val name: String?,
    public val revision: Long?,
)

public data class PaneReadResult(
    public val text: String,
    public val revision: Long?,
)

public data class PaneStatusResult(
    public val pane: String,
    public val agent: String?,
    public val status: AgentStatus,
    public val authority: String?,
    public val stateSource: String?,
    public val scrollOffset: Long?,
    public val historyRows: Long?,
    public val historyBudgetBytes: Long?,
    public val historyBytes: Long?,
    public val historyExact: Boolean?,
    public val historyBytesKind: String?,
    public val revision: Long?,
)

public data class PaneProcessesResult(
    public val pane: String,
    public val terminalId: String?,
    public val rootProcess: ProcessIdentity?,
    public val scan: String?,
    public val executables: List<String>,
    public val argumentsExposed: Boolean,
    public val revision: Long?,
)

public data class AttachPaneResult(
    public val pane: String,
    public val revision: Long?,
)

public data class LayoutExportResult(
    public val workspace: String,
    public val tab: String,
    public val focus: String?,
    public val tree: LayoutTree?,
    public val revision: Long?,
)

public data class LayoutApplyResult(
    public val workspace: String,
    public val tab: String,
    public val revision: Long?,
)

public data class LayoutSplitRatioResult(
    public val workspace: String,
    public val tab: String,
    public val ratio: Double?,
    public val revision: Long?,
)

public suspend fun LuviaSession.newWorkspace(ifRevision: Long? = null): Outcome<WorkspaceOpenResult> =
    engine.unary(
        "workspace.new",
        withIfRevision(JsonObject(emptyMap()), ifRevision),
        mutation = true,
    ) { mapWorkspaceCreated(it.asObjectOrEmpty()) }

public suspend fun LuviaSession.renameWorkspace(
    workspace: Int,
    name: String,
    ifRevision: Long? = null,
): Outcome<WorkspaceRenameResult> =
    engine.unary(
        "workspace.rename",
        withIfRevision(
            buildJsonObject {
                put("workspace", workspace)
                put("name", name)
            },
            ifRevision,
        ),
        mutation = true,
    ) { mapWorkspaceRename(it.asObjectOrEmpty()) }

public suspend fun LuviaSession.pinWorkspace(
    workspace: Int,
    pinned: Boolean,
    ifRevision: Long? = null,
): Outcome<WorkspacePinResult> =
    engine.unary(
        "workspace.pin",
        withIfRevision(
            buildJsonObject {
                put("workspace", workspace)
                put("pinned", pinned)
            },
            ifRevision,
        ),
        mutation = true,
    ) { mapWorkspacePin(it.asObjectOrEmpty()) }

public suspend fun LuviaSession.moveWorkspace(
    workspace: Int,
    to: Int,
    ifRevision: Long? = null,
): Outcome<WorkspaceMoveResult> =
    engine.unary(
        "workspace.move",
        withIfRevision(
            buildJsonObject {
                put("workspace", workspace)
                put("to", to)
            },
            ifRevision,
        ),
        mutation = true,
    ) { mapWorkspaceMove(it.asObjectOrEmpty()) }

public suspend fun LuviaSession.moveWorkspaceBlock(
    workspaces: List<Int>,
    to: Int,
    ifRevision: Long? = null,
): Outcome<WorkspaceMoveBlockResult> =
    engine.unary(
        "workspace.move_block",
        withIfRevision(
            buildJsonObject {
                put("workspaces", intArray(workspaces))
                put("to", to)
            },
            ifRevision,
        ),
        mutation = true,
    ) { mapWorkspaceMoveBlock(it.asObjectOrEmpty()) }

public suspend fun LuviaSession.closeWorkspace(
    workspace: Int? = null,
    ifRevision: Long? = null,
): Outcome<Unit> =
    engine.unary(
        "workspace.close",
        withIfRevision(
            buildJsonObject { if (workspace != null) put("workspace", workspace) },
            ifRevision,
        ),
        mutation = true,
    ) { }

public suspend fun LuviaSession.listTabs(): Outcome<TabListResult> =
    engine.unary("tab.list", JsonObject(emptyMap()), mutation = false) {
        mapTabList(it.asObjectOrEmpty())
    }

public suspend fun LuviaSession.getTab(
    workspace: Int? = null,
    tab: Int? = null,
): Outcome<TabGetResult> =
    engine.unary(
        "tab.get",
        buildJsonObject {
            if (workspace != null) put("workspace", workspace)
            if (tab != null) put("tab", tab)
        },
        mutation = false,
    ) { mapTabGet(it.asObjectOrEmpty()) }

public suspend fun LuviaSession.newTab(ifRevision: Long? = null): Outcome<TabNewResult> =
    engine.unary(
        "tab.new",
        withIfRevision(JsonObject(emptyMap()), ifRevision),
        mutation = true,
    ) { mapTabNew(it.asObjectOrEmpty()) }

public suspend fun LuviaSession.focusTab(
    tab: Int,
    ifRevision: Long? = null,
): Outcome<Unit> =
    engine.unary(
        "tab.focus",
        withIfRevision(buildJsonObject { put("tab", tab) }, ifRevision),
        mutation = true,
    ) { }

public suspend fun LuviaSession.moveTab(
    tab: Int,
    to: Int,
    ifRevision: Long? = null,
): Outcome<TabMoveResult> =
    engine.unary(
        "tab.move",
        withIfRevision(
            buildJsonObject {
                put("tab", tab)
                put("to", to)
            },
            ifRevision,
        ),
        mutation = true,
    ) { mapTabMove(it.asObjectOrEmpty()) }

public suspend fun LuviaSession.moveTab(
    direction: TabMoveDirection,
    tab: Int? = null,
    ifRevision: Long? = null,
): Outcome<TabMoveResult> =
    engine.unary(
        "tab.move",
        withIfRevision(
            buildJsonObject {
                put("direction", direction.wireName())
                if (tab != null) put("tab", tab)
            },
            ifRevision,
        ),
        mutation = true,
    ) { mapTabMove(it.asObjectOrEmpty()) }

public suspend fun LuviaSession.swapTabs(
    tab: Int,
    with: Int,
    ifRevision: Long? = null,
): Outcome<TabSwapResult> =
    engine.unary(
        "tab.swap",
        withIfRevision(
            buildJsonObject {
                put("tab", tab)
                put("with", with)
            },
            ifRevision,
        ),
        mutation = true,
    ) { mapTabSwap(it.asObjectOrEmpty()) }

public suspend fun LuviaSession.renameTab(
    name: String,
    tab: Int? = null,
    ifRevision: Long? = null,
): Outcome<Unit> =
    engine.unary(
        "tab.rename",
        withIfRevision(
            buildJsonObject {
                put("name", name)
                if (tab != null) put("tab", tab)
            },
            ifRevision,
        ),
        mutation = true,
    ) { }

public suspend fun LuviaSession.closeTab(
    tab: Int? = null,
    ifRevision: Long? = null,
): Outcome<Unit> =
    engine.unary(
        "tab.close",
        withIfRevision(
            buildJsonObject { if (tab != null) put("tab", tab) },
            ifRevision,
        ),
        mutation = true,
    ) { }

public suspend fun LuviaSession.listPanes(): Outcome<PaneListResult> =
    engine.unary("pane.list", JsonObject(emptyMap()), mutation = false) {
        mapPaneList(it.asObjectOrEmpty())
    }

public suspend fun LuviaSession.getPane(pane: String? = null): Outcome<PaneGetResult> =
    engine.unary("pane.get", paneParams(pane), mutation = false) {
        mapPaneGet(it.asObjectOrEmpty())
    }

public suspend fun LuviaSession.currentPane(): Outcome<PaneGetResult> =
    engine.unary("pane.current", JsonObject(emptyMap()), mutation = false) {
        mapPaneGet(it.asObjectOrEmpty())
    }

public suspend fun LuviaSession.paneLayout(pane: String? = null): Outcome<PaneLayoutResult> =
    engine.unary("pane.layout", paneParams(pane), mutation = false) {
        mapPaneLayout(it.asObjectOrEmpty())
    }

public suspend fun LuviaSession.paneNeighbor(
    direction: PaneDirection,
    pane: String? = null,
): Outcome<PaneNeighborResult> =
    engine.unary(
        "pane.neighbor",
        buildJsonObject {
            if (pane != null) put("pane", pane)
            put("direction", direction.wireName())
        },
        mutation = false,
    ) { mapPaneNeighbor(it.asObjectOrEmpty()) }

public suspend fun LuviaSession.paneEdges(pane: String? = null): Outcome<PaneEdgesResult> =
    engine.unary("pane.edges", paneParams(pane), mutation = false) {
        mapPaneEdges(it.asObjectOrEmpty())
    }

public suspend fun LuviaSession.movePane(
    tab: Int,
    pane: String? = null,
    ifRevision: Long? = null,
): Outcome<PaneMoveResult> =
    engine.unary(
        "pane.move",
        withIfRevision(
            buildJsonObject {
                if (pane != null) put("pane", pane)
                put("tab", tab)
            },
            ifRevision,
        ),
        mutation = true,
    ) { mapPaneMove(it.asObjectOrEmpty()) }

public suspend fun LuviaSession.movePaneToNewTab(
    pane: String? = null,
    ifRevision: Long? = null,
): Outcome<PaneMoveResult> =
    engine.unary(
        "pane.move",
        withIfRevision(
            buildJsonObject {
                if (pane != null) put("pane", pane)
                put("new_tab", true)
            },
            ifRevision,
        ),
        mutation = true,
    ) { mapPaneMove(it.asObjectOrEmpty()) }

public suspend fun LuviaSession.swapPanes(
    with: String,
    pane: String? = null,
    ifRevision: Long? = null,
): Outcome<PaneSwapResult> =
    engine.unary(
        "pane.swap",
        withIfRevision(
            buildJsonObject {
                if (pane != null) put("pane", pane)
                put("with", with)
            },
            ifRevision,
        ),
        mutation = true,
    ) { mapPaneSwap(it.asObjectOrEmpty()) }

public suspend fun LuviaSession.focusPane(
    pane: String? = null,
    ifRevision: Long? = null,
): Outcome<Unit> =
    engine.unary(
        "pane.focus",
        withIfRevision(paneParams(pane), ifRevision),
        mutation = true,
    ) { }

public suspend fun LuviaSession.focusPaneDirection(
    direction: PaneDirection,
    pane: String? = null,
    ifRevision: Long? = null,
): Outcome<PaneFocusResult> =
    engine.unary(
        "pane.focus_direction",
        withIfRevision(
            buildJsonObject {
                if (pane != null) put("pane", pane)
                put("direction", direction.wireName())
            },
            ifRevision,
        ),
        mutation = true,
    ) { mapPaneFocus(it.asObjectOrEmpty()) }

public suspend fun LuviaSession.resizePane(
    direction: PaneDirection,
    cells: Int = 1,
    pane: String? = null,
    ifRevision: Long? = null,
): Outcome<PaneResizeResult> =
    engine.unary(
        "pane.resize",
        withIfRevision(
            buildJsonObject {
                if (pane != null) put("pane", pane)
                put("direction", direction.wireName())
                put("cells", cells)
            },
            ifRevision,
        ),
        mutation = true,
    ) { mapPaneResize(it.asObjectOrEmpty()) }

public suspend fun LuviaSession.zoomPane(
    enabled: Boolean? = null,
    pane: String? = null,
    ifRevision: Long? = null,
): Outcome<PaneZoomResult> =
    engine.unary(
        "pane.zoom",
        withIfRevision(
            buildJsonObject {
                if (pane != null) put("pane", pane)
                if (enabled != null) put("enabled", enabled)
            },
            ifRevision,
        ),
        mutation = true,
    ) { mapPaneZoom(it.asObjectOrEmpty()) }

public suspend fun LuviaSession.renamePane(
    name: String,
    pane: String? = null,
    ifRevision: Long? = null,
): Outcome<PaneRenameResult> =
    engine.unary(
        "pane.rename",
        withIfRevision(
            buildJsonObject {
                if (pane != null) put("pane", pane)
                put("name", name)
            },
            ifRevision,
        ),
        mutation = true,
    ) { mapPaneRename(it.asObjectOrEmpty()) }

public suspend fun LuviaSession.runPane(
    command: String,
    pane: String? = null,
    ifRevision: Long? = null,
): Outcome<Unit> =
    engine.unary(
        "pane.run",
        withIfRevision(
            buildJsonObject {
                if (pane != null) put("pane", pane)
                put("command", command)
            },
            ifRevision,
        ),
        mutation = true,
    ) { }

public suspend fun LuviaSession.readPane(
    pane: String? = null,
    lines: Int? = null,
): Outcome<PaneReadResult> =
    engine.unary(
        "pane.read",
        buildJsonObject {
            if (pane != null) put("pane", pane)
            if (lines != null) put("lines", lines)
        },
        mutation = false,
    ) { mapPaneRead(it.asObjectOrEmpty()) }

public suspend fun LuviaSession.paneStatus(pane: String? = null): Outcome<PaneStatusResult> =
    engine.unary("pane.status", paneParams(pane), mutation = false) {
        mapPaneStatus(it.asObjectOrEmpty())
    }

public suspend fun LuviaSession.paneProcesses(pane: String? = null): Outcome<PaneProcessesResult> =
    engine.unary("pane.processes", paneParams(pane), mutation = false) {
        mapPaneProcesses(it.asObjectOrEmpty())
    }

public suspend fun LuviaSession.closePane(
    pane: String? = null,
    ifRevision: Long? = null,
): Outcome<Unit> =
    engine.unary(
        "pane.close",
        withIfRevision(paneParams(pane), ifRevision),
        mutation = true,
    ) { }

public suspend fun LuviaSession.attachPane(
    pane: String? = null,
    ifRevision: Long? = null,
): Outcome<AttachPaneResult> =
    engine.unary(
        "attach.pane",
        withIfRevision(paneParams(pane), ifRevision),
        mutation = true,
    ) { mapAttachPane(it.asObjectOrEmpty()) }

public suspend fun LuviaSession.exportLayout(
    workspace: Int? = null,
    tab: Int? = null,
): Outcome<LayoutExportResult> =
    engine.unary(
        "layout.export",
        buildJsonObject {
            if (workspace != null) put("workspace", workspace)
            if (tab != null) put("tab", tab)
        },
        mutation = false,
    ) { mapLayoutExport(it.asObjectOrEmpty()) }

public suspend fun LuviaSession.applyLayout(
    tree: LayoutTree,
    workspace: Int? = null,
    tab: Int? = null,
    focus: String? = null,
    ifRevision: Long? = null,
): Outcome<LayoutApplyResult> =
    engine.unary(
        "layout.apply",
        withIfRevision(
            buildJsonObject {
                if (workspace != null) put("workspace", workspace)
                if (tab != null) put("tab", tab)
                if (focus != null) put("focus", focus)
                put("tree", tree.toJson())
            },
            ifRevision,
        ),
        mutation = true,
    ) { mapLayoutApply(it.asObjectOrEmpty()) }

public suspend fun LuviaSession.setSplitRatio(
    path: List<LayoutSplitStep>,
    ratio: Double,
    workspace: Int? = null,
    tab: Int? = null,
    ifRevision: Long? = null,
): Outcome<LayoutSplitRatioResult> =
    engine.unary(
        "layout.set_split_ratio",
        withIfRevision(
            buildJsonObject {
                if (workspace != null) put("workspace", workspace)
                if (tab != null) put("tab", tab)
                put("path", stringArray(path.map { it.wireName() }))
                put("ratio", ratio)
            },
            ifRevision,
        ),
        mutation = true,
    ) { mapLayoutSplitRatio(it.asObjectOrEmpty()) }

private fun PaneDirection.wireName(): String =
    when (this) {
        PaneDirection.Left -> "left"
        PaneDirection.Right -> "right"
        PaneDirection.Up -> "up"
        PaneDirection.Down -> "down"
    }

private fun TabMoveDirection.wireName(): String =
    when (this) {
        TabMoveDirection.Left -> "left"
        TabMoveDirection.Right -> "right"
    }

private fun LayoutSplitStep.wireName(): String =
    when (this) {
        LayoutSplitStep.A -> "a"
        LayoutSplitStep.B -> "b"
    }

private fun paneParams(pane: String?): JsonObject =
    if (pane == null) JsonObject(emptyMap()) else buildJsonObject { put("pane", pane) }

private fun stringArray(values: List<String>): JsonArray =
    buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

private fun intArray(values: List<Int>): JsonArray =
    buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

private fun JsonElement.asObjectOrEmpty(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())

private fun mapWorkspaceCreated(result: JsonObject): WorkspaceOpenResult =
    WorkspaceOpenResult(
        workspace = result.optionalWireString("workspace") ?: "",
        revision = result.optionalStrictLong("revision"),
    )

private fun mapWorkspaceRename(result: JsonObject): WorkspaceRenameResult =
    WorkspaceRenameResult(
        workspace = result.optionalWireString("workspace") ?: "",
        name = result.optionalString("name") ?: "",
        cwd = result.optionalString("cwd"),
        pinned = result.booleanOrFalse("pinned"),
        displayPosition = result.optionalWireString("display_position"),
        revision = result.optionalStrictLong("revision"),
    )

private fun mapWorkspacePin(result: JsonObject): WorkspacePinResult =
    WorkspacePinResult(
        workspace = result.optionalWireString("workspace") ?: "",
        name = result.optionalString("name") ?: "",
        cwd = result.optionalString("cwd"),
        pinned = result.booleanOrFalse("pinned"),
        displayPosition = result.optionalWireString("display_position"),
        revision = result.optionalStrictLong("revision"),
    )

private fun mapWorkspaceMove(result: JsonObject): WorkspaceMoveResult =
    WorkspaceMoveResult(
        workspace = result.optionalWireString("workspace") ?: "",
        to = result.optionalWireString("to") ?: "",
        revision = result.optionalStrictLong("revision"),
    )

private fun mapWorkspaceMoveBlock(result: JsonObject): WorkspaceMoveBlockResult =
    WorkspaceMoveBlockResult(
        workspaces = result.wireIntList("workspaces"),
        positions = result.wireIntList("positions"),
        revision = result.optionalStrictLong("revision"),
    )

private fun mapTabList(result: JsonObject): TabListResult =
    TabListResult(
        tabs = result.optionalObjectList("tabs").map { mapTabListEntry(it) },
        revision = result.optionalStrictLong("revision"),
    )

private fun mapTabListEntry(obj: JsonObject): TabListEntry =
    TabListEntry(
        tab = obj.optionalWireString("tab") ?: "",
        tabId = obj.optionalString("tab_id"),
        active = obj.booleanOrFalse("active"),
        name = obj.optionalString("name"),
        kind = obj.optionalString("kind"),
    )

private fun mapTabGet(result: JsonObject): TabGetResult =
    TabGetResult(
        workspace = result.optionalWireString("workspace") ?: "",
        workspaceId = result.optionalString("workspace_id"),
        tab = result.optionalWireString("tab") ?: "",
        tabId = result.optionalString("tab_id"),
        active = result.booleanOrFalse("active"),
        name = result.optionalString("name"),
        kind = result.optionalString("kind"),
        focus = result.optionalWireString("focus"),
        panes = result.optionalStringList("panes"),
        revision = result.optionalStrictLong("revision"),
    )

private fun mapTabNew(result: JsonObject): TabNewResult =
    TabNewResult(
        tab = result.optionalWireString("tab") ?: "",
        revision = result.optionalStrictLong("revision"),
    )

private fun mapTabMove(result: JsonObject): TabMoveResult =
    TabMoveResult(
        from = result.optionalWireString("from") ?: "",
        to = result.optionalWireString("to") ?: "",
        active = result.optionalWireString("active") ?: "",
        revision = result.optionalStrictLong("revision"),
    )

private fun mapTabSwap(result: JsonObject): TabSwapResult =
    TabSwapResult(
        tab = result.optionalWireString("tab") ?: "",
        with = result.optionalWireString("with") ?: "",
        active = result.optionalWireString("active") ?: "",
        revision = result.optionalStrictLong("revision"),
    )

private fun mapPaneList(result: JsonObject): PaneListResult =
    PaneListResult(
        panes = result.optionalObjectList("panes").map { mapPaneListEntry(it) },
        revision = result.optionalStrictLong("revision"),
    )

private fun mapPaneListEntry(obj: JsonObject): PaneListEntry =
    PaneListEntry(
        pane = obj.optionalWireString("pane") ?: "",
        agent = obj.optionalString("agent"),
        status = parseAgentStatus(obj.optionalString("status")),
        focused = obj.booleanOrFalse("focused"),
        cwd = obj.optionalString("cwd"),
        module = obj.optionalObject("module")?.toPaneModule(),
        scrollOffset = obj.optionalStrictLong("scroll_offset"),
        historyRows = obj.optionalStrictLong("history_rows"),
        historyBudgetBytes = obj.optionalStrictLong("history_budget_bytes"),
        historyBytes = obj.optionalStrictLong("history_bytes"),
        historyExact = obj.optionalBoolean("history_exact"),
        historyBytesKind = obj.optionalString("history_bytes_kind"),
    )

private fun mapPaneGet(result: JsonObject): PaneGetResult =
    PaneGetResult(
        pane = result.optionalWireString("pane") ?: "",
        workspace = result.optionalWireString("workspace"),
        workspaceId = result.optionalString("workspace_id"),
        tab = result.optionalWireString("tab"),
        tabId = result.optionalString("tab_id"),
        terminalId = result.optionalString("terminal_id"),
        focused = result.booleanOrFalse("focused"),
        name = result.optionalString("name"),
        cwd = result.optionalString("cwd"),
        command = result.optionalString("command"),
        agent = result.optionalString("agent"),
        status = parseAgentStatus(result.optionalString("status")),
        module = result.optionalObject("module")?.toPaneModule(),
        revision = result.optionalStrictLong("revision"),
    )

private fun mapPaneLayout(result: JsonObject): PaneLayoutResult =
    PaneLayoutResult(
        pane = result.optionalWireString("pane") ?: "",
        workspace = result.optionalWireString("workspace"),
        tab = result.optionalWireString("tab"),
        logicalSize = result.optionalObject("logical_size")?.toPaneSize(),
        rect = result.optionalObject("rect")?.toPaneRect(),
        tree = result["tree"]?.toLayoutTree(),
        revision = result.optionalStrictLong("revision"),
    )

private fun mapPaneNeighbor(result: JsonObject): PaneNeighborResult =
    PaneNeighborResult(
        pane = result.optionalWireString("pane") ?: "",
        neighbor = result.optionalWireString("neighbor"),
        revision = result.optionalStrictLong("revision"),
    )

private fun mapPaneEdges(result: JsonObject): PaneEdgesResult {
    val edges = result.optionalObject("edges")
    return PaneEdgesResult(
        pane = result.optionalWireString("pane") ?: "",
        edges =
            PaneEdges(
                left = edges?.booleanOrFalse("left") ?: false,
                right = edges?.booleanOrFalse("right") ?: false,
                top = edges?.booleanOrFalse("top") ?: false,
                bottom = edges?.booleanOrFalse("bottom") ?: false,
            ),
        revision = result.optionalStrictLong("revision"),
    )
}

private fun mapPaneMove(result: JsonObject): PaneMoveResult =
    PaneMoveResult(
        pane = result.optionalWireString("pane") ?: "",
        workspace = result.optionalWireString("workspace"),
        tab = result.optionalWireString("tab"),
        revision = result.optionalStrictLong("revision"),
    )

private fun mapPaneSwap(result: JsonObject): PaneSwapResult =
    PaneSwapResult(
        pane = result.optionalWireString("pane") ?: "",
        with = result.optionalWireString("with") ?: "",
        revision = result.optionalStrictLong("revision"),
    )

private fun mapPaneFocus(result: JsonObject): PaneFocusResult =
    PaneFocusResult(
        pane = result.optionalWireString("pane") ?: "",
        revision = result.optionalStrictLong("revision"),
    )

private fun mapPaneResize(result: JsonObject): PaneResizeResult =
    PaneResizeResult(
        pane = result.optionalWireString("pane") ?: "",
        revision = result.optionalStrictLong("revision"),
    )

private fun mapPaneZoom(result: JsonObject): PaneZoomResult =
    PaneZoomResult(
        pane = result.optionalWireString("pane") ?: "",
        enabled = result.booleanOrFalse("enabled"),
        revision = result.optionalStrictLong("revision"),
    )

private fun mapPaneRename(result: JsonObject): PaneRenameResult =
    PaneRenameResult(
        pane = result.optionalWireString("pane") ?: "",
        name = result.optionalString("name"),
        revision = result.optionalStrictLong("revision"),
    )

private fun mapPaneRead(result: JsonObject): PaneReadResult =
    PaneReadResult(
        text = result.optionalString("text") ?: "",
        revision = result.optionalStrictLong("revision"),
    )

private fun mapPaneStatus(result: JsonObject): PaneStatusResult =
    PaneStatusResult(
        pane = result.optionalWireString("pane") ?: "",
        agent = result.optionalString("agent"),
        status = parseAgentStatus(result.optionalString("status")),
        authority = result.optionalString("authority"),
        stateSource = result.optionalString("state_source"),
        scrollOffset = result.optionalStrictLong("scroll_offset"),
        historyRows = result.optionalStrictLong("history_rows"),
        historyBudgetBytes = result.optionalStrictLong("history_budget_bytes"),
        historyBytes = result.optionalStrictLong("history_bytes"),
        historyExact = result.optionalBoolean("history_exact"),
        historyBytesKind = result.optionalString("history_bytes_kind"),
        revision = result.optionalStrictLong("revision"),
    )

private fun mapPaneProcesses(result: JsonObject): PaneProcessesResult {
    val root = result.optionalObject("root_process")
    return PaneProcessesResult(
        pane = result.optionalWireString("pane") ?: "",
        terminalId = result.optionalString("terminal_id"),
        rootProcess =
            root?.optionalStrictLong("pid")?.let { pid ->
                ProcessIdentity(pid = pid, startMarker = root.optionalString("start_marker"))
            },
        scan = result.optionalString("scan"),
        executables = result.optionalStringList("executables"),
        argumentsExposed = result.booleanOrFalse("arguments_exposed"),
        revision = result.optionalStrictLong("revision"),
    )
}

private fun mapAttachPane(result: JsonObject): AttachPaneResult =
    AttachPaneResult(
        pane = result.optionalWireString("pane") ?: "",
        revision = result.optionalStrictLong("revision"),
    )

private fun mapLayoutExport(result: JsonObject): LayoutExportResult =
    LayoutExportResult(
        workspace = result.optionalWireString("workspace") ?: "",
        tab = result.optionalWireString("tab") ?: "",
        focus = result.optionalWireString("focus"),
        tree = result["tree"]?.toLayoutTree(),
        revision = result.optionalStrictLong("revision"),
    )

private fun mapLayoutApply(result: JsonObject): LayoutApplyResult =
    LayoutApplyResult(
        workspace = result.optionalWireString("workspace") ?: "",
        tab = result.optionalWireString("tab") ?: "",
        revision = result.optionalStrictLong("revision"),
    )

private fun mapLayoutSplitRatio(result: JsonObject): LayoutSplitRatioResult =
    LayoutSplitRatioResult(
        workspace = result.optionalWireString("workspace") ?: "",
        tab = result.optionalWireString("tab") ?: "",
        ratio = result.optionalDouble("ratio"),
        revision = result.optionalStrictLong("revision"),
    )

private fun JsonObject.toPaneModule(): PaneModuleRef =
    PaneModuleRef(
        id = optionalString("id") ?: "",
        entrypoint = optionalString("entrypoint"),
    )

private fun JsonObject.toPaneSize(): PaneSize =
    PaneSize(
        width = optionalStrictLong("width"),
        height = optionalStrictLong("height"),
    )

private fun JsonObject.toPaneRect(): PaneRect =
    PaneRect(
        x = optionalStrictLong("x"),
        y = optionalStrictLong("y"),
        width = optionalStrictLong("width"),
        height = optionalStrictLong("height"),
    )

private fun JsonObject.wireIntList(key: String): List<Int> {
    val value = this[key] as? JsonArray ?: return emptyList()
    return value.mapNotNull { el ->
        val primitive = el as? JsonPrimitive ?: return@mapNotNull null
        primitive.content.toIntOrNull()
    }
}

private fun LayoutTree.toJson(): JsonElement =
    when (this) {
        is LayoutTree.Leaf -> buildJsonObject { put("Leaf", pane) }
        is LayoutTree.Split ->
            buildJsonObject {
                put(
                    "Split",
                    buildJsonObject {
                        put("axis", axis)
                        put("ratio", ratio)
                        put("a", a.toJson())
                        put("b", b.toJson())
                    },
                )
            }
    }

private fun JsonElement.toLayoutTree(): LayoutTree? {
    val obj = this as? JsonObject ?: return null
    obj["Leaf"]?.let { leaf ->
        val primitive = leaf as? JsonPrimitive ?: return null
        val pane = primitive.content.toLongOrNull() ?: return null
        return LayoutTree.Leaf(pane)
    }
    val split = obj["Split"] as? JsonObject ?: return null
    val axis = split.optionalStrictLong("axis")?.toInt() ?: return null
    val ratio = split.optionalDouble("ratio") ?: return null
    val a = split["a"]?.toLayoutTree() ?: return null
    val b = split["b"]?.toLayoutTree() ?: return null
    return LayoutTree.Split(axis = axis, ratio = ratio, a = a, b = b)
}
