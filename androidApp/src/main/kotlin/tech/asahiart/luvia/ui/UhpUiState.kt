package tech.asahiart.luvia.ui

import androidx.compose.runtime.Immutable
import tech.asahiart.luvia.AgentGetResult
import tech.asahiart.luvia.AgentReadResult
import tech.asahiart.luvia.AgentSummary
import tech.asahiart.luvia.DiffFile
import tech.asahiart.luvia.DiffLayer
import tech.asahiart.luvia.DiffListResult
import tech.asahiart.luvia.MissionSnapshot
import tech.asahiart.luvia.ReviewNote
import tech.asahiart.luvia.ReviewNoteSendResult
import tech.asahiart.luvia.TaskSummary

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
    val addTitle: String = "",
    val addPaths: String = "",
)

@Immutable
data class HostUhpUiState(
    val connected: Boolean = false,
    val isObserver: Boolean = false,
    val capabilities: HostCapabilitiesUi = HostCapabilitiesUi(),
    val agents: List<AgentSummary> = emptyList(),
    val mission: MissionSnapshot? = null,
    val agentDetail: AgentDetailUi = AgentDetailUi(),
    val review: ReviewUiState = ReviewUiState(),
    val tasks: TasksUiState = TasksUiState(),
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
    val checkTasks: (String) -> Unit,
    val setShowAddTask: (String, Boolean) -> Unit,
    val setCompleteTaskId: (String, String?) -> Unit,
    val setAddTaskDraft: (String, String, String) -> Unit,
)

internal fun HostUhpUiState.visibleSections(): List<HostSection> {
    if (!connected) return HostSection.entries.toList()
    return buildList {
        add(HostSection.Agents)
        if (capabilities.diffList) add(HostSection.Review)
        if (capabilities.taskList) add(HostSection.Tasks)
        add(HostSection.Terminal)
    }
}
