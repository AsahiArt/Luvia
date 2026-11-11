package tech.asahiart.luvia.thread

import tech.asahiart.luvia.AcpToolCall
import tech.asahiart.luvia.AcpToolStatus
import tech.asahiart.luvia.AgentKind
import tech.asahiart.luvia.AgentStatus

/**
 * Debug-only conversations so a playtester can open a diff card and a decision
 * without a live host. The phone must call these only from a debug build.
 */
public const val PLAYTEST_DIFF_ID: String = "playtest-diff"

public const val PLAYTEST_DECIDE_ID: String = "playtest-decide"

public const val PLAYTEST_NEXT_ID: String = "playtest-next"

/** Sample card with a question and no options. Not a live pane. */
public const val PLAYTEST_NONCURSOR_ID: String = "playtest-noncursor"

public fun isPlaytestThread(id: String): Boolean =
    id == PLAYTEST_DIFF_ID || id == PLAYTEST_DECIDE_ID || id == PLAYTEST_NEXT_ID ||
        id == PLAYTEST_NONCURSOR_ID ||
        isPlaytestListThread(id)

public fun playtestThreads(): List<AgentThread> = listOf(diffThread, decideThread, nextThread)

public fun playtestNoncursorThread(): AgentThread = noncursorThread

public fun playtestTimeline(id: String): List<TimelineItem>? =
    when (id) {
        PLAYTEST_DIFF_ID -> diffTimeline
        PLAYTEST_DECIDE_ID -> decideTimeline
        PLAYTEST_NEXT_ID -> nextTimeline
        PLAYTEST_NONCURSOR_ID -> noncursorTimeline
        else -> null
    }

private const val PLAYTEST_HOST: String = "playtest"

private val decideAsk =
    Ask(
        question = "把 TokenStore 拆成 iOS Keychain / Android Keystore 两个 actual?",
        options = listOf(
            AskOption(
                label = "拆成两个平台实现",
                tone = AskTone.Primary,
                action = AskAction.Prompt("split"),
                consequence = "expect/actual ×2 · 约 +120 行",
                recommended = true,
            ),
            AskOption(
                label = "先用内存实现，稍后再拆",
                tone = AskTone.Neutral,
                action = AskAction.Prompt("memory"),
                consequence = "+18 行 · 重启后需重新配对",
                recommended = false,
            ),
        ),
        index = 1,
        total = 2,
    )

private val nextAsk =
    Ask(
        question = "快照覆盖还是保留?",
        options = listOf(
            AskOption(
                label = "保留现有快照",
                tone = AskTone.Primary,
                action = AskAction.Prompt("keep"),
                consequence = "不覆盖已配对的主机",
                recommended = true,
            ),
            AskOption(
                label = "覆盖",
                tone = AskTone.Destructive,
                action = AskAction.Prompt("overwrite"),
                consequence = "当前快照会丢掉",
                recommended = false,
            ),
        ),
        index = 2,
        total = 2,
    )

private val diffThread =
    AgentThread(
        id = PLAYTEST_DIFF_ID,
        kind = AgentKind.Pane,
        hostId = PLAYTEST_HOST,
        projectKey = null,
        projectLabel = "试玩",
        title = "试玩 · 工具轨",
        agentType = "claude-code",
        status = AgentStatus.Idle,
        ask = null,
        summary = "ReconnectPolicy.kt",
        updatedEpochMs = 1_700_000_000_000L,
        paneId = null,
        acpSessionId = null,
    )

private val decideThread =
    AgentThread(
        id = PLAYTEST_DECIDE_ID,
        kind = AgentKind.Pane,
        hostId = PLAYTEST_HOST,
        projectKey = null,
        projectLabel = "试玩",
        title = "试玩 · 等你决定",
        agentType = "claude-code",
        status = AgentStatus.Blocked,
        ask = decideAsk,
        summary = "TokenStore",
        updatedEpochMs = 1_700_000_000_200L,
        paneId = null,
        acpSessionId = null,
    )

private val nextThread =
    AgentThread(
        id = PLAYTEST_NEXT_ID,
        kind = AgentKind.Pane,
        hostId = PLAYTEST_HOST,
        projectKey = null,
        projectLabel = "试玩",
        title = "试玩 · 下一条",
        agentType = "opencode",
        status = AgentStatus.Blocked,
        ask = nextAsk,
        summary = "快照",
        updatedEpochMs = 1_700_000_000_100L,
        paneId = null,
        acpSessionId = null,
    )

private val wowDiff =
    """
    diff --git a/shared/commonMain/ReconnectPolicy.kt b/shared/commonMain/ReconnectPolicy.kt
    --- a/shared/commonMain/ReconnectPolicy.kt
    +++ b/shared/commonMain/ReconnectPolicy.kt
    @@ -12,4 +12,5 @@
     class ReconnectPolicy(
    -    private val delayMs: Long = 1000
    +    private val base: Duration = 1.seconds,
    +    private val cap: Duration = 30.seconds,
     ) {
         fun next(n: Int) =
             minOf(base * (1 shl n), cap)
    """.trimIndent()

private val diffTimeline: List<TimelineItem> =
    listOf(
        TimelineItem.Mine("m1", "配对流程加上断线自动重连，token 过期就回到配对页。"),
        TimelineItem.Output("o1", "先看现有 CompanionClient，改重连策略，再跑测试。"),
        TimelineItem.Tool(
            "t1",
            AcpToolCall(
                toolCallId = "t1",
                title = "Read Pairing.kt",
                kind = "read",
                status = AcpToolStatus.Completed,
                summary = null,
                path = "shared/src/Pairing.kt",
                duration = "120ms",
            ),
        ),
        TimelineItem.Tool(
            "t2",
            AcpToolCall(
                toolCallId = "t2",
                title = "Edit ReconnectPolicy.kt",
                kind = "edit",
                status = AcpToolStatus.Completed,
                summary = wowDiff,
                path = "shared/commonMain/ReconnectPolicy.kt",
                duration = "1.2s",
            ),
        ),
        TimelineItem.Tool(
            "t3",
            AcpToolCall(
                toolCallId = "t3",
                title = "gradle test",
                kind = "execute",
                status = AcpToolStatus.Failed,
                summary = "tests 127/128\nReconnectTest",
                path = null,
                duration = "4.2s",
            ),
        ),
    )

private val decideTimeline: List<TimelineItem> =
    listOf(
        TimelineItem.Mine("d1", "TokenStore 要不要按平台拆开？"),
        TimelineItem.Output("d2", "两种做法都能编译。拆开之后重装不用重新配对。"),
    )

private val nextTimeline: List<TimelineItem> =
    listOf(TimelineItem.Output("n1", "下一条决定在这张卡后面露出一截。"))

private val noncursorAsk =
    Ask(
        question = "是否把 TokenStore 拆成两个 actual，并且这次只改共享层、不动 Android 和 iOS 的配对入口，等你在终端里确认后再继续？",
        options = emptyList(),
    )

private val noncursorThread =
    AgentThread(
        id = PLAYTEST_NONCURSOR_ID,
        kind = AgentKind.Pane,
        hostId = PLAYTEST_HOST,
        projectKey = null,
        projectLabel = "试玩",
        title = "试玩 · 去终端回答",
        agentType = "claude-code",
        status = AgentStatus.Blocked,
        ask = noncursorAsk,
        summary = "编号列表没有光标",
        updatedEpochMs = 1_700_000_000_050L,
        paneId = null,
        acpSessionId = null,
    )

private val noncursorTimeline: List<TimelineItem> =
    listOf(
        TimelineItem.Output(
            "nc1",
            "编号行没有光标，所以这张卡没有选项。问题留在对话里，选择块不重复。",
        ),
    )
