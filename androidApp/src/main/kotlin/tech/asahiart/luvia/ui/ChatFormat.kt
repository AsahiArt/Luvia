package tech.asahiart.luvia.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tech.asahiart.luvia.AgentStatus
import tech.asahiart.luvia.R
import tech.asahiart.luvia.thread.ToolTrailKind

/** Maps shared English failure copy onto the Chinese UI without changing protocol strings. */
internal fun localizeUiMessage(message: String): String {
    val lower = message.lowercase()
    return when {
        lower.contains("not a luvia1") || lower.contains("must start with luvia1") ->
            "这不是 luvia1: 配对码。再扫一次，或粘贴另一条码。草稿仍然有效。"
        lower.contains("different device key") ->
            "这条配对码属于另一把设备密钥。请在电脑上运行本页的命令，再扫描它打印的码。草稿仍然有效。"
        lower.contains("no host key") || lower.contains("empty host") ->
            "这条配对码没有主机密钥，不能信任。再扫一次，或粘贴另一条码。草稿仍然有效。"
        lower.contains("malformed") ->
            "这条配对码格式不对。再扫一次，或粘贴另一条码。草稿仍然有效。"
        message == "Not connected to this host." -> "还没有连上这台电脑。"
        lower.contains("no longer available") -> "这个窗格已经不在了。换一个正在运行的窗格，或刷新。"
        lower.contains("still handling a previous message") -> "agent 还在处理上一条消息。等它完成。"
        lower.contains("screen changed") -> "agent 的画面变了。刷新后再发按键。"
        lower.contains("host key changed") -> "主机密钥变了。请重新配对。"
        lower.contains("no address reachable") -> "没有能连上的地址。检查网络或 Tailscale。"
        lower.contains("observe still works") -> message.substringBefore("Observe").trim() + " 观察仍然有效。"
        else -> message
    }
}

internal fun formatRemaining(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

internal fun looksLikeTailscale(address: String): Boolean {
    val host = address.substringBefore('%').substringBefore('/')
    val parts = host.split('.')
    if (parts.size != 4) return false
    val first = parts[0].toIntOrNull() ?: return false
    val second = parts[1].toIntOrNull() ?: return false
    return first == 100 && second in 64..127
}

@Composable
internal fun AgentStatus.sessionLabel(): String =
    when (this) {
        AgentStatus.Idle -> stringResource(R.string.status_idle)
        AgentStatus.Working -> stringResource(R.string.status_running)
        AgentStatus.Blocked -> stringResource(R.string.status_needs_you)
        AgentStatus.Done -> stringResource(R.string.status_done)
        AgentStatus.Unknown -> stringResource(R.string.status_unknown)
    }

@Composable
internal fun ConnectionBadge.label(): String =
    when (this) {
        ConnectionBadge.Live -> stringResource(R.string.link_live)
        ConnectionBadge.Connecting -> stringResource(R.string.link_connecting)
        ConnectionBadge.Stale -> stringResource(R.string.link_stale)
        ConnectionBadge.Offline -> stringResource(R.string.link_offline)
    }

@Composable
internal fun ConnectionBadge.chromeLabel(): String =
    when (this) {
        ConnectionBadge.Live -> stringResource(R.string.link_live)
        ConnectionBadge.Connecting -> stringResource(R.string.link_reconnecting)
        ConnectionBadge.Stale, ConnectionBadge.Offline -> stringResource(R.string.link_offline)
    }

@Composable
internal fun ToolTrailKind.shortLabel(): String =
    when (this) {
        ToolTrailKind.Read -> stringResource(R.string.tool_read)
        ToolTrailKind.Edit -> stringResource(R.string.tool_edit)
        ToolTrailKind.Run -> stringResource(R.string.tool_run)
        ToolTrailKind.Other -> stringResource(R.string.tool_other)
    }
