import LuviaShared
import SwiftUI

struct PairingView: View {
    @Bindable var model: AppModel
    var onPaired: () -> Void
    var onClose: (() -> Void)?

    @State private var label = UIDevice.current.name
    @State private var role: HostRole = .controller
    @State private var draft: PairingDraft?
    @State private var longCode = ""
    @State private var error: String?
    @State private var busy = false
    @State private var scanning = false
    @State private var copied = false
    @State private var started = Date()

    var body: some View {
        NavigationStack {
            Group {
                if let draft {
                    ritual(draft)
                } else {
                    start
                }
            }
            .navigationTitle("配对电脑")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if let onClose {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("关闭") {
                            onClose()
                        }
                    }
                }
            }
        }
        .sheet(isPresented: $scanning) {
            QRScannerView(
                onCode: { code in
                    longCode = code.trimmingCharacters(in: .whitespacesAndNewlines)
                    scanning = false
                },
                onUnavailable: {
                    error = "相机不可用。"
                    scanning = false
                }
            )
            .ignoresSafeArea()
        }
    }

    private var start: some View {
        Form {
            Section {
                TextField("设备名称", text: $label)
                Picker("角色", selection: $role) {
                    Text("控制者").tag(HostRole.controller)
                    Text("观察者").tag(HostRole.observer)
                }
            } footer: {
                Text("在电脑上运行 luvia-host pair，得到一条 luvia1: 码。")
            }
            if let error {
                Section { Text(error).foregroundStyle(.red) }
            }
            Section {
                Button("开始配对") { begin() }
                    .disabled(label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
    }

    private func ritual(_ draft: PairingDraft) -> some View {
        TimelineView(.periodic(from: started, by: 1)) { context in
            let remaining = max(0, 300 - Int(context.date.timeIntervalSince(started)))
            let decoded = decodedCode
            ScrollView {
                VStack(spacing: 16) {
                    ZStack {
                        PairOrb(progress: CGFloat(remaining) / 300)
                            .frame(height: 260)
                        VStack(spacing: 8) {
                            Text(String(format: "%d:%02d", remaining / 60, remaining % 60))
                                .font(.system(.title2, design: .monospaced).weight(.semibold))
                                .foregroundStyle(Color.blue)
                            Text("电脑终端显示的配对码")
                                .font(.caption)
                                .foregroundStyle(.white.opacity(0.7))
                            shortTiles
                        }
                    }
                    .padding(18)
                    .luviaGlass(in: RoundedRectangle(cornerRadius: 36, style: .continuous), tint: Color.blue)
                    fingerprintRow(
                        draft,
                        matches: decoded?.deviceKeyFingerprint == draft.deviceKeyFingerprint,
                        hostKey: hostKeys(decoded).first
                    )
                    if let decoded {
                        let address = addresses(decoded).first ?? ""
                        HStack {
                            VStack(alignment: .leading) {
                                Text(decoded.hostLabel).font(.headline)
                                Text(address)
                                    .font(.caption.monospaced())
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            if looksLikeTailscale(address) {
                                Text("Tailscale")
                                    .font(.caption.weight(.semibold))
                                    .padding(.horizontal, 10)
                                    .padding(.vertical, 6)
                                    .luviaGlass(in: Capsule())
                            }
                        }
                    }
                    commandRow(draft.command)
                    TextField("luvia1: 码", text: $longCode, axis: .vertical)
                        .textFieldStyle(.roundedBorder)
                        .font(.system(.footnote, design: .monospaced))
                    if remaining == 0 {
                        Text("倒计时结束。仍可用下面的 luvia1: 码配对。")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(16)
            }
            .background(ChatPalette.pairBackground.ignoresSafeArea())
            .safeAreaInset(edge: .bottom) {
                VStack(alignment: .leading, spacing: 8) {
                    if let error {
                        Text(error)
                            .font(.footnote)
                            .foregroundStyle(.red)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    HStack(spacing: 10) {
                        Button("改用扫码") { scanning = true }
                            .buttonStyle(.bordered)
                        Button {
                            if !busy { confirm(draft) }
                        } label: {
                            if busy {
                                HStack(spacing: 8) {
                                    ProgressView().controlSize(.small).tint(.white)
                                    Text("连接中")
                                }
                            } else {
                                Text("确认配对")
                            }
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(Color.blue)
                        .disabled(longCode.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
                .padding()
                .luviaGlass(in: RoundedRectangle(cornerRadius: 28, style: .continuous), tint: Color.blue)
                .padding(.horizontal, 12)
            }
        }
    }

    private var shortTiles: some View {
        TimelineView(.periodic(from: started, by: 0.45)) { context in
            let slot = Int(context.date.timeIntervalSince(started) / 0.45) % 6
            HStack(spacing: 6) {
                ForEach(0..<6, id: \.self) { index in
                    RoundedRectangle(cornerRadius: index % 2 == 0 ? 22 : 12, style: .continuous)
                        .fill(index == slot ? Color.blue : Color.white.opacity(0.08))
                        .frame(width: 42, height: 56)
                        .overlay {
                            if index == slot {
                                Circle()
                                    .fill(Color.white.opacity(0.85))
                                    .frame(width: 8, height: 8)
                            }
                        }
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("电脑终端显示的配对码")
    }

    private func fingerprintRow(_ draft: PairingDraft, matches: Bool, hostKey: String?) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text("核对指纹").font(.caption).foregroundStyle(.secondary)
                Text(draft.deviceKeyFingerprint).font(.system(.footnote, design: .monospaced))
                if let hostKey {
                    Text(hostKey).font(.system(.caption2, design: .monospaced)).foregroundStyle(.secondary)
                }
            }
            Spacer()
            if matches {
                Image(systemName: "checkmark")
                    .padding(8)
                    .background(Color.blue, in: Circle())
                    .foregroundStyle(.white)
            }
        }
        .padding(14)
        .luviaGlass(in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .foregroundStyle(.white)
    }

    private func commandRow(_ command: String) -> some View {
        HStack {
            Text(command)
                .font(.system(.caption2, design: .monospaced))
                .lineLimit(3)
            Spacer()
            Button(copied ? "已复制" : "复制") {
                UIPasteboard.general.string = command
                copied = true
            }
        }
        .padding(12)
        .luviaGlass(in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .foregroundStyle(.white)
    }

    private func hostKeys(_ code: PairingCode?) -> [String] {
        KotlinLists.array(code?.hostKeyFingerprints as Any)
    }

    private func addresses(_ code: PairingCode) -> [String] {
        KotlinLists.array(code.addresses as Any)
    }

    private var decodedCode: PairingCode? {
        switch onEnum(of: PairingCodes.shared.decode(raw: longCode)) {
        case .ok(let ok):
            return ok.value
        case .err(_):
            return nil
        }
    }

    private func begin() {
        error = nil
        switch model.beginPairing(deviceLabel: label, role: role) {
        case .success(let draft):
            self.draft = draft
            started = Date()
        case .failure(let failure):
            error = failure.message
        }
    }

    private func confirm(_ draft: PairingDraft) {
        busy = true
        error = nil
        let code = longCode
        _Concurrency.Task {
            let result = await model.completePairing(draft: draft, rawCode: code)
            await MainActor.run {
                busy = false
                switch result {
                case .success:
                    onPaired()
                case .failure(let failure):
                    error = failure.message
                }
            }
        }
    }
}

private struct PairOrb: View {
    var progress: CGFloat

    var body: some View {
        Canvas { context, size in
            let center = CGPoint(x: size.width / 2, y: size.height / 2)
            let radius = min(size.width, size.height) * 0.34
            for index in 0..<48 {
                let angle = Double(index) * 2.399
                let distance = radius * CGFloat((Double(index) / 48).squareRoot())
                let point = CGPoint(
                    x: center.x + CGFloat(cos(angle)) * distance,
                    y: center.y + CGFloat(sin(angle)) * distance
                )
                let rect = CGRect(x: point.x - 2, y: point.y - 2, width: 4, height: 4)
                context.fill(Path(ellipseIn: rect), with: .color(Color.blue.opacity(0.85)))
            }
            var ring = Path()
            ring.addArc(
                center: center,
                radius: radius + 18,
                startAngle: .degrees(-90),
                endAngle: .degrees(-90 + 360 * Double(progress)),
                clockwise: false
            )
            context.stroke(ring, with: .color(Color.blue), lineWidth: 6)
        }
    }
}

private func looksLikeTailscale(_ address: String) -> Bool {
    let host = address.split(separator: "%").first.map(String.init) ?? address
    let parts = host.split(separator: ".")
    guard parts.count == 4, let a = Int(parts[0]), let b = Int(parts[1]) else { return false }
    return a == 100 && (64...127).contains(b)
}
