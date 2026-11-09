import LuviaShared

/// Swift-side calls for the shared per-host filter. Memory is process-local.
@MainActor
enum HostFilterBridge {
    private static let memory = InMemoryHostFilterMemory()

    static func stored(for hostId: String?) -> HostSessionFilter {
        guard let hostId else {
            return HostSessionFilter(scope: allOnline(), status: .all)
        }
        return memory.get(hostId: hostId)
    }

    static func remember(_ filter: HostSessionFilter, hostId: String) {
        memory.put(hostId: hostId, filter: filter)
    }

    static func switched(to hostId: String) -> HostSessionFilter {
        HostSessionFilterKt.applyHostSwitch(memory, hostId: hostId)
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

enum MachinePath {
    /// Display only. Filtering still uses the original cwd.
    static func display(_ cwd: String) -> String {
        let trimmed = cwd.trimmingCharacters(in: .whitespacesAndNewlines)
        let parts = trimmed.split(separator: "/").map(String.init)
        guard parts.count >= 3, parts[0] == "Users" || parts[0] == "home" else {
            return trimmed.isEmpty ? cwd : trimmed
        }
        let rest = parts.dropFirst(2).joined(separator: "/")
        return rest.isEmpty ? "~" : "~/\(rest)"
    }
}
