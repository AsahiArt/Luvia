import CryptoKit
import Foundation
import LuviaShared
import UIKit
import UserNotifications

enum PushSettings {
    static let enabledKey = "luvia.push.enabled"
    static let tokenKey = "luvia.push.token"
    static let hashesKey = "luvia.push.hostHashes"

    static var apnsEnvironment: String {
        #if DEBUG
        "sandbox"
        #else
        "production"
        #endif
    }
}

extension AppModel {
    func restorePushRegistration() {
        isPushEnabled = UserDefaults.standard.bool(forKey: PushSettings.enabledKey)
        if let token = UserDefaults.standard.string(forKey: PushSettings.tokenKey), !token.isEmpty {
            cachedPushToken = token
            if isPushEnabled {
                applyPushRegistration(token: token)
            }
        }
        if isPushEnabled {
            requestPushAuthorizationAndRegister()
        }
    }

    func setWakeForApprovals(_ enabled: Bool) {
        isPushEnabled = enabled
        UserDefaults.standard.set(enabled, forKey: PushSettings.enabledKey)
        if enabled {
            requestPushAuthorizationAndRegister()
            if let token = cachedPushToken {
                applyPushRegistration(token: token)
                hostUhp()?.setPushEnabled(enabled: true)
            }
        } else {
            hostUhp()?.setPushEnabled(enabled: false)
        }
    }

    func didRegisterPushToken(_ deviceToken: Data) {
        let hex = deviceToken.map { String(format: "%02x", $0) }.joined()
        cachedPushToken = hex
        UserDefaults.standard.set(hex, forKey: PushSettings.tokenKey)
        guard isPushEnabled else { return }
        applyPushRegistration(token: hex)
        hostUhp()?.setPushEnabled(enabled: true)
    }

    func syncPushAfterConnect(wasConnected: Bool, connected: Bool) {
        guard let id = selectedHostID else { return }
        if connected {
            if isPushEnabled, !pushSyncedHostIDs.contains(id) {
                pushSyncedHostIDs.insert(id)
                hostUhp()?.setPushEnabled(enabled: true)
            }
            consumePendingBlockedWake()
        } else if !connected {
            pushSyncedHostIDs.remove(id)
        }
        _ = wasConnected
    }

    func handlePushUserInfo(_ userInfo: [AnyHashable: Any]) {
        guard let payload = Self.pushPayload(userInfo) else { return }
        rememberHostHash(payload.host)
        openHost(hash: payload.host, wake: payload.wake)
    }

    func consumePendingBlockedWake() {
        guard pendingBlockedWake else { return }
        guard hasLiveSession else { return }
        if let blocked = uhp.agents.first(where: \.isBlocked) {
            pendingOpenAgentID = blocked.id
        }
        pendingBlockedWake = false
    }

    private func applyPushRegistration(token: String) {
        let registration = PushRegistration(
            kind: "apns",
            token: token,
            environment: PushSettings.apnsEnvironment
        )
        manager.setPushRegistration(registration: registration)
    }

    private func requestPushAuthorizationAndRegister() {
        let center = UNUserNotificationCenter.current()
        _Concurrency.Task {
            let granted = try? await center.requestAuthorization(options: [.alert, .sound, .badge])
            await MainActor.run {
                if granted == true {
                    UIApplication.shared.registerForRemoteNotifications()
                }
            }
        }
    }

    private func openHost(hash: String, wake: String) {
        guard let host = hostMatching(hash: hash) else { return }
        select(host)
        if host.connection != .live && host.connection != .connecting {
            connect(host.id)
        }
        switch wake {
        case "permission":
            pendingPresentAcp = true
        case "done":
            workspaceSegment = .tasks
            selectedSection = .workspace
        default:
            selectedSection = .agents
            pendingBlockedWake = true
            consumePendingBlockedWake()
        }
    }

    private func hostMatching(hash: String) -> HostViewState? {
        let wanted = hash.lowercased()
        if let mappedID = storedHostHashes()[wanted],
           let host = hosts.first(where: { $0.id == mappedID })
        {
            return host
        }
        if let match = hosts.first(where: { Self.candidateHashes(for: $0).contains(wanted) }) {
            storeHostHash(wanted, hostID: match.id)
            return match
        }
        let live = hosts.filter { $0.connection == .live }
        if live.count == 1 { return live[0] }
        if hosts.count == 1 { return hosts[0] }
        return selectedHost
    }

    private func rememberHostHash(_ hash: String) {
        let live = hosts.filter { $0.connection == .live }
        if live.count == 1 {
            storeHostHash(hash.lowercased(), hostID: live[0].id)
        }
    }

    private func storedHostHashes() -> [String: String] {
        UserDefaults.standard.dictionary(forKey: PushSettings.hashesKey) as? [String: String] ?? [:]
    }

    private func storeHostHash(_ hash: String, hostID: String) {
        var map = storedHostHashes()
        map[hash] = hostID
        UserDefaults.standard.set(map, forKey: PushSettings.hashesKey)
    }

    private static func candidateHashes(for host: HostViewState) -> Set<String> {
        var names = Set<String>()
        names.insert(host.name)
        names.insert(host.address)
        if let sessionName = host.sessionName { names.insert(sessionName) }
        for address in host.addresses {
            names.insert(address)
            names.formUnion(hostnames(from: address))
        }
        names.formUnion(hostnames(from: host.address))
        return Set(names.filter { !$0.isEmpty }.map(hostHash))
    }

    private static func hostnames(from address: String) -> [String] {
        var value = address
        if let at = value.lastIndex(of: "@") {
            value = String(value[value.index(after: at)...])
        }
        if value.hasPrefix("["), let end = value.firstIndex(of: "]") {
            value = String(value[value.index(after: value.startIndex)..<end])
            return [value]
        }
        if let colon = value.lastIndex(of: ":"),
           value[value.index(after: colon)...].allSatisfy(\.isNumber)
        {
            value = String(value[..<colon])
        }
        return [value]
    }

    static func hostHash(_ hostname: String) -> String {
        let digest = SHA256.hash(data: Data(hostname.utf8))
        return digest.prefix(4).map { String(format: "%02x", $0) }.joined()
    }

    static func pushPayload(_ userInfo: [AnyHashable: Any]) -> (host: String, wake: String)? {
        func string(_ value: Any?) -> String? {
            if let value = value as? String, !value.isEmpty { return value }
            if let value = value as? NSNumber { return value.stringValue }
            return nil
        }
        if let nested = userInfo["luvia"] as? [AnyHashable: Any],
           let host = string(nested["host"]),
           let wake = string(nested["wake"])
        {
            return (host, wake)
        }
        if let host = string(userInfo["host"]), let wake = string(userInfo["wake"]) {
            return (host, wake)
        }
        return nil
    }
}
