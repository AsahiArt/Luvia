package tech.asahiart.luvia.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tech.asahiart.luvia.HostRole
import tech.asahiart.luvia.PairingDraft
import tech.asahiart.luvia.PairingUiState
import tech.asahiart.luvia.ui.theme.LuviaTheme

/**
 * Empty pairing ritual. The help sheet must not clear typed values or change
 * whether confirm is enabled.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi", fontScale = 1.0f)
class PairingHelpSheetTest {
    @get:Rule
    val composeRule = AndroidComposeTestRule(PublicActivityRule()) { it.activity }

    @Before
    fun freezeClock() {
        composeRule.mainClock.autoAdvance = false
    }

    @Test
    fun helpSheetShowsTheApprovedCopyAndKeepsTypedValues() {
        composeRule.activity.setContent {
            LuviaTheme(darkTheme = false, dynamicColor = false) {
                PairingScreen(
                    pairing = PairingUiState(draft = ritualDraft()),
                    onBegin = { _, _ -> },
                    onComplete = { _, _ -> },
                    onCancel = {},
                    onClearError = {},
                    onBack = {},
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("确认配对").assertIsNotEnabled()
        composeRule.onNode(hasContentDescription("输入电脑上的六位短码")).performTextInput("123456")
        composeRule.onNode(hasText("电脑地址") and hasSetTextAction()).performTextInput("10.0.0.8")
        composeRule.onNode(hasText("粘贴 luvia2: 码") and hasSetTextAction()).performTextInput("luvia2:kept")
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("确认配对").assertIsEnabled()

        composeRule.onNodeWithText("?").performClick()
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("如何配对").assertIsDisplayed()
        composeRule.onNodeWithText("在电脑终端运行本页显示的配对命令。").assertIsDisplayed()
        composeRule.onNodeWithText(
            "在手机上输入电脑显示的六位短码和电脑地址；也可以扫描或粘贴电脑提供的 luvia2: 配对码，这种方式不用另填地址。",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("核对手机与电脑上的指纹，一致后点「确认配对」；不一致就先不要继续。").assertIsDisplayed()
        composeRule.onNodeWithText("配对新电脑").assertIsDisplayed()

        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("如何配对").assertCountEquals(0)
        composeRule.onNodeWithText("已输入短码 123456").assertIsDisplayed()
        composeRule.onNodeWithText("10.0.0.8").assertIsDisplayed()
        composeRule.onNodeWithText("luvia2:kept").assertIsDisplayed()
        composeRule.onNodeWithText("确认配对").assertIsEnabled()
        composeRule.onNodeWithText("核对指纹").assertIsDisplayed()
        composeRule.onNodeWithText("配对新电脑").assertIsDisplayed()
    }
}

public class PublicActivityRule : TestRule {
    lateinit var activity: ComponentActivity
        private set

    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            override fun evaluate() {
                val controller = Robolectric.buildActivity(ComponentActivity::class.java)
                try {
                    activity = controller.setup().get()
                    base.evaluate()
                } finally {
                    controller.pause().stop().destroy()
                }
            }
        }
    }
}

private fun ritualDraft(): PairingDraft {
    val ctor = PairingDraft::class.java.declaredConstructors.first { it.parameterCount == 6 }
    ctor.isAccessible = true
    return ctor.newInstance(
        "手机",
        HostRole.Controller,
        "ssh-ed25519 AAAA",
        "SHA256:KuNXIeQtDa+yZlf97uAHVWMPQspw3GVhe2p3c7NUv3s",
        "luvia-host pair --name 手机 --role controller",
        "debug",
    ) as PairingDraft
}
