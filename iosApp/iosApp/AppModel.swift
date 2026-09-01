import ActivityKit
import Foundation
import Observation
import LuviaShared

@MainActor
@Observable
final class AppModel {
    private nonisolated(unsafe) let manager: HostManager
    @ObservationIgnored private nonisolated(unsafe) var hostsTask: _Concurrency.Task<Void, Never>?
    @ObservationIgnored private nonisolated(unsafe) var terminalTask: _Concurrency.Task<Void, Never>?
    private var terminalControl: TerminalControl?
    private let liveActivity = LiveActivityController()
    private var liveActivityHostID: String?
    private var liveActivityBlockedAgents = 0

    private(set) var hosts: [HostViewState] = []
    var selectedHostID: String?
    var selectedSection: HostSection = .overview
    var isPairingPresented = false
    var terminalText = ""
    var terminalStatus: String?

    var selectedHost: HostViewState? {
        hosts.first { $0.id == selectedHostID }
    }

    init() {
        let store = HostStore(filePath: Self.hostStorePath())
        let vault = DeviceKeyVault(service: "tech.asahiart.luvia.device-keys")
        let scope = HostManagerScope()
        manager = HostManager(store: store, vault: vault, scope: scope)
        let managerRef = manager
        hostsTask = _Concurrency.Task { [weak self] in
            for await runtimes in managerRef.hosts {
                let states = KotlinLists.array(runtimes as Any).map(HostViewState.init)
                await MainActor.run {
                    self?.replaceHosts(states)
                }
            }
        }
    }

    deinit {
        // hosts is a StateFlow and never completes, so the collecting task has to be
        // cancelled explicitly or it keeps the manager alive past deinit.
        hostsTask?.cancel()
        terminalTask?.cancel()
        manager.close()
    }

    func select(_ host: HostViewState) {
        selectedHostID = host.id
        selectedSection = .overview
        stopTerminal()
    }

    func beginPairing(deviceLabel: String, role: HostRole) -> Result<PairingDraft, UserFacingError> {
        switch onEnum(of: manager.beginPairing(deviceLabel: deviceLabel, role: role)) {
        case .ok(let ok):
            guard let draft = ok.value else {
                return .failure(UserFacingError(message: "Could not start pairing."))
            }
            return .success(draft)
        case .err(let err):
            return .failure(UserFacingError(message: FailureText.describe(err.failure)))
        }
    }

    func completePairing(draft: PairingDraft, rawCode: String) async -> Result<HostProfile, UserFacingError> {
        do {
            let outcome = try await manager.completePairing(draft: draft, rawCode: rawCode)
            switch onEnum(of: outcome) {
            case .ok(let ok):
                guard let profile = ok.value else {
                    return .failure(UserFacingError(message: "Pairing did not return a host."))
                }
                selectedHostID = profile.id
                isPairingPresented = false
                return .success(profile)
            case .err(let err):
                return .failure(UserFacingError(message: FailureText.describe(err.failure)))
            }
        } catch {
            return .failure(UserFacingError(message: error.localizedDescription))
        }
    }

    func connect(_ hostId: String) {
        manager.connect(hostId: hostId)
    }

    func disconnect(_ hostId: String) {
        stopTerminal()
        manager.disconnect(hostId: hostId)
    }

    func refresh(_ hostId: String) async {
        do {
            let outcome = try await manager.refresh(hostId: hostId)
            if case .err(let err) = onEnum(of: outcome) {
                terminalStatus = FailureText.describe(err.failure)
            }
        } catch {
            terminalStatus = error.localizedDescription
        }
    }

    func unpair(_ hostId: String) async {
        if selectedHostID == hostId {
            selectedHostID = nil
            selectedSection = .overview
        }
        if liveActivityHostID == hostId {
            await liveActivity.end(nil)
            liveActivityHostID = nil
            liveActivityBlockedAgents = 0
        }
        stopTerminal()
        do {
            try await manager.unpair(hostId: hostId)
        } catch {
            terminalStatus = error.localizedDescription
        }
    }

    func handleSectionChange() {
        if selectedSection == .terminal {
            startTerminal()
        } else {
            stopTerminal()
        }
    }

    func sendTerminal(_ text: String) async {
        guard let terminalControl else { return }
        do {
            let outcome = try await terminalControl.submitText(text: text)
            if case .err(let err) = onEnum(of: outcome) {
                terminalStatus = FailureText.describe(err.failure)
            }
        } catch {
            terminalStatus = error.localizedDescription
        }
    }

    private func replaceHosts(_ states: [HostViewState]) {
        hosts = states
        if let selectedHostID, !states.contains(where: { $0.id == selectedHostID }) {
            self.selectedHostID = nil
        }
        if selectedHostID == nil {
            selectedHostID = states.first?.id
        }
        if selectedSection == .terminal {
            startTerminal()
        }
        syncLiveActivity()
    }

    private func startTerminal() {
        guard selectedSection == .terminal, let host = selectedHost else {
            stopTerminal()
            return
        }
        guard let locator = host.terminalLocator else {
            stopTerminal()
            terminalText = ""
            terminalStatus = "Connect to load a live pane."
            return
        }
        stopTerminal()
        terminalStatus = nil
        let hostId = host.id
        let identity = locator.identity()
        let managerRef = manager
        if host.isController {
            _Concurrency.Task { [weak self] in
                do {
                    let outcome = try await managerRef.openTerminal(hostId: hostId, identity: identity)
                    await MainActor.run {
                        switch onEnum(of: outcome) {
                        case .ok(let ok):
                            self?.terminalControl = ok.value
                        case .err(let err):
                            self?.terminalStatus = FailureText.describe(err.failure)
                        }
                    }
                } catch {
                    await MainActor.run {
                        self?.terminalStatus = error.localizedDescription
                    }
                }
            }
        }
        terminalTask = _Concurrency.Task { [weak self] in
            for await update in managerRef.observeTerminal(hostId: hostId, identity: identity) {
                await MainActor.run {
                    self?.applyTerminal(update)
                }
            }
        }
    }

    private func stopTerminal() {
        terminalTask?.cancel()
        terminalTask = nil
        terminalControl?.close()
        terminalControl = nil
    }

    private func applyTerminal(_ update: TerminalUpdate) {
        switch onEnum(of: update) {
        case .frame(let wrapped):
            terminalText = wrapped.frame.text
            terminalStatus = wrapped.frame.truncated ? "Output truncated." : nil
        case .resyncing(_):
            terminalStatus = "Resyncing…"
        case .failed(let wrapped):
            terminalStatus = FailureText.describe(wrapped.failure)
        }
    }

    private func syncLiveActivity() {
        let host = selectedHost ?? hosts.first { $0.connection == .live || $0.connection == .connecting }
        guard let host else {
            _Concurrency.Task { await liveActivity.end(nil) }
            liveActivityHostID = nil
            liveActivityBlockedAgents = 0
            return
        }
        let state = host.activityState()
        if liveActivityHostID != host.id || !liveActivity.isActive {
            liveActivityHostID = host.id
            liveActivityBlockedAgents = host.blockedAgents
            try? liveActivity.start(
                hostName: host.name,
                sessionName: host.sessionName ?? host.address,
                state: state
            )
            return
        }
        let alert = blockedAgentAlert(for: host)
        liveActivityBlockedAgents = host.blockedAgents
        if host.connection == .stale {
            _Concurrency.Task { await liveActivity.markStale(state) }
        } else {
            _Concurrency.Task { await liveActivity.update(state, alert: alert) }
        }
    }

    /// An agent blocking on input is the only state worth interrupting for. Alerting
    /// on a rising count keeps a steady backlog silent while still announcing each
    /// newly blocked agent. Switching hosts reseeds the baseline instead of alerting,
    /// so adopting an already-blocked host stays quiet.
    private func blockedAgentAlert(for host: HostViewState) -> AlertConfiguration? {
        guard host.blockedAgents > liveActivityBlockedAgents else { return nil }
        let count = host.blockedAgents
        let subject = count == 1 ? "1 agent needs input" : "\(count) agents need input"
        return AlertConfiguration(
            title: "Agent needs you",
            body: LocalizedStringResource(stringLiteral: "\(host.name): \(subject)"),
            sound: .default
        )
    }

    private static func hostStorePath() -> String {
        let fileManager = FileManager.default
        let root = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? fileManager.temporaryDirectory
        let directory = root.appendingPathComponent("Luvia", isDirectory: true)
        try? fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appendingPathComponent("hosts.json").path
    }
}

private final class HostManagerScope: NSObject, Kotlinx_coroutines_coreCoroutineScope {
    let coroutineContext: KotlinCoroutineContext = EmptyKotlinCoroutineContext()
}

private final class EmptyKotlinCoroutineContext: NSObject, KotlinCoroutineContext {
    func fold(initial: Any?, operation: @escaping (Any?, KotlinCoroutineContextElement) -> Any?) -> Any? {
        initial
    }

    func get(key: KotlinCoroutineContextKey) -> KotlinCoroutineContextElement? {
        nil
    }

    func minusKey(key: KotlinCoroutineContextKey) -> KotlinCoroutineContext {
        self
    }

    func plus(context: KotlinCoroutineContext) -> KotlinCoroutineContext {
        context
    }
}

