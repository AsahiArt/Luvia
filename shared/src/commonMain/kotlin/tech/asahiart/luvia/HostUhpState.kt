package tech.asahiart.luvia

public enum class HostSection {
    Agents,
    Files,
    Search,
    Review,
    Worktrees,
    Automations,
    Tasks,
    Layout,
    Terminal,
}

public enum class UnconfirmedKind {
    AgentPrompt,
    AgentKeys,
    AddReviewNote,
    ResolveReviewNote,
    ReopenReviewNote,
    RemoveReviewNote,
    SendNotes,
    AddTask,
    CompleteTask,
    ClaimTask,
    DeleteTask,
    RetryTask,

}

public data class HostCapabilities(
    public val agentRead: Boolean = false,
    public val agentPrompt: Boolean = false,
    public val agentKeys: Boolean = false,
    public val missionSnapshot: Boolean = false,
    public val diffList: Boolean = false,
    public val diffGet: Boolean = false,
    public val diffNoteList: Boolean = false,
    public val diffNoteAdd: Boolean = false,
    public val diffNoteSend: Boolean = false,
    public val diffNoteResolve: Boolean = false,
    public val diffNoteReopen: Boolean = false,
    public val diffNoteRemove: Boolean = false,
    public val taskList: Boolean = false,
    public val taskAdd: Boolean = false,
    public val taskDone: Boolean = false,
    public val taskGet: Boolean = false,
    public val taskClaim: Boolean = false,
    public val taskDelete: Boolean = false,
    public val agentSessions: Boolean = false,
    public val agentResume: Boolean = false,
    public val agentFork: Boolean = false,
    public val agentName: Boolean = false,
    public val filesTree: Boolean = false,
    public val filesOpen: Boolean = false,
    public val filesReveal: Boolean = false,
    public val filesRefresh: Boolean = false,
    public val searchQuery: Boolean = false,
    public val searchActivate: Boolean = false,
    public val worktreeList: Boolean = false,
    public val worktreeCreate: Boolean = false,
    public val worktreeOpen: Boolean = false,
    public val worktreeRemove: Boolean = false,
    public val automationList: Boolean = false,
    public val automationEnable: Boolean = false,
    public val automationDisable: Boolean = false,
    public val automationRun: Boolean = false,
    public val automationHealth: Boolean = false,
    public val workspaceList: Boolean = false,
    public val workspaceFocus: Boolean = false,
    public val workspaceClose: Boolean = false,
    public val paneList: Boolean = false,
    public val paneFocus: Boolean = false,
    public val paneClose: Boolean = false,
    public val paneRename: Boolean = false,
    public val acpAgents: Boolean = false,
    public val acpSession: Boolean = false,
    public val taskRetry: Boolean = false,
    public val push: Boolean = false,

)

public data class AgentDetailState(
    public val paneId: String? = null,
    // Independent of paneId so Back can keep draft/error/unconfirmed without showing detail.
    public val open: Boolean = false,
    public val summary: AgentSummary? = null,
    public val detail: AgentGetResult? = null,
    public val transcript: AgentReadResult? = null,
    public val sending: Boolean = false,
    public val unconfirmed: UnconfirmedKind? = null,
    public val errorText: String? = null,
    public val loading: Boolean = false,
    public val draft: String = "",
    public val showName: Boolean = false,
    public val nameDraft: String = "",
    public val showFork: Boolean = false,
    public val forkDraft: String = "",
)

public data class ReviewState(
    public val list: DiffListResult? = null,
    public val selectedPath: String? = null,
    public val selectedLayer: DiffLayer? = null,
    public val selectedFile: DiffFile? = null,
    public val notes: List<ReviewNote> = emptyList(),
    public val loading: Boolean = false,
    public val sending: Boolean = false,
    public val unconfirmed: UnconfirmedKind? = null,
    public val errorText: String? = null,
    public val lastSend: ReviewNoteSendResult? = null,
    public val noteDraft: String = "",
    public val sendTarget: String? = null,
)

public data class TasksState(
    public val tasks: List<TaskSummary> = emptyList(),
    public val revisions: Map<String, Long> = emptyMap(),
    public val boardRevision: Long? = null,
    public val loading: Boolean = false,
    public val mutating: Boolean = false,
    public val unconfirmed: UnconfirmedKind? = null,
    public val unconfirmedTaskId: String? = null,
    public val errorText: String? = null,
    public val boardChanged: Boolean = false,
    public val showAdd: Boolean = false,
    public val completeId: String? = null,
    public val deleteId: String? = null,
    public val addTitle: String = "",
    public val addPaths: String = "",
)

public data class FilesState(
    public val root: String = "",
    public val rows: List<FileTreeRow> = emptyList(),
    public val loading: Boolean = false,
    public val mutating: Boolean = false,
    public val errorText: String? = null,
)

public data class SearchState(
    public val query: String = "",
    public val result: SearchQueryResult? = null,
    public val matches: List<SearchMatch> = emptyList(),
    public val loading: Boolean = false,
    public val errorText: String? = null,
    public val searched: Boolean = false,
    public val scopeLabel: String? = null,
)

public data class WorktreesState(
    public val worktrees: List<WorktreeEntry> = emptyList(),
    public val loading: Boolean = false,
    public val mutating: Boolean = false,
    public val errorText: String? = null,
    public val showCreate: Boolean = false,
    public val createBranch: String = "",
    public val removePath: String? = null,
    public val workspace: Int? = null,
)

public data class AutomationsState(
    public val automations: List<Automation> = emptyList(),
    public val health: AutomationHealthResult? = null,
    public val loading: Boolean = false,
    public val mutating: Boolean = false,
    public val errorText: String? = null,
    public val history: Map<String, List<AutomationRun>> = emptyMap(),
    public val historyLoading: String? = null,
    public val preview: List<Long>? = null,
    public val previewLoading: Boolean = false,
    public val editorError: String? = null,

)

public data class LayoutState(
    public val workspaces: List<WorkspaceSummary> = emptyList(),
    public val panes: List<PaneListEntry> = emptyList(),
    public val loading: Boolean = false,
    public val mutating: Boolean = false,
    public val errorText: String? = null,
    public val closeWorkspace: Int? = null,
    public val closePane: String? = null,
    public val renamePane: String? = null,
    public val renameDraft: String = "",
)

public enum class AcpTranscriptRole { User, Agent, Thought }

public sealed class AcpTranscriptItem {
    public abstract val id: String

    public data class Message(
        public override val id: String,
        public val role: AcpTranscriptRole,
        public val text: String,
        public val streaming: Boolean,
    ) : AcpTranscriptItem()

    public data class Tool(
        public override val id: String,
        public val call: AcpToolCall,
    ) : AcpTranscriptItem()

    public data class Turn(
        public override val id: String,
        public val stopReason: AcpStopReason,
    ) : AcpTranscriptItem()
}

public enum class AcpRunState { Idle, Starting, Ready, Working, AwaitingPermission, Exited }

public data class AcpState(
    public val agents: List<AcpAgentKind> = emptyList(),
    public val agentsLoading: Boolean = false,
    public val showLaunch: Boolean = false,
    public val launchAgentId: String? = null,
    public val launchCwd: String = "",
    public val open: Boolean = false,
    public val info: AcpSessionInfo? = null,
    public val run: AcpRunState = AcpRunState.Idle,
    public val transcript: List<AcpTranscriptItem> = emptyList(),
    public val plan: List<AcpPlanEntry> = emptyList(),
    public val permission: AcpPermissionRequest? = null,
    public val draft: String = "",
    public val errorText: String? = null,
    public val exitMessage: String? = null,
)

public data class HostUhpState(
    public val connected: Boolean = false,
    public val isObserver: Boolean = false,
    public val capabilities: HostCapabilities = HostCapabilities(),
    public val agents: List<AgentSummary> = emptyList(),
    public val agentSessions: List<AgentSessionEntry> = emptyList(),
    public val mission: MissionSnapshot? = null,
    public val agentDetail: AgentDetailState = AgentDetailState(),
    public val review: ReviewState = ReviewState(),
    public val tasks: TasksState = TasksState(),
    public val files: FilesState = FilesState(),
    public val search: SearchState = SearchState(),
    public val worktrees: WorktreesState = WorktreesState(),
    public val automations: AutomationsState = AutomationsState(),
    public val layout: LayoutState = LayoutState(),
    public val acp: AcpState = AcpState(),
    public val section: HostSection = HostSection.Agents,
    public val errorText: String? = null,
    public val loading: Boolean = false,
    public val backend: String = "luvus",
) {

    public val canMutate: Boolean get() = connected && !isObserver

    public fun visibleSections(): List<HostSection> {
        val hostChrome = HostSection.entries.filter { it != HostSection.Terminal }
        if (!connected) return hostChrome
        return buildList {
            add(HostSection.Agents)
            if (capabilities.filesTree) add(HostSection.Files)
            if (capabilities.searchQuery) add(HostSection.Search)
            if (capabilities.diffList) add(HostSection.Review)
            if (capabilities.worktreeList) add(HostSection.Worktrees)
            if (capabilities.automationList) add(HostSection.Automations)
            if (capabilities.taskList) add(HostSection.Tasks)
            if (capabilities.workspaceList || capabilities.paneList) add(HostSection.Layout)
        }
    }

    /** Selected Agent's project, else focused, else the Host's only workspace. */
    public fun projectWorkspaceId(): String? {
        val selected = agentDetail.paneId
        if (selected != null) {
            agents.firstOrNull { it.paneId == selected }?.workspaceId?.let { return it }
        }
        agents.firstOrNull { it.focused && it.workspaceId != null }?.workspaceId?.let { return it }
        val agentIds = agents.mapNotNull { it.workspaceId }.distinct()
        if (agentIds.size == 1) return agentIds.first()
        val missionIds = mission?.rows?.mapNotNull { it.workspaceId }?.distinct().orEmpty()
        return missionIds.singleOrNull()
    }

    public fun projectLabel(): String? {
        val selected = agentDetail.paneId
        val agent =
            agents.firstOrNull { it.paneId == selected } ?:
                agents.firstOrNull { it.focused } ?:
                agents.singleOrNull()
        return agent?.workspaceName
            ?: agent?.project
            ?: agent?.workspace
            ?: projectWorkspaceId()
    }

    public fun projectWorkspaceIndex(): Int? {
        fun indexOf(agent: AgentSummary?): Int? = agent?.workspace?.toIntOrNull()
        val selected = agentDetail.paneId
        indexOf(agents.firstOrNull { it.paneId == selected })?.let { return it }
        indexOf(agents.firstOrNull { it.focused })?.let { return it }
        val id = projectWorkspaceId()
        if (id != null) {
            indexOf(agents.firstOrNull { it.workspaceId == id })?.let { return it }
        }
        return agents.mapNotNull { it.workspace?.toIntOrNull() }.distinct().singleOrNull()
    }

    public fun projectTasks(): List<TaskSummary> {
        val id = projectWorkspaceId() ?: return tasks.tasks
        return if (tasks.tasks.any { !it.workspaceId.isNullOrBlank() }) {
            tasks.tasks.filter { it.workspaceId == id }
        } else {
            tasks.tasks
        }
    }
}

internal fun LuviaSession.toCapabilities(): HostCapabilities =
    HostCapabilities(
        agentRead = supports(UhpMethods.AGENT_READ),
        agentPrompt = supports(UhpMethods.AGENT_PROMPT),
        agentKeys = supports(UhpMethods.AGENT_KEYS),
        missionSnapshot = supports(UhpMethods.MISSION_SNAPSHOT),
        diffList = supports(UhpMethods.DIFF_LIST),
        diffGet = supports(UhpMethods.DIFF_GET),
        diffNoteList = supports(UhpMethods.DIFF_NOTE_LIST),
        diffNoteAdd = supports(UhpMethods.DIFF_NOTE_ADD),
        diffNoteSend = supports(UhpMethods.DIFF_NOTE_SEND),
        diffNoteResolve = supports(UhpMethods.DIFF_NOTE_RESOLVE),
        diffNoteReopen = supports(UhpMethods.DIFF_NOTE_REOPEN),
        diffNoteRemove = supports(UhpMethods.DIFF_NOTE_REMOVE),
        taskList = supports(UhpMethods.TASK_LIST),
        taskAdd = supports(UhpMethods.TASK_ADD),
        taskDone = supports(UhpMethods.TASK_DONE),
        taskGet = supports(UhpMethods.TASK_GET),
        taskClaim = supports(UhpMethods.TASK_CLAIM),
        taskDelete = supports(UhpMethods.TASK_DELETE),
        agentSessions = supports(UhpMethods.AGENT_SESSIONS),
        agentResume = supports(UhpMethods.AGENT_RESUME),
        agentFork = supports(UhpMethods.AGENT_FORK),
        agentName = supports(UhpMethods.AGENT_NAME),
        filesTree = supports(UhpMethods.FILES_TREE),
        filesOpen = supports(UhpMethods.FILES_OPEN),
        filesReveal = supports(UhpMethods.FILES_REVEAL),
        filesRefresh = supports(UhpMethods.FILES_REFRESH),
        searchQuery = supports(UhpMethods.SEARCH_QUERY),
        searchActivate = supports(UhpMethods.SEARCH_ACTIVATE),
        worktreeList = supports(UhpMethods.WORKTREE_LIST),
        worktreeCreate = supports(UhpMethods.WORKTREE_CREATE),
        worktreeOpen = supports(UhpMethods.WORKTREE_OPEN),
        worktreeRemove = supports(UhpMethods.WORKTREE_REMOVE),
        automationList = supports(UhpMethods.AUTOMATION_LIST),
        automationEnable = supports(UhpMethods.AUTOMATION_ENABLE),
        automationDisable = supports(UhpMethods.AUTOMATION_DISABLE),
        automationRun = supports(UhpMethods.AUTOMATION_RUN),
        automationHealth = supports(UhpMethods.AUTOMATION_HEALTH),
        workspaceList = supports(UhpMethods.WORKSPACE_LIST),
        workspaceFocus = supports(UhpMethods.WORKSPACE_FOCUS),
        workspaceClose = supports(UhpMethods.WORKSPACE_CLOSE),
        paneList = supports(UhpMethods.PANE_LIST),
        paneFocus = supports(UhpMethods.PANE_FOCUS),
        paneClose = supports(UhpMethods.PANE_CLOSE),
        paneRename = supports(UhpMethods.PANE_RENAME),
        acpAgents = supports(UhpMethods.ACP_AGENTS),
        acpSession = supports(UhpMethods.ACP_SESSION_OPEN),
        taskRetry = supports(UhpMethods.TASK_RETRY),
        push = supports(UhpMethods.PUSH_REGISTER),

    )
