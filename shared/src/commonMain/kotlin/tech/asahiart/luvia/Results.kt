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
    Merging,
    Merged,
    Failed,
    Unknown,
}

public data class ProcessIdentity(
    public val pid: Long,
    public val startMarker: String?,
)

public data class WorkspaceWorker(
    public val workspaceId: String,
    public val tabId: String,
    public val root: String,
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
    public val mode: String? = null,
    public val workspaceWorker: WorkspaceWorker? = null,
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
        public val mode: String? = null,
        public val workspaceId: String? = null,
        public val tabId: String? = null,
        public val cwd: String? = null,
        public val commit: String? = null,
        public val message: String? = null,
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

    public class AgentHook(
        override val sequence: Long,
        public val pane: String?,
        public val agent: String?,
        public val kind: String?,
        public val message: String?,
        public val tool: String?,
    ) : BusEvent()

    public class PaneRenamed(
        override val sequence: Long,
        public val pane: String?,
        public val name: String?,
    ) : BusEvent()

    public class LeaseChanged(
        override val sequence: Long,
        public val name: String,
        public val id: String?,
        public val pane: String?,
        public val task: String?,
        public val paths: List<String>,
        public val acquired: Long?,
        public val leases: List<String>,
    ) : BusEvent()

    public class Ignored(
        override val sequence: Long,
        public val name: String,
    ) : BusEvent()
}

public data class AgentReadResult(
    public val pane: String,
    public val text: String,
    public val revision: Long?,
)

public data class AgentPromptResult(
    public val pane: String,
    public val submitted: Boolean,
    public val matched: Boolean,
    public val status: AgentStatus?,
    public val baselineRevision: Long,
    public val contentRevision: Long,
    public val evidence: String,
    public val revision: Long?,
)

public data class AgentSessionEntry(
    public val agent: String,
    public val sessionId: String,
    public val cwd: String,
)

public data class MissionUsage(
    public val model: String?,
    public val tokensIn: Long?,
    public val tokensOut: Long?,
    public val cacheTokens: Long?,
    public val totalTokens: Long?,
    public val context: Double?,
    public val costUsd: Double?,
)

public data class MissionSummary(
    public val agents: Long,
    public val tokens: Long,
    public val costUsd: Double,
    public val burnUsdPerHour: Double,
)

public enum class MissionRowKind {
    LIVE,
    RESUMABLE,
}

public data class MissionRow(
    public val kind: MissionRowKind,
    public val pane: String?,
    public val agent: String?,
    public val state: String?,
    public val workspace: String?,
    public val workspaceId: String?,
    public val workspaceName: String?,
    public val tab: String?,
    public val location: String?,
    public val usage: MissionUsage?,
)

public data class MissionSnapshot(
    public val scope: String?,
    public val workspace: String?,
    public val workspaceId: String?,
    public val refreshing: Boolean,
    public val summary: MissionSummary,
    public val rows: List<MissionRow>,
)

public data class DiffLine(
    public val kind: String,
    public val oldLine: Long?,
    public val newLine: Long?,
    public val text: String,
)

public data class DiffHunk(
    public val id: String,
    public val oldStart: Long,
    public val newStart: Long,
    public val header: String,
    public val lines: List<DiffLine>,
)

public data class DiffFile(
    public val path: String,
    public val pathRawHex: String? = null,
    public val oldPath: String? = null,
    public val oldPathRawHex: String? = null,
    public val layer: DiffLayer? = null,
    public val status: String? = null,
    public val additions: Long? = null,
    public val deletions: Long? = null,
    public val binary: Boolean? = null,
    public val notes: Long? = null,
    public val viewed: Boolean? = null,
    public val modifiedSinceReview: Boolean? = null,
    public val fingerprint: String? = null,
    public val truncated: Boolean? = null,
    public val omittedLines: Long? = null,
    public val hunks: List<DiffHunk> = emptyList(),
)

public data class DiffListResult(
    public val repo: String?,
    public val branch: String?,
    public val generation: Long?,
    public val fingerprint: String?,
    public val omitted: Long?,
    public val refreshing: Boolean,
    public val files: List<DiffFile>,
)

public data class ReviewNoteDelivery(
    public val target: String,
    public val deliveredAtMs: Long?,
)

public data class ReviewNote(
    public val id: String,
    public val review: String?,
    public val author: String?,
    public val kind: ReviewNoteKind?,
    public val body: String,
    public val state: ReviewNoteState?,
    public val path: String?,
    public val layer: DiffLayer?,
    public val side: String?,
    public val startLine: Long?,
    public val endLine: Long?,
    public val revision: Long?,
    public val deliveries: List<ReviewNoteDelivery>,
    public val createdAtMs: Long?,
    public val updatedAtMs: Long?,
)

public data class ReviewNoteSendResult(
    public val pane: String?,
    public val target: String?,
    public val count: Long,
)

public data class GitFileChange(
    public val code: String,
    public val path: String,
)

public data class GitStatus(
    public val branch: String?,
    public val upstream: String?,
    public val ahead: Long?,
    public val behind: Long?,
    public val staged: List<GitFileChange>,
    public val unstaged: List<GitFileChange>,
    public val untracked: List<String>,
    public val stashes: List<String>,
)

public data class GitCommit(
    public val sha: String,
    public val subject: String,
    public val author: String?,
    public val whenText: String?,
    public val refs: String?,
)
