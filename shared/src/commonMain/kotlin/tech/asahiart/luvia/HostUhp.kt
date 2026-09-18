package tech.asahiart.luvia

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.asahiart.luvia.internal.uhp.AgentBoard
import tech.asahiart.luvia.internal.uhp.AcpBoard
import tech.asahiart.luvia.internal.uhp.AutomationBoard
import tech.asahiart.luvia.internal.uhp.FilesBoard
import tech.asahiart.luvia.internal.uhp.LayoutBoard
import tech.asahiart.luvia.internal.uhp.ReviewBoard
import tech.asahiart.luvia.internal.uhp.SearchBoard
import tech.asahiart.luvia.internal.uhp.TaskBoard
import tech.asahiart.luvia.internal.uhp.UhpContext
import tech.asahiart.luvia.internal.uhp.WorktreeBoard

public class HostUhp(
    session: () -> LuviaSession?,
    runtime: () -> HostRuntime?,
    scope: CoroutineScope,
    private val manager: HostManager? = null,
) {

    private val job: Job = SupervisorJob(scope.coroutineContext[Job])
    private val uhpScope: CoroutineScope = CoroutineScope(scope.coroutineContext + job)
    private val stateFlow: MutableStateFlow<HostUhpState> = MutableStateFlow(HostUhpState())
    private val ctx: UhpContext = UhpContext(session, runtime, stateFlow, uhpScope)
    private val agents: AgentBoard = AgentBoard(ctx)
    private val review: ReviewBoard = ReviewBoard(ctx)
    private val tasks: TaskBoard = TaskBoard(ctx)
    private val files: FilesBoard = FilesBoard(ctx)
    private val search: SearchBoard = SearchBoard(ctx)
    private val worktrees: WorktreeBoard = WorktreeBoard(ctx)
    private val automations: AutomationBoard = AutomationBoard(ctx)
    private val layout: LayoutBoard = LayoutBoard(ctx)
    private val acp: AcpBoard = AcpBoard(ctx)

    public val state: StateFlow<HostUhpState> = stateFlow.asStateFlow()

    public fun applyRuntime(runtime: HostRuntime) {
        agents.applyRuntime(runtime)
    }

    public fun shown() {
        agents.load()
    }

    public fun show(section: HostSection) {
        when (section) {
            HostSection.Agents -> {
                agents.load()
                acp.loadAgents()
            }
            HostSection.Files -> files.load()
            HostSection.Search -> search.shown()
            HostSection.Review -> {
                layout.load()
                review.load()
            }
            HostSection.Worktrees -> worktrees.load()
            HostSection.Automations -> automations.load()
            HostSection.Tasks -> {
                layout.load()
                tasks.load()
            }
            HostSection.Layout -> layout.load()
            HostSection.Terminal -> Unit
        }
    }

    public fun setSection(section: HostSection) {
        ctx.update { it.copy(section = section) }
    }

    public fun setSelectedWorkspace(id: String?) {
        val trimmed = id?.trim()?.ifEmpty { null }
        ctx.update { it.copy(selectedWorkspaceId = trimmed) }
        show(ctx.value().section)
    }

    public fun openAgent(paneId: String) = agents.open(paneId)

    public fun closeAgent() = agents.close()

    public fun setAgentDraft(text: String) = agents.setDraft(text)

    public fun promptAgent(text: String) = agents.prompt(text)

    public fun sendAgentKeys(keys: List<AgentKey>) = agents.sendKeys(keys)

    public fun checkAgent() = agents.check()

    public fun resumeAgent(sessionId: String) = agents.resume(sessionId)

    public fun setShowNameAgent(show: Boolean) = agents.setShowName(show)

    public fun setNameAgentDraft(text: String) = agents.setNameDraft(text)

    public fun nameAgent() = agents.name()

    public fun setShowForkAgent(show: Boolean) = agents.setShowFork(show)

    public fun setForkAgentDraft(text: String) = agents.setForkDraft(text)

    public fun forkAgent() = agents.fork()

    public fun openDiffFile(path: String, layer: DiffLayer?) = review.openFile(path, layer)

    public fun closeDiffFile() = review.closeFile()

    public fun addReviewNote(
        file: String,
        line: ReviewLine,
        body: String,
        layer: DiffLayer?,
    ) = review.addNote(file, line, body, layer)

    public fun resolveReviewNote(id: String) = review.resolve(id)

    public fun reopenReviewNote(id: String) = review.reopen(id)

    public fun removeReviewNote(id: String) = review.remove(id)

    public fun sendReviewNotes(to: String) = review.send(to)

    public fun checkNotes() = review.check()

    public fun setNoteDraft(text: String) = review.setNoteDraft(text)

    public fun setSendTarget(paneId: String?) = review.setSendTarget(paneId)

    public fun addTask(title: String, paths: List<String>) = tasks.add(title, paths)

    public fun completeTask(taskId: String) = tasks.complete(taskId)
    public fun retryTask(taskId: String) = tasks.retry(taskId)


    public fun claimTask(taskId: String) = tasks.claim(taskId)

    public fun deleteTask(taskId: String) = tasks.delete(taskId)

    public fun checkTasks() = tasks.check()

    public fun setShowAddTask(show: Boolean) = tasks.setShowAdd(show)

    public fun setCompleteTaskId(id: String?) = tasks.setCompleteId(id)

    public fun setDeleteTaskId(id: String?) = tasks.setDeleteId(id)

    public fun setAddTaskDraft(title: String, paths: String) = tasks.setAddDraft(title, paths)

    public fun refreshFiles() = files.refresh()

    public fun openFile(path: String) = files.open(path)

    public fun revealFile(path: String) = files.reveal(path)

    public fun setSearchQuery(query: String) = search.setQuery(query)

    public fun querySearch() = search.query()

    public fun activateSearch(matchId: String) = search.activate(matchId)

    public fun setShowCreateWorktree(show: Boolean) = worktrees.setShowCreate(show)

    public fun setCreateWorktreeBranch(branch: String) = worktrees.setCreateBranch(branch)

    public fun createWorktree() = worktrees.create()

    public fun openWorktree(path: String) = worktrees.open(path)

    public fun setRemoveWorktreePath(path: String?) = worktrees.setRemovePath(path)

    public fun removeWorktree(path: String) = worktrees.remove(path)

    public fun enableAutomation(id: String) = automations.enable(id)

    public fun disableAutomation(id: String) = automations.disable(id)

    public fun runAutomation(id: String) = automations.run(id)

    public fun createAutomation(draft: AutomationDraft) = automations.create(draft)

    public fun updateAutomation(id: String, draft: AutomationDraft) = automations.update(id, draft)

    public fun deleteAutomation(id: String) = automations.delete(id)

    public fun rebindAutomation(id: String, pane: String, terminalId: String? = null) =
        automations.rebind(id, pane, terminalId)

    public fun loadAutomationHistory(id: String, limit: Long = 20) = automations.loadHistory(id, limit)

    public fun previewAutomation(trigger: AutomationTrigger) = automations.preview(trigger)

    public fun clearAutomationPreview() = automations.clearPreview()

    public fun setPushEnabled(enabled: Boolean) {
        val mgr = manager ?: return
        if (enabled) {
            val registration = mgr.pushRegistration.value ?: return
            mgr.registerPush(registration)
        } else {
            mgr.unregisterPush()
        }
    }

    internal fun applyBusEvent(event: BusEvent) {
        if (event is BusEvent.AutomationChanged) {
            automations.onBusEvent(event)
        }
    }

    public fun focusWorkspace(index: Int) = layout.focusWorkspace(index)

    public fun setCloseWorkspace(index: Int?) = layout.setCloseWorkspace(index)

    public fun closeWorkspace(index: Int) = layout.closeWorkspace(index)

    public fun focusPane(pane: String) = layout.focusPane(pane)

    public fun setClosePane(pane: String?) = layout.setClosePane(pane)

    public fun closePane(pane: String) = layout.closePane(pane)

    public fun setRenamePane(pane: String?, draft: String) = layout.setRenamePane(pane, draft)

    public fun renamePane() = layout.rename()

    public fun loadAcpAgents() = acp.loadAgents()

    public fun setShowLaunchAcp(show: Boolean) = acp.setShowLaunch(show)

    public fun setLaunchAcpAgent(id: String?) = acp.setLaunchAgent(id)

    public fun setLaunchAcpCwd(cwd: String) = acp.setLaunchCwd(cwd)

    public fun launchAcp() = acp.launch()

    public fun setAcpDraft(text: String) = acp.setDraft(text)

    public fun promptAcp() = acp.prompt()

    public fun answerAcpPermission(optionId: String) = acp.answerPermission(optionId)

    public fun cancelAcp() = acp.cancel()

    public fun closeAcp() = acp.close()

    public fun close() {
        acp.close()
        job.cancel()
    }
}

public class HostUhpRegistry(
    private val manager: HostManager,
    scope: CoroutineScope,
) {
    private val job: Job = SupervisorJob(scope.coroutineContext[Job])
    private val registryScope: CoroutineScope = CoroutineScope(scope.coroutineContext + job)
    private val workspaces: MutableMap<String, HostUhp> = mutableMapOf()
    private val combined: MutableStateFlow<Map<String, HostUhpState>> = MutableStateFlow(emptyMap())
    private val collectJobs: MutableMap<String, Job> = mutableMapOf()

    public val states: StateFlow<Map<String, HostUhpState>> = combined.asStateFlow()

    init {
        registryScope.launch {
            manager.hosts.collect { runtimes ->
                val ids = runtimes.map { it.profile.id }.toSet()
                workspaces.keys.filter { it !in ids }.toList().forEach { drop(it) }
                runtimes.forEach { runtime ->
                    workspace(runtime.profile.id).applyRuntime(runtime)
                }
            }
        }
        registryScope.launch {
            manager.busEvents.collect { (hostId, event) ->
                workspaces[hostId]?.applyBusEvent(event)
            }
        }

    }

    public fun workspace(hostId: String): HostUhp {
        workspaces[hostId]?.let { return it }
        val created =
            HostUhp(
                session = { manager.session(hostId) },
                runtime = { manager.hosts.value.firstOrNull { it.profile.id == hostId } },
                scope = registryScope,
                manager = manager,
            )

        workspaces[hostId] = created
        collectJobs[hostId] =
            registryScope.launch {
                created.state.collect { slice ->
                    combined.update { it + (hostId to slice) }
                }
            }
        return created
    }

    public fun refreshSection(hostId: String, section: HostSection) {
        registryScope.launch { manager.refresh(hostId) }
        workspace(hostId).show(section)
    }

    public fun close() {
        workspaces.keys.toList().forEach { drop(it) }
        job.cancel()
    }

    private fun drop(hostId: String) {
        collectJobs.remove(hostId)?.cancel()
        workspaces.remove(hostId)?.close()
        combined.update { it - hostId }
    }
}
