package tech.asahiart.luvia

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import tech.asahiart.luvia.ui.theme.LuviaTheme

/**
 * Debug-only fixture host so the chat-first screens can be captured without a paired computer.
 * Not in the release manifest.
 */
class ScreenshotActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val screen = intent.getStringExtra("screen") ?: "pair"
        val night = intent.getBooleanExtra("night", false)
        setContent {
            LuviaTheme(darkTheme = night, dynamicColor = false) {
                when (screen) {
                    "sessions" -> SessionListScreen(
                        hosts = SampleShots.hosts,
                        selected = SampleShots.hosts.first(),
                        threads = SampleShots.threads,
                        filter = tech.asahiart.luvia.thread.SessionFilter.All,
                        onFilter = {},
                        onSelectHost = {},
                        onPair = {},
                        onOpenSettings = {},
                        onOpenThread = {},
                        onAnswer = { _, _ -> },
                        onNewSession = {},
                        onOpenTerminal = {},
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
                    )
                    "transition" -> SampleShots.Transition()
                    else -> PairingScreen(
                        pairing = PairingUiState(draft = SampleShots.draft()),
                        onBegin = { _, _ -> },
                        onComplete = {},
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
            updatedEpochMs = null,
        ),
        tech.asahiart.luvia.thread.AgentThread(
            id = "b",
            kind = AgentKind.Acp,
            hostId = "h",
            projectKey = "luvia",
            projectLabel = "Luvia",
            title = "TokenStore 拆分",
            agentType = "codex",
            status = AgentStatus.Working,
            ask = null,
            summary = "正在改 ReconnectPolicy",
            updatedEpochMs = null,
        ),
    )

    val items = listOf(
        tech.asahiart.luvia.thread.TimelineItem.Mine("m", "配对流程加上断线自动重连，token 过期就回到配对页。"),
        tech.asahiart.luvia.thread.TimelineItem.Output("o", "我先看了握手，再改重连。"),
        tech.asahiart.luvia.thread.TimelineItem.Tool(
            "r",
            AcpToolCall("r", "Read Pairing.kt", "read", AcpToolStatus.Completed, null),
        ),
        tech.asahiart.luvia.thread.TimelineItem.Tool(
            "e",
            AcpToolCall(
                "e",
                "Edit ReconnectPolicy",
                "edit",
                AcpToolStatus.Completed,
                "+ fun reconnect()\n- fun retry()\n@@ policy",
            ),
        ),
        tech.asahiart.luvia.thread.TimelineItem.Tool(
            "x",
            AcpToolCall("x", "gradle test", "execute", AcpToolStatus.Failed, "tests 127/128\nReconnectTest"),
        ),
    )

    val terminal = TerminalUiModel(
        title = "claude-code",
        text = "luvia-host pair\nwaiting for the phone\n",
        isAnsi = false,
        isTruncated = false,
        control = TerminalControl.Observing,
        panes = listOf(
            TerminalPaneChoice("1", "claude-code"),
            TerminalPaneChoice("2", "shell"),
        ),
        paneId = "1",
    )

    fun draft(): PairingDraft {
        val ctor = PairingDraft::class.java.declaredConstructors.first { it.parameterCount == 6 }
        ctor.isAccessible = true
        return ctor.newInstance(
            "手机",
            HostRole.Controller,
            "ssh-ed25519 AAAA",
            "SHA256:KuNXIeQtDa+yZlf97uAHVWMPQspw3GVhe2p3c7NUv3s",
            "luvia-host pair --name 手机 --role controller --key ssh-ed25519 AAAA",
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
