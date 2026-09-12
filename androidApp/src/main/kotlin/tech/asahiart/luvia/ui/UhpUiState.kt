package tech.asahiart.luvia.ui

import androidx.compose.runtime.Immutable
import tech.asahiart.luvia.AgentGetResult
import tech.asahiart.luvia.AgentReadResult
import tech.asahiart.luvia.AgentSessionEntry
import tech.asahiart.luvia.AgentSummary
import tech.asahiart.luvia.Automation
import tech.asahiart.luvia.AutomationHealthResult
import tech.asahiart.luvia.DiffFile
import tech.asahiart.luvia.DiffLayer
import tech.asahiart.luvia.DiffListResult
import tech.asahiart.luvia.FileTreeRow
import tech.asahiart.luvia.MissionSnapshot
import tech.asahiart.luvia.PaneListEntry
import tech.asahiart.luvia.ReviewNote
import tech.asahiart.luvia.ReviewNoteSendResult
import tech.asahiart.luvia.SearchMatch
import tech.asahiart.luvia.SearchQueryResult
import tech.asahiart.luvia.TaskSummary
import tech.asahiart.luvia.WorkspaceSummary
import tech.asahiart.luvia.WorktreeEntry

enum class UnconfirmedKind {
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
}

@Immutable
data class HostCapabilitiesUi(
    val agentRead: Boolean = false,
    val agentPrompt: Boolean = false,
    val agentKeys: Boolean = false,
    val missionSnapshot: Boolean = false,
    val diffList: Boolean = false,
    val diffGet: Boolean = false,
    val diffNoteList: Boolean = false,
    val diffNoteAdd: Boolean = false,
    val diffNoteSend: Boolean = false,
    val diffNoteResolve: Boolean = false,
    val diffNoteReopen: Boolean = false,
    val diffNoteRemove: Boolean = false,
    val taskList: Boolean = false,
    val taskAdd: Boolean = false,
    val taskDone: Boolean = false,
    val taskGet: Boolean = false,
    val taskClaim: Boolean = false,
    val taskDelete: Boolean = false,
    val agentSessions: Boolean = false,
    val agentResume: Boolean = false,
    val agentFork: Boolean = false,
    val agentName: Boolean = false,
    val filesTree: Boolean = false,
    val filesOpen: Boolean = false,
    val filesReveal: Boolean = false,
    val filesRefresh: Boolean = false,
    val searchQuery: Boolean = false,
    val searchActivate: Boolean = false,
    val worktreeList: Boolean = false,
    val worktreeCreate: Boolean = false,
    val worktreeOpen: Boolean = false,
    val worktreeRemove: Boolean = false,
    val automationList: Boolean = false,
    val automationEnable: Boolean = false,
    val automationDisable: Boolean = false,
    val automationRun: Boolean = false,
    val automationHealth: Boolean = false,
    val workspaceList: Boolean = false,
    val workspaceFocus: Boolean = false,
    val workspaceClose: Boolean = false,
    val paneList: Boolean = false,
    val paneFocus: Boolean = false,
    val paneClose: Boolean = false,
    val paneRename: Boolean = false,
)

@Immutable
data class AgentDetailUi(
    val paneId: String? = null,
    // Independent of paneId so Back can keep draft/error/unconfirmed without showing detail.
    val open: Boolean = false,
    val summary: AgentSummary? = null,
    val detail: AgentGetResult? = null,
    val transcript: AgentReadResult? = null,
    val sending: Boolean = false,
    val unconfirmed: UnconfirmedKind? = null,
    val errorText: String? = null,
    val loading: Boolean = false,
    val draft: String = "",
    val showName: Boolean = false,
    val nameDraft: String = "",
    val showFork: Boolean = false,
    val forkDraft: String = "",
)

@Immutable
data class ReviewUiState(
    val list: DiffListResult? = null,
    val selectedPath: String? = null,
    val selectedLayer: DiffLayer? = null,
    val selectedFile: DiffFile? = null,
    val notes: List<ReviewNote> = emptyList(),
    val loading: Boolean = false,
    val sending: Boolean = false,
    val unconfirmed: UnconfirmedKind? = null,
    val errorText: String? = null,
    val lastSend: ReviewNoteSendResult? = null,
    // Sheet and draft state live here rather than in rememberSaveable: the
    // compact and expanded layouts place these panes under different
    // saveable-state scopes, so composition-local state is lost on rotation.
    val noteDraft: String = "",
    val sendTarget: String? = null,
)

@Immutable
data class TasksUiState(
    val tasks: List<TaskSummary> = emptyList(),
    val revisions: Map<String, Long> = emptyMap(),
    val boardRevision: Long? = null,
    val loading: Boolean = false,
    val mutating: Boolean = false,
    val unconfirmed: UnconfirmedKind? = null,
    val unconfirmedTaskId: String? = null,
    val errorText: String? = null,
    val boardChanged: Boolean = false,
    val showAdd: Boolean = false,
    val completeId: String? = null,
    val deleteId: String? = null,
    val addTitle: String = "",
    val addPaths: String = "",
)

@Immutable
data class FilesUiState(
    val root: String = "",
    val rows: List<FileTreeRow> = emptyList(),
    val loading: Boolean = false,
    val mutating: Boolean = false,
    val errorText: String? = null,
)

@Immutable
data class SearchUiState(
    val query: String = "",
    val result: SearchQueryResult? = null,
    val matches: List<SearchMatch> = emptyList(),
    val loading: Boolean = false,
    val errorText: String? = null,
    val searched: Boolean = false,
    val scopeLabel: String? = null,
)

@Immutable
data class WorktreesUiState(
    val worktrees: List<WorktreeEntry> = emptyList(),
    val loading: Boolean = false,
    val mutating: Boolean = false,
    val errorText: String? = null,
    val showCreate: Boolean = false,
    val createBranch: String = "",
    val removePath: String? = null,
    val workspace: Int? = null,
)

@Immutable
data class AutomationsUiState(
    val automations: List<Automation> = emptyList(),
    val health: AutomationHealthResult? = null,
    val loading: Boolean = false,
    val mutating: Boolean = false,
    val errorText: String? = null,
)

@Immutable
data class LayoutUiState(
    val workspaces: List<WorkspaceSummary> = emptyList(),
    val panes: List<PaneListEntry> = emptyList(),
    val loading: Boolean = false,
    val mutating: Boolean = false,
    val errorText: String? = null,
    val closeWorkspace: Int? = null,
    val closePane: String? = null,
    val renamePane: String? = null,
    val renameDraft: String = "",
)

@Immutable
data class HostUhpUiState(
    val connected: Boolean = false,
    val isObserver: Boolean = false,
    val capabilities: HostCapabilitiesUi = HostCapabilitiesUi(),
    val agents: List<AgentSummary> = emptyList(),
    val agentSessions: List<AgentSessionEntry> = emptyList(),
    val mission: MissionSnapshot? = null,
    val agentDetail: AgentDetailUi = AgentDetailUi(),
    val review: ReviewUiState = ReviewUiState(),
    val tasks: TasksUiState = TasksUiState(),
    val files: FilesUiState = FilesUiState(),
    val search: SearchUiState = SearchUiState(),
    val worktrees: WorktreesUiState = WorktreesUiState(),
    val automations: AutomationsUiState = AutomationsUiState(),
    val layout: LayoutUiState = LayoutUiState(),
    // Selected section is held here, not in the NavDisplay entry: the compact
    // and expanded layouts recreate that entry under different saveable scopes.
    val section: HostSection = HostSection.Agents,
    val errorText: String? = null,
    val loading: Boolean = false,
) {
    val canMutate: Boolean get() = connected && !isObserver
}

class UhpHostActions(
    val shown: (String) -> Unit,
    val sectionShown: (String, HostSection) -> Unit,
    val setSection: (String, HostSection) -> Unit,
    val refreshSection: (String, HostSection) -> Unit,
    val openAgent: (String, String) -> Unit,
    val closeAgent: (String) -> Unit,
    val promptAgent: (String, String) -> Unit,
    val setAgentDraft: (String, String) -> Unit,
    val sendKeys: (String, List<tech.asahiart.luvia.AgentKey>) -> Unit,
    val checkAgent: (String) -> Unit,
    val resumeAgent: (String, String) -> Unit,
    val setShowNameAgent: (String, Boolean) -> Unit,
    val setNameAgentDraft: (String, String) -> Unit,
    val nameAgent: (String) -> Unit,
    val setShowForkAgent: (String, Boolean) -> Unit,
    val setForkAgentDraft: (String, String) -> Unit,
    val forkAgent: (String) -> Unit,
    val openDiffFile: (String, String, tech.asahiart.luvia.DiffLayer?) -> Unit,
    val closeDiffFile: (String) -> Unit,
    val addNote: (String, String, tech.asahiart.luvia.ReviewLine, String, tech.asahiart.luvia.DiffLayer?) -> Unit,
    val resolveNote: (String, String) -> Unit,
    val reopenNote: (String, String) -> Unit,
    val removeNote: (String, String) -> Unit,
    val sendNotes: (String, String) -> Unit,
    val checkNotes: (String) -> Unit,
    val setNoteDraft: (String, String) -> Unit,
    val setSendTarget: (String, String?) -> Unit,
    val addTask: (String, String, List<String>) -> Unit,
    val completeTask: (String, String) -> Unit,
    val claimTask: (String, String) -> Unit,
    val deleteTask: (String, String) -> Unit,
    val checkTasks: (String) -> Unit,
    val setShowAddTask: (String, Boolean) -> Unit,
    val setCompleteTaskId: (String, String?) -> Unit,
    val setDeleteTaskId: (String, String?) -> Unit,
    val setAddTaskDraft: (String, String, String) -> Unit,
    val refreshFiles: (String) -> Unit,
    val openFile: (String, String) -> Unit,
    val revealFile: (String, String) -> Unit,
    val setSearchQuery: (String, String) -> Unit,
    val querySearch: (String) -> Unit,
    val activateSearch: (String, String) -> Unit,
    val setShowCreateWorktree: (String, Boolean) -> Unit,
    val setCreateWorktreeBranch: (String, String) -> Unit,
    val createWorktree: (String) -> Unit,
    val openWorktree: (String, String) -> Unit,
    val setRemoveWorktreePath: (String, String?) -> Unit,
    val removeWorktree: (String, String) -> Unit,
    val enableAutomation: (String, String) -> Unit,
    val disableAutomation: (String, String) -> Unit,
    val runAutomation: (String, String) -> Unit,
    val focusWorkspace: (String, Int) -> Unit,
    val setCloseWorkspace: (String, Int?) -> Unit,
    val closeWorkspace: (String, Int) -> Unit,
    val focusPane: (String, String) -> Unit,
    val setClosePane: (String, String?) -> Unit,
    val closePane: (String, String) -> Unit,
    val setRenamePane: (String, String?, String) -> Unit,
    val renamePane: (String) -> Unit,
)

internal fun HostUhpUiState.visibleSections(): List<HostSection> {
    if (!connected) return HostSection.entries.toList()
    return buildList {
        add(HostSection.Agents)
        if (capabilities.filesTree) add(HostSection.Files)
        if (capabilities.searchQuery) add(HostSection.Search)
        if (capabilities.diffList) add(HostSection.Review)
        if (capabilities.worktreeList) add(HostSection.Worktrees)
        if (capabilities.automationList) add(HostSection.Automations)
        if (capabilities.taskList) add(HostSection.Tasks)
        if (capabilities.workspaceList || capabilities.paneList) add(HostSection.Layout)
        add(HostSection.Terminal)
    }
}
