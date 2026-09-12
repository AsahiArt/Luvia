import Foundation
import LuviaShared

extension AppModel {
    func loadMoreSurface(_ surface: MoreSurface) async {
        refreshCaps()
        guard hasLiveSession, liveSession() != nil else { return }
        switch surface {
        case .files:
            await loadFileTree()
        case .search:
            if !uhp.searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                await runSearch()
            }
        case .worktrees:
            await loadWorktrees()
        case .automations:
            await loadAutomations()
        case .layout:
            await loadLayout()
        }
    }

    @discardableResult
    func loadFileTree() async -> Bool {
        guard uhp.caps.filesTree, let session = liveSession() else { return false }
        do {
            let outcome = try await session.fileTree()
            switch onEnum(of: outcome) {
            case .ok(let ok):
                guard let result = ok.value else { return false }
                uhp.fileRoot = result.root
                let rows: [FileTreeRow] = KotlinLists.array(result.rows as Any)
                uhp.fileRows = rows.map { row in
                    FileTreeRowItem(
                        path: row.path,
                        name: row.name,
                        depth: Int(row.depth),
                        isDirectory: row.dir,
                        isExpanded: row.expanded
                    )
                }
                uhp.errorMessage = nil
                return true
            case .err(let err):
                uhp.errorMessage = FailureText.describe(err.failure)
                return false
            }
        } catch {
            if FailureText.isCancellation(error) { return false }
            uhp.errorMessage = error.localizedDescription
            return false
        }
    }

    func openHostFile(_ path: String) async {
        guard uhp.allowsMutation, uhp.caps.filesOpen, let session = liveSession() else { return }
        uhp.isSending = true
        defer { uhp.isSending = false }
        do {
            let outcome = try await session.openFile(path: path, target: nil)
            switch onEnum(of: outcome) {
            case .ok:
                uhp.errorMessage = nil
            case .err(let err):
                handleMutationFailure(err.failure, action: .openFile)
            }
        } catch {
            markUnconfirmed(.openFile, error.localizedDescription)
        }
    }

    func revealHostFile(_ path: String) async {
        guard uhp.allowsMutation, uhp.caps.filesReveal, let session = liveSession() else { return }
        uhp.isSending = true
        defer { uhp.isSending = false }
        do {
            let outcome = try await session.revealFile(path: path)
            switch onEnum(of: outcome) {
            case .ok:
                uhp.errorMessage = nil
            case .err(let err):
                handleMutationFailure(err.failure, action: .revealFile)
            }
        } catch {
            markUnconfirmed(.revealFile, error.localizedDescription)
        }
    }

    @discardableResult
    func runSearch() async -> Bool {
        let query = uhp.searchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty, uhp.caps.searchQuery, let session = liveSession() else { return false }
        do {
            let outcome = try await session.querySearch(
                query: query,
                scope: .files,
                caseSensitive: nil,
                allSessions: nil,
                limit: 50
            )
            switch onEnum(of: outcome) {
            case .ok(let ok):
                guard let result = ok.value else { return false }
                uhp.searchTotal = kotlinInt64(result.total) ?? 0
                uhp.searchShown = kotlinInt64(result.shown) ?? 0
                uhp.searchPartial = result.partial
                let matches: [SearchMatch] = KotlinLists.array(result.matches as Any)
                uhp.searchMatches = matches.enumerated().map { index, match in
                    SearchMatchItem(
                        id: match.id.isEmpty ? "match-\(index)" : match.id,
                        kind: match.kind,
                        label: match.label,
                        detail: match.detail,
                        match: match
                    )
                }
                uhp.errorMessage = nil
                return true
            case .err(let err):
                uhp.errorMessage = FailureText.describe(err.failure)
                return false
            }
        } catch {
            if FailureText.isCancellation(error) { return false }
            uhp.errorMessage = error.localizedDescription
            return false
        }
    }

    func activateSearchMatch(_ item: SearchMatchItem) async {
        guard uhp.allowsMutation, uhp.caps.searchActivate, let session = liveSession() else { return }
        guard let kind = searchKind(from: item.kind) else {
            uhp.errorMessage = "Unknown search kind \(item.kind)."
            return
        }
        uhp.isSending = true
        defer { uhp.isSending = false }
        do {
            let outcome = try await session.activateSearch(kind: kind, target: item.match.target)
            switch onEnum(of: outcome) {
            case .ok:
                uhp.errorMessage = nil
            case .err(let err):
                handleMutationFailure(err.failure, action: .activateSearch)
            }
        } catch {
            markUnconfirmed(.activateSearch, error.localizedDescription)
        }
    }

    @discardableResult
    func loadWorktrees() async -> Bool {
        guard uhp.caps.worktreeList, let session = liveSession() else { return false }
        do {
            let outcome = try await session.listWorktrees(workspace: nil)
            switch onEnum(of: outcome) {
            case .ok(let ok):
                let entries: [WorktreeEntry] = KotlinLists.array(ok.value as Any)
                uhp.worktrees = entries.map { entry in
                    WorktreeItem(
                        path: entry.path,
                        branch: entry.branch,
                        head: entry.head,
                        isMain: entry.main
                    )
                }
                uhp.errorMessage = nil
                return true
            case .err(let err):
                uhp.errorMessage = FailureText.describe(err.failure)
                return false
            }
        } catch {
            if FailureText.isCancellation(error) { return false }
            uhp.errorMessage = error.localizedDescription
            return false
        }
    }

    func createHostWorktree() async {
        let branch = uhp.createWorktreeBranch.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !branch.isEmpty, uhp.allowsMutation, uhp.caps.worktreeCreate, let session = liveSession() else { return }
        uhp.isSending = true
        defer { uhp.isSending = false }
        do {
            let outcome = try await session.createWorktree(branch: branch, ifRevision: nil, workspace: nil)
            switch onEnum(of: outcome) {
            case .ok:
                uhp.isCreateWorktreePresented = false
                uhp.createWorktreeBranch = ""
                uhp.errorMessage = nil
                await loadWorktrees()
            case .err(let err):
                handleMutationFailure(err.failure, action: .createWorktree)
                if uhp.unconfirmed != nil {
                    uhp.isCreateWorktreePresented = false
                }
            }
        } catch {
            markUnconfirmed(.createWorktree, error.localizedDescription)
            uhp.isCreateWorktreePresented = false
        }
    }

    func openHostWorktree(_ path: String) async {
        guard uhp.allowsMutation, uhp.caps.worktreeOpen, let session = liveSession() else { return }
        uhp.isSending = true
        defer { uhp.isSending = false }
        do {
            let outcome = try await session.openWorktree(path: path, ifRevision: nil)
            switch onEnum(of: outcome) {
            case .ok:
                uhp.errorMessage = nil
                await loadWorktrees()
            case .err(let err):
                handleMutationFailure(err.failure, action: .openWorktree)
            }
        } catch {
            markUnconfirmed(.openWorktree, error.localizedDescription)
        }
    }

    func removeHostWorktree(_ path: String) async {
        guard uhp.allowsMutation, uhp.caps.worktreeRemove, let session = liveSession() else { return }
        uhp.isSending = true
        defer { uhp.isSending = false }
        do {
            let outcome = try await session.removeWorktree(path: path, ifRevision: nil)
            switch onEnum(of: outcome) {
            case .ok:
                uhp.errorMessage = nil
                await loadWorktrees()
            case .err(let err):
                handleMutationFailure(err.failure, action: .removeWorktree)
            }
        } catch {
            markUnconfirmed(.removeWorktree, error.localizedDescription)
        }
    }

    @discardableResult
    func loadAutomations() async -> Bool {
        guard let session = liveSession() else { return false }
        var listed = false
        if uhp.caps.automationList {
            do {
                let outcome = try await session.listAutomations()
                switch onEnum(of: outcome) {
                case .ok(let ok):
                    let rows: [Automation] = KotlinLists.array(ok.value as Any)
                    uhp.automations = rows.map { row in
                        AutomationItem(
                            id: row.id,
                            name: row.name,
                            enabled: row.enabled,
                            state: nil,
                            nextRun: kotlinInt64(row.nextRunAt).map(formatEpochSeconds),
                            latestStatus: nil,
                            latestError: nil
                        )
                    }
                    uhp.errorMessage = nil
                    listed = true
                case .err(let err):
                    uhp.errorMessage = FailureText.describe(err.failure)
                }
            } catch {
                if !FailureText.isCancellation(error) {
                    uhp.errorMessage = error.localizedDescription
                }
            }
        }
        if uhp.caps.automationHealth {
            do {
                let outcome = try await session.automationHealth()
                if case .ok(let ok) = onEnum(of: outcome), let result = ok.value {
                    let enabled = kotlinInt64(result.summary.enabled) ?? 0
                    let running = kotlinInt64(result.summary.running) ?? 0
                    let failed = kotlinInt64(result.summary.failed) ?? 0
                    uhp.automationHealthSummary = "\(enabled) enabled · \(running) running · \(failed) failed"
                    let views: [AutomationView] = KotlinLists.array(result.automations as Any)
                    if uhp.automations.isEmpty {
                        uhp.automations = views.map { view in
                            AutomationItem(
                                id: view.id,
                                name: view.name,
                                enabled: view.state != "disabled",
                                state: view.state,
                                nextRun: kotlinInt64(view.nextRunAt).map(formatEpochSeconds),
                                latestStatus: view.latestStatus,
                                latestError: view.latestError
                            )
                        }
                    } else {
                        for view in views {
                            guard let index = uhp.automations.firstIndex(where: { $0.id == view.id }) else { continue }
                            uhp.automations[index].state = view.state
                            uhp.automations[index].latestStatus = view.latestStatus
                            uhp.automations[index].latestError = view.latestError
                            if let next = kotlinInt64(view.nextRunAt) {
                                uhp.automations[index].nextRun = formatEpochSeconds(next)
                            }
                        }
                    }
                    if listed || uhp.errorMessage == nil {
                        uhp.errorMessage = nil
                    }
                    listed = true
                }
            } catch {
                if !listed && !FailureText.isCancellation(error) {
                    uhp.errorMessage = error.localizedDescription
                }
            }
        }
        return listed
    }

    func setAutomationEnabled(_ id: String, enabled: Bool) async {
        guard uhp.allowsMutation, let session = liveSession() else { return }
        let canCall = enabled ? uhp.caps.automationEnable : uhp.caps.automationDisable
        guard canCall else { return }
        let action: UnconfirmedAction = enabled ? .enableAutomation : .disableAutomation
        uhp.isSending = true
        defer { uhp.isSending = false }
        do {
            let outcome = if enabled {
                try await session.enableAutomation(id: id, ifRevision: nil)
            } else {
                try await session.disableAutomation(id: id, ifRevision: nil)
            }
            switch onEnum(of: outcome) {
            case .ok:
                uhp.errorMessage = nil
                await loadAutomations()
            case .err(let err):
                handleMutationFailure(err.failure, action: action)
            }
        } catch {
            markUnconfirmed(action, error.localizedDescription)
        }
    }

    func runHostAutomation(_ id: String) async {
        guard uhp.allowsMutation, uhp.caps.automationRun, let session = liveSession() else { return }
        uhp.isSending = true
        defer { uhp.isSending = false }
        do {
            let outcome = try await session.runAutomation(id: id, idempotencyKey: nil, ifRevision: nil)
            switch onEnum(of: outcome) {
            case .ok:
                uhp.errorMessage = nil
                await loadAutomations()
            case .err(let err):
                handleMutationFailure(err.failure, action: .runAutomation)
            }
        } catch {
            markUnconfirmed(.runAutomation, error.localizedDescription)
        }
    }

    @discardableResult
    func loadLayout() async -> Bool {
        guard let session = liveSession() else { return false }
        var loaded = false
        if uhp.caps.workspaceList {
            do {
                let outcome = try await session.listWorkspaces()
                switch onEnum(of: outcome) {
                case .ok(let ok):
                    let summaries: [WorkspaceSummary] = KotlinLists.array(ok.value as Any)
                    uhp.workspaces = summaries.map { summary in
                        WorkspaceItem(
                            index: Int(summary.index),
                            name: summary.name,
                            isActive: summary.active,
                            isPinned: summary.pinned,
                            cwd: summary.cwd,
                            branch: summary.branch,
                            tabCount: Int(summary.tabCount)
                        )
                    }
                    loaded = true
                    uhp.errorMessage = nil
                case .err(let err):
                    uhp.errorMessage = FailureText.describe(err.failure)
                }
            } catch {
                if !FailureText.isCancellation(error) {
                    uhp.errorMessage = error.localizedDescription
                }
            }
        }
        if uhp.caps.paneList {
            do {
                let outcome = try await session.listPanes()
                switch onEnum(of: outcome) {
                case .ok(let ok):
                    guard let result = ok.value else { break }
                    let entries: [PaneListEntry] = KotlinLists.array(result.panes as Any)
                    uhp.panes = entries.map { entry in
                        PaneItem(
                            pane: entry.pane,
                            agent: entry.agent,
                            status: AgentStatusKind(entry.status).label,
                            isFocused: entry.focused,
                            cwd: entry.cwd
                        )
                    }
                    loaded = true
                    if uhp.errorMessage == nil || uhp.caps.workspaceList == false {
                        uhp.errorMessage = nil
                    }
                case .err(let err):
                    if !loaded {
                        uhp.errorMessage = FailureText.describe(err.failure)
                    }
                }
            } catch {
                if !loaded && !FailureText.isCancellation(error) {
                    uhp.errorMessage = error.localizedDescription
                }
            }
        }
        return loaded
    }

    func focusHostPane(_ pane: String) async {
        guard uhp.allowsMutation, uhp.caps.paneFocus, let session = liveSession() else { return }
        uhp.isSending = true
        defer { uhp.isSending = false }
        do {
            let outcome = try await session.focusPane(pane: pane, ifRevision: nil)
            switch onEnum(of: outcome) {
            case .ok:
                uhp.errorMessage = nil
                await loadLayout()
            case .err(let err):
                handleMutationFailure(err.failure, action: .focusPane)
            }
        } catch {
            markUnconfirmed(.focusPane, error.localizedDescription)
        }
    }

    func closeHostPane(_ pane: String) async {
        guard uhp.allowsMutation, uhp.caps.paneClose, let session = liveSession() else { return }
        uhp.isSending = true
        defer { uhp.isSending = false }
        do {
            let outcome = try await session.closePane(pane: pane, ifRevision: nil)
            switch onEnum(of: outcome) {
            case .ok:
                uhp.errorMessage = nil
                await loadLayout()
            case .err(let err):
                handleMutationFailure(err.failure, action: .closePane)
            }
        } catch {
            markUnconfirmed(.closePane, error.localizedDescription)
        }
    }

    func closeHostWorkspace(_ index: Int) async {
        guard uhp.allowsMutation, uhp.caps.workspaceClose, let session = liveSession() else { return }
        uhp.isSending = true
        defer { uhp.isSending = false }
        do {
            let outcome = try await session.closeWorkspace(
                workspace: KotlinInt(int: Int32(index)),
                ifRevision: nil
            )
            switch onEnum(of: outcome) {
            case .ok:
                uhp.errorMessage = nil
                await loadLayout()
            case .err(let err):
                handleMutationFailure(err.failure, action: .closeWorkspace)
            }
        } catch {
            markUnconfirmed(.closeWorkspace, error.localizedDescription)
        }
    }

    @discardableResult
    func loadAgentSessions() async -> Bool {
        guard uhp.caps.agentSessions, let session = liveSession() else { return false }
        do {
            let outcome = try await session.listAgentSessions()
            switch onEnum(of: outcome) {
            case .ok(let ok):
                let rows: [AgentSessionEntry] = KotlinLists.array(ok.value as Any)
                uhp.agentSessions = rows.map { row in
                    AgentSessionItem(agent: row.agent, sessionId: row.sessionId, cwd: row.cwd)
                }
                uhp.errorMessage = nil
                return true
            case .err(let err):
                uhp.errorMessage = FailureText.describe(err.failure)
                return false
            }
        } catch {
            uhp.errorMessage = error.localizedDescription
            return false
        }
    }

    func resumeHostAgent(_ sessionId: String) async {
        guard uhp.allowsMutation, uhp.caps.agentResume, let session = liveSession() else { return }
        uhp.isSending = true
        defer { uhp.isSending = false }
        do {
            let outcome = try await session.resumeAgent(sessionId: sessionId)
            switch onEnum(of: outcome) {
            case .ok:
                uhp.errorMessage = nil
                await loadAgents()
                await loadAgentSessions()
            case .err(let err):
                handleMutationFailure(err.failure, action: .resumeAgent)
            }
        } catch {
            markUnconfirmed(.resumeAgent, error.localizedDescription)
        }
    }

    func beginNameAgent() {
        guard uhp.allowsMutation, uhp.caps.agentName else { return }
        uhp.nameAgentText = uhp.header?.name ?? ""
        uhp.isNameAgentPresented = true
    }

    func nameOpenAgent() async {
        let name = uhp.nameAgentText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty,
              uhp.allowsMutation,
              uhp.caps.agentName,
              let pane = uhp.selectedAgentID,
              let session = liveSession()
        else { return }
        uhp.isSending = true
        defer { uhp.isSending = false }
        do {
            let outcome = try await session.nameAgent(pane: pane, name: name, clear: false)
            switch onEnum(of: outcome) {
            case .ok:
                uhp.isNameAgentPresented = false
                uhp.errorMessage = nil
                await refreshOpenAgent()
                await loadAgents()
            case .err(let err):
                handleMutationFailure(err.failure, action: .nameAgent)
                if uhp.unconfirmed != nil {
                    uhp.isNameAgentPresented = false
                }
            }
        } catch {
            markUnconfirmed(.nameAgent, error.localizedDescription)
            uhp.isNameAgentPresented = false
        }
    }

    func beginForkAgent() {
        guard uhp.allowsMutation, uhp.caps.agentFork else { return }
        uhp.forkAgentName = ""
        uhp.isForkAgentPresented = true
    }

    func forkOpenAgent() async {
        guard uhp.allowsMutation,
              uhp.caps.agentFork,
              let target = uhp.selectedAgentID,
              let session = liveSession()
        else { return }
        let name = uhp.forkAgentName.trimmingCharacters(in: .whitespacesAndNewlines)
        uhp.isSending = true
        defer { uhp.isSending = false }
        do {
            let outcome = try await session.forkAgent(
                target: target,
                name: name.isEmpty ? nil : name,
                focus: nil
            )
            switch onEnum(of: outcome) {
            case .ok:
                uhp.isForkAgentPresented = false
                uhp.forkAgentName = ""
                uhp.errorMessage = nil
                await loadAgents()
            case .err(let err):
                handleMutationFailure(err.failure, action: .forkAgent)
                if uhp.unconfirmed != nil {
                    uhp.isForkAgentPresented = false
                }
            }
        } catch {
            markUnconfirmed(.forkAgent, error.localizedDescription)
            uhp.isForkAgentPresented = false
        }
    }

    func claimTask(_ id: String) async {
        guard uhp.allowsMutation, uhp.caps.taskClaim, let session = liveSession() else { return }
        uhp.isSending = true
        defer { uhp.isSending = false }
        if uhp.taskRevisions[id] == nil {
            await refreshTaskRevision(id)
        }
        let revision = uhp.taskRevisions[id].map { KotlinLong(longLong: $0) }
        do {
            let outcome = try await session.claimTask(id: id, pane: nil, ifRevision: revision)
            switch onEnum(of: outcome) {
            case .ok(let ok):
                if let result = ok.value {
                    storeTaskRevision(result.task.id, result.revision)
                }
                uhp.boardChangedMessage = nil
                uhp.errorMessage = nil
                await loadTasks()
            case .err(let err):
                handleTaskMutationFailure(err.failure, action: .claimTask, taskID: id)
            }
        } catch {
            uhp.unconfirmedTaskID = id
            markUnconfirmed(.claimTask, error.localizedDescription)
        }
    }

    func deleteTask(_ id: String) async {
        guard uhp.allowsMutation, uhp.caps.taskDelete, let session = liveSession() else { return }
        uhp.isSending = true
        defer { uhp.isSending = false }
        if uhp.taskRevisions[id] == nil {
            await refreshTaskRevision(id)
        }
        let revision = uhp.taskRevisions[id].map { KotlinLong(longLong: $0) }
        do {
            let outcome = try await session.deleteTask(id: id, ifRevision: revision)
            switch onEnum(of: outcome) {
            case .ok(let ok):
                if let result = ok.value {
                    storeTaskRevision(result.task.id, result.revision)
                }
                uhp.boardChangedMessage = nil
                uhp.errorMessage = nil
                await loadTasks()
            case .err(let err):
                handleTaskMutationFailure(err.failure, action: .deleteTask, taskID: id)
            }
        } catch {
            uhp.unconfirmedTaskID = id
            markUnconfirmed(.deleteTask, error.localizedDescription)
        }
    }
}

func searchKind(from raw: String) -> SearchKind? {
    switch raw.lowercased() {
    case "session": .session
    case "folder": .folder
    case "tab": .tab
    case "pane": .pane
    case "agent": .agent
    case "file": .file
    case "output": .output
    default: nil
    }
}

private func formatEpochSeconds(_ value: Int64) -> String {
    Date(timeIntervalSince1970: TimeInterval(value))
        .formatted(date: .abbreviated, time: .shortened)
}
