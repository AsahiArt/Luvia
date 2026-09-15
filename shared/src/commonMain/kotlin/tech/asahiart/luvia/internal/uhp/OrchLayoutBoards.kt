package tech.asahiart.luvia.internal.uhp

import tech.asahiart.luvia.LuviaSession
import tech.asahiart.luvia.Outcome
import tech.asahiart.luvia.UhpMethods
import tech.asahiart.luvia.automationHealth
import tech.asahiart.luvia.closePane
import tech.asahiart.luvia.closeWorkspace
import tech.asahiart.luvia.createWorktree
import tech.asahiart.luvia.disableAutomation
import tech.asahiart.luvia.enableAutomation
import tech.asahiart.luvia.focusPane
import tech.asahiart.luvia.listAutomations
import tech.asahiart.luvia.listPanes
import tech.asahiart.luvia.listWorktrees
import tech.asahiart.luvia.openWorktree
import tech.asahiart.luvia.removeWorktree
import tech.asahiart.luvia.renamePane
import tech.asahiart.luvia.runAutomation
import tech.asahiart.luvia.userMessage

internal class WorktreeBoard(private val ctx: UhpContext) {
    fun load() {
        ctx.launch { refresh() }
    }

    fun setShowCreate(show: Boolean) {
        ctx.update {
            it.copy(
                worktrees =
                    when {
                        show -> it.worktrees.copy(showCreate = true)
                        else -> it.worktrees.copy(showCreate = false, createBranch = "")
                    },
            )
        }
    }

    fun setCreateBranch(branch: String) {
        ctx.update { it.copy(worktrees = it.worktrees.copy(createBranch = branch)) }
    }

    fun setRemovePath(path: String?) {
        ctx.update { it.copy(worktrees = it.worktrees.copy(removePath = path)) }
    }

    fun create() {
        val session = ctx.session() ?: return
        val state = ctx.value()
        if (!state.canMutate || state.worktrees.mutating || !session.supports(UhpMethods.WORKTREE_CREATE)) return
        val branch = state.worktrees.createBranch.trim()
        if (branch.isEmpty()) return
        ctx.update { it.copy(worktrees = it.worktrees.copy(mutating = true, errorText = null)) }
        ctx.launch {
            when (val result = session.createWorktree(branch, workspace = ctx.value().worktrees.workspace)) {
                is Outcome.Ok -> {
                    ctx.update {
                        it.copy(worktrees = it.worktrees.copy(mutating = false, showCreate = false, createBranch = ""))
                    }
                    refresh()
                }
                is Outcome.Err ->
                    ctx.update {
                        it.copy(worktrees = it.worktrees.copy(mutating = false, errorText = result.failure.userMessage()))
                    }
            }
        }
    }

    fun open(path: String) {
        val session = ctx.session() ?: return
        val state = ctx.value()
        if (!state.canMutate || !session.supports(UhpMethods.WORKTREE_OPEN) || path.isBlank()) return
        ctx.update { it.copy(worktrees = it.worktrees.copy(mutating = true, errorText = null)) }
        ctx.launch {
            when (val result = session.openWorktree(path)) {
                is Outcome.Ok -> {
                    ctx.update { it.copy(worktrees = it.worktrees.copy(mutating = false)) }
                    refresh()
                }
                is Outcome.Err ->
                    ctx.update {
                        it.copy(worktrees = it.worktrees.copy(mutating = false, errorText = result.failure.userMessage()))
                    }
            }
        }
    }

    fun remove(path: String) {
        val session = ctx.session() ?: return
        val state = ctx.value()
        if (!state.canMutate || !session.supports(UhpMethods.WORKTREE_REMOVE) || path.isBlank()) return
        ctx.update { it.copy(worktrees = it.worktrees.copy(mutating = true, errorText = null)) }
        ctx.launch {
            when (val result = session.removeWorktree(path)) {
                is Outcome.Ok -> {
                    ctx.update { it.copy(worktrees = it.worktrees.copy(mutating = false, removePath = null)) }
                    refresh()
                }
                is Outcome.Err ->
                    ctx.update {
                        it.copy(worktrees = it.worktrees.copy(mutating = false, errorText = result.failure.userMessage()))
                    }
            }
        }
    }

    private suspend fun refresh() {
        val session = ctx.session()
        if (session == null) {
            ctx.update {
                it.copy(connected = false, worktrees = it.worktrees.copy(loading = false, errorText = NOT_CONNECTED))
            }
            return
        }
        if (!session.supports(UhpMethods.WORKTREE_LIST)) return
        val runtime = ctx.runtime()
        val workspace = preferredGitWorkspace(runtime, ctx.value().files.root)
        ctx.update { it.copy(connected = true, worktrees = it.worktrees.copy(loading = true, errorText = null)) }
        var usedWorkspace = workspace
        val result =
            session.listWorktrees(workspace).let { first ->
                if (first is Outcome.Err && workspace != null) {
                    usedWorkspace = null
                    session.listWorktrees()
                } else {
                    first
                }
            }
        when (result) {
            is Outcome.Ok ->
                ctx.update {
                    it.copy(
                        worktrees = it.worktrees.copy(
                            worktrees = result.value,
                            loading = false,
                            mutating = false,
                            workspace = usedWorkspace,
                            errorText = null,
                        ),
                    )
                }
            is Outcome.Err ->
                ctx.update {
                    it.copy(
                        worktrees = it.worktrees.copy(
                            loading = false,
                            workspace = null,
                            errorText = result.failure.userMessage(),
                        ),
                    )
                }
        }
    }
}

internal class AutomationBoard(private val ctx: UhpContext) {
    fun load() {
        ctx.launch { refresh() }
    }

    fun enable(id: String) {
        mutate(id, UhpMethods.AUTOMATION_ENABLE) { it.enableAutomation(id) }
    }

    fun disable(id: String) {
        mutate(id, UhpMethods.AUTOMATION_DISABLE) { it.disableAutomation(id) }
    }

    fun run(id: String) {
        mutate(id, UhpMethods.AUTOMATION_RUN) { it.runAutomation(id) }
    }

    private fun mutate(id: String, method: String, call: suspend (LuviaSession) -> Outcome<*>) {
        val session = ctx.session() ?: return
        val state = ctx.value()
        if (!state.canMutate || state.automations.mutating || !session.supports(method) || id.isBlank()) return
        ctx.update { it.copy(automations = it.automations.copy(mutating = true, errorText = null)) }
        ctx.launch {
            when (val result = call(session)) {
                is Outcome.Ok -> {
                    ctx.update { it.copy(automations = it.automations.copy(mutating = false)) }
                    refresh()
                }
                is Outcome.Err ->
                    ctx.update {
                        it.copy(automations = it.automations.copy(mutating = false, errorText = result.failure.userMessage()))
                    }
            }
        }
    }

    private suspend fun refresh() {
        val session = ctx.session()
        if (session == null) {
            ctx.update {
                it.copy(
                    connected = false,
                    automations = it.automations.copy(loading = false, errorText = NOT_CONNECTED),
                )
            }
            return
        }
        if (!session.supports(UhpMethods.AUTOMATION_LIST)) return
        ctx.update { it.copy(connected = true, automations = it.automations.copy(loading = true, errorText = null)) }
        val listed =
            when (val result = session.listAutomations()) {
                is Outcome.Ok -> result.value
                is Outcome.Err -> {
                    ctx.update {
                        it.copy(automations = it.automations.copy(loading = false, errorText = result.failure.userMessage()))
                    }
                    return
                }
            }
        val health =
            if (session.supports(UhpMethods.AUTOMATION_HEALTH)) {
                when (val result = session.automationHealth()) {
                    is Outcome.Ok -> result.value
                    is Outcome.Err -> null
                }
            } else {
                null
            }
        ctx.update {
            it.copy(
                automations = it.automations.copy(
                    automations = listed,
                    health = health ?: it.automations.health,
                    loading = false,
                    mutating = false,
                ),
            )
        }
    }
}

internal class LayoutBoard(private val ctx: UhpContext) {
    fun load() {
        ctx.launch { refresh() }
    }

    fun setCloseWorkspace(index: Int?) {
        ctx.update { it.copy(layout = it.layout.copy(closeWorkspace = index)) }
    }

    fun setClosePane(pane: String?) {
        ctx.update { it.copy(layout = it.layout.copy(closePane = pane)) }
    }

    fun setRenamePane(pane: String?, draft: String) {
        ctx.update { it.copy(layout = it.layout.copy(renamePane = pane, renameDraft = draft)) }
    }

    fun focusWorkspace(index: Int) {
        val session = ctx.session() ?: return
        val state = ctx.value()
        if (!state.canMutate || !session.supports(UhpMethods.WORKSPACE_FOCUS)) return
        ctx.update { it.copy(layout = it.layout.copy(mutating = true, errorText = null)) }
        ctx.launch {
            when (val result = session.focusWorkspace(index)) {
                is Outcome.Ok -> {
                    ctx.update { it.copy(layout = it.layout.copy(mutating = false)) }
                    refresh()
                }
                is Outcome.Err ->
                    ctx.update { it.copy(layout = it.layout.copy(mutating = false, errorText = result.failure.userMessage())) }
            }
        }
    }

    fun closeWorkspace(index: Int) {
        val session = ctx.session() ?: return
        val state = ctx.value()
        if (!state.canMutate || !session.supports(UhpMethods.WORKSPACE_CLOSE)) return
        ctx.update { it.copy(layout = it.layout.copy(mutating = true, errorText = null)) }
        ctx.launch {
            when (val result = session.closeWorkspace(index)) {
                is Outcome.Ok -> {
                    ctx.update { it.copy(layout = it.layout.copy(mutating = false, closeWorkspace = null)) }
                    refresh()
                }
                is Outcome.Err ->
                    ctx.update { it.copy(layout = it.layout.copy(mutating = false, errorText = result.failure.userMessage())) }
            }
        }
    }

    fun focusPane(pane: String) {
        val session = ctx.session() ?: return
        val state = ctx.value()
        if (!state.canMutate || !session.supports(UhpMethods.PANE_FOCUS) || pane.isBlank()) return
        ctx.update { it.copy(layout = it.layout.copy(mutating = true, errorText = null)) }
        ctx.launch {
            when (val result = session.focusPane(pane)) {
                is Outcome.Ok -> {
                    ctx.update { it.copy(layout = it.layout.copy(mutating = false)) }
                    refresh()
                }
                is Outcome.Err ->
                    ctx.update { it.copy(layout = it.layout.copy(mutating = false, errorText = result.failure.userMessage())) }
            }
        }
    }

    fun closePane(pane: String) {
        val session = ctx.session() ?: return
        val state = ctx.value()
        if (!state.canMutate || !session.supports(UhpMethods.PANE_CLOSE) || pane.isBlank()) return
        ctx.update { it.copy(layout = it.layout.copy(mutating = true, errorText = null)) }
        ctx.launch {
            when (val result = session.closePane(pane)) {
                is Outcome.Ok -> {
                    ctx.update { it.copy(layout = it.layout.copy(mutating = false, closePane = null)) }
                    refresh()
                }
                is Outcome.Err ->
                    ctx.update { it.copy(layout = it.layout.copy(mutating = false, errorText = result.failure.userMessage())) }
            }
        }
    }

    fun rename() {
        val session = ctx.session() ?: return
        val state = ctx.value()
        val pane = state.layout.renamePane ?: return
        if (!state.canMutate || !session.supports(UhpMethods.PANE_RENAME)) return
        val name = state.layout.renameDraft.trim()
        if (name.isEmpty()) return
        ctx.update { it.copy(layout = it.layout.copy(mutating = true, errorText = null)) }
        ctx.launch {
            when (val result = session.renamePane(name = name, pane = pane)) {
                is Outcome.Ok -> {
                    ctx.update { it.copy(layout = it.layout.copy(mutating = false, renamePane = null, renameDraft = "")) }
                    refresh()
                }
                is Outcome.Err ->
                    ctx.update { it.copy(layout = it.layout.copy(mutating = false, errorText = result.failure.userMessage())) }
            }
        }
    }

    private suspend fun refresh() {
        val session = ctx.session()
        val runtime = ctx.runtime()
        if (session == null) {
            ctx.update {
                it.copy(connected = false, layout = it.layout.copy(loading = false, errorText = NOT_CONNECTED))
            }
            return
        }
        val canListWorkspaces = session.supports(UhpMethods.WORKSPACE_LIST)
        val canListPanes = session.supports(UhpMethods.PANE_LIST)
        if (!canListWorkspaces && !canListPanes) return
        ctx.update { it.copy(connected = true, layout = it.layout.copy(loading = true, errorText = null)) }
        var error: String? = null
        val workspaces =
            if (canListWorkspaces) {
                when (val result = session.listWorkspaces()) {
                    is Outcome.Ok -> result.value
                    is Outcome.Err -> {
                        error = result.failure.userMessage()
                        runtime?.snapshot?.workspaces.orEmpty()
                    }
                }
            } else {
                runtime?.snapshot?.workspaces.orEmpty()
            }
        val panes =
            if (canListPanes) {
                when (val result = session.listPanes()) {
                    is Outcome.Ok -> result.value.panes
                    is Outcome.Err -> {
                        error = error ?: result.failure.userMessage()
                        emptyList()
                    }
                }
            } else {
                emptyList()
            }
        ctx.update {
            it.copy(
                layout = it.layout.copy(
                    workspaces = workspaces,
                    panes = panes,
                    loading = false,
                    mutating = false,
                    errorText = error,
                ),
            )
        }
    }
}
