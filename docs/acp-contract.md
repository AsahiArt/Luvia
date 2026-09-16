# ACP bridge contract (P0)

`luvia-host bridge` acts as an [Agent Client Protocol](https://agentclientprotocol.com) v1
client. It spawns an ACP agent headless on the Host and exposes it to the Device over
the existing NDJSON channel as host-served methods in the `luvia.acp.*` namespace.
These methods never reach Luvus; the bridge answers them itself. Everything else in
the channel (prelude, framing, `id` rules, 1 MiB frames, idle timeout) is unchanged.

Complement to ADR 0001: UHP stays the surface for layout, review, tasks and for
agents the user started in a pane. ACP is the surface for agents the phone starts.

## Availability

The bridge appends `luvia.acp.agents` and `luvia.acp.session.open` to the
`uhp.capabilities` result (`methods[]`, and `method_contracts[]` when present, with
`access`/`scope` below) only when at least one ACP agent is available on the Host.
The phone gates on `LuviaSession.supports(...)` as for every other method.

| method | access | scope | role |
|---|---|---|---|
| `luvia.acp.agents` | read | read | Observer, Controller |
| `luvia.acp.session.open` | write | agent | Controller |

## Agent catalog

Built-in defaults, each `available` when its command resolves on `PATH`:

| id | name | command |
|---|---|---|
| `codex` | Codex | `codex-acp` |
| `claude` | Claude Code | `claude-code-acp` |
| `gemini` | Gemini CLI | `gemini --experimental-acp` |

Optional `${config_dir}/acp-agents.json` (`Paths.config_dir`, same permissions rules
as grants) adds or overrides entries:

```json
[{"id":"goose","name":"Goose","command":"goose","args":["acp"]}]
```

## `luvia.acp.agents` (unary)

Params: `{}`.

```json
{"type":"acp_agents","agents":[{"id":"codex","name":"Codex","command":"codex-acp","available":true}]}
```

## `luvia.acp.session.open` (bidirectional stream)

Params: `{"agent":"codex","cwd":"/abs/dir"}`. `cwd` must be absolute and an existing
directory. Errors on the ack: `forbidden` (Observer), `invalid_params`,
`unknown_agent`, `agent_unavailable`, `spawn_failed`, `acp_error` (initialize or
`session/new` failed; message carries the agent's error).

Bridge sequence: spawn `command args` with `cwd`, stdio piped, stderr discarded;
`initialize {protocolVersion:1, clientCapabilities:{fs:{readTextFile:false,writeTextFile:false},terminal:false}}`;
`session/new {cwd, mcpServers:[]}`. Then ack:

```json
{"id":"…","result":{"type":"acp_session","session_id":"sess_…","agent":"codex","agent_name":"Codex","protocol_version":1,"cwd":"/abs/dir"}}
```

After the ack the channel stays open. The stream ends when the Device closes the
channel (the bridge kills the agent) or the agent exits (`acp.exit` then EOF).

### Server → Device events

Envelope `{"event":"<name>","sequence":<u64 from 1, per stream>,"data":{…}}`.

- `acp.update` — `data: {"session_id":"…","update":<ACP session/update `update` object, verbatim>}`.
  The phone handles `sessionUpdate` in `agent_message_chunk`, `agent_thought_chunk`,
  `user_message_chunk`, `tool_call`, `tool_call_update`, `plan`; ignores others.
- `acp.permission` — normalized from `session/request_permission`:
  `data: {"request_id":"<string>","session_id":"…","title":"…","description":null|"…","tool_call":null|{"tool_call_id","title","kind","status"},"options":[{"option_id","name","kind"}]}`.
  `kind` ∈ `allow_once allow_always reject_once reject_always` or other string.
- `acp.turn` — `data: {"stop_reason":"end_turn|max_tokens|max_turn_requests|refusal|cancelled|<other>"}` when the agent's `session/prompt` response arrives.
- `acp.exit` — `data: {"code":<int|null>,"message":"…"}`; the bridge then closes the stream.

### Device → Server action frames

`{"id":"<id>","action":"<action>","params":{…}}`, same `id` rules as UHP requests.
Every action is answered with `{"id":"…","result":{"type":"ok"}}` or `{"id":"…","error":{…}}`
on the same stream.

- `prompt` — `params: {"text":"…"}` non-empty, ≤ 262144 bytes. Sends
  `session/prompt {sessionId, prompt:[{type:"text",text}]}`. `acp_busy` if a turn is
  already active. The `ok` reply means submitted; completion is `acp.turn`.
- `permission` — `params: {"request_id":"…","option_id":"…"}` or
  `{"request_id":"…","cancelled":true}`. Answers the pending JSON-RPC request with
  `{outcome:{outcome:"selected",optionId}}` / `{outcome:{outcome:"cancelled"}}`.
  `not_found` if no pending request has that id.
- `cancel` — `params: {}`. Sends `session/cancel {sessionId}` and answers every
  pending permission with `cancelled`.

Any other frame (a `method` request, `auth`, unknown action) is refused with
`forbidden` / `invalid_params` and never forwarded to the agent.

Agent → client requests other than `session/request_permission` (`fs/*`, `terminal/*`)
are answered with JSON-RPC error `-32601`.

## Kotlin shared API

```kotlin
// AcpDomain.kt
public data class AcpAgentKind(val id: String, val name: String, val command: String, val available: Boolean)
public data class AcpSessionInfo(val sessionId: String, val agentId: String, val agentName: String, val protocolVersion: Int, val cwd: String)
public enum class AcpPermissionKind { AllowOnce, AllowAlways, RejectOnce, RejectAlways, Other }
public data class AcpPermissionOption(val optionId: String, val name: String, val kind: AcpPermissionKind)
public data class AcpPermissionRequest(val requestId: String, val title: String, val description: String?, val toolTitle: String?, val toolKind: String?, val options: List<AcpPermissionOption>)
public enum class AcpToolStatus { Pending, InProgress, Completed, Failed, Unknown }
public data class AcpToolCall(val toolCallId: String, val title: String, val kind: String?, val status: AcpToolStatus, val summary: String?)
public data class AcpToolCallUpdate(val toolCallId: String, val title: String?, val kind: String?, val status: AcpToolStatus?, val summary: String?)
public enum class AcpPlanStatus { Pending, InProgress, Completed }
public data class AcpPlanEntry(val content: String, val priority: String?, val status: AcpPlanStatus)
public enum class AcpStopReason { EndTurn, MaxTokens, MaxTurnRequests, Refusal, Cancelled, Unknown }

public sealed class AcpEvent {
    public data class AgentMessage(val text: String) : AcpEvent()
    public data class AgentThought(val text: String) : AcpEvent()
    public data class UserMessage(val text: String) : AcpEvent()
    public data class ToolCall(val call: AcpToolCall) : AcpEvent()
    public data class ToolCallUpdate(val update: AcpToolCallUpdate) : AcpEvent()
    public data class Plan(val entries: List<AcpPlanEntry>) : AcpEvent()
    public data class Permission(val request: AcpPermissionRequest) : AcpEvent()
    public data class TurnEnded(val stopReason: AcpStopReason) : AcpEvent()
    public data class Exited(val code: Int?, val message: String) : AcpEvent()
    public data class Failed(val failure: Failure) : AcpEvent()
}

// LuviaSession additions
public suspend fun acpAgents(): Outcome<List<AcpAgentKind>>
public suspend fun openAcpSession(agentId: String, cwd: String): Outcome<AcpSession>

public class AcpSession {
    public val info: AcpSessionInfo
    public fun events(): Flow<AcpEvent>            // ends after Exited / Failed
    public suspend fun prompt(text: String): Outcome<Unit>
    public suspend fun answerPermission(requestId: String, optionId: String): Outcome<Unit>
    public suspend fun cancel(): Outcome<Unit>
    public fun close()
}

// UhpMethods additions
public const val ACP_AGENTS: String = "luvia.acp.agents"
public const val ACP_SESSION_OPEN: String = "luvia.acp.session.open"

// HostCapabilities additions
public val acpAgents: Boolean = false
public val acpSession: Boolean = false

// HostUhpState additions
public enum class AcpTranscriptRole { User, Agent, Thought }
public sealed class AcpTranscriptItem {
    public abstract val id: String
    public data class Message(override val id: String, val role: AcpTranscriptRole, val text: String, val streaming: Boolean) : AcpTranscriptItem()
    public data class Tool(override val id: String, val call: AcpToolCall) : AcpTranscriptItem()
    public data class Turn(override val id: String, val stopReason: AcpStopReason) : AcpTranscriptItem()
}
public enum class AcpRunState { Idle, Starting, Ready, Working, AwaitingPermission, Exited }
public data class AcpState(
    val agents: List<AcpAgentKind> = emptyList(),
    val agentsLoading: Boolean = false,
    val showLaunch: Boolean = false,
    val launchAgentId: String? = null,
    val launchCwd: String = "",
    val open: Boolean = false,
    val info: AcpSessionInfo? = null,
    val run: AcpRunState = AcpRunState.Idle,
    val transcript: List<AcpTranscriptItem> = emptyList(),
    val plan: List<AcpPlanEntry> = emptyList(),
    val permission: AcpPermissionRequest? = null,
    val draft: String = "",
    val errorText: String? = null,
    val exitMessage: String? = null,
)
// HostUhpState gets: public val acp: AcpState = AcpState()

// HostUhp additions
public fun loadAcpAgents()
public fun setShowLaunchAcp(show: Boolean)
public fun setLaunchAcpAgent(id: String?)
public fun setLaunchAcpCwd(cwd: String)
public fun launchAcp()                       // uses launchAgentId + launchCwd; default cwd = first workspace cwd if blank
public fun setAcpDraft(text: String)
public fun promptAcp()                       // sends draft, clears it, appends User message
public fun answerAcpPermission(optionId: String)
public fun cancelAcp()
public fun closeAcp()                        // closes the stream, open=false, keeps transcript until next launch
```

Transcript coalescing: consecutive `AgentMessage` chunks append to the last
`Message(role=Agent, streaming=true)`; same for `AgentThought` → `Thought` and
`UserMessage` → `User`. `TurnEnded` marks the last message `streaming=false` and
appends a `Turn` item. `ToolCall` appends `Tool`; `ToolCallUpdate` merges into the
existing `Tool` by `toolCallId` (append if missing). `Plan` replaces `plan`.
`Permission` sets `permission` and `run=AwaitingPermission`; answering clears it and
sets `run=Working`. `Exited` sets `run=Exited`, `exitMessage`.
