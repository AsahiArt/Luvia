package tech.asahiart.luvia.internal.uhp

import tech.asahiart.luvia.Failure
import tech.asahiart.luvia.HostUhpState
import tech.asahiart.luvia.LuviaSession
import tech.asahiart.luvia.Outcome
import tech.asahiart.luvia.TaskMutationResult
import tech.asahiart.luvia.UhpMethods
import tech.asahiart.luvia.UnconfirmedKind
import tech.asahiart.luvia.claimTask
import tech.asahiart.luvia.deleteTask
import tech.asahiart.luvia.isLostMutation
import tech.asahiart.luvia.userMessage

internal class TaskBoard(private val ctx: UhpContext) {
    fun load() {
        ctx.launch { refresh() }
    }

    fun setShowAdd(show: Boolean) {
        ctx.update {
            it.copy(
                tasks =
                    when {
                        show -> it.tasks.copy(showAdd = true)
                        else -> it.tasks.copy(showAdd = false, addTitle = "", addPaths = "")
                    },
            )
        }
    }

    fun setCompleteId(id: String?) {
        ctx.update { it.copy(tasks = it.tasks.copy(completeId = id)) }
    }

    fun setDeleteId(id: String?) {
        ctx.update { it.copy(tasks = it.tasks.copy(deleteId = id)) }
    }

    fun setAddDraft(title: String, paths: String) {
        ctx.update { it.copy(tasks = it.tasks.copy(addTitle = title, addPaths = paths)) }
    }

    fun add(title: String, paths: List<String>) {
        val session = ctx.session() ?: return
        val state = ctx.value()
        if (!state.canMutate || state.tasks.mutating || state.tasks.unconfirmed != null) return
        if (!session.supports(UhpMethods.TASK_ADD)) return
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        ctx.update { it.copy(tasks = it.tasks.copy(mutating = true, errorText = null, boardChanged = false)) }
        ctx.launch {
            val current = ctx.value()
            val ifRevision = current.tasks.boardRevision
            when (
                val result =
                    session.addTask(
                        title = trimmed,
                        paths = paths,
                        ifRevision = ifRevision,
                        workspaceId = activeWorkspaceId(current),
                    )
            ) {
                is Outcome.Ok -> {
                    val task = result.value.task
                    ctx.update { current ->
                        val revisions =
                            current.tasks.revisions +
                                (task.id to (result.value.revision ?: current.tasks.revisions[task.id] ?: 0L))
                        current.copy(
                            tasks = current.tasks.copy(
                                mutating = false,
                                unconfirmed = null,
                                boardRevision = result.value.revision ?: current.tasks.boardRevision,
                                revisions = revisions,
                                showAdd = false,
                                addTitle = "",
                                addPaths = "",
                            ),
                        )
                    }
                    refresh()
                }
                is Outcome.Err -> applyMutationFailure(UnconfirmedKind.AddTask, null, result.failure)
            }
        }
    }

    fun complete(taskId: String) {
        val session = ctx.session() ?: return
        val state = ctx.value()
        if (!state.canMutate || state.tasks.mutating || state.tasks.unconfirmed != null) return
        if (!session.supports(UhpMethods.TASK_DONE)) return
        ctx.update { it.copy(tasks = it.tasks.copy(mutating = true, errorText = null, boardChanged = false)) }
        ctx.launch {
            var ifRevision = ctx.value().tasks.revisions[taskId]
            if (ifRevision == null && session.supports(UhpMethods.TASK_GET)) {
                when (val got = session.getTask(taskId)) {
                    is Outcome.Ok -> {
                        ifRevision = got.value.revision
                        ctx.update { current ->
                            current.copy(
                                tasks = current.tasks.copy(
                                    boardRevision = got.value.revision ?: current.tasks.boardRevision,
                                    revisions = current.tasks.revisions + (taskId to (got.value.revision ?: 0L)),
                                ),
                            )
                        }
                    }
                    is Outcome.Err -> {
                        applyMutationFailure(UnconfirmedKind.CompleteTask, taskId, got.failure)
                        return@launch
                    }
                }
            }
            when (val result = session.completeTask(taskId, ifRevision)) {
                is Outcome.Ok -> {
                    ctx.update { current ->
                        current.copy(
                            tasks = current.tasks.copy(
                                mutating = false,
                                unconfirmed = null,
                                unconfirmedTaskId = null,
                                boardRevision = result.value.revision ?: current.tasks.boardRevision,
                                completeId = null,
                                revisions = current.tasks.revisions +
                                    (taskId to (result.value.revision ?: current.tasks.revisions[taskId] ?: 0L)),
                            ),
                        )
                    }
                    refresh()
                }
                is Outcome.Err -> applyMutationFailure(UnconfirmedKind.CompleteTask, taskId, result.failure)
            }
        }
    }

    fun claim(taskId: String) {
        mutate(taskId, UhpMethods.TASK_CLAIM, UnconfirmedKind.ClaimTask) { session, ifRevision ->
            session.claimTask(taskId, ifRevision = ifRevision)
        }
    }

    fun delete(taskId: String) {
        mutate(taskId, UhpMethods.TASK_DELETE, UnconfirmedKind.DeleteTask) { session, ifRevision ->
            session.deleteTask(taskId, ifRevision = ifRevision)
        }
    }

    fun check() {
        val taskId = ctx.value().tasks.unconfirmedTaskId
        ctx.launch {
            val session = ctx.session()
            if (session == null) {
                ctx.update {
                    it.copy(connected = false, tasks = it.tasks.copy(errorText = NOT_CONNECTED))
                }
                return@launch
            }
            var verified = false
            if (taskId != null && session.supports(UhpMethods.TASK_GET)) {
                when (val got = session.getTask(taskId)) {
                    is Outcome.Ok -> {
                        ctx.update { current ->
                            current.copy(
                                tasks = current.tasks.copy(
                                    boardRevision = got.value.revision ?: current.tasks.boardRevision,
                                    revisions = current.tasks.revisions + (taskId to (got.value.revision ?: 0L)),
                                ),
                            )
                        }
                        verified = true
                    }
                    is Outcome.Err ->
                        ctx.update { it.copy(tasks = it.tasks.copy(errorText = got.failure.userMessage())) }
                }
            }
            val listed = refresh()
            if (verified || listed) {
                ctx.update {
                    it.copy(tasks = it.tasks.copy(unconfirmed = null, unconfirmedTaskId = null, mutating = false))
                }
            } else if (!session.supports(UhpMethods.TASK_LIST) &&
                (taskId == null || !session.supports(UhpMethods.TASK_GET))
            ) {
                val message =
                    if (taskId != null) {
                        "The host does not support task.get or task.list."
                    } else {
                        "The host does not support task.list."
                    }
                ctx.update { it.copy(tasks = it.tasks.copy(errorText = message)) }
            }
        }
    }

    private fun mutate(
        taskId: String,
        method: String,
        kind: UnconfirmedKind,
        call: suspend (LuviaSession, Long?) -> Outcome<TaskMutationResult>,
    ) {
        val session = ctx.session() ?: return
        val state = ctx.value()
        if (!state.canMutate || state.tasks.mutating || state.tasks.unconfirmed != null) return
        if (!session.supports(method)) return
        ctx.update { it.copy(tasks = it.tasks.copy(mutating = true, errorText = null, boardChanged = false)) }
        ctx.launch {
            var ifRevision = ctx.value().tasks.revisions[taskId]
            if (ifRevision == null && session.supports(UhpMethods.TASK_GET)) {
                when (val got = session.getTask(taskId)) {
                    is Outcome.Ok -> {
                        ifRevision = got.value.revision
                        ctx.update { current ->
                            current.copy(
                                tasks = current.tasks.copy(
                                    boardRevision = got.value.revision ?: current.tasks.boardRevision,
                                    revisions = current.tasks.revisions + (taskId to (got.value.revision ?: 0L)),
                                ),
                            )
                        }
                    }
                    is Outcome.Err -> {
                        applyMutationFailure(kind, taskId, got.failure)
                        return@launch
                    }
                }
            }
            when (val result = call(session, ifRevision)) {
                is Outcome.Ok -> {
                    ctx.update { current ->
                        current.copy(
                            tasks = current.tasks.copy(
                                mutating = false,
                                unconfirmed = null,
                                unconfirmedTaskId = null,
                                boardRevision = result.value.revision ?: current.tasks.boardRevision,
                                deleteId = null,
                                revisions = current.tasks.revisions +
                                    (taskId to (result.value.revision ?: current.tasks.revisions[taskId] ?: 0L)),
                            ),
                        )
                    }
                    refresh()
                }
                is Outcome.Err -> applyMutationFailure(kind, taskId, result.failure)
            }
        }
    }

    private suspend fun refresh(preserveUserState: Boolean = false): Boolean {
        val session = ctx.session()
        if (session == null) {
            ctx.update {
                it.copy(connected = false, tasks = it.tasks.copy(loading = false, errorText = NOT_CONNECTED))
            }
            return false
        }
        if (!session.supports(UhpMethods.TASK_LIST)) return false
        ctx.update {
            it.copy(
                connected = true,
                tasks = it.tasks.copy(
                    loading = true,
                    errorText = if (preserveUserState) it.tasks.errorText else null,
                    boardChanged = if (preserveUserState) it.tasks.boardChanged else false,
                ),
            )
        }
        return when (val result = session.listTasks()) {
            is Outcome.Ok -> {
                ctx.update { it.copy(tasks = it.tasks.copy(tasks = result.value, loading = false)) }
                true
            }
            is Outcome.Err -> {
                ctx.update { it.copy(tasks = it.tasks.copy(loading = false, errorText = result.failure.userMessage())) }
                false
            }
        }
    }

    /**
     * Stable workspace id of the focused workspace, from `agent.list` rows
     * (`workspace_id`, Luvus #264) or Mission rows. Null on single-project Hosts
     * or older Luvus; the server then falls back to its implicit project.
     */
    private fun activeWorkspaceId(state: HostUhpState): String? {
        state.agents.firstOrNull { it.focused && it.workspaceId != null }?.workspaceId?.let { return it }
        val agentIds = state.agents.mapNotNull { it.workspaceId }.distinct()
        if (agentIds.size == 1) return agentIds.first()
        val missionIds = state.mission?.rows?.mapNotNull { it.workspaceId }?.distinct().orEmpty()
        return missionIds.singleOrNull()
    }

    private fun applyMutationFailure(kind: UnconfirmedKind, taskId: String?, failure: Failure) {
        if (failure is Failure.RevisionConflict) {
            ctx.update {
                val revisions =
                    if (taskId != null) {
                        it.tasks.revisions + (taskId to failure.actual)
                    } else {
                        it.tasks.revisions
                    }
                it.copy(
                    tasks = it.tasks.copy(
                        mutating = false,
                        boardChanged = true,
                        errorText = "Updated by someone else. Showing latest.",
                        boardRevision = failure.actual,
                        revisions = revisions,
                    ),
                )
            }
            ctx.launch { refresh(preserveUserState = true) }
            return
        }
        ctx.update {
            it.copy(
                tasks =
                    if (failure.isLostMutation()) {
                        it.tasks.copy(
                            mutating = false,
                            unconfirmed = kind,
                            unconfirmedTaskId = taskId,
                            errorText = null,
                        )
                    } else {
                        it.tasks.copy(mutating = false, errorText = failure.userMessage())
                    },
            )
        }
    }
}
