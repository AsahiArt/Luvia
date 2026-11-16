package tech.asahiart.luvia.ui

import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.time.Duration
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.asahiart.luvia.AgentKind
import tech.asahiart.luvia.AgentStatus
import tech.asahiart.luvia.thread.AgentThread
import tech.asahiart.luvia.thread.HostSessionFilter
import tech.asahiart.luvia.thread.SessionPathOption
import tech.asahiart.luvia.ui.theme.LuviaTheme

/**
 * Scheme 2 title cluster and path chip. The row is a fixture, not a live session.
 * The path string comes from the fixture cwd, not from a product constant.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi", fontScale = 1.0f)
class SessionScopeSummaryTest {
    @get:Rule
    val composeRule = AndroidComposeTestRule(PublicActivityRule()) { it.activity }

    @Before
    fun freezeClock() {
        composeRule.mainClock.autoAdvance = false
    }

    @Test
    fun pathChoicePutsTheFullPathOnTheChipAndBesideTheTitle() {
        val path = "/fixture/only"
        composeRule.activity.setContent {
            var filter by remember { mutableStateOf(HostSessionFilter()) }
            LuviaTheme(darkTheme = false, dynamicColor = false) {
                SessionListScreen(
                    hosts = listOf(host),
                    selected = host,
                    threads = listOf(thread(path)),
                    filter = filter,
                    onFilter = { filter = it },
                    scopeChoices = SessionScopeChoices(
                        allOnline = 1,
                        currentHost = 1,
                        paths = listOf(SessionPathOption(path, 1)),
                    ),
                    onSelectHost = {},
                    onPair = {},
                    onOpenSettings = {},
                    onOpenThread = {},
                    onAnswer = { _, _ -> },
                    onNewSession = {},
                    onOpenTerminal = {},
                    nowEpochMs = SHOT,
                )
            }
        }
        settle()
        assertSummarySharesTheTitleRow("全部 · 1 会话")
        composeRule.onNodeWithText("0 运行").assertIsDisplayed()
        composeRule.onNodeWithText("0 等你").assertIsDisplayed()

        composeRule.onNode(hasContentDescription("会话范围") and hasClickAction()).performClick()
        settle()
        composeRule.onNodeWithText("主机").assertIsDisplayed()
        composeRule.onNodeWithText("路径").assertIsDisplayed()
        composeRule.onNode(hasText(path) and hasClickAction()).performClick()
        settle()

        val summary = "$path · 1 会话"
        composeRule.onNodeWithText(summary).assertIsDisplayed()
        composeRule.onNode(hasText(path) and hasClickAction()).assertIsDisplayed()
        composeRule.onNode(hasContentDescription(path)).assertIsDisplayed()
        composeRule.onAllNodesWithText("only").assertCountEquals(0)
        assertSummarySharesTheTitleRow(summary)
    }

    private fun assertSummarySharesTheTitleRow(summary: String) {
        val title = composeRule.onAllNodesWithText("会话").fetchSemanticsNodes().maxBy { it.boundsInRoot.height }
        val cluster = composeRule.onNode(hasContentDescription(summary)).fetchSemanticsNode()
        val titleBounds = title.boundsInRoot
        val clusterBounds = cluster.boundsInRoot
        assertTrue(
            "summary sits to the right of the title: title=$titleBounds cluster=$clusterBounds",
            clusterBounds.left > titleBounds.left && clusterBounds.right > titleBounds.right,
        )
        assertTrue(
            "summary shares the title row: title=$titleBounds cluster=$clusterBounds",
            clusterBounds.top < titleBounds.bottom && clusterBounds.bottom <= titleBounds.bottom + 8f,
        )
    }

    private fun settle() {
        val looper = Shadows.shadowOf(Looper.getMainLooper())
        repeat(24) {
            looper.idleFor(Duration.ofMillis(16))
            composeRule.mainClock.advanceTimeByFrame()
        }
        composeRule.waitForIdle()
    }
}

private const val SHOT = 1_762_000_000_000L

private val host = HostUiModel(
    id = "h",
    name = "misaka-mbp",
    address = "100.101.4.12",
    sessionName = "luvia",
    connection = ConnectionBadge.Live,
    connected = true,
    hasSnapshot = true,
)

private fun thread(path: String) = AgentThread(
    id = "fixture-a",
    kind = AgentKind.Pane,
    hostId = "h",
    projectKey = "luvia",
    projectLabel = "Luvia",
    title = "fixture row",
    agentType = "claude-code",
    status = AgentStatus.Blocked,
    ask = null,
    summary = "fixture, not a live session",
    updatedEpochMs = SHOT,
    paneId = "a",
    cwd = path,
)
