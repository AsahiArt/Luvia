package tech.asahiart.luvia

public enum class HostStatus {
    Unknown,
    Reachable,
    Unreachable,
    Stale,
}

public enum class HostRole {
    Observer,
    Controller,
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
    public val addresses: List<String>,
    public val sshPort: Int,
    public val username: String,
    public val hostKeyFingerprints: List<String>,
    public val role: HostRole,
    public val lastStatus: HostStatus,
    public val lastUpdatedEpochMs: Long,
    public val lastConnectedAddress: String?,
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
    public val branch: String? = null,
    public val cwd: String? = null,
)

public data class PaneSummary(
    public val paneId: String,
    public val terminalId: String?,
    public val kind: String,
    public val focused: Boolean,
    public val cwd: String? = null,
    public val contentRevision: Long? = null,
    public val agentAuthority: String? = null,
    public val agentSession: String? = null,
    public val rootProcessPid: Long? = null,
    public val rootProcessStartMarker: String? = null,
)

public data class AgentSummary(
    public val paneId: String,
    public val name: String?,
    public val status: AgentStatus,
    public val agent: String? = null,
    public val authority: String? = null,
    public val stateSource: String? = null,
    public val session: String? = null,
    public val focused: Boolean = false,
    public val workspace: String? = null,
    public val workspaceName: String? = null,
    public val tab: String? = null,
    public val cwd: String? = null,
    public val branch: String? = null,
    public val project: String? = null,
    public val repo: String? = null,
    public val worktree: Boolean? = null,
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

public enum class AgentReadSource {
    VISIBLE,
    RECENT,
}

public enum class MissionScope {
    ALL,
    WORKSPACE,
}

public enum class DiffLayer {
    STAGED,
    WORKTREE,
    UNTRACKED,
    CONFLICT,
}

public enum class ReviewNoteState {
    OPEN,
    RESOLVED,
    OUTDATED,
    ORPHANED,
}

public enum class ReviewNoteKind {
    QUESTION,
    ISSUE,
    SUGGESTION,
    PRAISE,
}

/**
 * Named keys use the wire tokens accepted by `key_to_bytes`
 * (`dispatch.rs:5591-5630`). `Ctrl` encodes as `ctrl+a`; `Char` is a single
 * character passed through as itself.
 */
public sealed class AgentKey {
    public abstract val wire: String

    public data object ENTER : AgentKey() {
        override val wire: String = "enter"
    }

    public data object ESC : AgentKey() {
        override val wire: String = "esc"
    }

    public data object TAB : AgentKey() {
        override val wire: String = "tab"
    }

    public data object SPACE : AgentKey() {
        override val wire: String = "space"
    }

    public data object BACKSPACE : AgentKey() {
        override val wire: String = "backspace"
    }

    public data object DELETE : AgentKey() {
        override val wire: String = "delete"
    }

    public data object UP : AgentKey() {
        override val wire: String = "up"
    }

    public data object DOWN : AgentKey() {
        override val wire: String = "down"
    }

    public data object LEFT : AgentKey() {
        override val wire: String = "left"
    }

    public data object RIGHT : AgentKey() {
        override val wire: String = "right"
    }

    public data object HOME : AgentKey() {
        override val wire: String = "home"
    }

    public data object END : AgentKey() {
        override val wire: String = "end"
    }

    public data object PAGE_UP : AgentKey() {
        override val wire: String = "pageup"
    }

    public data object PAGE_DOWN : AgentKey() {
        override val wire: String = "pagedown"
    }

    public class Ctrl(public val letter: kotlin.Char) : AgentKey() {
        override val wire: String = "ctrl+${letter.lowercaseChar()}"
    }

    public class Char(public val c: kotlin.Char) : AgentKey() {
        override val wire: String = c.toString()
    }
}

/**
 * Exactly one of `old_line` / `new_line` is required (`dispatch.rs:3686-3696`).
 */
public sealed class ReviewLine {
    public class Old(public val line: Int) : ReviewLine()

    public class New(public val line: Int) : ReviewLine()
}
