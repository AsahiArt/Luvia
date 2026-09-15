import Foundation
import LuviaShared

extension AppModel {
    func hostUhp() -> HostUhp? {
        guard let id = selectedHostID else { return nil }
        return uhpRegistry.workspace(hostId: id)
    }

    func bindUhp(hostID: String) {
        if boundUhpHostID == hostID, uhpTask != nil { return }
        boundUhpHostID = hostID
        uhpTask?.cancel()
        let workspace = uhpRegistry.workspace(hostId: hostID)
        uhpTask = _Concurrency.Task { [weak self] in
            for await state in workspace.state {
                await MainActor.run {
                    self?.applyHostUhp(state)
                }
            }
        }
    }

    func applyHostUhp(_ state: HostUhpState) {
        hasLiveSession = state.connected
        uhp.isController = state.canMutate || (state.connected && !state.isObserver)
        uhp.caps = UhpCaps(state.capabilities)
        let agents: [AgentSummary] = KotlinLists.array(state.agents as Any)
        uhp.agents = agents.map(AgentViewState.init)
        let sessions: [AgentSessionEntry] = KotlinLists.array(state.agentSessions as Any)
        uhp.agentSessions = sessions.map { session in
            AgentSessionItem(agent: session.agent, sessionId: session.sessionId, cwd: session.cwd)
        }
        if let summary = state.agentDetail.summary {
            uhp.selectedAgentID = state.agentDetail.open ? summary.paneId : uhp.selectedAgentID
            uhp.header = AgentHeaderState(
                paneId: summary.paneId,
                name: summary.name ?? summary.agent ?? summary.paneId,
                kind: summary.agent,
                status: agentStatusLabel(summary.status),
                isBlocked: AgentStatusKind(summary.status) == .blocked,
                workspace: summary.workspaceName ?? summary.workspace,
                branch: summary.branch,
                cwd: summary.cwd,
                missionUsage: nil
            )
        }
        if let transcript = state.agentDetail.transcript {
            uhp.transcript = transcript.text
            uhp.transcriptRevision = kotlinInt64(transcript.revision)
            uhp.transcriptContentRevision = kotlinInt64(transcript.contentRevision)
            uhp.transcriptTerminalID = transcript.terminalId
        }
        uhp.isSending = state.agentDetail.sending || state.review.sending || state.tasks.mutating
            || state.files.mutating || state.worktrees.mutating || state.automations.mutating
            || state.layout.mutating
        uhp.unconfirmed = UnconfirmedAction(state)
        uhp.unconfirmedTaskID = state.tasks.unconfirmedTaskId
        uhp.errorMessage = state.agentDetail.errorText
            ?? state.review.errorText
            ?? state.tasks.errorText
            ?? state.files.errorText
            ?? state.search.errorText
            ?? state.worktrees.errorText
            ?? state.automations.errorText
            ?? state.layout.errorText
            ?? state.errorText
        if let list = state.review.list {
            uhp.diffBranch = list.branch
            let files: [DiffFile] = KotlinLists.array(list.files as Any)
            uhp.diffFiles = files.map { file in
                DiffFileItem(
                    path: file.path,
                    layer: diffLayerLabel(file.layer),
                    additions: Int(kotlinInt64(file.additions) ?? 0),
                    deletions: Int(kotlinInt64(file.deletions) ?? 0)
                )
            }
        }
        if let selected = state.review.selectedFile {
            uhp.selectedDiff = DiffFileDetail(
                item: DiffFileItem(
                    path: selected.path,
                    layer: diffLayerLabel(selected.layer),
                    additions: Int(kotlinInt64(selected.additions) ?? 0),
                    deletions: Int(kotlinInt64(selected.deletions) ?? 0)
                ),
                hunks: []
            )
        }
        let notes: [ReviewNote] = KotlinLists.array(state.review.notes as Any)
        uhp.notes = notes.map { note in
            ReviewNoteItem(
                id: note.id,
                body: note.body,
                stateLabel: note.state == .resolved ? "resolved" : "open",
                isOpen: note.state == .open,
                isResolved: note.state == .resolved,
                path: note.path,
                line: kotlinInt64(note.startLine).map { Int($0) },
                deliveries: nil
            )
        }
        let tasks: [TaskSummary] = KotlinLists.array(state.tasks.tasks as Any)
        uhp.tasks = tasks.map(TaskViewState.init)
        uhp.boardChangedMessage = state.tasks.boardChanged ? "Updated by someone else. Showing latest." : nil
        uhp.fileRoot = state.files.root.isEmpty ? nil : state.files.root
        let rows: [FileTreeRow] = KotlinLists.array(state.files.rows as Any)
        uhp.fileRows = rows.map { row in
            FileTreeRowItem(
                path: row.path,
                name: row.name,
                depth: Int(row.depth),
                isDirectory: row.dir,
                isExpanded: row.expanded
            )
        }
        if let result = state.search.result {
            uhp.searchTotal = kotlinInt64(result.total) ?? 0
            uhp.searchShown = kotlinInt64(result.shown) ?? 0
            uhp.searchPartial = result.partial
        }
        let matches: [SearchMatch] = KotlinLists.array(state.search.matches as Any)
        uhp.searchMatches = matches.map { match in
            SearchMatchItem(id: match.id, kind: match.kind, label: match.label, detail: match.detail, match: match)
        }
        let worktrees: [WorktreeEntry] = KotlinLists.array(state.worktrees.worktrees as Any)
        uhp.worktrees = worktrees.map { tree in
            WorktreeItem(path: tree.path, branch: tree.branch, head: tree.head, isMain: tree.main)
        }
        let automations: [Automation] = KotlinLists.array(state.automations.automations as Any)
        uhp.automations = automations.map { item in
            AutomationItem(
                id: item.id,
                name: item.name,
                enabled: item.enabled,
                state: item.targetState,
                nextRun: nil,
                latestStatus: nil,
                latestError: nil
            )
        }
        let workspaces: [WorkspaceSummary] = KotlinLists.array(state.layout.workspaces as Any)
        uhp.workspaces = workspaces.map { workspace in
            WorkspaceItem(
                index: Int(workspace.index),
                name: workspace.name,
                isActive: workspace.active,
                isPinned: workspace.pinned,
                cwd: workspace.cwd,
                branch: workspace.branch,
                tabCount: Int(workspace.tabCount)
            )
        }
        let panes: [PaneListEntry] = KotlinLists.array(state.layout.panes as Any)
        uhp.panes = panes.map { pane in
            PaneItem(
                pane: pane.pane,
                agent: pane.agent,
                status: agentStatusLabel(pane.status),
                isFocused: pane.focused,
                cwd: pane.cwd
            )
        }
    }
}

extension UhpCaps {
    init(_ caps: HostCapabilities) {
        self.init(
            agentRead: caps.agentRead,
            agentPrompt: caps.agentPrompt,
            agentKeys: caps.agentKeys,
            agentSessions: caps.agentSessions,
            agentResume: caps.agentResume,
            agentFork: caps.agentFork,
            agentName: caps.agentName,
            missionSnapshot: caps.missionSnapshot,
            diffList: caps.diffList,
            diffGet: caps.diffGet,
            diffNoteList: caps.diffNoteList,
            diffNoteAdd: caps.diffNoteAdd,
            diffNoteSend: caps.diffNoteSend,
            diffNoteResolve: caps.diffNoteResolve,
            diffNoteReopen: caps.diffNoteReopen,
            diffNoteRemove: caps.diffNoteRemove,
            taskList: caps.taskList,
            taskAdd: caps.taskAdd,
            taskDone: caps.taskDone,
            taskClaim: caps.taskClaim,
            taskDelete: caps.taskDelete,
            filesTree: caps.filesTree,
            filesOpen: caps.filesOpen,
            filesReveal: caps.filesReveal,
            searchQuery: caps.searchQuery,
            searchActivate: caps.searchActivate,
            worktreeList: caps.worktreeList,
            worktreeCreate: caps.worktreeCreate,
            worktreeOpen: caps.worktreeOpen,
            worktreeRemove: caps.worktreeRemove,
            automationList: caps.automationList,
            automationEnable: caps.automationEnable,
            automationDisable: caps.automationDisable,
            automationRun: caps.automationRun,
            automationHealth: caps.automationHealth,
            paneList: caps.paneList,
            paneFocus: caps.paneFocus,
            paneClose: caps.paneClose,
            workspaceList: caps.workspaceList,
            workspaceClose: caps.workspaceClose
        )
    }
}

extension UnconfirmedAction {
    init?(_ state: HostUhpState) {
        if let kind = state.agentDetail.unconfirmed {
            self.init(kind)
            return
        }
        if let kind = state.review.unconfirmed {
            self.init(kind)
            return
        }
        if let kind = state.tasks.unconfirmed {
            self.init(kind)
            return
        }
        return nil
    }

    init(_ kind: UnconfirmedKind) {
        switch kind {
        case .agentPrompt: self = .agentPrompt
        case .agentKeys: self = .agentKeys
        case .addReviewNote: self = .addNote
        case .resolveReviewNote: self = .resolveNote
        case .reopenReviewNote: self = .reopenNote
        case .removeReviewNote: self = .removeNote
        case .sendNotes: self = .sendNotes
        case .addTask: self = .addTask
        case .completeTask: self = .completeTask
        case .claimTask: self = .claimTask
        case .deleteTask: self = .deleteTask
        default: self = .agentPrompt
        }
    }
}
