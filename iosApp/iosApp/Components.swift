import SwiftUI
import UIKit
import LuviaShared

struct StatusPill: View {
    let text: String
    let color: Color
    var filled = false

    var body: some View {
        Text(text)
            .font(.caption.weight(.semibold))
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .foregroundStyle(filled ? Color.white : color)
            .background(filled ? color : color.opacity(0.16), in: Capsule())
            .accessibilityLabel(text)
            .accessibilityAddTraits(.isStaticText)
    }

    static func link(_ badge: HostConnectionBadge) -> StatusPill {
        StatusPill(text: badge.statusLabel, color: badge.tokenColor)
    }

    static func agent(_ kind: AgentStatusKind) -> StatusPill {
        StatusPill(
            text: kind.label,
            color: kind.tokenColor,
            filled: kind == .blocked
        )
    }
}

struct TypeBadge: View {
    enum Kind {
        case pane, acp, tailnet, herdr, observer

        var title: String {
            switch self {
            case .pane: "Pane"
            case .acp: "ACP"
            case .tailnet: "Tailnet"
            case .herdr: "Herdr"
            case .observer: "Observer"
            }
        }

        var symbol: String {
            switch self {
            case .pane: "rectangle.split.2x1"
            case .acp: "bubble.left.and.bubble.right"
            case .tailnet: "point.3.connected.trianglepath.dotted"
            case .herdr: "square.stack.3d.up"
            case .observer: "eye"
            }
        }

        var color: Color {
            switch self {
            case .pane: DesignTokens.inkMuted
            case .acp: DesignTokens.linkConnecting
            case .tailnet: DesignTokens.linkConnecting
            case .herdr: DesignTokens.linkStale
            case .observer: DesignTokens.inkMuted
            }
        }
    }

    let kind: Kind

    var body: some View {
        Label(kind.title, systemImage: kind.symbol)
            .font(.caption2.weight(.medium))
            .labelStyle(.titleAndIcon)
            .foregroundStyle(kind.color)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(kind.color.opacity(0.12), in: Capsule())
            .accessibilityLabel(kind.title)
    }
}

struct HostAvatar: View {
    let name: String
    let connection: HostConnectionBadge

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            Text(letter)
                .font(.headline)
                .foregroundStyle(DesignTokens.accent)
                .frame(width: 40, height: 40)
                .background(DesignTokens.accent.opacity(0.16), in: Circle())
            Circle()
                .fill(connection.tokenColor)
                .frame(width: 11, height: 11)
                .overlay(Circle().stroke(DesignTokens.surface, lineWidth: 2))
                .accessibilityHidden(true)
        }
        .accessibilityHidden(true)
    }

    private var letter: String {
        String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(1)).uppercased()
    }
}

struct AgentRow: View {
    let item: AgentListItem

    var body: some View {
        HStack(alignment: .top, spacing: DesignTokens.Space.s) {
            RoundedRectangle(cornerRadius: 1.5, style: .continuous)
                .fill(item.statusKind.tokenColor)
                .frame(width: 3)
                .padding(.vertical, 2)
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(item.name)
                        .font(.body.weight(.semibold))
                        .foregroundStyle(DesignTokens.ink)
                        .lineLimit(2)
                    Spacer(minLength: 8)
                    if item.isWaiting {
                        Text("Blocked")
                            .font(.caption.weight(.semibold))
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .foregroundStyle(.white)
                            .background(DesignTokens.accent, in: Capsule())
                    }
                    TypeBadge(kind: item.isAcp ? .acp : .pane)
                }
                if let subtitle = item.subtitle, !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.subheadline)
                        .foregroundStyle(DesignTokens.inkMuted)
                        .lineLimit(1)
                }
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    if let lastLine = item.lastLine, !lastLine.isEmpty {
                        Text(withNerdGlyphs(lastLine))
                            .font(.caption)
                            .foregroundStyle(DesignTokens.inkMuted)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 0)
                    if let time = item.relativeTime {
                        Text(time)
                            .font(.caption)
                            .foregroundStyle(DesignTokens.inkMuted)
                    }
                }
            }
        }
        .padding(.vertical, 4)
        .frame(minHeight: 44)
    }
}

struct EmptyState: View {
    let title: String
    var message: String? = nil
    var systemImage: String? = nil
    var serif = false
    var actionTitle: String? = nil
    var action: (() -> Void)? = nil

    var body: some View {
        VStack(spacing: DesignTokens.Space.m) {
            if let systemImage {
                Image(systemName: systemImage)
                    .font(.largeTitle)
                    .foregroundStyle(DesignTokens.accent)
            }
            Text(title)
                .font(serif ? DesignTokens.Typography.title : .headline)
                .foregroundStyle(DesignTokens.ink)
                .multilineTextAlignment(.center)
            if let message {
                Text(message)
                    .font(.subheadline)
                    .foregroundStyle(DesignTokens.inkMuted)
                    .multilineTextAlignment(.center)
            }
            if let actionTitle, let action {
                Button(actionTitle, action: action)
                    .buttonStyle(.borderedProminent)
                    .tint(DesignTokens.accent)
            }
        }
        .padding(DesignTokens.Space.l)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

struct SectionHeader: View {
    let title: String

    var body: some View {
        Text(title)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(DesignTokens.inkMuted)
            .textCase(.uppercase)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct ProjectChips: View {
    let choices: [ProjectChoice]
    let selectedId: String?
    var revealToken: String = ""
    let onSelect: (String) -> Void

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: DesignTokens.Space.s) {
                    ForEach(choices, id: \.id) { choice in
                        let selected = choice.id == selectedId
                        Button(choice.label) { onSelect(choice.id) }
                            .font(.subheadline.weight(.semibold))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 8)
                            .foregroundStyle(selected ? Color.white : DesignTokens.ink)
                            .background(
                                selected ? DesignTokens.accent : DesignTokens.inkMuted.opacity(0.14),
                                in: Capsule()
                            )
                            .id(choice.id)
                    }
                }
                .padding(.horizontal, DesignTokens.Space.m)
                .padding(.vertical, DesignTokens.Space.s)
            }
            .accessibilityLabel("Project")
            .task(id: "\(revealToken)|\(selectedId ?? "")") {
                guard let selectedId else { return }
                try? await _Concurrency.Task.sleep(for: .milliseconds(50))
                var transaction = Transaction()
                transaction.animation = .easeInOut(duration: 0.2)
                withTransaction(transaction) {
                    proxy.scrollTo(selectedId, anchor: .center)
                }
            }
        }
    }
}

struct KeyChip: View {
    let title: String
    let action: () -> Void
    var enabled = true

    var body: some View {
        Button(title, action: action)
            .buttonStyle(.bordered)
            .controlSize(.small)
            .disabled(!enabled)
    }
}

struct UnconfirmedBanner: View {
    let action: UnconfirmedAction
    let onCheck: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            VStack(alignment: .leading, spacing: 4) {
                Text(action.title)
                    .font(.headline)
                    .foregroundStyle(DesignTokens.ink)
                Text(action.detail)
                    .font(.caption)
                    .foregroundStyle(DesignTokens.inkMuted)
                Text("This change is not resent automatically.")
                    .font(.caption)
                    .foregroundStyle(DesignTokens.inkMuted)
            }
            Spacer(minLength: 8)
            Button(buttonTitle, action: onCheck)
                .buttonStyle(.borderedProminent)
                .tint(DesignTokens.accent)
        }
        .padding(DesignTokens.Space.m)
        .background(
            Color.red.opacity(0.12),
            in: RoundedRectangle(cornerRadius: DesignTokens.Radius.m, style: .continuous)
        )
    }

    private var buttonTitle: String {
        switch action {
        case .agentPrompt, .agentKeys, .resumeAgent, .forkAgent, .nameAgent:
            "Re-read agent"
        default:
            "Check"
        }
    }
}

extension HostConnectionBadge {
    var statusLabel: String {
        switch self {
        case .stale: "Stale"
        default: rawValue
        }
    }

    var tokenColor: Color {
        switch self {
        case .live: DesignTokens.linkLive
        case .connecting: DesignTokens.linkConnecting
        case .stale: DesignTokens.linkStale
        case .offline: DesignTokens.linkOffline
        }
    }
}

extension AgentStatusKind {
    var tokenColor: Color {
        switch self {
        case .idle, .done: DesignTokens.agentIdle
        case .working: DesignTokens.agentWorking
        case .blocked: DesignTokens.agentBlocked
        case .unknown: DesignTokens.agentUnknown
        }
    }
}
