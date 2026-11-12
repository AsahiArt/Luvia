import LuviaShared
import SwiftUI
import UIKit

struct PairingView: View {
    @Bindable var model: AppModel
    var onPaired: () -> Void
    var onClose: (() -> Void)?

    @State private var label = PairingIdentity.deviceLabel
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
    @State private var now = Date()
    @State private var showsSetup = false
    @State private var issuedLabel = ""
    @State private var issuedRole: HostRole = .controller
    @FocusState private var focus: PairFocus?

    private var remaining: Int {
        max(0, 300 - Int(now.timeIntervalSince(started)))
    }

    var body: some View {
        NavigationStack {
            stage
                .toolbar(.hidden, for: .navigationBar)
                .toolbar {
                    ToolbarItemGroup(placement: .keyboard) {
                        Spacer()
                        Button("完成") { dismissKeyboard() }
                    }
                }
        }
        .preferredColorScheme(.dark)
        .sheet(isPresented: $scanning) {
            QRScannerView(
                onCode: { code in
                    longCode = code.trimmingCharacters(in: .whitespacesAndNewlines)
                    scanning = false
                },
                onUnavailable: {
                    error = "相机不可用。"
                    scanning = false
                },
                onCancel: {
                    scanning = false
                }
            )
        }
        .onAppear {
            if draft == nil { begin() }
        }
        .onChange(of: shortCode) { _, _ in error = nil }
        .onChange(of: shortHost) { _, _ in error = nil }
        .onChange(of: longCode) { _, _ in error = nil }
    }

    private var stage: some View {
        ZStack(alignment: .top) {
            stageBackground
            GeometryReader { geo in
                let visible = max(geo.size.height - 150, 320)
                ScrollViewReader { proxy in
                    ScrollView {
                        scrollContent(
                            hero: min(268, max(188, visible * 0.34)),
                            width: geo.size.width,
                            viewport: visible
                        )
                    }
                    .scrollDismissesKeyboard(.immediately)
                    .onChange(of: error) { _, newValue in
                        guard newValue != nil else { return }
                        DispatchQueue.main.async {
                            withAnimation {
                                proxy.scrollTo(pasteFieldID, anchor: .bottom)
                            }
                        }
                    }
                }
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                actionBar
            }
            if showsSetup {
                setupLayer
            }
            chrome
            TimelineView(.periodic(from: started, by: 1)) { context in
                Color.clear
                    .onAppear { tick(context.date) }
                    .onChange(of: context.date) { _, date in tick(date) }
            }
            .frame(width: 0, height: 0)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
        }
    }

    private var stageBackground: some View {
        ZStack {
            PairColor.stage
            RadialGradient(
                colors: [Color(hex: "0B1A33"), PairColor.stage],
                center: UnitPoint(x: 0.5, y: 0.28),
                startRadius: 10,
                endRadius: 560
            )
        }
        .ignoresSafeArea()
    }

    private func scrollContent(hero: CGFloat, width: CGFloat, viewport: CGFloat) -> some View {
        VStack(spacing: 0) {
            PairField(
                progress: CGFloat(remaining) / 300,
                timer: clock(remaining)
            )
            .frame(height: hero)
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
            .onTapGesture { dismissKeyboard() }
            VStack(spacing: 0) {
                Text(pairTitle)
                    .font(.system(size: 28, weight: .bold))
                    .foregroundStyle(.white)
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                    .minimumScaleFactor(0.7)
                    .frame(maxWidth: .infinity)
                    .onTapGesture { dismissKeyboard() }
                Text("输入电脑终端里显示的配对码")
                    .font(.system(size: 15))
                    .foregroundStyle(PairColor.label2)
                    .padding(.top, 6)
                    .onTapGesture { dismissKeyboard() }
                shortTiles(width: width)
                    .padding(.top, 20)
                fingerprintRow
                    .padding(.top, 16)
                if let draft {
                    commandRow(draft.command)
                        .padding(.top, 12)
                    Text("在电脑上运行这条命令。终端会显示六位短码和地址，也可以扫或粘贴 luvia2: 码。")
                        .font(.system(size: 13))
                        .foregroundStyle(PairColor.label2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 8)
                        .onTapGesture { dismissKeyboard() }
                }
                darkField("电脑地址 · 短码旁边", text: $shortHost, focus: .host)
                    .padding(.top, 12)
                VStack(alignment: .leading, spacing: 4) {
                    darkField("粘贴 luvia2: 码", text: $longCode, focus: .code)
                    if let error {
                        Text(error)
                            .font(.system(size: 13))
                            .foregroundStyle(Color(hex: "FF3B30"))
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .padding(.top, 8)
                .id(pasteFieldID)
                if remaining == 0 {
                    Text("倒计时结束。仍可用粘贴的 luvia2: 码配对。")
                        .font(.system(size: 13))
                        .foregroundStyle(PairColor.label2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.top, 8)
                        .onTapGesture { dismissKeyboard() }
                }
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 12)
            Spacer(minLength: 24)
                .frame(maxWidth: .infinity)
                .contentShape(Rectangle())
                .onTapGesture { dismissKeyboard() }
        }
        .frame(maxWidth: .infinity, minHeight: viewport, alignment: .top)
    }

    private var pairTitle: String {
        let host = decodedCode?.name.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return host.isEmpty ? "与电脑配对" : "与 \(host) 配对"
    }

    private var chrome: some View {
        HStack {
            if let onClose {
                chromeButton("chevron.left", label: "关闭") {
                    dismissKeyboard()
                    onClose()
                }
            } else {
                Color.clear.frame(width: 44, height: 44)
            }
            Spacer()
            helpButton
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
    }

    private var helpButton: some View {
        let button = chromeButton("questionmark", label: "设备名称和角色") {
            dismissKeyboard()
            if showsSetup {
                showsSetup = false
                begin()
            } else {
                showsSetup = true
            }
        }
        return Group {
            if showsSetup {
                button.accessibilityAddTraits(.isSelected)
            } else {
                button
            }
        }
    }

    private var setupLayer: some View {
        VStack(spacing: 0) {
            setupCard
                .padding(.horizontal, 16)
                .padding(.top, 60)
            Spacer(minLength: 0)
                .contentShape(Rectangle())
                .onTapGesture {
                    showsSetup = false
                    dismissKeyboard()
                    begin()
                }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }

    private var setupCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("这台手机")
                .font(.system(size: 13))
                .foregroundStyle(PairColor.label2)
            TextField("设备名称", text: $label)
                .focused($focus, equals: .label)
                .submitLabel(.done)
                .onSubmit { begin() }
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .textFieldStyle(.plain)
                .font(.system(size: 17))
                .foregroundStyle(.white)
                .padding(.horizontal, 14)
                .frame(height: 44)
                .pairGlass(
                    in: RoundedRectangle(cornerRadius: 16, style: .continuous),
                    fill: Color(red: 20 / 255, green: 24 / 255, blue: 32 / 255).opacity(0.72)
                )
            HStack(spacing: 8) {
                roleChip("控制者", role: .controller)
                roleChip("观察者", role: .observer)
            }
            Text("名称和角色会写进配对命令。改完后，重新复制命令到电脑。")
                .font(.system(size: 13))
                .foregroundStyle(PairColor.label2)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(16)
        .pairGlass(
            in: RoundedRectangle(cornerRadius: 22, style: .continuous),
            fill: Color(red: 12 / 255, green: 16 / 255, blue: 28 / 255).opacity(0.72)
        )
    }

    private func roleChip(_ title: String, role value: HostRole) -> some View {
        let selected = role == value
        return Button {
            dismissKeyboard()
            begin(role: value)
        } label: {
            Text(title)
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .frame(height: 36)
                .background(
                    Capsule().fill(selected ? PairColor.blue.opacity(0.9) : Color.white.opacity(0.08))
                )
                .overlay {
                    Capsule().stroke(Color.white.opacity(selected ? 0.35 : 0.14), lineWidth: 0.6)
                }
        }
        .buttonStyle(.plain)
    }

    private var actionBar: some View {
        VStack(spacing: 10) {
            Button(action: confirmTapped) {
                ZStack {
                    if busy {
                        HStack(spacing: 8) {
                            ProgressView().controlSize(.small).tint(.white)
                            Text("连接中")
                        }
                    } else {
                        Text("确认配对")
                    }
                }
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .frame(height: 54)
                .background {
                    ZStack {
                        Capsule().fill(.ultraThinMaterial)
                        Capsule().fill(PairColor.blue.opacity(0.88))
                    }
                }
                .overlay {
                    Capsule().stroke(
                        LinearGradient(
                            colors: [Color.white.opacity(0.5), Color.white.opacity(0.08)],
                            startPoint: .top,
                            endPoint: .bottom
                        ),
                        lineWidth: 0.8
                    )
                }
                .shadow(color: PairColor.blue.opacity(canSubmit ? 0.45 : 0.22), radius: 18, y: 8)
                .opacity(canSubmit || busy ? 1 : 0.78)
            }
            .buttonStyle(.plain)
            .accessibilityHint(canSubmit ? "用当前配对码连接" : "先输入六位短码，或粘贴 luvia2: 码")
            Button {
                dismissKeyboard()
                scanning = true
            } label: {
                Label("改用扫码", systemImage: "qrcode.viewfinder")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 50)
                    .pairGlass(in: Capsule())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 20)
        .padding(.top, 8)
        .padding(.bottom, 16)
    }

    private var pasteFieldID: String { "luvia-paste-field" }

    private func shortTiles(width: CGFloat) -> some View {
        let tileWidth = min(46, max(36, (width - 40 - 18 - 24) / 6))
        return ZStack {
            HStack(spacing: 18) {
                tileGroup(0..<3, tileWidth: tileWidth)
                tileGroup(3..<6, tileWidth: tileWidth)
            }
            .allowsHitTesting(false)
            TextField("", text: $shortCode)
                .focused($focus, equals: .short)
                .keyboardType(.numberPad)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .textFieldStyle(.plain)
                .foregroundStyle(.clear)
                .tint(.clear)
                .frame(height: 62)
                .onChange(of: shortCode) { old, value in
                    let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
                    if PairingCodes.shared.looksLikeCode(raw: trimmed) {
                        if longCode != trimmed { longCode = trimmed }
                        if !shortCode.isEmpty { shortCode = "" }
                        return
                    }
                    let base = PairingManualEntryKt.applyShortCodeInput(current: "", proposed: old)
                    let next = PairingManualEntryKt.applyShortCodeInput(current: base, proposed: trimmed)
                    if shortCode != next { shortCode = next }
                }
        }
        .contentShape(Rectangle())
        .onTapGesture { focus = .short }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(shortCode.isEmpty ? "输入电脑上的六位短码" : "已输入短码 \(shortCode)")
    }

    private func tileGroup(_ range: Range<Int>, tileWidth: CGFloat) -> some View {
        HStack(spacing: 6) {
            ForEach(range, id: \.self) { index in
                codeTile(index, width: tileWidth)
            }
        }
    }

    private func codeTile(_ index: Int, width: CGFloat) -> some View {
        let filled = index < shortCode.count
        let digit = filled ? shortCode[shortCode.index(shortCode.startIndex, offsetBy: index)] : nil
        let glowing = filled && index == shortCode.count - 1
        let active = focus == .short && !filled && index == min(shortCode.count, 5)
        return Text(digit.map(String.init) ?? "")
            .font(.system(size: width >= 44 ? 30 : 24, weight: .semibold, design: .monospaced))
            .foregroundStyle(.white)
            .frame(width: width, height: 62)
            .background {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(
                        glowing
                            ? PairColor.blue.opacity(0.5)
                            : Color(red: 58 / 255, green: 58 / 255, blue: 64 / 255).opacity(0.78)
                    )
            }
            .background {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(.ultraThinMaterial)
            }
            .overlay {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(
                        glowing ? Color(red: 120 / 255, green: 190 / 255, blue: 1).opacity(0.7) : Color.white.opacity(0.16),
                        lineWidth: glowing ? 1 : 0.6
                    )
            }
            .overlay {
                if active {
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .stroke(PairColor.blue.opacity(0.85), lineWidth: 1.4)
                }
            }
            .shadow(color: glowing ? PairColor.blue.opacity(0.55) : .clear, radius: glowing ? 16 : 0)
    }

    private var fingerprintRow: some View {
        let fingerprint = draft.map { PairingKt.shortFingerprint(raw: $0.deviceKeyFingerprint) } ?? "···· ···· ····"
        let matches = decodedCode?.deviceKey == draft?.deviceKeyFingerprint && decodedCode != nil
        return HStack(spacing: 10) {
            Image(systemName: "checkmark.shield.fill")
                .font(.system(size: 20))
                .foregroundStyle(PairColor.shield)
            VStack(alignment: .leading, spacing: 2) {
                Text(matches ? "指纹 · 与电脑一致" : "核对指纹")
                    .font(.system(size: 12))
                    .foregroundStyle(PairColor.label2)
                Text(fingerprint)
                    .font(.system(size: 15, weight: .semibold, design: .monospaced))
                    .tracking(1)
                    .foregroundStyle(.white)
            }
            Spacer(minLength: 8)
            if showsTailscale {
                Text("Tailscale")
                    .font(.system(size: 11, weight: .medium, design: .monospaced))
                    .foregroundStyle(Color.white.opacity(0.5))
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 11)
        .pairGlass(in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .onTapGesture { dismissKeyboard() }
    }

    private var showsTailscale: Bool {
        if looksLikeTailscale(shortHost) { return true }
        guard let code = decodedCode else { return false }
        return addresses(code).contains(where: looksLikeTailscale)
    }

    private func commandRow(_ command: String) -> some View {
        HStack(spacing: 10) {
            Text(command)
                .font(.system(size: 11, design: .monospaced))
                .lineLimit(1)
                .truncationMode(.middle)
            Spacer(minLength: 8)
            Button(copied ? "已复制" : "复制") {
                UIPasteboard.general.string = command
                copied = true
            }
            .buttonStyle(.plain)
            .font(.system(size: 15, weight: .semibold))
            .foregroundStyle(PairColor.timer)
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 14)
        .frame(minHeight: 48)
        .pairGlass(in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }

    private func darkField(_ placeholder: String, text: Binding<String>, focus field: PairFocus) -> some View {
        TextField(placeholder, text: text)
            .focused($focus, equals: field)
            .submitLabel(.done)
            .onSubmit { dismissKeyboard() }
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .textFieldStyle(.plain)
            .font(.system(size: 13, design: .monospaced))
            .foregroundStyle(.white)
            .padding(.horizontal, 14)
            .frame(height: 44)
            .pairGlass(
                in: RoundedRectangle(cornerRadius: 16, style: .continuous),
                fill: Color(red: 20 / 255, green: 24 / 255, blue: 32 / 255).opacity(0.72)
            )
    }

    private func chromeButton(_ systemName: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: systemName == "chevron.left" ? 17 : 18, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 44, height: 44)
                .pairGlass(
                    in: Circle(),
                    fill: Color(red: 40 / 255, green: 40 / 255, blue: 44 / 255).opacity(0.55)
                )
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
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

    private var canSubmit: Bool {
        guard draft != nil, !busy else { return false }
        return !pairingMaterial.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private func addresses(_ code: PairingCode) -> [String] {
        KotlinLists.array(code.hints as Any)
    }

    private var decodedCode: PairingCode? {
        switch onEnum(of: PairingCodes.shared.decode(raw: longCode)) {
        case .ok(let ok):
            return ok.value
        case .err:
            return nil
        }
    }

    private func confirmTapped() {
        dismissKeyboard()
        guard !busy else { return }
        guard let draft else {
            begin()
            return
        }
        guard canSubmit else {
            error = "输入六位短码，或粘贴 luvia2: 码。"
            return
        }
        confirm(draft)
    }

    private func begin(role nextRole: HostRole? = nil) {
        if let nextRole { role = nextRole }
        let trimmed = label.trimmingCharacters(in: .whitespacesAndNewlines)
        let useRole = nextRole ?? role
        guard !trimmed.isEmpty else {
            error = "填写设备名称。"
            return
        }
        if draft != nil, issuedLabel == trimmed, issuedRole == useRole {
            return
        }
        error = nil
        copied = false
        switch model.beginPairing(deviceLabel: trimmed, role: useRole) {
        case .success(let next):
            draft = next
            issuedLabel = trimmed
            issuedRole = useRole
            started = Date()
            now = Date()
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

    private func dismissKeyboard() {
        focus = nil
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    }

    private func clock(_ remaining: Int) -> String {
        String(format: "%d:%02d", remaining / 60, remaining % 60)
    }

    private func tick(_ date: Date) {
        let shown = Int(now.timeIntervalSince(started))
        let next = Int(date.timeIntervalSince(started))
        if shown != next { now = date }
    }
}

private enum PairFocus: Hashable {
    case short
    case host
    case code
    case label
}

private enum PairingIdentity {
    static var deviceLabel: String {
        let name = UIDevice.current.name.trimmingCharacters(in: .whitespacesAndNewlines)
        return name.isEmpty ? "iPhone" : name
    }
}

private enum PairColor {
    static let stage = ChatPalette.pairBackground
    static let blue = Color(hex: "0A84FF")
    static let timer = Color(hex: "9ACBFF")
    static let shield = Color(hex: "30D158")
    static let label2 = Color.white.opacity(0.6)
}

private extension View {
    func pairGlass<S: InsettableShape>(
        in shape: S,
        fill: Color = Color(red: 58 / 255, green: 58 / 255, blue: 64 / 255).opacity(0.58)
    ) -> some View {
        background(fill, in: shape)
            .background(.ultraThinMaterial, in: shape)
            .overlay {
                shape.strokeBorder(
                    LinearGradient(
                        colors: [Color.white.opacity(0.32), Color.white.opacity(0.08)],
                        startPoint: .top,
                        endPoint: .bottom
                    ),
                    lineWidth: 0.7
                )
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
                let maxRadius = size.width * 0.48
                let glowRadius = min(maxRadius, size.height * 0.62)
                context.fill(
                    Path(ellipseIn: CGRect(
                        x: center.x - glowRadius,
                        y: center.y - glowRadius,
                        width: glowRadius * 2,
                        height: glowRadius * 2
                    )),
                    with: .radialGradient(
                        Gradient(colors: [
                            Color(red: 0.04, green: 0.52, blue: 1).opacity(0.35),
                            Color(red: 0.04, green: 0.52, blue: 1).opacity(0),
                        ]),
                        center: center,
                        startRadius: 0,
                        endRadius: glowRadius
                    )
                )
                let count = 210
                let golden = 2.399963229728653
                for index in 0..<count {
                    let unit = (Double(index) + 0.5) / Double(count)
                    let angle = (Double(index) + 0.5) * golden
                    let distance = maxRadius * CGFloat(unit.squareRoot())
                    let point = CGPoint(
                        x: center.x + CGFloat(cos(angle)) * distance,
                        y: center.y + CGFloat(sin(angle)) * distance
                    )
                    let closeness = 1 - Double(distance / max(maxRadius, 1))
                    let dot = CGFloat(1.15 + 1.35 * closeness)
                    let rect = CGRect(x: point.x - dot / 2, y: point.y - dot / 2, width: dot, height: dot)
                    let alpha = 0.16 + 0.74 * closeness
                    context.fill(
                        Path(ellipseIn: rect),
                        with: .color(Color(red: 100 / 255, green: 180 / 255, blue: 1).opacity(alpha))
                    )
                }
                let ringRadius: CGFloat = 76
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
                    with: .color(Color(hex: "0A84FF")),
                    style: StrokeStyle(lineWidth: 4, lineCap: .round)
                )
            }
            .allowsHitTesting(false)
            VStack(spacing: 4) {
                Image(systemName: "laptopcomputer")
                    .font(.system(size: 36))
                    .foregroundStyle(.white)
                Text(timer)
                    .font(.system(size: 13, weight: .semibold, design: .monospaced))
                    .foregroundStyle(PairColor.timer)
            }
            .frame(width: 120, height: 120)
            .pairGlass(
                in: Circle(),
                fill: Color(red: 30 / 255, green: 40 / 255, blue: 60 / 255).opacity(0.48)
            )
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("倒计时 \(timer)")
    }
}

private func looksLikeTailscale(_ address: String) -> Bool {
    let host = address.split(separator: ":").first.map(String.init) ?? address
    let bare = host.split(separator: "%").first.map(String.init) ?? host
    let parts = bare.split(separator: ".")
    guard parts.count == 4, let a = Int(parts[0]), let b = Int(parts[1]) else { return false }
    return a == 100 && (64...127).contains(b)
}
