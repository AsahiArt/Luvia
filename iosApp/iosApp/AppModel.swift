import Foundation
import Observation

@MainActor
@Observable
final class AppModel {
    private let keyStore = DeviceKeyStore()
    private(set) var hosts: [HostViewState] = []
    var selectedHostID: HostViewState.ID?
    var selectedSection: HostSection = .overview
    var isPairingPresented = false

    var selectedHost: HostViewState? {
        hosts.first { $0.id == selectedHostID }
    }

    func select(_ host: HostViewState) {
        selectedHostID = host.id
        selectedSection = .overview
    }

    func addPairedHost(_ host: HostViewState, privateKey: String) {
        hosts.append(host)
        selectedHostID = host.id
        try? keyStore.save(Data(privateKey.utf8), deviceID: host.id.uuidString)
    }
}
