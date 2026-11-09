import Foundation
import LuviaShared

/// Shared per-host filter. The string is stored in UserDefaults, one key per host.
@MainActor
enum HostFilterBridge {
    private static let memory = DefaultsHostFilterMemory()
    private static var pinnedHostIds: Set<String> = []

    static func stored(for hostId: String?) -> HostSessionFilter {
        guard let hostId else {
            return HostSessionFilter(scope: allOnline(), status: .all)
        }
        return memory.get(hostId: hostId)
    }

    static func remember(_ filter: HostSessionFilter, hostId: String) {
        memory.put(hostId: hostId, filter: filter)
        if cwd(of: filter.scope) != nil {
            pinnedHostIds.insert(hostId)
        }
    }

    static func isPinned(_ hostId: String?) -> Bool {
        guard let hostId else { return false }
        return pinnedHostIds.contains(hostId)
    }

    /// In memory only. Does not write the stored filter.
    static func noteArrived(hostId: String, threads: [AgentThread]) {
        if pinnedHostIds.contains(hostId) { return }
        if HostSessionFilterKt.shouldPinStoredPath(stored: stored(for: hostId), arrivedThreads: threads) {
            pinnedHostIds.insert(hostId)
        }
    }

    static func displayed(
        _ stored: HostSessionFilter,
        threads: [AgentThread],
        hostId: String?
    ) -> HostSessionFilter {
        HostSessionFilterKt.displayedHostSessionFilter(
            stored: stored,
            arrivedThreads: threads,
            pinned: isPinned(hostId)
        )
    }

    static func switched(to hostId: String) -> HostSessionFilter {
        HostSessionFilterKt.applyHostSwitch(memory, hostId: hostId)
    }

    static func restore(_ stored: HostSessionFilter, threads: [AgentThread]) -> HostSessionFilter {
        HostSessionFilterKt.restoreHostSessionFilter(stored: stored, threadsForHost: threads)
    }

    static func includingSelected(_ paths: [SessionPathOption], scope: SessionScope) -> [SessionPathOption] {
        HostSessionFilterKt.withSelectedPath(paths: paths, scope: scope)
    }

    static func homeDirectory(username: String?, paths: [String]) -> String? {
        HostSessionFilterKt.hostHomeDirectory(username: username, paths: paths)
    }

    static func relative(_ cwd: String, home: String?) -> String {
        HostSessionFilterKt.homeRelativePath(cwd: cwd, homeDir: home)
    }

    static func allOnline() -> SessionScope { SessionScope.AllOnline.shared }

    static func currentHost() -> SessionScope { SessionScope.CurrentHost.shared }

    static func path(_ cwd: String) -> SessionScope { SessionScope.Path(cwd: cwd) }

    static func make(scope: SessionScope, status: SessionFilter) -> HostSessionFilter {
        HostSessionFilter(scope: scope, status: status)
    }

    static func cwd(of scope: SessionScope) -> String? {
        if case .path(let path) = onEnum(of: scope) {
            return path.cwd
        }
        return nil
    }

    static func isAllOnline(_ scope: SessionScope) -> Bool {
        if case .allOnline = onEnum(of: scope) { return true }
        return false
    }

    static func isCurrentHost(_ scope: SessionScope) -> Bool {
        if case .currentHost = onEnum(of: scope) { return true }
        return false
    }

    static func sessions(
        threads: [AgentThread],
        hostId: String?,
        scope: SessionScope,
        status: SessionFilter,
        online: Set<String>
    ) -> [AgentThread] {
        filterSessionsByHost(
            threads: threads,
            hostId: hostId,
            filter: make(scope: scope, status: status),
            onlineHostIds: online
        )
    }

    static func paths(threads: [AgentThread], hostId: String?) -> [SessionPathOption] {
        openSessionPaths(threads: threads, hostId: hostId)
    }
}

/// UserDefaults backing for [HostFilterMemory]. Keys include the host id.
private final class DefaultsHostFilterMemory: NSObject, HostFilterMemory {
    func get(hostId: String) -> HostSessionFilter {
        guard let raw = UserDefaults.standard.string(forKey: Self.key(hostId)) else {
            return HostSessionFilter(scope: SessionScope.AllOnline.shared, status: .all)
        }
        return HostSessionFilterKt.decodeHostSessionFilter(raw: raw)
    }

    func put(hostId: String, filter: HostSessionFilter) {
        let encoded = HostSessionFilterKt.encodeHostSessionFilter(filter: filter)
        UserDefaults.standard.set(encoded, forKey: Self.key(hostId))
    }

    private static func key(_ hostId: String) -> String {
        "host-session-filter:\(hostId)"
    }
}
