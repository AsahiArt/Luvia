package tech.asahiart.luvia

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import kotlinx.coroutines.delay
import tech.asahiart.luvia.ui.ChatScreen
import tech.asahiart.luvia.ui.ConnectionBadge
import tech.asahiart.luvia.ui.HostUiModel
import tech.asahiart.luvia.ui.PairingScreen
import tech.asahiart.luvia.ui.SessionListScreen
import tech.asahiart.luvia.ui.TerminalControl
import tech.asahiart.luvia.ui.TerminalPaneChoice
import tech.asahiart.luvia.ui.TerminalScreen
import tech.asahiart.luvia.ui.TerminalUiModel
import tech.asahiart.luvia.thread.trailThumb
import tech.asahiart.luvia.ui.theme.LuviaTheme

/**
 * Debug-only fixture host so the chat-first screens can be captured without a paired computer.
 * Not in the release manifest.
 *
 * List light/dark: `screen=sessions-light` or `screen=sessions-dark`.
 * `screen=sessions` still follows the `night` extra.
 */
class ScreenshotActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        applyLuviaEdgeToEdge()
        super.onCreate(savedInstanceState)
        val screen = intent.getStringExtra("screen") ?: "pair"
        val night = intent.getBooleanExtra("night", false)
        val nightMode = when (screen) {
            "sessions-dark" -> true
            "sessions-light" -> false
            else -> night
        }
        val page = when (screen) {
            "sessions-light", "sessions-dark" -> "sessions"
            else -> screen
        }
        setContent {
            LuviaTheme(darkTheme = nightMode, dynamicColor = false) {
                when (page) {
                    "sessions" -> SessionListScreen(
                        hosts = SampleShots.hosts,
                        selected = SampleShots.hosts.first(),
                        threads = SampleShots.threads,
                        filter = tech.asahiart.luvia.thread.HostSessionFilter(),
                        onFilter = { _ -> },
                        onSelectHost = {},
                        onPair = {},
                        onOpenSettings = {},
                        onOpenThread = {},
                        onAnswer = { _, _ -> },
                        onNewSession = {},
                        onOpenTerminal = {},
                        trails = SampleShots.trails,
                        latencyMs = 12,
                        nowEpochMs = SampleShots.shotNow,
                    )
                    "chat" -> SampleShots.Chat()
                    "terminal" -> TerminalScreen(
                        model = SampleShots.terminal,
                        pendingDecisions = 1,
                        onBack = {},
                        onSelectPane = {},
                        onRequestControl = {},
                        onReleaseControl = {},
                        onSendText = {},
                        onSendKey = {},
                        connection = ConnectionBadge.Live,
                        canAskFix = true,
                    )
                    "terminal-offline" -> TerminalScreen(
                        model = SampleShots.terminal.copy(control = TerminalControl.Observing, synced = false),
                        pendingDecisions = 0,
                        onBack = {},
                        onSelectPane = {},
                        onRequestControl = {},
                        onReleaseControl = {},
                        onSendText = {},
                        onSendKey = {},
                        connection = ConnectionBadge.Offline,
                    )
                    "transition" -> SampleShots.Transition()
                    else -> PairingScreen(
                        pairing = PairingUiState(draft = SampleShots.draft()),
                        onBegin = { _, _ -> },
                        onComplete = { _, _ -> },
                        onCancel = {},
                        onClearError = {},
                        onBack = {},
                        initialLongCode = SampleShots.goldenCode,
                    )
                }
            }
        }
    }
}

private object SampleShots {
    const val shotNow: Long = 1_762_000_000_000L

    const val goldenCode: String =
        "luvia1:eyJ2IjoxLCJpZCI6IjA0ZWQxZTczNTk1NjBkNTA1ZmViODI5Zjk5NzFjNGE5IiwiZGsiOiJTSEEyNTY6S3VOWEllUXREYSt5WmxmOTd1QUhWV01QUXNwdzNHVmhlMnAzYzdOVXYzcyIsIm5hbWUiOiJZdWkubG9jYWwiLCJ1c2VyIjoibWlzYWthIiwicG9ydCI6MjIsImFkZHJzIjpbIjE5Mi4xNjguMS4xNiIsIjE3Mi4xOC4wLjEiLCJZdWkubG9jYWwiXSwiaGsiOlsiU0hBMjU2OjQ2bk5sakpMRmhzRmlvMW5sMUJUUWo5b2dSdnJFY2IzSkZIYWxwMVhqRFkiLCJTSEEyNTY6NjJFK2FEM1p2am9CV1ZwQjh2aE9aRzgzM3NaTGNxNHNSRmZIT011eHJ3QSIsIlNIQTI1NjpqVFBxMUwvQnB4R0JGRitmWjM0dU8rSE1vOVdNZTdmV3JlSDBYclIyT3NzIl0sInJvbGUiOiJjb250cm9sbGVyIn0"

    val hosts = listOf(
        HostUiModel(
            id = "h",
            name = "misaka-mbp",
            address = "100.101.4.12",
            sessionName = "luvia",
            connection = ConnectionBadge.Live,
            connected = true,
            hasSnapshot = true,
        ),
        HostUiModel(
            id = "h2",
            name = "lab-mini",
            address = "",
            sessionName = null,
            connection = ConnectionBadge.Offline,
            connected = false,
            hasSnapshot = false,
        ),
    )

    private val ask = tech.asahiart.luvia.thread.Ask(
        question = "拆成两个平台实现？",
        options = listOf(
            tech.asahiart.luvia.thread.AskOption(
                "拆成两个平台实现",
                tech.asahiart.luvia.thread.AskTone.Primary,
                tech.asahiart.luvia.thread.AskAction.Prompt("split"),
                consequence = "expect/actual ×2 · 约 +120 行",
                recommended = true,
            ),
            tech.asahiart.luvia.thread.AskOption(
                "先用内存实现",
                tech.asahiart.luvia.thread.AskTone.Neutral,
                tech.asahiart.luvia.thread.AskAction.Prompt("mem"),
                consequence = "+18 行 · 重启后需重新配对",
            ),
        ),
        index = 1,
        total = 2,
    )

    private val snapshotAsk = tech.asahiart.luvia.thread.Ask(
        question = "发现 3 个快照不一致，覆盖还是保留旧快照？",
        options = listOf(
            tech.asahiart.luvia.thread.AskOption(
                "保留",
                tech.asahiart.luvia.thread.AskTone.Primary,
                tech.asahiart.luvia.thread.AskAction.Prompt("keep"),
                recommended = true,
            ),
            tech.asahiart.luvia.thread.AskOption(
                "覆盖",
                tech.asahiart.luvia.thread.AskTone.Destructive,
                tech.asahiart.luvia.thread.AskAction.Prompt("overwrite"),
            ),
        ),
        index = 2,
        total = 2,
    )

    val threads = listOf(
        tech.asahiart.luvia.thread.AgentThread(
            id = "a",
            kind = AgentKind.Pane,
            hostId = "h",
            projectKey = "luvia",
            projectLabel = "Luvia",
            title = "重构 Pairing 模块",
            agentType = "claude-code",
            status = AgentStatus.Blocked,
            ask = ask,
            summary = "先核对指纹，再确认配对",
            updatedEpochMs = shotNow - 2 * 60_000,
            paneId = "a",
        ),
        tech.asahiart.luvia.thread.AgentThread(
            id = "c",
            kind = AgentKind.Pane,
            hostId = "h",
            projectKey = "luvia",
            projectLabel = "Luvia",
            title = "终端 ANSI 解析测试",
            agentType = "opencode",
            status = AgentStatus.Blocked,
            ask = snapshotAsk,
            summary = "发现 3 个快照不一致，覆盖还是保留旧快照？",
            updatedEpochMs = shotNow - 9 * 60_000,
            paneId = "c",
        ),
        tech.asahiart.luvia.thread.AgentThread(
            id = "b",
            kind = AgentKind.Acp,
            hostId = "h",
            projectKey = "luvia",
            projectLabel = "Luvia",
            title = "修复 UHP 断线重连",
            agentType = "codex",
            status = AgentStatus.Working,
            ask = null,
            summary = "正在改 ReconnectPolicy",
            updatedEpochMs = shotNow - 9 * 60_000,
        ),
        tech.asahiart.luvia.thread.AgentThread(
            id = "d",
            kind = AgentKind.Pane,
            hostId = "h",
            projectKey = "luvia",
            projectLabel = "Luvia",
            title = "迁移 Room → SQLDelight",
            agentType = "gemini",
            status = AgentStatus.Done,
            ask = null,
            summary = "12 个文件变更，等你 review。",
            updatedEpochMs = shotNow - 60 * 60_000,
            paneId = "d",
        ),
    )

    val trails = mapOf(
        "a" to trailThumb(
            listOf(
                AcpToolCall("r", "Read TokenStore.kt", "read", AcpToolStatus.Completed, null),
                AcpToolCall("e", "Edit TokenStore.kt", "edit", AcpToolStatus.Completed, null),
            ),
        )!!,
        "b" to trailThumb(
            listOf(
                AcpToolCall("r", "Read ReconnectPolicy.kt", "read", AcpToolStatus.Completed, null),
                AcpToolCall("x", "./gradlew :shared:allTests", "shell", AcpToolStatus.Failed, "96/128"),
            ),
        )!!,
        "c" to trailThumb(
            listOf(
                AcpToolCall("r", "Read Ansi.kt", "read", AcpToolStatus.Completed, null),
                AcpToolCall("x", "ansi snapshot", "shell", AcpToolStatus.Failed, "3 mismatches"),
            ),
        )!!,
        "d" to trailThumb(
            listOf(
                AcpToolCall("e1", "Edit Room.kt", "edit", AcpToolStatus.Completed, null),
                AcpToolCall("e2", "Edit Delight.kt", "edit", AcpToolStatus.Completed, null),
                AcpToolCall("e3", "Edit Schema.kt", "edit", AcpToolStatus.Completed, null),
            ),
        )!!,
    )

    val items = listOf(
        tech.asahiart.luvia.thread.TimelineItem.Mine("m", "配对流程加上断线自动重连，token 过期就回到配对页。"),
        tech.asahiart.luvia.thread.TimelineItem.Output("o", "我先看了握手，再改重连。"),
        tech.asahiart.luvia.thread.TimelineItem.Tool(
            "r",
            AcpToolCall(
                toolCallId = "r",
                title = "Read Pairing.kt",
                kind = "read",
                status = AcpToolStatus.Completed,
                summary = null,
                path = "shared/src/Pairing.kt",
                duration = "120ms",
            ),
        ),
        tech.asahiart.luvia.thread.TimelineItem.Tool(
            "e",
            AcpToolCall(
                toolCallId = "e",
                title = "Edit ReconnectPolicy",
                kind = "edit",
                status = AcpToolStatus.Completed,
                summary = """
                    diff --git a/shared/ReconnectPolicy.kt b/shared/ReconnectPolicy.kt
                    --- a/shared/ReconnectPolicy.kt
                    +++ b/shared/ReconnectPolicy.kt
                    @@ -12,3 +12,4 @@
                     class ReconnectPolicy {
                    -    fun retry()
                    +    fun reconnect()
                    +    fun backoff()
                     }
                """.trimIndent(),
                duration = "1.2s",
            ),
        ),
        tech.asahiart.luvia.thread.TimelineItem.Tool(
            "x",
            AcpToolCall(
                toolCallId = "x",
                title = "gradle test",
                kind = "execute",
                status = AcpToolStatus.Failed,
                summary = "tests 127/128\nReconnectTest",
                duration = "4.2s",
            ),
        ),
    )

    private val wowTerminal =
        """
        ~/code/luvus-mobile feat/reconnect
        ${'$'} ./gradlew :shared:allTests
        > Task :shared:compileKotlinIosSimulatorArm64
        > Task :shared:iosSimulatorArm64Test
        ReconnectPolicyTest > backoff caps at 30s PASSED
        ReconnectPolicyTest > 401 stops retry PASSED
        SnapshotSyncTest > resync on seq gap PASSED
        AnsiParserTest > sgr 38;5 truecolor FAILED
          expected:<#2EE6B6> but was:<#2EE6B7>
        > Task :shared:testDebugUnitTest
        CompanionClientTest > pairs with code PASSED
        CompanionClientTest > rejects expired PASSED

        127 tests completed, 1 failed
        BUILD FAILED in 41s
        ${'$'} git diff --stat
        shared/.../ReconnectPolicy.kt | 42 ++++++++--
        shared/.../CompanionClient.kt | 18 +++--
        shared/.../AnsiParser.kt | 6 +-
        ${'$'}
        """.trimIndent()

    val terminal = TerminalUiModel(
        title = "claude-code",
        text = wowTerminal,
        isAnsi = false,
        isTruncated = false,
        control = TerminalControl.Controlling,
        synced = true,
        panes = listOf(
            TerminalPaneChoice("1", "zsh"),
            TerminalPaneChoice("2", "gradle"),
            TerminalPaneChoice("3", "claude"),
        ),
        paneId = "3",
    )

    fun draft(): PairingDraft {
        val ctor = PairingDraft::class.java.declaredConstructors.first { it.parameterCount == 6 }
        ctor.isAccessible = true
        return ctor.newInstance(
            "手机",
            HostRole.Controller,
            "ssh-ed25519 AAAA",
            "SHA256:KuNXIeQtDa+yZlf97uAHVWMPQspw3GVhe2p3c7NUv3s",
            "luvia-host pair --name 手机 --role controller --key ssh-ed25519 AAAA --short",
            "debug",
        ) as PairingDraft
    }

    @Composable
    fun Chat() {
        ChatScreen(
            thread = threads.first(),
            items = items,
            queueIndex = 1,
            queueTotal = 2,
            initialDraft = "",
            onSend = {},
            onAnswer = {},
            onBack = {},
            onOpenTerminal = {},
            terminalPreview = "luvia-host pair\nwaiting for the phone",
            sending = false,
            isObserver = false,
        )
    }

    @Composable
    fun Transition() {
        var showTerminal by remember { mutableStateOf(false) }
        val emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        LaunchedEffect(Unit) {
            while (true) {
                delay(1600)
                showTerminal = !showTerminal
            }
        }
        Box(Modifier.fillMaxSize()) {
            Chat()
            if (showTerminal) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)))
            }
            AnimatedVisibility(
                visible = showTerminal,
                enter = fadeIn(tween(350, easing = emphasized)) + scaleIn(
                    initialScale = 0.84f,
                    transformOrigin = TransformOrigin(0.92f, 0.04f),
                    animationSpec = tween(350, easing = emphasized),
                ),
                exit = fadeOut(tween(220)) + scaleOut(
                    targetScale = 0.84f,
                    transformOrigin = TransformOrigin(0.92f, 0.04f),
                    animationSpec = tween(350, easing = emphasized),
                ),
            ) {
                TerminalScreen(
                    model = terminal,
                    pendingDecisions = 1,
                    onBack = {},
                    onSelectPane = {},
                    onRequestControl = {},
                    onReleaseControl = {},
                    onSendText = {},
                    onSendKey = {},
                )
            }
        }
    }
}
