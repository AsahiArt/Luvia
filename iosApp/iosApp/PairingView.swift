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
    @State private var shortCode = ""
    @State private var shortHost = ""
    @State private var error: String?
    @State private var busy = false
    @State private var scanning = false
    @State private var copied = false
    @State private var started = Date()
    @FocusState private var codeFocused: Bool
    @FocusState private var shortFocused: Bool

    var body: some View {
        NavigationStack {
            Group {
                if let draft {
                    ritual(draft)
                } else {
                    start
                }
            }
            .navigationTitle(draft == nil ? "配对电脑" : "")
            .navigationBarTitleDisplayMode(.inline)
            .modifier(PairStageBar(ritual: draft != nil))
            .toolbar {
                if let onClose {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("关闭") {
                            onClose()
                        }
                    }
                }
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("完成") { codeFocused = false }
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
                Text("在电脑上运行本页复制的命令。终端会显示六位短码，也可以扫或粘贴 luvia1: 码。")
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
            let hostName = decoded?.hostLabel ?? ""
            let address = decoded.map { addresses($0).first ?? "" } ?? ""
            let title = hostName.isEmpty ? "配对" : "与 \(hostName) 配对"
            ScrollView {
                VStack(spacing: 0) {
                    PairField(
                        progress: CGFloat(remaining) / 300,
                        timer: String(format: "%d:%02d", remaining / 60, remaining % 60)
                    )
                    .frame(height: 280)
                    .onTapGesture { codeFocused = false }
                    Text(title)
                        .font(.system(size: 28, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.top, 4)
                        .onTapGesture { codeFocused = false }
                    Text("电脑终端显示的配对码")
                        .font(.subheadline)
                        .foregroundStyle(.white.opacity(0.6))
                        .padding(.top, 6)
                    shortTiles
                        .padding(.top, 20)
                    TextField("电脑地址，例如 192.168.1.4:8741", text: $shortHost)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .textFieldStyle(.plain)
                        .font(.system(.footnote, design: .monospaced))
                        .foregroundStyle(.white)
                        .padding(12)
                        .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .padding(.top, 12)
                    Text("六位短码约 90 秒有效，用过即废。也可以粘贴或扫描 luvia1: 码。")
                        .font(.footnote)
                        .foregroundStyle(.white.opacity(0.6))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.top, 8)
                    fingerprintRow(
                        draft,
                        matches: decoded?.deviceKeyFingerprint == draft.deviceKeyFingerprint,
                        tailscale: looksLikeTailscale(address)
                    )
                    .padding(.top, 16)
                    commandRow(draft.command)
                        .padding(.top, 10)
                    TextField("粘贴 luvia1: 码", text: $longCode)
                        .focused($codeFocused)
                        .submitLabel(.done)
                        .onSubmit { codeFocused = false }
                        .textFieldStyle(.plain)
                        .font(.system(.footnote, design: .monospaced))
                        .foregroundStyle(.white)
                        .padding(12)
                        .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 16, style: .continuous)
                                .stroke(Color.white.opacity(0.16), lineWidth: 1)
                        )
                        .padding(.top, 10)
                    if remaining == 0 {
                        Text("倒计时结束。仍可用粘贴的 luvia1: 码配对。")
                            .font(.footnote)
                            .foregroundStyle(.white.opacity(0.6))
                            .padding(.top, 8)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 12)
            }
            .scrollDismissesKeyboard(.immediately)
            .background(ChatPalette.pairBackground.ignoresSafeArea())
            .safeAreaInset(edge: .bottom) {
                VStack(spacing: 10) {
                    if let error {
                        Text(error)
                            .font(.subheadline)
                            .foregroundStyle(.red)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Button {
                        if !busy { confirm(draft) }
                    } label: {
                        Group {
                            if busy {
                                HStack(spacing: 8) {
                                    ProgressView().controlSize(.small).tint(.white)
                                    Text("连接中")
                                }
                            } else {
                                Text("确认配对")
                            }
                        }
                        .font(.body.weight(.semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Color.blue)
                    .disabled(pairingMaterial.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    Button {
                        scanning = true
                    } label: {
                        Label("改用扫码", systemImage: "qrcode.viewfinder")
                            .font(.body.weight(.medium))
                            .foregroundStyle(.white)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                    }
                    .luviaGlass(in: Capsule())
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 10)
                .frame(maxWidth: .infinity)
                .background(ChatPalette.pairBackground)
            }
        }
    }

    private var shortTiles: some View {
        ZStack {
            HStack(spacing: 6) {
                ForEach(0..<6, id: \.self) { index in
                    let filled = index < shortCode.count
                    let digit = filled ? shortCode[shortCode.index(shortCode.startIndex, offsetBy: index)] : nil
                    RoundedRectangle(cornerRadius: index % 2 == 0 ? 22 : 12, style: .continuous)
                        .fill(filled || index == min(shortCode.count, 5) ? Color.blue : Color.white.opacity(0.08))
                        .frame(width: 42, height: 56)
                        .overlay {
                            if let digit {
                                Text(String(digit))
                                    .font(.system(size: 28, weight: .semibold, design: .monospaced))
                                    .foregroundStyle(.white)
                            }
                        }
                }
            }
            .allowsHitTesting(false)
            TextField("", text: $shortCode)
                .keyboardType(.numberPad)
                .focused($shortFocused)
                .foregroundStyle(.clear)
                .tint(.clear)
                .frame(height: 56)
                .onChange(of: shortCode) { _, value in
                    let digits = String(value.filter(\.isNumber).prefix(6))
                    if digits != value { shortCode = digits }
                }
        }
        .contentShape(Rectangle())
        .onTapGesture { shortFocused = true }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(shortCode.isEmpty ? "输入电脑上的六位短码" : "已输入短码 \(shortCode)")
    }

    private var pairingMaterial: String {
        let pasted = longCode.trimmingCharacters(in: .whitespacesAndNewlines)
        if PairingCodes.shared.looksLikeCode(raw: pasted) {
            return pasted
        }
        if shortCode.count == 6 {
            return shortCode
        }
        if ShortCodeKt.looksLikeShortCode(raw: pasted) {
            return pasted
        }
        return pasted
    }

    private func fingerprintRow(_ draft: PairingDraft, matches: Bool, tailscale: Bool) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "checkmark.shield.fill")
                .foregroundStyle(Color.green)
            VStack(alignment: .leading, spacing: 2) {
                Text(matches ? "指纹 · 与电脑一致" : "核对指纹")
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.6))
                Text(PairingKt.shortFingerprint(raw: draft.deviceKeyFingerprint))
                    .font(.system(.subheadline, design: .monospaced).weight(.semibold))
            }
            Spacer()
            if tailscale {
                Text("Tailscale")
                    .font(.caption2.monospaced())
                    .foregroundStyle(.white.opacity(0.55))
                    .multilineTextAlignment(.trailing)
            }
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
                .lineLimit(1)
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
        let code = pairingMaterial
        let host = ShortCodeKt.looksLikeShortCode(raw: code) ? shortHost : ""
        _Concurrency.Task {
            let result = await model.completePairing(draft: draft, rawCode: code, shortHost: host)
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

private struct PairStageBar: ViewModifier {
    var ritual: Bool

    func body(content: Content) -> some View {
        if ritual {
            content
                .toolbarColorScheme(.dark, for: .navigationBar)
                .toolbarBackground(ChatPalette.pairBackground, for: .navigationBar)
                .toolbarBackground(.visible, for: .navigationBar)
        } else {
            content
        }
    }
}

private struct PairField: View {
    var progress: CGFloat
    var timer: String

    var body: some View {
        ZStack {
            Canvas { context, size in
                let center = CGPoint(x: size.width / 2, y: size.height / 2)
                let radius = min(size.width, size.height) * 0.32
                let glow = CGRect(
                    x: center.x - radius * 1.7,
                    y: center.y - radius * 1.7,
                    width: radius * 3.4,
                    height: radius * 3.4
                )
                context.fill(Path(ellipseIn: glow), with: .color(Color.blue.opacity(0.18)))
                for index in 0..<96 {
                    let angle = Double(index) * 2.399
                    let distance = radius * 1.45 * CGFloat((Double(index) / 96).squareRoot())
                    let point = CGPoint(
                        x: center.x + CGFloat(cos(angle)) * distance,
                        y: center.y + CGFloat(sin(angle)) * distance * 0.92
                    )
                    let dot: CGFloat = index % 11 == 0 ? 3.2 : 1.7
                    let rect = CGRect(x: point.x - dot / 2, y: point.y - dot / 2, width: dot, height: dot)
                    let alpha = 0.28 + 0.55 * (1 - Double(index) / 96)
                    context.fill(
                        Path(ellipseIn: rect),
                        with: .color(Color(red: 0.39, green: 0.71, blue: 1).opacity(alpha))
                    )
                }
                let ringRadius = radius + 22
                var track = Path()
                track.addEllipse(in: CGRect(
                    x: center.x - ringRadius,
                    y: center.y - ringRadius,
                    width: ringRadius * 2,
                    height: ringRadius * 2
                ))
                context.stroke(track, with: .color(Color.white.opacity(0.12)), lineWidth: 4)
                var ring = Path()
                ring.addArc(
                    center: center,
                    radius: ringRadius,
                    startAngle: .degrees(-90),
                    endAngle: .degrees(-90 + 360 * Double(min(max(progress, 0), 1))),
                    clockwise: false
                )
                context.stroke(
                    ring,
                    with: .color(Color(red: 0.04, green: 0.52, blue: 1)),
                    style: StrokeStyle(lineWidth: 4, lineCap: .round)
                )
            }
            VStack(spacing: 4) {
                Image(systemName: "laptopcomputer")
                    .font(.system(size: 34))
                Text(timer)
                    .font(.system(size: 13, weight: .semibold, design: .monospaced))
                    .foregroundStyle(Color(red: 0.60, green: 0.80, blue: 1))
            }
            .frame(width: 112, height: 112)
            .luviaGlass(in: Circle(), tint: Color.blue)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("倒计时 \(timer)")
    }
}

private func looksLikeTailscale(_ address: String) -> Bool {
    let host = address.split(separator: "%").first.map(String.init) ?? address
    let parts = host.split(separator: ".")
    guard parts.count == 4, let a = Int(parts[0]), let b = Int(parts[1]) else { return false }
    return a == 100 && (64...127).contains(b)
}
