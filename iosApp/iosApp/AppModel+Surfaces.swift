import Foundation
import LuviaShared

extension AppModel {
    func loadMoreSurface(_ surface: MoreSurface) async {
        guard let workspace = hostUhp() else { return }
        switch surface {
        case .files:
            workspace.show(section: LuviaShared.HostSection.files)
        case .search:
            workspace.setSearchQuery(query: uhp.searchQuery)
            workspace.show(section: LuviaShared.HostSection.search)
        case .worktrees:
            workspace.show(section: LuviaShared.HostSection.worktrees)
        case .automations:
            workspace.show(section: LuviaShared.HostSection.automations)
        case .layout:
            workspace.show(section: LuviaShared.HostSection.layout)
        }
    }

    @discardableResult
    func loadFileTree() async -> Bool {
        hostUhp()?.show(section: LuviaShared.HostSection.files)
        return true
    }

    func openHostFile(_ path: String) async {
        hostUhp()?.openFile(path: path)
    }

    func revealHostFile(_ path: String) async {
        hostUhp()?.revealFile(path: path)
    }

    @discardableResult
    func runSearch() async -> Bool {
        guard let workspace = hostUhp() else { return false }
        workspace.setSearchQuery(query: uhp.searchQuery)
        workspace.querySearch()
        return true
    }

    func activateSearchMatch(_ item: SearchMatchItem) async {
        hostUhp()?.activateSearch(matchId: item.id)
    }

    @discardableResult
    func loadWorktrees() async -> Bool {
        hostUhp()?.show(section: LuviaShared.HostSection.worktrees)
        return true
    }

    func createHostWorktree() async {
        let branch = uhp.createWorktreeBranch.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !branch.isEmpty, let workspace = hostUhp() else { return }
        workspace.setCreateWorktreeBranch(branch: branch)
        workspace.createWorktree()
        uhp.isCreateWorktreePresented = false
        uhp.createWorktreeBranch = ""
    }

    func openHostWorktree(_ path: String) async {
        hostUhp()?.openWorktree(path: path)
    }

    func removeHostWorktree(_ path: String) async {
        hostUhp()?.removeWorktree(path: path)
    }

    @discardableResult
    func loadAutomations() async -> Bool {
        hostUhp()?.show(section: LuviaShared.HostSection.automations)
        return true
    }

    func setAutomationEnabled(_ id: String, enabled: Bool) async {
        if enabled {
            hostUhp()?.enableAutomation(id: id)
        } else {
            hostUhp()?.disableAutomation(id: id)
        }
    }

    func runHostAutomation(_ id: String) {
        hostUhp()?.runAutomation(id: id)
    }

    @discardableResult
    func loadLayout() async -> Bool {
        hostUhp()?.show(section: LuviaShared.HostSection.layout)
        return true
    }

    func focusHostPane(_ pane: String) async {
        hostUhp()?.focusPane(pane: pane)
    }

    func closeHostPane(_ pane: String) async {
        hostUhp()?.closePane(pane: pane)
    }

    func closeHostWorkspace(_ index: Int) async {
        hostUhp()?.closeWorkspace(index: Int32(index))
    }

    @discardableResult
    func loadAgentSessions() async -> Bool {
        hostUhp()?.shown()
        return true
    }

    func resumeHostAgent(_ sessionId: String) async {
        hostUhp()?.resumeAgent(sessionId: sessionId)
    }

    func beginNameAgent() {
        guard uhp.allowsMutation, uhp.caps.agentName else { return }
        uhp.nameAgentText = uhp.header?.name ?? ""
        uhp.isNameAgentPresented = true
    }

    func nameOpenAgent() async {
        let name = uhp.nameAgentText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, let workspace = hostUhp() else { return }
        workspace.setNameAgentDraft(text: name)
        workspace.nameAgent()
        uhp.isNameAgentPresented = false
    }

    func beginForkAgent() {
        guard uhp.allowsMutation, uhp.caps.agentFork else { return }
        uhp.forkAgentName = ""
        uhp.isForkAgentPresented = true
    }

    func forkOpenAgent() async {
        guard let workspace = hostUhp() else { return }
        workspace.setForkAgentDraft(text: uhp.forkAgentName)
        workspace.forkAgent()
        uhp.isForkAgentPresented = false
        uhp.forkAgentName = ""
    }

    func claimTask(_ id: String) async {
        hostUhp()?.claimTask(taskId: id)
    }

    func deleteTask(_ id: String) async {
        hostUhp()?.deleteTask(taskId: id)
    }

    func retryTask(_ id: String) async {
        hostUhp()?.retryTask(taskId: id)
    }

    func createHostAutomation(_ draft: AutomationDraft) {
        hostUhp()?.createAutomation(draft: draft)
    }

    func updateHostAutomation(id: String, draft: AutomationDraft) {
        hostUhp()?.updateAutomation(id: id, draft: draft)
    }

    func deleteHostAutomation(_ id: String) {
        hostUhp()?.deleteAutomation(id: id)
    }

    func rebindHostAutomation(id: String, pane: String, terminalId: String?) {
        hostUhp()?.rebindAutomation(id: id, pane: pane, terminalId: terminalId)
    }

    func loadAutomationHistory(_ id: String) {
        hostUhp()?.loadAutomationHistory(id: id, limit: 20)
    }

    func previewHostAutomation(_ trigger: AutomationTrigger) {
        hostUhp()?.previewAutomation(trigger: trigger)
    }

    func clearAutomationPreview() {
        hostUhp()?.clearAutomationPreview()
    }

}
