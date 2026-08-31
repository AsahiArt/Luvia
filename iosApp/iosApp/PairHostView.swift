import SwiftUI
import LuviaShared

struct PairHostView: View {
    @Environment(\.dismiss) private var dismiss
    var onPaired: (HostViewState, String) -> Void

    @State private var host = ""
    @State private var port = 22
    @State private var user = ""
    @State private var fingerprint = ""
    @State private var publicKey = ""
    @State private var privateKey = ""
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("Host") {
                    TextField("MagicDNS name or IP", text: $host)
                        .textContentType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("SSH user", text: $user)
                        .textContentType(.username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Stepper("Port \(port)", value: $port, in: 1...65535)
                    TextField("Host key SHA256", text: $fingerprint)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
                Section("Device key") {
                    if publicKey.isEmpty {
                        Button("Generate device key") { generateKey() }
                    } else {
                        Text(publicKey)
                            .font(.system(.footnote, design: .monospaced))
                            .textSelection(.enabled)
                    }
                }
                Section {
                    Label("On the host run `luvia-host pair --name \"iPhone\" --role controller` and paste this public key.", systemImage: "lock.shield")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
                if let errorMessage {
                    Section {
                        Text(errorMessage).foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle("Pair Host")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }
                        .disabled(host.isEmpty || user.isEmpty || publicKey.isEmpty || fingerprint.isEmpty)
                }
            }
            .onAppear { if publicKey.isEmpty { generateKey() } }
        }
    }

    private func generateKey() {
        switch onEnum(of: DeviceKeys.shared.generate()) {
        case .ok(let ok):
            guard let key = ok.value else { return }
            publicKey = key.identity.authorizedKeys
            privateKey = key.privateKeyOpenssh
            errorMessage = nil
        case .err(let err):
            errorMessage = String(describing: err.failure)
        }
    }

    private func save() {
        let state = HostViewState(
            name: host.split(separator: ".").first.map(String.init) ?? host,
            address: host,
            sessionName: nil,
            connection: .offline
        )
        onPaired(state, privateKey)
        dismiss()
    }
}
