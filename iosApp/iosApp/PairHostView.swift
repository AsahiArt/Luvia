import SwiftUI
import UIKit
import LuviaShared

struct PairHostView: View {
    @Bindable var model: AppModel
    @Environment(\.dismiss) private var dismiss

    private enum Step {
        case identity
        case command
        case code
    }

    @State private var step: Step = .identity
    @State private var deviceLabel = UIDevice.current.name
    @State private var role: HostRole = .controller
    @State private var hostAddress = ""
    @State private var sshPort = "22"
    @State private var sshUser = ""
    @State private var draft: PairingDraft?
    @State private var errorMessage: String?
    @State private var isScanning = false
    @State private var showPaste = false
    @State private var pasteCode = ""
    @State private var isCompleting = false
    @State private var didCopy = false
    @State private var connectingAfterPair = false
    @State private var pairedHostID: String?

    var body: some View {
        NavigationStack {
            Group {
                if connectingAfterPair {
                    connectingForm
                } else {
                    switch step {
                    case .identity:
                        identityForm
                    case .command:
                        commandForm
                    case .code:
                        codeForm
                    }
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .safeAreaInset(edge: .top, spacing: 0) {
                if !connectingAfterPair {
                    PairingStepRail(step: stepNumber)
                        .padding(.horizontal, 20)
                        .padding(.bottom, 8)
                }
            }
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Text(title)
                        .font(.system(.headline, design: .serif))
                        .foregroundStyle(DesignTokens.ink)
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        model.isPairingPresented = false
                        dismiss()
                    }
                }
            }
            .onChange(of: model.hosts) { _, _ in
                settleAfterPair()
            }
            .onChange(of: model.hasLiveSession) { _, _ in
                settleAfterPair()
            }
        }
    }

    private var stepNumber: Int {
        switch step {
        case .identity: 1
        case .command: 2
        case .code: 3
        }
    }

    private var title: String {
        if connectingAfterPair { return "Paired" }
        switch step {
        case .identity: return "Add Host"
        case .command: return "Run on Host"
        case .code: return "Scan Pairing Code"
        }
    }

    private var connectingForm: some View {
        VStack(spacing: 16) {
            ProgressView()
            Text("Paired. Connecting…")
                .font(.system(.title2, design: .serif))
                .foregroundStyle(DesignTokens.ink)
            Text("Landing on this Host once the first snapshot arrives.")
                .font(.subheadline)
                .foregroundStyle(DesignTokens.inkMuted)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding()
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
                Text("Observer can watch sessions. Controller can prompt agents, review, tasks, and type in terminals.")
            }
            Section {
                TextField("Host", text: $hostAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                TextField("Port", text: $sshPort)
                    .keyboardType(.numberPad)
                TextField("Username", text: $sshUser)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            } header: {
                Text("Reach this Host")
            } footer: {
                Text("Optional. Overrides addresses in the pairing code. SSH host keys still come from pairing.")
            }
            if let errorMessage {
                Section {
                    Text(errorMessage).foregroundStyle(.red)
                }
            }
            Section {
                Button("Continue") { startPairing() }
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, DesignTokens.Space.s)
                    .luviaGlass(in: Capsule())
                    .disabled(deviceLabel.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    .listRowBackground(Color.clear)
            }
        }
        .scrollContentBackground(.hidden)
        .background(DesignTokens.canvas)
    }

    @ViewBuilder
    private var commandForm: some View {
        if let draft {
            Form {
                Section {
                    Text("Run this command on the Host, then scan the QR it prints.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
                Section {
                    Text(draft.deviceKeyFingerprint)
                        .font(.system(.body, design: .monospaced))
                        .foregroundStyle(DesignTokens.ink)
                        .textSelection(.enabled)
                        .padding(DesignTokens.Space.m)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .luviaGlass(in: RoundedRectangle(cornerRadius: DesignTokens.Radius.m, style: .continuous))
                        .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                        .listRowBackground(Color.clear)
                } header: {
                    Text("Device key")
                } footer: {
                    Text("The full ssh-ed25519 key stays in the copied command.")
                }
                Section {
                    Button {
                        UIPasteboard.general.string = draft.command
                        didCopy = true
                    } label: {
                        Label(
                            didCopy ? "Copied. Paste it in a terminal on the host." : "Copy full command",
                            systemImage: didCopy ? "checkmark" : "doc.on.doc"
                        )
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, DesignTokens.Space.s)
                    }
                    .buttonStyle(.plain)
                    .luviaGlass(in: Capsule())
                    .listRowBackground(Color.clear)
                }
                Section {
                    Button("I ran the command") {
                        errorMessage = nil
                        showPaste = false
                        step = .code
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(DesignTokens.canvas)
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
                    Button("Scan QR code") {
                        #if targetEnvironment(simulator)
                        showPaste = true
                        errorMessage = "Camera access is unavailable. Paste the luvia1: pairing code instead."
                        #else
                        isScanning = true
                        #endif
                    }
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
                        .foregroundStyle(DesignTokens.ink)
                        .padding(8)
                        .background(DesignTokens.surface, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                        .lineLimit(3...8)
                        .accessibilityLabel("luvia1 pairing code")
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
        let result = await model.completePairing(
            draft: draft,
            rawCode: trimmed,
            host: hostAddress,
            port: sshPort,
            user: sshUser
        )
        isCompleting = false
        switch result {
        case .success(let profile):
            pairedHostID = profile.id
            connectingAfterPair = true
            settleAfterPair()
        case .failure(let error):
            errorMessage = error.message
        }
    }

    private func settleAfterPair() {
        guard connectingAfterPair, let id = pairedHostID else { return }
        guard let host = model.hosts.first(where: { $0.id == id }) else { return }
        if host.connection == .live || (model.hasLiveSession && model.selectedHostID == id) {
            finishConnecting()
            return
        }
        if let failure = host.failureMessage, !failure.isEmpty {
            finishConnecting()
        }
    }

    private func finishConnecting() {
        connectingAfterPair = false
        model.isPairingPresented = false
        dismiss()
    }
}

private struct PairingStepRail: View {
    let step: Int

    var body: some View {
        HStack(spacing: 8) {
            ForEach(1...3, id: \.self) { index in
                Capsule()
                    .fill(index <= step ? DesignTokens.accent : DesignTokens.inkMuted.opacity(0.22))
                    .frame(height: 4)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Step \(step) of 3")
    }
}
