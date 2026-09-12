package tech.asahiart.luvia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import tech.asahiart.luvia.internal.NdjsonFramer
import tech.asahiart.luvia.internal.UhpRequest
import tech.asahiart.luvia.internal.decodeUhpRequest
import tech.asahiart.luvia.internal.parseObject
import tech.asahiart.luvia.support.ScriptedFactory

class LayoutSurfaceTest {
    @Test
    fun encodesLayoutMethodsWithExactParams() = runTest {
        val seen = mutableListOf<UhpRequest>()
        val session = openLayout(backgroundScope) { seen += it }
        val tree =
            LayoutTree.Split(
                axis = 0,
                ratio = 0.5,
                a = LayoutTree.Leaf(7),
                b = LayoutTree.Leaf(9),
            )

        session.newWorkspace()
        session.renameWorkspace(0, "proj")
        session.pinWorkspace(0, true)
        session.moveWorkspace(0, 2)
        session.moveWorkspaceBlock(listOf(0, 1), to = 2)
        session.closeWorkspace(0, ifRevision = 41)

        session.listTabs()
        session.getTab(workspace = 0, tab = 1)
        session.newTab()
        session.focusTab(1)
        session.moveTab(tab = 1, to = 2)
        session.moveTab(TabMoveDirection.Right, tab = 1)
        session.swapTabs(1, 2)
        session.renameTab("main", tab = 1)
        session.closeTab(1)

        session.listPanes()
        session.getPane("7")
        session.currentPane()
        session.paneLayout("7")
        session.paneNeighbor(PaneDirection.Right, pane = "7")
        session.paneEdges("7")
        session.movePane(tab = 2, pane = "7")
        session.movePaneToNewTab(pane = "7")
        session.swapPanes(with = "9", pane = "7")
        session.focusPane("7")
        session.focusPaneDirection(PaneDirection.Right, pane = "7")
        session.resizePane(PaneDirection.Right, cells = 3, pane = "7")
        session.zoomPane(enabled = true, pane = "7")
        session.renamePane("reviewer", pane = "7")
        session.runPane("ls", pane = "7")
        session.readPane(pane = "7", lines = 80)
        session.paneStatus("7")
        session.paneProcesses("7")
        session.closePane("7")

        session.attachPane("7")
        session.exportLayout(workspace = 0, tab = 1)
        session.applyLayout(tree, workspace = 0, tab = 1, focus = "7")
        session.setSplitRatio(
            path = listOf(LayoutSplitStep.A, LayoutSplitStep.B),
            ratio = 0.5,
            workspace = 0,
            tab = 1,
        )

        fun params(method: String): JsonObject = seen.single { it.method == method }.params

        assertTrue(params("workspace.new").isEmpty())
        assertFalse("if_revision" in params("workspace.new"))

        val rename = params("workspace.rename")
        assertEquals("0", rename.getValue("workspace").jsonPrimitive.content)
        assertFalse(rename.getValue("workspace").jsonPrimitive.isString)
        assertEquals("proj", rename.getValue("name").jsonPrimitive.content)
        assertEquals(setOf("workspace", "name"), rename.keys)

        val pin = params("workspace.pin")
        assertEquals("0", pin.getValue("workspace").jsonPrimitive.content)
        assertEquals("true", pin.getValue("pinned").jsonPrimitive.content)
        assertFalse(pin.getValue("pinned").jsonPrimitive.isString)
        assertEquals(setOf("workspace", "pinned"), pin.keys)

        val move = params("workspace.move")
        assertEquals("0", move.getValue("workspace").jsonPrimitive.content)
        assertEquals("2", move.getValue("to").jsonPrimitive.content)
        assertEquals(setOf("workspace", "to"), move.keys)

        val block = params("workspace.move_block")
        assertEquals(
            listOf("0", "1"),
            block.getValue("workspaces").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("2", block.getValue("to").jsonPrimitive.content)
        assertEquals(setOf("workspaces", "to"), block.keys)

        val closeWs = params("workspace.close")
        assertEquals("0", closeWs.getValue("workspace").jsonPrimitive.content)
        assertEquals("41", closeWs.getValue("if_revision").jsonPrimitive.content)
        assertEquals(setOf("workspace", "if_revision"), closeWs.keys)

        assertTrue(params("tab.list").isEmpty())
        assertFalse("if_revision" in params("tab.list"))

        val getTab = params("tab.get")
        assertEquals("0", getTab.getValue("workspace").jsonPrimitive.content)
        assertEquals("1", getTab.getValue("tab").jsonPrimitive.content)
        assertFalse("if_revision" in getTab)
        assertEquals(setOf("workspace", "tab"), getTab.keys)

        assertTrue(params("tab.new").isEmpty())
        assertEquals("1", params("tab.focus").getValue("tab").jsonPrimitive.content)
        assertEquals(setOf("tab"), params("tab.focus").keys)

        val tabMoves = seen.filter { it.method == "tab.move" }
        assertEquals(2, tabMoves.size)
        assertEquals("1", tabMoves[0].params.getValue("tab").jsonPrimitive.content)
        assertEquals("2", tabMoves[0].params.getValue("to").jsonPrimitive.content)
        assertEquals(setOf("tab", "to"), tabMoves[0].params.keys)
        assertEquals("right", tabMoves[1].params.getValue("direction").jsonPrimitive.content)
        assertEquals("1", tabMoves[1].params.getValue("tab").jsonPrimitive.content)
        assertEquals(setOf("direction", "tab"), tabMoves[1].params.keys)

        val swap = params("tab.swap")
        assertEquals("1", swap.getValue("tab").jsonPrimitive.content)
        assertEquals("2", swap.getValue("with").jsonPrimitive.content)
        assertEquals(setOf("tab", "with"), swap.keys)

        val renameTab = params("tab.rename")
        assertEquals("main", renameTab.getValue("name").jsonPrimitive.content)
        assertEquals("1", renameTab.getValue("tab").jsonPrimitive.content)
        assertEquals(setOf("name", "tab"), renameTab.keys)
        assertEquals("1", params("tab.close").getValue("tab").jsonPrimitive.content)

        assertTrue(params("pane.list").isEmpty())
        assertFalse("if_revision" in params("pane.list"))
        assertEquals("7", params("pane.get").getValue("pane").jsonPrimitive.content)
        assertTrue(params("pane.get").getValue("pane").jsonPrimitive.isString)
        assertTrue(params("pane.current").isEmpty())
        assertEquals("7", params("pane.layout").getValue("pane").jsonPrimitive.content)

        val neighbor = params("pane.neighbor")
        assertEquals("7", neighbor.getValue("pane").jsonPrimitive.content)
        assertEquals("right", neighbor.getValue("direction").jsonPrimitive.content)
        assertEquals(setOf("pane", "direction"), neighbor.keys)
        assertFalse("if_revision" in neighbor)
        assertEquals("7", params("pane.edges").getValue("pane").jsonPrimitive.content)

        val paneMoves = seen.filter { it.method == "pane.move" }
        assertEquals(2, paneMoves.size)
        assertEquals("7", paneMoves[0].params.getValue("pane").jsonPrimitive.content)
        assertEquals("2", paneMoves[0].params.getValue("tab").jsonPrimitive.content)
        assertEquals(setOf("pane", "tab"), paneMoves[0].params.keys)
        assertEquals("7", paneMoves[1].params.getValue("pane").jsonPrimitive.content)
        assertEquals("true", paneMoves[1].params.getValue("new_tab").jsonPrimitive.content)
        assertEquals(setOf("pane", "new_tab"), paneMoves[1].params.keys)

        val paneSwap = params("pane.swap")
        assertEquals("7", paneSwap.getValue("pane").jsonPrimitive.content)
        assertEquals("9", paneSwap.getValue("with").jsonPrimitive.content)
        assertTrue(paneSwap.getValue("with").jsonPrimitive.isString)
        assertEquals("7", params("pane.focus").getValue("pane").jsonPrimitive.content)

        val focusDir = params("pane.focus_direction")
        assertEquals("7", focusDir.getValue("pane").jsonPrimitive.content)
        assertEquals("right", focusDir.getValue("direction").jsonPrimitive.content)

        val resize = params("pane.resize")
        assertEquals("7", resize.getValue("pane").jsonPrimitive.content)
        assertEquals("right", resize.getValue("direction").jsonPrimitive.content)
        assertEquals("3", resize.getValue("cells").jsonPrimitive.content)
        assertEquals(setOf("pane", "direction", "cells"), resize.keys)

        val zoom = params("pane.zoom")
        assertEquals("7", zoom.getValue("pane").jsonPrimitive.content)
        assertEquals("true", zoom.getValue("enabled").jsonPrimitive.content)
        assertEquals("reviewer", params("pane.rename").getValue("name").jsonPrimitive.content)
        assertEquals("ls", params("pane.run").getValue("command").jsonPrimitive.content)

        val read = params("pane.read")
        assertEquals("7", read.getValue("pane").jsonPrimitive.content)
        assertEquals("80", read.getValue("lines").jsonPrimitive.content)
        assertFalse("if_revision" in read)
        assertEquals("7", params("pane.status").getValue("pane").jsonPrimitive.content)
        assertFalse("if_revision" in params("pane.status"))
        assertEquals("7", params("pane.processes").getValue("pane").jsonPrimitive.content)
        assertEquals("7", params("pane.close").getValue("pane").jsonPrimitive.content)
        assertEquals("7", params("attach.pane").getValue("pane").jsonPrimitive.content)

        val exported = params("layout.export")
        assertEquals("0", exported.getValue("workspace").jsonPrimitive.content)
        assertEquals("1", exported.getValue("tab").jsonPrimitive.content)
        assertFalse("if_revision" in exported)
        assertEquals(setOf("workspace", "tab"), exported.keys)

        val apply = params("layout.apply")
        assertEquals("0", apply.getValue("workspace").jsonPrimitive.content)
        assertEquals("1", apply.getValue("tab").jsonPrimitive.content)
        assertEquals("7", apply.getValue("focus").jsonPrimitive.content)
        assertEquals("7", apply.getValue("tree").jsonObject.getValue("Split").jsonObject.getValue("a").jsonObject.getValue("Leaf").jsonPrimitive.content)
        assertEquals("9", apply.getValue("tree").jsonObject.getValue("Split").jsonObject.getValue("b").jsonObject.getValue("Leaf").jsonPrimitive.content)
        assertEquals("0", apply.getValue("tree").jsonObject.getValue("Split").jsonObject.getValue("axis").jsonPrimitive.content)
        assertEquals("0.5", apply.getValue("tree").jsonObject.getValue("Split").jsonObject.getValue("ratio").jsonPrimitive.content)
        assertEquals(setOf("workspace", "tab", "focus", "tree"), apply.keys)

        val ratio = params("layout.set_split_ratio")
        assertEquals("0", ratio.getValue("workspace").jsonPrimitive.content)
        assertEquals("1", ratio.getValue("tab").jsonPrimitive.content)
        assertEquals(
            listOf("a", "b"),
            ratio.getValue("path").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("0.5", ratio.getValue("ratio").jsonPrimitive.content)
        assertEquals(setOf("workspace", "tab", "path", "ratio"), ratio.keys)

        session.close()
    }

    @Test
    fun mapsLayoutResultFixtures() = runTest {
        val session = openLayout(backgroundScope) { }

        val created = (session.newWorkspace() as Outcome.Ok).value
        assertEquals("1", created.workspace)
        assertEquals(11L, created.revision)

        val renamed = (session.renameWorkspace(0, "proj") as Outcome.Ok).value
        assertEquals("0", renamed.workspace)
        assertEquals("proj", renamed.name)
        assertEquals("/tmp/work", renamed.cwd)
        assertFalse(renamed.pinned)
        assertEquals("0", renamed.displayPosition)

        val pinned = (session.pinWorkspace(0, true) as Outcome.Ok).value
        assertTrue(pinned.pinned)
        assertEquals("0", pinned.displayPosition)

        val moved = (session.moveWorkspace(0, 2) as Outcome.Ok).value
        assertEquals("0", moved.workspace)
        assertEquals("2", moved.to)

        val block = (session.moveWorkspaceBlock(listOf(0, 1), 2) as Outcome.Ok).value
        assertEquals(listOf(0, 1), block.workspaces)
        assertEquals(listOf(2, 3), block.positions)

        val tabs = (session.listTabs() as Outcome.Ok).value
        assertEquals("1", tabs.tabs.single().tab)
        assertEquals("tab_abc", tabs.tabs.single().tabId)
        assertEquals("panes", tabs.tabs.single().kind)
        assertEquals("main", tabs.tabs.single().name)

        val tab = (session.getTab(0, 1) as Outcome.Ok).value
        assertEquals("workspace_example", tab.workspaceId)
        assertEquals("7", tab.focus)
        assertEquals(listOf("7", "9"), tab.panes)

        val newTab = (session.newTab() as Outcome.Ok).value
        assertEquals("2", newTab.tab)

        val tabMove = (session.moveTab(1, 2) as Outcome.Ok).value
        assertEquals("1", tabMove.from)
        assertEquals("2", tabMove.to)
        assertEquals("2", tabMove.active)

        val tabSwap = (session.swapTabs(1, 2) as Outcome.Ok).value
        assertEquals("1", tabSwap.tab)
        assertEquals("2", tabSwap.with)

        val panes = (session.listPanes() as Outcome.Ok).value
        assertEquals("7", panes.panes.single().pane)
        assertEquals(AgentStatus.Working, panes.panes.single().status)
        assertEquals(420L, panes.panes.single().scrollOffset)
        assertEquals("estimated", panes.panes.single().historyBytesKind)
        assertFalse(panes.panes.single().historyExact ?: true)

        val pane = (session.getPane("7") as Outcome.Ok).value
        assertEquals("7", pane.pane)
        assertEquals("0123456789abcdef0123456789abcdef", pane.terminalId)
        assertEquals("reviewer", pane.name)
        assertEquals("files", pane.module?.id)

        val current = (session.currentPane() as Outcome.Ok).value
        assertEquals("7", current.pane)
        assertTrue(current.focused)

        val layout = (session.paneLayout("7") as Outcome.Ok).value
        assertEquals(10000L, layout.logicalSize?.width)
        assertEquals(0L, layout.rect?.x)
        val layoutTree = assertIs<LayoutTree.Split>(layout.tree)
        assertEquals(LayoutTree.Leaf(7), layoutTree.a)
        assertEquals(LayoutTree.Leaf(9), layoutTree.b)

        val neighbor = (session.paneNeighbor(PaneDirection.Right, "7") as Outcome.Ok).value
        assertEquals("9", neighbor.neighbor)

        val edges = (session.paneEdges("7") as Outcome.Ok).value
        assertTrue(edges.edges.left)
        assertFalse(edges.edges.right)

        val paneMove = (session.movePane(2, "7") as Outcome.Ok).value
        assertEquals("0", paneMove.workspace)
        assertEquals("2", paneMove.tab)

        val zoom = (session.zoomPane(true, "7") as Outcome.Ok).value
        assertTrue(zoom.enabled)

        val read = (session.readPane("7", lines = 80) as Outcome.Ok).value
        assertEquals("hello from pane", read.text)

        val status = (session.paneStatus("7") as Outcome.Ok).value
        assertEquals(AgentStatus.Working, status.status)
        assertEquals("integration_report", status.authority)

        val processes = (session.paneProcesses("7") as Outcome.Ok).value
        assertEquals(4321L, processes.rootProcess?.pid)
        assertEquals("observed", processes.scan)
        assertEquals(listOf("zsh"), processes.executables)
        assertFalse(processes.argumentsExposed)

        val attached = (session.attachPane("7") as Outcome.Ok).value
        assertEquals("7", attached.pane)

        val exported = (session.exportLayout(0, 1) as Outcome.Ok).value
        assertEquals("0", exported.workspace)
        assertEquals("1", exported.tab)
        assertEquals("7", exported.focus)
        val exportedTree = assertIs<LayoutTree.Split>(exported.tree)

        val applied =
            (session.applyLayout(exportedTree, workspace = 0, tab = 1, focus = "7") as Outcome.Ok)
                .value
        assertEquals("0", applied.workspace)
        assertEquals("1", applied.tab)

        val ratio =
            (
                session.setSplitRatio(listOf(LayoutSplitStep.A), 0.5, workspace = 0, tab = 1)
                    as Outcome.Ok
            ).value
        assertEquals(0.5, ratio.ratio)

        assertIs<Outcome.Ok<*>>(session.closeWorkspace(0))
        assertIs<Outcome.Ok<*>>(session.focusTab(1))
        assertIs<Outcome.Ok<*>>(session.renameTab("main"))
        assertIs<Outcome.Ok<*>>(session.closeTab(1))
        assertIs<Outcome.Ok<*>>(session.focusPane("7"))
        assertIs<Outcome.Ok<*>>(session.runPane("ls", "7"))
        assertIs<Outcome.Ok<*>>(session.closePane("7"))

        session.close()
    }

    @Test
    fun supportsUsesAdvertisedLayoutMethods() = runTest {
        val session = openLayout(backgroundScope) { }
        assertTrue(session.supports("workspace.new"))
        assertTrue(session.supports("tab.move"))
        assertTrue(session.supports("pane.layout"))
        assertTrue(session.supports("layout.apply"))
        assertTrue(session.supports("attach.pane"))
        assertFalse(session.supports("node.new"))
        assertFalse(session.supports("pane.send_input"))
        session.close()
    }

    @Test
    fun omitsOptionalKeysAndIfRevisionOnReads() = runTest {
        val seen = mutableListOf<UhpRequest>()
        val session = openLayout(backgroundScope) { seen += it }

        session.getTab()
        session.readPane()
        session.exportLayout()
        session.zoomPane()
        session.closeWorkspace()

        assertTrue(seen.single { it.method == "tab.get" }.params.isEmpty())
        assertTrue(seen.single { it.method == "pane.read" }.params.isEmpty())
        assertTrue(seen.single { it.method == "layout.export" }.params.isEmpty())
        assertTrue(seen.single { it.method == "pane.zoom" }.params.isEmpty())
        assertTrue(seen.single { it.method == "workspace.close" }.params.isEmpty())
        assertFalse("if_revision" in seen.single { it.method == "tab.get" }.params)
        assertFalse("if_revision" in seen.single { it.method == "pane.read" }.params)

        session.close()
    }
}

private suspend fun openLayout(
    scope: kotlinx.coroutines.CoroutineScope,
    onRequest: (UhpRequest) -> Unit,
): LuviaSession {
    val factory =
        ScriptedFactory(scope) { framer ->
            dispatchLayout(framer, onRequest)
        }
    return (LuviaClient(factory).open("default") as Outcome.Ok).value
}

private suspend fun dispatchLayout(
    framer: NdjsonFramer,
    onRequest: (UhpRequest) -> Unit,
) {
    val prelude = parseObject(framer.readFrame())
    when (prelude["operation"]?.let { (it as JsonPrimitive).content }) {
        "discover" ->
            framer.writeFrame(
                """{"version":1,"sessions":[{"name":"default","default":true,"running":true,"transport":"unix_socket"}]}""",
            )
        "open" -> {
            framer.writeFrame("""{"version":1,"status":"ready","session":"default"}""")
            val request = decodeUhpRequest(framer.readFrame())
            onRequest(request)
            respondLayout(framer, request)
        }
    }
}

private suspend fun respondLayout(framer: NdjsonFramer, request: UhpRequest) {
    val result: JsonObject =
        when (request.method) {
            "uhp.capabilities" ->
                buildJsonObject {
                    put("type", "uhp_capabilities")
                    put(
                        "protocol",
                        buildJsonObject {
                            put("name", "luvus-uhp")
                            put("major", 1)
                            put("minor", 0)
                        },
                    )
                    put("methods", stringArray(LAYOUT_METHODS))
                    put("event_sequence", 10)
                }
            "workspace.new" -> parseObject(WORKSPACE_NEW_RESULT)
            "workspace.rename" -> parseObject(WORKSPACE_RENAME_RESULT)
            "workspace.pin" -> parseObject(WORKSPACE_PIN_RESULT)
            "workspace.move" -> parseObject(WORKSPACE_MOVE_RESULT)
            "workspace.move_block" -> parseObject(WORKSPACE_MOVE_BLOCK_RESULT)
            "tab.list" -> parseObject(TAB_LIST_RESULT)
            "tab.get" -> parseObject(TAB_GET_RESULT)
            "tab.new" -> parseObject(TAB_NEW_RESULT)
            "tab.move" -> parseObject(TAB_MOVE_RESULT)
            "tab.swap" -> parseObject(TAB_SWAP_RESULT)
            "pane.list" -> parseObject(PANE_LIST_RESULT)
            "pane.get", "pane.current" -> parseObject(PANE_GET_RESULT)
            "pane.layout" -> parseObject(PANE_LAYOUT_RESULT)
            "pane.neighbor" -> parseObject(PANE_NEIGHBOR_RESULT)
            "pane.edges" -> parseObject(PANE_EDGES_RESULT)
            "pane.move" -> parseObject(PANE_MOVE_RESULT)
            "pane.swap" -> parseObject(PANE_SWAP_RESULT)
            "pane.focus_direction" -> parseObject(PANE_FOCUS_RESULT)
            "pane.resize" -> parseObject(PANE_RESIZE_RESULT)
            "pane.zoom" -> parseObject(PANE_ZOOM_RESULT)
            "pane.rename" -> parseObject(PANE_RENAME_RESULT)
            "pane.read" -> parseObject(PANE_READ_RESULT)
            "pane.status" -> parseObject(PANE_STATUS_RESULT)
            "pane.processes" -> parseObject(PANE_PROCESSES_RESULT)
            "attach.pane" -> parseObject(ATTACH_PANE_RESULT)
            "layout.export" -> parseObject(LAYOUT_EXPORT_RESULT)
            "layout.apply" -> parseObject(LAYOUT_APPLY_RESULT)
            "layout.set_split_ratio" -> parseObject(LAYOUT_RATIO_RESULT)
            else -> buildJsonObject { put("type", "ok") }
        }
    framer.writeFrame(
        tech.asahiart.luvia.internal.compactJson.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("id", request.id)
                put("result", result)
            },
        ),
    )
}

private fun stringArray(values: List<String>): JsonArray =
    buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }

private val LAYOUT_METHODS =
    listOf(
        "uhp.capabilities",
        "workspace.new",
        "workspace.rename",
        "workspace.pin",
        "workspace.move",
        "workspace.move_block",
        "workspace.close",
        "tab.list",
        "tab.get",
        "tab.new",
        "tab.focus",
        "tab.move",
        "tab.swap",
        "tab.rename",
        "tab.close",
        "pane.list",
        "pane.get",
        "pane.current",
        "pane.layout",
        "pane.neighbor",
        "pane.edges",
        "pane.move",
        "pane.swap",
        "pane.focus",
        "pane.focus_direction",
        "pane.resize",
        "pane.zoom",
        "pane.rename",
        "pane.run",
        "pane.read",
        "pane.status",
        "pane.processes",
        "pane.close",
        "attach.pane",
        "layout.export",
        "layout.apply",
        "layout.set_split_ratio",
    )

private const val WORKSPACE_NEW_RESULT =
    """{"type":"workspace","workspace":"1","revision":11}"""

private const val WORKSPACE_RENAME_RESULT =
    """{"type":"workspace_rename","workspace":"0","name":"proj","cwd":"/tmp/work","pinned":false,"display_position":"0","revision":12}"""

private const val WORKSPACE_PIN_RESULT =
    """{"type":"workspace_pin","workspace":"0","name":"proj","cwd":"/tmp/work","pinned":true,"display_position":"0","revision":13}"""

private const val WORKSPACE_MOVE_RESULT =
    """{"type":"workspace_move","workspace":"0","to":"2","revision":14}"""

private const val WORKSPACE_MOVE_BLOCK_RESULT =
    """{"type":"workspace_move_block","workspaces":[0,1],"positions":[2,3],"revision":15}"""

private const val TAB_LIST_RESULT =
    """{"type":"tab_list","tabs":[{"tab":"1","tab_id":"tab_abc","active":true,"name":"main","kind":"panes"}],"revision":16}"""

private const val TAB_GET_RESULT =
    """{"type":"tab","workspace":"0","workspace_id":"workspace_example","tab":"1","tab_id":"tab_abc","active":true,"name":"main","kind":"panes","focus":"7","panes":["7","9"],"revision":17}"""

private const val TAB_NEW_RESULT =
    """{"type":"tab","tab":"2","revision":18}"""

private const val TAB_MOVE_RESULT =
    """{"type":"tab_move","from":"1","to":"2","active":"2","revision":19}"""

private const val TAB_SWAP_RESULT =
    """{"type":"tab_swap","tab":"1","with":"2","active":"1","revision":20}"""

private const val PANE_LIST_RESULT =
    """{"type":"pane_list","panes":[{"pane":"7","agent":"pi","status":"working","focused":true,"cwd":"/tmp/work","module":null,"scroll_offset":420,"history_rows":1830,"history_budget_bytes":10485760,"history_bytes":7340032,"history_exact":false,"history_bytes_kind":"estimated"}],"revision":21}"""

private const val PANE_GET_RESULT =
    """{"type":"pane","pane":"7","workspace":"0","workspace_id":"workspace_example","tab":"1","tab_id":"tab_abc","terminal_id":"0123456789abcdef0123456789abcdef","focused":true,"name":"reviewer","cwd":"/tmp/work","command":"zsh","agent":"pi","status":"working","module":{"id":"files","entrypoint":"main"},"revision":22}"""

private const val PANE_LAYOUT_RESULT =
    """{"type":"pane_layout","pane":"7","workspace":"0","tab":"1","logical_size":{"width":10000,"height":10000},"rect":{"x":0,"y":0,"width":5000,"height":10000},"tree":{"Split":{"axis":0,"ratio":0.5,"a":{"Leaf":7},"b":{"Leaf":9}}},"revision":23}"""

private const val PANE_NEIGHBOR_RESULT =
    """{"type":"pane_neighbor","pane":"7","neighbor":"9","revision":24}"""

private const val PANE_EDGES_RESULT =
    """{"type":"pane_edges","pane":"7","edges":{"left":true,"right":false,"top":true,"bottom":true},"revision":25}"""

private const val PANE_MOVE_RESULT =
    """{"type":"pane_move","pane":"7","workspace":"0","tab":"2","revision":26}"""

private const val PANE_SWAP_RESULT =
    """{"type":"pane_swap","pane":"7","with":"9","revision":27}"""

private const val PANE_FOCUS_RESULT =
    """{"type":"pane_focus","pane":"9","revision":28}"""

private const val PANE_RESIZE_RESULT =
    """{"type":"pane_resize","pane":"7","revision":29}"""

private const val PANE_ZOOM_RESULT =
    """{"type":"pane_zoom","pane":"7","enabled":true,"revision":30}"""

private const val PANE_RENAME_RESULT =
    """{"type":"pane_rename","pane":"7","name":"reviewer","revision":31}"""

private const val PANE_READ_RESULT =
    """{"type":"pane_read","text":"hello from pane","revision":32}"""

private const val PANE_STATUS_RESULT =
    """{"type":"pane_status","pane":"7","agent":"pi","status":"working","authority":"integration_report","state_source":"manifest_rule","scroll_offset":420,"history_rows":1830,"history_budget_bytes":10485760,"history_bytes":7340032,"history_exact":false,"history_bytes_kind":"estimated","revision":33}"""

private const val PANE_PROCESSES_RESULT =
    """{"type":"pane_processes","pane":"7","terminal_id":"0123456789abcdef0123456789abcdef","root_process":{"pid":4321,"start_marker":"abc"},"scan":"observed","executables":["zsh"],"arguments_exposed":false,"revision":34}"""

private const val ATTACH_PANE_RESULT =
    """{"type":"ok","pane":"7","revision":35}"""

private const val LAYOUT_EXPORT_RESULT =
    """{"type":"layout","workspace":"0","tab":"1","focus":"7","tree":{"Split":{"axis":0,"ratio":0.5,"a":{"Leaf":7},"b":{"Leaf":9}}},"revision":36}"""

private const val LAYOUT_APPLY_RESULT =
    """{"type":"layout_applied","workspace":"0","tab":"1","revision":37}"""

private const val LAYOUT_RATIO_RESULT =
    """{"type":"layout_split_ratio","workspace":"0","tab":"1","ratio":0.5,"revision":38}"""
