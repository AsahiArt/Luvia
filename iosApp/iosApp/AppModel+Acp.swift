import SwiftUI
import LuviaShared

extension AppModel {
    var acpState: AcpState? {
        uhp.snapshot?.acp
    }

    var acpLaunchPresented: Binding<Bool> {
        Binding(
            get: { self.acpState?.showLaunch == true },
            set: { self.setShowLaunchAcp($0) }
        )
    }

    func loadAcpAgents() {
        hostUhp()?.loadAcpAgents()
    }

    func setShowLaunchAcp(_ show: Bool) {
        hostUhp()?.setShowLaunchAcp(show: show)
    }

    func setLaunchAcpAgent(_ id: String?) {
        hostUhp()?.setLaunchAcpAgent(id: id)
    }

    func setLaunchAcpCwd(_ cwd: String) {
        hostUhp()?.setLaunchAcpCwd(cwd: cwd)
    }

    func launchAcp() {
        hostUhp()?.launchAcp()
    }

    func setAcpDraft(_ text: String) {
        hostUhp()?.setAcpDraft(text: text)
    }

    func promptAcp() {
        hostUhp()?.promptAcp()
    }

    func answerAcpPermission(_ optionId: String) {
        hostUhp()?.answerAcpPermission(optionId: optionId)
    }

    func cancelAcp() {
        hostUhp()?.cancelAcp()
    }
    func closeAcp() {
        hostUhp()?.closeAcp()
    }

    func hideAcp() {
        hostUhp()?.hideAcp()
    }

    func viewAcp() {
        hostUhp()?.viewAcp()
    }

    func beginLaunchAcp() {
        loadAcpAgents()
        setShowLaunchAcp(true)
    }
}
