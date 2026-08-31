package tech.asahiart.luvia.internal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tech.asahiart.luvia.AgentStatus
import tech.asahiart.luvia.AgentSummary
import tech.asahiart.luvia.Capabilities
import tech.asahiart.luvia.Luvia
import tech.asahiart.luvia.PaneSummary
import tech.asahiart.luvia.SessionSnapshot
import tech.asahiart.luvia.WorkspaceSummary

internal object Methods {
    const val CAPABILITIES: String = "uhp.capabilities"
    const val SNAPSHOT: String = "session.snapshot"
    const val EVENTS_SUBSCRIBE: String = "events.subscribe"
    const val WORKSPACE_LIST: String = "workspace.list"
    const val WORKSPACE_GET: String = "workspace.get"
    const val WORKSPACE_OPEN: String = "workspace.open"
    const val WORKSPACE_FOCUS: String = "workspace.focus"
    const val PANE_SPLIT: String = "pane.split"
    const val AGENT_LIST: String = "agent.list"
    const val AGENT_GET: String = "agent.get"
    const val AGENT_EXPLAIN: String = "agent.explain"
    const val AGENT_START: String = "agent.start"
    const val AGENT_PROMPT: String = "agent.prompt"
    const val TASK_LIST: String = "task.list"
    const val TASK_GET: String = "task.get"
    const val TASK_ADD: String = "task.add"
    const val TASK_NEXT: String = "task.next"
    const val TASK_START: String = "task.start"
    const val TASK_HEARTBEAT: String = "task.heartbeat"
    const val TASK_DONE: String = "task.done"
    const val TERMINAL_INVENTORY: String = "terminal.backend.inventory"
    const val TERMINAL_SNAPSHOT: String = "terminal.backend.snapshot"
    const val TERMINAL_CAPTURE: String = "terminal.backend.capture"
    const val TERMINAL_OBSERVE: String = "terminal.backend.observe"
    const val TERMINAL_CONTROL: String = "terminal.backend.control"
    const val TERMINAL_TYPE: String = "terminal.backend.type_literal"
    const val TERMINAL_SUBMIT: String = "terminal.backend.submit_text"
    const val TERMINAL_KEY: String = "terminal.backend.send_key"

    val MUTATIONS: Set<String> =
        setOf(
            WORKSPACE_OPEN,
            WORKSPACE_FOCUS,
            PANE_SPLIT,
            AGENT_START,
            AGENT_PROMPT,
            TASK_ADD,
            TASK_START,
            TASK_HEARTBEAT,
            TASK_DONE,
            TERMINAL_CONTROL,
            TERMINAL_TYPE,
            TERMINAL_SUBMIT,
            TERMINAL_KEY,
        )
}

internal fun mapCapabilities(result: JsonObject): Capabilities {
    val protocol = result.optionalObject("protocol")
        ?: throw CodecException(CodecException.Kind.Schema, "capabilities missing protocol")
    val name = protocol.string("name")
    val major = protocol.strictLong("major").toInt()
    val minor = protocol.optionalStrictLong("minor")?.toInt() ?: 0
    if (name != Luvia.protocolName || major != Luvia.protocolMajor) {
        throw UnknownMajorException(name, major)
    }
    val methods =
        if ("methods" in result) {
            result.stringList("methods")
        } else {
            emptyList()
        }
    val agentStates =
        if ("agent_states" in result) {
            result.stringList("agent_states")
        } else {
            emptyList()
        }
    return Capabilities(
        protocolName = name,
        protocolMajor = major,
        protocolMinor = minor,
        methods = methods,
        sessionName = result.optionalString("session"),
        eventSequence = result.optionalStrictLong("event_sequence") ?: 0L,
        serverGeneration = result.optionalString("server_generation"),
        agentStates = agentStates,
    )
}

internal fun mapSnapshot(result: JsonObject): SessionSnapshot {
    val protocol = result.optionalObject("protocol")
    if (protocol != null) {
        val name = protocol.string("name")
        val major = protocol.strictLong("major").toInt()
        if (name != Luvia.protocolName || major != Luvia.protocolMajor) {
            throw UnknownMajorException(name, major)
        }
    }
    val workspaces = ArrayList<WorkspaceSummary>()
    val panes = ArrayList<PaneSummary>()
    val agents = ArrayList<AgentSummary>()
    val workspaceArray = result["workspaces"] as? JsonArray ?: JsonArray(emptyList())
    workspaceArray.forEachIndexed { fallbackIndex, el ->
        val ws = el as? JsonObject ?: return@forEachIndexed
        val index = ws.optionalStrictLong("index")?.toInt() ?: (fallbackIndex + 1)
        val tabs = ws["tabs"] as? JsonArray
        workspaces +=
            WorkspaceSummary(
                index = index,
                name = ws.optionalString("name") ?: "",
                pinned = optionalBoolean(ws, "pinned"),
                active = optionalBoolean(ws, "active"),
                tabCount = tabs?.size ?: 0,
            )
        tabs?.forEach { tabEl ->
            val tab = tabEl as? JsonObject ?: return@forEach
            val paneArray = tab["panes"] as? JsonArray ?: return@forEach
            paneArray.forEach { paneEl ->
                val pane = paneEl as? JsonObject ?: return@forEach
                val paneId = pane.optionalString("pane_id") ?: return@forEach
                panes +=
                    PaneSummary(
                        paneId = paneId,
                        terminalId = pane.optionalString("terminal_id"),
                        kind = pane.optionalString("kind") ?: "terminal",
                        focused = optionalBoolean(pane, "focused"),
                    )
                val agentName = pane.optionalString("agent")
                val agentStatus = pane.optionalString("agent_status")
                if (agentName != null || agentStatus != null) {
                    agents +=
                        AgentSummary(
                            paneId = paneId,
                            name = agentName,
                            status = parseAgentStatus(agentStatus),
                        )
                }
            }
        }
    }
    return SessionSnapshot(
        sessionName = result.optionalString("session") ?: "",
        serverGeneration = result.optionalString("server_generation") ?: "",
        eventSequence = result.optionalStrictLong("event_sequence") ?: 0L,
        workspaces = workspaces,
        panes = panes,
        agents = agents,
    )
}

internal fun parseAgentStatus(raw: String?): AgentStatus =
    when (raw) {
        "idle" -> AgentStatus.Idle
        "working" -> AgentStatus.Working
        "blocked" -> AgentStatus.Blocked
        "done" -> AgentStatus.Done
        else -> AgentStatus.Unknown
    }

internal class UnknownMajorException(val name: String, val major: Int) : Exception("unsupported protocol $name/$major")

private fun optionalBoolean(obj: JsonObject, key: String): Boolean {
    val value = obj[key] as? JsonPrimitive ?: return false
    if (value.isString) return false
    return value.content.toBooleanStrictOrNull() ?: false
}
