package tech.asahiart.luvia

public enum class SplitDirection {
    Right,
    Down,
    Stack,
}

public enum class TaskStatus {
    Queued,
    Claimed,
    Running,
    Blocked,
    Review,
    Done,
    Failed,
    Unknown,
}

public data class ProcessIdentity(
    public val pid: Long,
    public val startMarker: String?,
)

public data class Task(
    public val id: String,
    public val title: String,
    public val status: TaskStatus,
    public val assignee: Long?,
    public val deps: List<String>,
    public val paths: List<String>,
    public val gate: String?,
    public val outputs: List<String>,
    public val notes: List<String>,
    public val worktree: String?,
    public val branch: String?,
    public val context: Double?,
    public val created: Long,
    public val updated: Long,
)

public data class WorkspaceListEntry(
    public val workspace: String,
    public val workspaceId: String?,
    public val name: String,
    public val cwd: String?,
    public val terminalCwd: String?,
    public val pinned: Boolean,
    public val displayPosition: String?,
    public val active: Boolean,
    public val tabs: Int,
)

public data class WorkspaceListResult(
    public val workspaces: List<WorkspaceListEntry>,
    public val revision: Long?,
)

public data class WorkspaceGetResult(
    public val workspace: String,
    public val workspaceId: String?,
    public val name: String,
    public val active: Boolean,
    public val activeTab: String?,
    public val tabs: Int,
    public val pinned: Boolean,
    public val branch: String?,
    public val ahead: Long?,
    public val behind: Long?,
    public val cwd: String?,
    public val terminalCwd: String?,
    public val displayPosition: String?,
    public val revision: Long?,
)

public data class WorkspaceOpenResult(
    public val workspace: String,
    public val revision: Long?,
)

public data class PaneSplitResult(
    public val pane: String,
    public val revision: Long?,
)

public data class AgentListResult(
    public val agents: List<AgentSummary>,
    public val revision: Long?,
)

public data class AgentGetResult(
    public val pane: String,
    public val name: String?,
    public val agent: String?,
    public val status: AgentStatus,
    public val authority: String?,
    public val stateSource: String?,
    public val session: String?,
    public val cwd: String?,
    public val revision: Long?,
)

public data class AgentIdentityEvidence(
    public val confidence: String?,
    public val source: String?,
)

public data class AgentStateEvidence(
    public val source: String?,
    public val confidence: String?,
    public val blockedHint: String?,
    public val rulePriority: Long?,
    public val ruleRegion: String?,
)

public data class AgentAuthorityLease(
    public val source: String?,
    public val sequence: Long?,
    public val message: String?,
    public val expiresInMs: Long?,
)

public data class AgentExplainSession(
    public val agent: String?,
    public val id: String?,
)

public data class AgentExplainResult(
    public val pane: String,
    public val agent: String?,
    public val status: AgentStatus,
    public val available: Boolean,
    public val authority: AgentAuthorityLease?,
    public val session: AgentExplainSession?,
    public val identity: AgentIdentityEvidence?,
    public val stateEvidence: AgentStateEvidence?,
    public val revision: Long?,
)

public data class TaskListResult(
    public val tasks: List<Task>,
    public val revision: Long?,
)

public data class TaskMutationResult(
    public val task: Task,
    public val revision: Long?,
)

public data class TaskStartResult(
    public val task: Task,
    public val pane: String?,
    public val worktree: String?,
    public val revision: Long?,
)

public data class TaskDoneResult(
    public val task: Task,
    public val gateRunning: Boolean,
    public val revision: Long?,
)

public data class TaskHeartbeatResult(
    public val overThreshold: Boolean,
    public val revision: Long?,
)

public sealed class TaskNextResult {
    public class None(
        public val message: String,
        public val revision: Long?,
    ) : TaskNextResult()

    public class Ready(
        public val task: Task,
        public val pane: String?,
        public val worktree: String?,
        public val revision: Long?,
    ) : TaskNextResult()
}

public data class TerminalWorkspaceRef(
    public val index: Int?,
    public val name: String?,
    public val root: String?,
)

public data class TerminalTabRef(
    public val index: Int?,
    public val name: String?,
)

public data class TerminalInventoryEntry(
    public val terminalId: String,
    public val paneId: String,
    public val contentRevision: Long,
    public val terminalTitle: String?,
    public val label: String?,
    public val cwd: String?,
    public val workspace: TerminalWorkspaceRef?,
    public val tab: TerminalTabRef?,
    public val rootProcess: ProcessIdentity?,
)

public data class TerminalInventoryResult(
    public val serverGeneration: String,
    public val terminals: List<TerminalInventoryEntry>,
    public val truncated: Boolean,
)

public data class TerminalBackendSnapshot(
    public val serverGeneration: String,
    public val eventSequence: Long,
    public val terminals: List<TerminalInventoryEntry>,
    public val truncated: Boolean,
)

public data class TerminalCaptureResult(
    public val identity: TerminalIdentity,
    public val text: String,
    public val lines: Int,
    public val bytes: Int,
    public val mode: TerminalCaptureMode,
    public val ansi: Boolean,
    public val truncated: Boolean,
    public val contentRevision: Long,
)

public data class EventSubscriptionAck(
    public val sequence: Long,
    public val replayed: Long?,
    public val queueCapacity: Long?,
    public val lossBehavior: String?,
)

public sealed class BusEvent {
    public abstract val sequence: Long

    public class AgentStatusChanged(
        override val sequence: Long,
        public val pane: String?,
        public val status: AgentStatus,
        public val agent: String?,
        public val cwd: String?,
        public val project: String?,
        public val branch: String?,
        public val authority: String?,
        public val stateSource: String?,
    ) : BusEvent()

    public class TaskPayload(
        override val sequence: Long,
        public val name: String,
        public val task: Task?,
        public val id: String?,
        public val pane: String?,
        public val worktree: String?,
        public val branch: String?,
        public val context: Double?,
        public val gate: String?,
        public val code: Long?,
        public val files: List<String>,
        public val into: String?,
    ) : BusEvent()

    public class PaneChanged(
        override val sequence: Long,
        public val name: String,
        public val pane: String?,
        public val terminalId: String?,
        public val workspace: String?,
        public val tab: String?,
        public val from: String?,
        public val to: String?,
        public val module: String?,
    ) : BusEvent()

    public class WorkspaceChanged(
        override val sequence: Long,
        public val name: String,
        public val workspace: String?,
        public val to: String?,
    ) : BusEvent()

    public class TerminalChanged(
        override val sequence: Long,
        public val name: String,
        public val serverGeneration: String?,
        public val terminalId: String?,
        public val paneId: String?,
        public val contentRevision: Long?,
        public val workspace: Long?,
        public val tab: Long?,
        public val label: String?,
    ) : BusEvent()

    public class ResyncRequired(
        override val sequence: Long,
        public val name: String,
        public val reason: String?,
    ) : BusEvent()

    public class Ignored(
        override val sequence: Long,
        public val name: String,
    ) : BusEvent()
}
