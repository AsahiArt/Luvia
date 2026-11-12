package tech.asahiart.luvia.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tech.asahiart.luvia.AgentStatus
import tech.asahiart.luvia.R
import tech.asahiart.luvia.thread.PanePiece
import tech.asahiart.luvia.thread.ToolTrailKind
import tech.asahiart.luvia.thread.splitPaneTranscript

/** Maps shared English failure copy onto the Chinese UI without changing protocol strings. */
internal fun localizeUiMessage(message: String): String {
    val lower = message.lowercase()
    return when {
        lower.contains("luvia1:") || lower.contains("old pairing code") ->
            "这是旧版配对码，已不再支持。在电脑上更新 luvia-host，重新运行 pair，再扫新码。草稿仍然有效。"
        lower.contains("not a luvia2") || lower.contains("must start with luvia2") ->
            "这不是 luvia2: 配对码。再扫一次，或粘贴另一条码。草稿仍然有效。"
        lower.contains("short code expired") ->
            "六位短码已过期。在电脑上重新运行 pair --short，或粘贴 luvia2: 码。草稿仍然有效。"
        lower.contains("invalidated after failed") ->
            "六位短码已因错误尝试作废。在电脑上重新运行 pair --short，或粘贴 luvia2: 码。草稿仍然有效。"
        lower.contains("computer address shown next") || lower.contains("short code needs") ->
            "填写电脑上短码旁边的地址。草稿仍然有效。"
        lower.contains("could not reach the computer to redeem") || lower.contains("short code redeem failed") ->
            "连不上电脑兑换短码。检查地址，或粘贴 luvia2: 码。草稿仍然有效。"
        lower.contains("rejected this short code") || lower.contains("rejected for this device key") || lower.contains("short code was rejected") || lower.contains("short code device key") ->
            "电脑拒绝了这个短码。核对数字和设备密钥，或粘贴 luvia2: 码。草稿仍然有效。"
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
        lower.contains("host key changed") || lower.contains("host key mismatch") -> "主机密钥变了。请重新配对。"
        lower.contains("host refused the connection") || lower.contains("closed before banner") ->
            "主机拒绝了连接"
        lower.contains("name lookup timed out") || lower.contains("dns timeout") ->
            "找不到这台电脑（主机名解析超时）。检查网络或 Tailscale。"
        lower.contains("candidate timeout") || lower.contains("no address reachable") ->
            "没有能连上的地址。检查网络或 Tailscale。"
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

/**
 * Prose stays prose. Tool lines, diffs, cost bars, and a `DONE` receipt leave the grey bubble.
 * Does not invent a plan or a permission; those still come from the host.
 */
internal fun chatSurfacePieces(text: String): List<PanePiece> {
    if (text.isBlank()) return emptyList()
    return splitPaneTranscript(text.lines()).flatMap { piece ->
        when (piece) {
            is PanePiece.Prose -> splitDoneReceipt(piece.text)
            else -> listOf(piece)
        }
    }
}

internal fun splitDoneReceipt(text: String): List<PanePiece> {
    if (text.isBlank()) return emptyList()
    val pieces = mutableListOf<PanePiece>()
    val prose = mutableListOf<String>()
    fun flushProse() {
        val body = prose.joinToString("\n").trim('\n')
        prose.clear()
        if (body.isNotBlank()) pieces += PanePiece.Prose(body)
    }
    val lines = text.lines()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()
        if (trimmed == "DONE" || trimmed.startsWith("DONE ") || trimmed.startsWith("DONE\t")) {
            val block = mutableListOf(trimmed)
            var next = index + 1
            while (next < lines.size && lines[next].isBlank()) next++
            while (next < lines.size && isDoneContinuation(lines[next])) {
                block += lines[next].trim()
                next++
            }
            if (block.size > 1 || '=' in trimmed) {
                flushProse()
                pieces += PanePiece.Status(block.joinToString(" · "))
                index = next
                continue
            }
        }
        prose += line
        index++
    }
    flushProse()
    return pieces
}

private fun isDoneContinuation(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return false
    if (trimmed.any { it.code in 0x4E00..0x9FFF }) return false
    return '=' in trimmed
}
