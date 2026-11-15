package tech.asahiart.luvia.ui

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View.MeasureSpec
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.asahiart.luvia.AcpToolCall
import tech.asahiart.luvia.AcpToolStatus
import tech.asahiart.luvia.AgentKind
import tech.asahiart.luvia.AgentStatus
import tech.asahiart.luvia.HostRole
import tech.asahiart.luvia.PairingDraft
import tech.asahiart.luvia.PairingUiState
import tech.asahiart.luvia.thread.AgentThread
import tech.asahiart.luvia.thread.Ask
import tech.asahiart.luvia.thread.AskAction
import tech.asahiart.luvia.thread.AskOption
import tech.asahiart.luvia.thread.AskTone
import tech.asahiart.luvia.thread.HostSessionFilter
import tech.asahiart.luvia.thread.trailThumb
import tech.asahiart.luvia.ui.theme.LuviaTheme

/**
 * Robolectric software-canvas fixture. PixelCopy does not complete on this host.
 * These pixels are not a phone, a paired session, or iOS.
 * The board is the finalized 390×844 dp frame at xhdpi so the bitmap is 780×1688.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi", fontScale = 1.0f)
class ProductionComposeCaptureTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun freezeClock() {
        composeRule.mainClock.autoAdvance = false
    }

    @Test
    fun pairRitualLight() = capturePair(dark = false, name = "pair-ritual-light")

    @Test
    fun pairRitualDark() = capturePair(dark = true, name = "pair-ritual-dark")

    @Test
    fun sessionListLight() = captureList(dark = false, machines = false, name = "list-sessions-light")

    @Test
    fun sessionListDark() = captureList(dark = true, machines = false, name = "list-sessions-dark")

    @Test
    fun machinesPlaceholderLight() = captureList(dark = false, machines = true, name = "machines-placeholder-light")

    @Test
    fun machinesPlaceholderDark() = captureList(dark = true, machines = true, name = "machines-placeholder-dark")

    private fun capturePair(dark: Boolean, name: String) {
        show(dark) {
            PairingScreen(
                pairing = PairingUiState(draft = ritualDraft()),
                onBegin = { _, _ -> },
                onComplete = { _, _ -> },
                onCancel = {},
                onClearError = {},
                onBack = {},
                initialLongCode = GOLDEN_CODE,
            )
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        writeFrame("$name-t0.png")
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
        writeFrame("$name-t1000.png")
    }

    private fun captureList(dark: Boolean, machines: Boolean, name: String) {
        show(dark) {
            SessionListScreen(
                hosts = hosts,
                selected = hosts.first(),
                threads = threads,
                filter = HostSessionFilter(),
                onFilter = {},
                onSelectHost = {},
                onPair = {},
                onOpenSettings = {},
                onOpenThread = {},
                onAnswer = { _, _ -> },
                onNewSession = {},
                onOpenTerminal = {},
                trails = trails,
                nowEpochMs = SHOT_NOW,
            )
        }
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.waitForIdle()
        var switched = !machines
        if (machines) {
            val tabs = composeRule.onAllNodes(hasText("机器") and hasClickAction())
            val node = tabs[0].fetchSemanticsNode()
            val bounds = node.boundsInRoot
            note(
                "machineClickTargets=${tabs.fetchSemanticsNodes().size} " +
                    "bounds=${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}\n",
            )
            tabs[0].performClick()
            composeRule.mainClock.advanceTimeBy(300)
            composeRule.waitForIdle()
            switched = placeholderVisible()
            note("placeholderAfterSemanticsClick=$switched\n")
            if (!switched) {
                val centerX = (bounds.left + bounds.right) / 2f
                val centerY = (bounds.top + bounds.bottom) / 2f
                dispatchTap(centerX, centerY)
                composeRule.mainClock.advanceTimeBy(300)
                composeRule.waitForIdle()
                switched = placeholderVisible()
                note("placeholderAfterPointer=$switched x=$centerX y=$centerY\n")
            }
        }
        val frame = when {
            machines && !switched -> "$name-tab-not-switched.png"
            // Pointer reached MachinesBody. performClick did not, so this is not a semantics navigation pass.
            machines -> "$name-pointer.png"
            else -> "$name.png"
        }
        writeFrame(frame)
    }

    private fun show(dark: Boolean, content: @Composable () -> Unit) {
        composeRule.activity.setContent {
            val density = LocalDensity.current
            val configuration = LocalConfiguration.current
            SideEffect {
                note(
                    buildString {
                        appendLine("dark=$dark")
                        appendLine("widthDp=${configuration.screenWidthDp}")
                        appendLine("heightDp=${configuration.screenHeightDp}")
                        appendLine("density=${density.density}")
                        appendLine("fontScale=${density.fontScale}")
                    },
                )
            }
            LuviaTheme(darkTheme = dark, dynamicColor = false) {
                Box(Modifier.requiredSize(390.dp, 844.dp)) {
                    content()
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.waitForIdle()
    }

    private fun placeholderVisible(): Boolean =
        composeRule.onAllNodes(hasText("已配对的电脑", substring = true))
            .fetchSemanticsNodes()
            .isNotEmpty()

    private fun dispatchTap(x: Float, y: Float) {
        val view = composeRule.activity.window.decorView
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(downTime, downTime + 40, MotionEvent.ACTION_UP, x, y, 0)
        view.dispatchTouchEvent(down)
        view.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    private fun writeFrame(name: String) {
        // captureToImage waits for a hardware draw that this headless Robolectric
        // fixture never delivers. Draw the already composed production view instead.
        val view = composeRule.activity.window.decorView
        val widthSpec = MeasureSpec.makeMeasureSpec(780, MeasureSpec.EXACTLY)
        val heightSpec = MeasureSpec.makeMeasureSpec(1688, MeasureSpec.EXACTLY)
        view.measure(widthSpec, heightSpec)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val file = File(captureDir(), name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        note("file=$name ${bitmap.width}x${bitmap.height} softwareCanvas=true\n")
    }

    private fun note(text: String) {
        File(captureDir(), "fixture.txt").appendText(text)
    }

    private fun captureDir(): File {
        val raw = System.getenv("LUVIA_CAPTURE_DIR")
            ?: error("Set LUVIA_CAPTURE_DIR to the directory that should receive the PNGs")
        return File(raw).apply { mkdirs() }
    }
}

private const val SHOT_NOW: Long = 1_762_000_000_000L

private const val GOLDEN_CODE: String =
    "luvia2:eyJ2IjoxLCJpZCI6IjA0ZWQxZTczNTk1NjBkNTA1ZmViODI5Zjk5NzFjNGE5IiwiZGsiOiJTSEEyNTY6S3VOWEllUXREYSt5WmxmOTd1QUhWV01QUXNwdzNHVmhlMnAzYzdOVXYzcyIsIm5hbWUiOiJZdWkubG9jYWwiLCJ1c2VyIjoibWlzYWthIiwicG9ydCI6MjIsImFkZHJzIjpbIjE5Mi4xNjguMS4xNiIsIjE3Mi4xOC4wLjEiLCJZdWkubG9jYWwiXSwiaGsiOlsiU0hBMjU2OjQ2bk5sakpMRmhzRmlvMW5sMUJUUWo5b2dSdnJFY2IzSkZIYWxwMVhqRFkiLCJTSEEyNTY6NjJFK2FEM1p2am9CV1ZwQjh2aE9aRzgzM3NaTGNxNHNSRmZIT011eHJ3QSIsIlNIQTI1NjpqVFBxMUwvQnB4R0JGRitmWjM0dU8rSE1vOVdNZTdmV3JlSDBYclIyT3NzIl0sInJvbGUiOiJjb250cm9sbGVyIn0"

private fun ritualDraft(): PairingDraft {
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

private val hosts = listOf(
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

private val ask = Ask(
    question = "拆成两个平台实现？",
    options = listOf(
        AskOption(
            "拆成两个平台实现",
            AskTone.Primary,
            AskAction.Prompt("split"),
            consequence = "expect/actual ×2 · 约 +120 行",
            recommended = true,
        ),
        AskOption(
            "先用内存实现",
            AskTone.Neutral,
            AskAction.Prompt("mem"),
            consequence = "+18 行 · 重启后需重新配对",
        ),
    ),
    index = 1,
    total = 2,
)

private val threads = listOf(
    AgentThread(
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
        updatedEpochMs = SHOT_NOW - 2 * 60_000,
        paneId = "a",
    ),
    AgentThread(
        id = "c",
        kind = AgentKind.Pane,
        hostId = "h",
        projectKey = "luvia",
        projectLabel = "Luvia",
        title = "终端 ANSI 解析测试",
        agentType = "opencode",
        status = AgentStatus.Blocked,
        ask = Ask(
            question = "发现 3 个快照不一致，覆盖还是保留旧快照？",
            options = listOf(
                AskOption("保留", AskTone.Primary, AskAction.Prompt("keep"), recommended = true),
                AskOption("覆盖", AskTone.Destructive, AskAction.Prompt("overwrite")),
            ),
            index = 2,
            total = 2,
        ),
        summary = "发现 3 个快照不一致，覆盖还是保留旧快照？",
        updatedEpochMs = SHOT_NOW - 9 * 60_000,
        paneId = "c",
    ),
)

private val trails = mapOf(
    "a" to trailThumb(
        listOf(
            AcpToolCall("r", "Read TokenStore.kt", "read", AcpToolStatus.Completed, null),
            AcpToolCall("e", "Edit TokenStore.kt", "edit", AcpToolStatus.Completed, null),
        ),
    )!!,
    "c" to trailThumb(
        listOf(
            AcpToolCall("r", "Read Ansi.kt", "read", AcpToolStatus.Completed, null),
            AcpToolCall("x", "ansi snapshot", "shell", AcpToolStatus.Failed, "3 mismatches"),
        ),
    )!!,
)
