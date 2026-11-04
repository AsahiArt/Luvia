package tech.asahiart.luvia.thread

/**
 * Debug-only terminal capture so a playtester can photograph the failure
 * card and named panes without a live failing host. The phone must call
 * these only from a debug build. Release must not paint this capture.
 */
public data class PlaytestPane(
    public val id: String,
    public val title: String,
)

public const val PLAYTEST_PANE_ZSH: String = "playtest-pane-zsh"

public const val PLAYTEST_PANE_GRADLE: String = "playtest-pane-gradle"

public const val PLAYTEST_PANE_CLAUDE: String = "playtest-pane-claude"

/** Pane whose capture is the failed run. */
public const val PLAYTEST_TERMINAL_FAILURE_PANE: String = PLAYTEST_PANE_CLAUDE

public fun isPlaytestPane(id: String): Boolean =
    id == PLAYTEST_PANE_ZSH || id == PLAYTEST_PANE_GRADLE || id == PLAYTEST_PANE_CLAUDE

public fun playtestTerminalPanes(): List<PlaytestPane> = listOf(
    PlaytestPane(PLAYTEST_PANE_ZSH, "zsh"),
    PlaytestPane(PLAYTEST_PANE_GRADLE, "gradle"),
    PlaytestPane(PLAYTEST_PANE_CLAUDE, "claude"),
)

/**
 * Sample capture for [paneId]. Unknown ids get the failed run so an offline
 * host with its own pane ids still has a card to photograph.
 */
public fun playtestTerminalText(paneId: String): String =
    when (paneId) {
        PLAYTEST_PANE_ZSH -> ZSH_CAPTURE
        PLAYTEST_PANE_GRADLE -> GRADLE_CAPTURE
        else -> FAILURE_CAPTURE
    }

private val FAILURE_CAPTURE =
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
    shared/.../AnsiParser.kt | 6 +-
    ${'$'}
    """.trimIndent()

private val GRADLE_CAPTURE =
    """
    ${'$'} ./gradlew :shared:allTests
    BUILD SUCCESSFUL in 4s
    ${'$'}
    """.trimIndent()

private val ZSH_CAPTURE =
    """
    ${'$'}
    """.trimIndent()
