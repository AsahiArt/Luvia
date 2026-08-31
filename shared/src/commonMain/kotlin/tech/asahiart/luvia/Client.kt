package tech.asahiart.luvia

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.asahiart.luvia.internal.ByteChannelFactory
import tech.asahiart.luvia.internal.ControlFrame
import tech.asahiart.luvia.internal.Methods
import tech.asahiart.luvia.internal.SessionEngine
import tech.asahiart.luvia.internal.wireName

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

    public suspend fun snapshot(): Outcome<SessionSnapshot> = engine.snapshot()

    public fun events(): Flow<SessionUpdate> = engine.events()

    public fun observe(identity: TerminalIdentity): Flow<TerminalUpdate> = engine.observe(identity)

    public suspend fun openControl(identity: TerminalIdentity): Outcome<TerminalControl> = engine.openControl(identity)

    public suspend fun inventory(): Outcome<Unit> = engine.unary(Methods.TERMINAL_INVENTORY, JsonObject(emptyMap()), mutation = false) { }

    public suspend fun capture(identity: TerminalIdentity, mode: TerminalCaptureMode, lines: Int, ansi: Boolean): Outcome<Unit> {
        val params =
            buildJsonObject {
                put("server_generation", identity.serverGeneration)
                put("terminal_id", identity.terminalId)
                put("pane_id", identity.paneId)
                put("mode", if (mode == TerminalCaptureMode.Visible) "visible" else "recent_unwrapped")
                put("lines", lines)
                put("ansi", ansi)
            }
        return engine.unary(Methods.TERMINAL_CAPTURE, params, mutation = false) { }
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

    public suspend fun listWorkspaces(): Outcome<Unit> =
        engine.unary(Methods.WORKSPACE_LIST, JsonObject(emptyMap()), mutation = false) { }

    public suspend fun openWorkspace(index: Int): Outcome<Unit> =
        engine.unary(Methods.WORKSPACE_OPEN, buildJsonObject { put("workspace", index) }, mutation = true) { }

    public suspend fun focusWorkspace(index: Int): Outcome<Unit> =
        engine.unary(Methods.WORKSPACE_FOCUS, buildJsonObject { put("workspace", index) }, mutation = true) { }

    public suspend fun splitPane(down: Boolean): Outcome<Unit> =
        engine.unary(Methods.PANE_SPLIT, buildJsonObject { put("down", down) }, mutation = true) { }

    public suspend fun listAgents(): Outcome<Unit> =
        engine.unary(Methods.AGENT_LIST, JsonObject(emptyMap()), mutation = false) { }

    public suspend fun explainAgent(paneId: String): Outcome<Unit> =
        engine.unary(Methods.AGENT_EXPLAIN, buildJsonObject { put("pane", paneId) }, mutation = false) { }

    public suspend fun startAgent(name: String, kind: String): Outcome<Unit> =
        engine.unary(
            Methods.AGENT_START,
            buildJsonObject {
                put("name", name)
                put("kind", kind)
            },
            mutation = true,
        ) { }

    public suspend fun promptAgent(target: String, text: String): Outcome<Unit> =
        engine.unary(
            Methods.AGENT_PROMPT,
            buildJsonObject {
                put("target", target)
                put("text", text)
            },
            mutation = true,
        ) { }

    public suspend fun listTasks(): Outcome<Unit> =
        engine.unary(Methods.TASK_LIST, JsonObject(emptyMap()), mutation = false) { }

    public suspend fun addTask(title: String): Outcome<Unit> =
        engine.unary(Methods.TASK_ADD, buildJsonObject { put("title", title) }, mutation = true) { }

    public suspend fun nextTask(): Outcome<Unit> =
        engine.unary(Methods.TASK_NEXT, JsonObject(emptyMap()), mutation = false) { }

    public suspend fun startTask(id: String): Outcome<Unit> =
        engine.unary(Methods.TASK_START, buildJsonObject { put("id", id) }, mutation = true) { }

    public suspend fun heartbeatTask(id: String): Outcome<Unit> =
        engine.unary(Methods.TASK_HEARTBEAT, buildJsonObject { put("id", id) }, mutation = true) { }

    public suspend fun completeTask(id: String): Outcome<Unit> =
        engine.unary(Methods.TASK_DONE, buildJsonObject { put("id", id) }, mutation = true) { }

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
