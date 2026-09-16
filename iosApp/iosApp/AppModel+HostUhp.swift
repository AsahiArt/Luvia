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
        uhp.snapshot = state
        uhp.localError = nil
        if state.agentDetail.open, let paneId = state.agentDetail.summary?.paneId {
            uhp.selectedAgentID = paneId
        }
        uhp.isAcpPresented = state.acp.open
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
