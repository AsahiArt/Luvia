import SwiftUI
import UIKit
import LuviaShared

struct PairHostView: View {
    var model: AppModel
    @Environment(\.dismiss) private var dismiss

    private enum Step {
        case identity
        case command
        case code
    }

    @State private var step: Step = .identity
    @State private var deviceLabel = UIDevice.current.name
    @State private var role: HostRole = .controller
    @State private var draft: PairingDraft?
    @State private var errorMessage: String?
    @State private var isScanning = false
    @State private var showPaste = false
    @State private var pasteCode = ""
    @State private var isCompleting = false
    @State private var didCopy = false

    var body: some View {
        NavigationStack {
            Group {
                switch step {
                case .identity:
                    identityForm
                case .command:
                    commandForm
                case .code:
                    codeForm
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }

    private var title: String {
        switch step {
        case .identity: "Add Host"
        case .command: "Run on Host"
        case .code: "Scan Pairing Code"
        }
    }

    private var identityForm: some View {
        Form {
            Section {
                TextField("Device label", text: $deviceLabel)
                    .textInputAutocapitalization(.words)
                Picker("Role", selection: $role) {
                    Text("Observer").tag(HostRole.observer)
                    Text("Controller").tag(HostRole.controller)
                }
                .pickerStyle(.segmented)
            } footer: {
                Text("Observer can watch sessions. Controller can type in terminals.")
            }
            if let errorMessage {
                Section {
                    Text(errorMessage).foregroundStyle(.red)
                }
            }
            Section {
                Button("Continue") { startPairing() }
                    .disabled(deviceLabel.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
    }

    @ViewBuilder
    private var commandForm: some View {
        if let draft {
            Form {
                Section {
                    Text("Run this command on the host machine, then scan the QR it prints.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
                Section("Command") {
                    Text(draft.command)
                        .font(.system(.footnote, design: .monospaced))
                        .textSelection(.enabled)
                    Button {
                        UIPasteboard.general.string = draft.command
                        didCopy = true
                    } label: {
                        Label(didCopy ? "Copied" : "Copy command", systemImage: didCopy ? "checkmark" : "doc.on.doc")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                }
                Section("Public key") {
                    Text(draft.authorizedKeysLine)
                        .font(.system(.caption, design: .monospaced))
                        .textSelection(.enabled)
                    Text(draft.deviceKeyFingerprint)
                        .font(.system(.caption, design: .monospaced))
                        .foregroundStyle(.secondary)
                        .textSelection(.enabled)
                }
                Section {
                    Button("I ran the command") {
                        errorMessage = nil
                        showPaste = false
                        step = .code
                    }
                }
            }
        }
    }

    private var codeForm: some View {
        Form {
            Section {
                Text("Scan the QR printed by luvia-host, or paste the luvia1: line.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            if !showPaste {
                Section {
                    Button("Scan QR code") { isScanning = true }
                        .buttonStyle(.borderedProminent)
                    Button("Paste code instead") {
                        showPaste = true
                    }
                }
            } else {
                Section("Pairing code") {
                    TextField("luvia1:…", text: $pasteCode, axis: .vertical)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.system(.footnote, design: .monospaced))
                    Button("Pair") {
                        _Concurrency.Task { await submit(pasteCode) }
                    }
                    .disabled(pasteCode.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isCompleting)
                }
            }
            if let errorMessage {
                Section {
                    Text(errorMessage).foregroundStyle(.red)
                    Button("Scan again") { isScanning = true }
                }
            }
            if isCompleting {
                Section {
                    ProgressView("Pairing…")
                }
            }
        }
        .fullScreenCover(isPresented: $isScanning) {
            NavigationStack {
                QRScannerView(
                    onCode: { code in
                        isScanning = false
                        _Concurrency.Task { await submit(code) }
                    },
                    onUnavailable: {
                        isScanning = false
                        showPaste = true
                        errorMessage = "Camera access is unavailable. Paste the luvia1: pairing code instead."
                    }
                )
                .ignoresSafeArea()
                .navigationTitle("Scan QR")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Close") { isScanning = false }
                    }
                    ToolbarItem(placement: .bottomBar) {
                        Button("Paste code instead") {
                            isScanning = false
                            showPaste = true
                        }
                    }
                }
            }
        }
    }

    private func startPairing() {
        let label = deviceLabel.trimmingCharacters(in: .whitespacesAndNewlines)
        switch model.beginPairing(deviceLabel: label, role: role) {
        case .success(let next):
            draft = next
            didCopy = false
            errorMessage = nil
            step = .command
        case .failure(let error):
            errorMessage = error.message
        }
    }

    private func submit(_ raw: String) async {
        guard let draft else { return }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        isCompleting = true
        errorMessage = nil
        let result = await model.completePairing(draft: draft, rawCode: trimmed)
        isCompleting = false
        switch result {
        case .success:
            dismiss()
        case .failure(let error):
            errorMessage = error.message
        }
    }
}
