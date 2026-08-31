package tech.asahiart.luvia

public enum class HostStatus {
    Unknown,
    Reachable,
    Unreachable,
    Stale,
}

public enum class ConnectionFreshness {
    Live,
    Stale,
    Offline,
}

public enum class AgentStatus {
    Idle,
    Working,
    Blocked,
    Done,
    Unknown,
}

public enum class TerminalCaptureMode {
    Visible,
    RecentUnwrapped,
}

public enum class BridgeTransport {
    UnixSocket,
    NamedPipe,
}

public enum class ResyncReason {
    Gap,
    Overflow,
    Eof,
    GenerationChange,
    StaleSnapshot,
}

public enum class TerminalKey {
    Enter,
    Escape,
    Tab,
    Backtab,
    Up,
    Down,
    Left,
    Right,
    Home,
    End,
    Backspace,
    Delete,
    PageUp,
    PageDown,
    CtrlC,
    CtrlD,
    CtrlU,
    CtrlW,
    Space,
    Digit0,
    Digit1,
    Digit2,
    Digit3,
    Digit4,
    Digit5,
    Digit6,
    Digit7,
    Digit8,
    Digit9,
}

public data class HostProfile(
    public val id: String,
    public val alias: String,
    public val address: String,
    public val sshPort: Int,
    public val username: String?,
    public val hostKeySha256: String? = null,
    public val lastStatus: HostStatus,
    public val lastUpdatedEpochMs: Long,
    public val topology: CachedTopology?,
)

public data class HostCatalog(
    public val hosts: List<HostProfile>,
    public val updatedEpochMs: Long,
)

public data class CachedTopology(
    public val sessionName: String?,
    public val serverGeneration: String?,
    public val eventSequence: Long,
    public val workspaces: List<WorkspaceSummary>,
    public val agents: List<AgentSummary>,
    public val tasks: List<TaskSummary>,
    public val capturedAtEpochMs: Long,
)

public data class DiscoveredSession(
    public val name: String,
    public val isDefault: Boolean,
    public val running: Boolean,
    public val transport: BridgeTransport,
)

public data class Capabilities(
    public val protocolName: String,
    public val protocolMajor: Int,
    public val protocolMinor: Int,
    public val methods: List<String>,
    public val sessionName: String?,
    public val eventSequence: Long,
    public val serverGeneration: String?,
    public val agentStates: List<String>,
)

public data class WorkspaceSummary(
    public val index: Int,
    public val name: String,
    public val pinned: Boolean,
    public val active: Boolean,
    public val tabCount: Int,
)

public data class PaneSummary(
    public val paneId: String,
    public val terminalId: String?,
    public val kind: String,
    public val focused: Boolean,
)

public data class AgentSummary(
    public val paneId: String,
    public val name: String?,
    public val status: AgentStatus,
)

public data class TaskSummary(
    public val id: String,
    public val title: String,
    public val status: String,
)

public data class SessionSnapshot(
    public val sessionName: String,
    public val serverGeneration: String,
    public val eventSequence: Long,
    public val workspaces: List<WorkspaceSummary>,
    public val panes: List<PaneSummary>,
    public val agents: List<AgentSummary>,
)

public data class TerminalIdentity(
    public val serverGeneration: String,
    public val terminalId: String,
    public val paneId: String,
)

public data class TerminalFrame(
    public val identity: TerminalIdentity,
    public val contentRevision: Long,
    public val mode: TerminalCaptureMode,
    public val ansi: Boolean,
    public val text: String,
    public val lines: Int,
    public val bytes: Int,
    public val truncated: Boolean,
)

public data class SessionEvent(
    public val name: String,
    public val sequence: Long,
    public val paneId: String?,
)

public sealed class SessionUpdate {
    public class Snapshot(public val snapshot: SessionSnapshot) : SessionUpdate()

    public class Event(public val event: SessionEvent) : SessionUpdate()

    public class Resyncing(public val reason: ResyncReason) : SessionUpdate()

    public class Failed(public val failure: Failure) : SessionUpdate()
}

public sealed class TerminalUpdate {
    public class Frame(public val frame: TerminalFrame) : TerminalUpdate()

    public class Resyncing(public val reason: ResyncReason) : TerminalUpdate()

    public class Failed(public val failure: Failure) : TerminalUpdate()
}
