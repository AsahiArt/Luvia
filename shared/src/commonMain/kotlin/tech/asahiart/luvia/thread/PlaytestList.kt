package tech.asahiart.luvia.thread

import tech.asahiart.luvia.AcpToolCall
import tech.asahiart.luvia.AcpToolStatus
import tech.asahiart.luvia.AgentKind
import tech.asahiart.luvia.AgentStatus

/**
 * Debug-only session rows so a playtester can photograph distinct avatars,
 * a recent-step line, and the「等你决定」stack without a live host that
 * happens to be running those agents. The phone must call these only from
 * a debug build. Release must not append them to the list.
 */
public const val PLAYTEST_LIST_CODEX: String = "playtest-list-codex"

public const val PLAYTEST_LIST_CLAUDE: String = "playtest-list-claude"

public const val PLAYTEST_LIST_OPENCODE: String = "playtest-list-opencode"

public const val PLAYTEST_LIST_GEMINI: String = "playtest-list-gemini"

public fun isPlaytestListThread(id: String): Boolean =
    id == PLAYTEST_LIST_CODEX ||
        id == PLAYTEST_LIST_CLAUDE ||
        id == PLAYTEST_LIST_OPENCODE ||
        id == PLAYTEST_LIST_GEMINI

public fun playtestListThreads(nowEpochMs: Long): List<AgentThread> = listOf(
    listThread(
        id = PLAYTEST_LIST_CODEX,
        title = "修复 UHP 断线重连",
        agent = "codex",
        status = AgentStatus.Working,
        ask = null,
        summary = null,
        updated = nowEpochMs - 30_000L,
    ),
    listThread(
        id = PLAYTEST_LIST_CLAUDE,
        title = "重构 Pairing 模块",
        agent = "claude-code",
        status = AgentStatus.Blocked,
        ask = claudeAsk,
        summary = "要把 TokenStore 拆成两个 actual 吗？",
        updated = nowEpochMs - 2 * 60_000L,
    ),
    listThread(
        id = PLAYTEST_LIST_OPENCODE,
        title = "终端 ANSI 解析测试",
        agent = "opencode",
        status = AgentStatus.Blocked,
        ask = opencodeAsk,
        summary = "发现 3 个快照不一致，覆盖还是保留？",
        updated = nowEpochMs - 9 * 60_000L,
    ),
    listThread(
        id = PLAYTEST_LIST_GEMINI,
        title = "迁移 Room → SQLDelight",
        agent = "gemini",
        status = AgentStatus.Done,
        ask = null,
        summary = "12 个文件变更，等你 review。",
        updated = nowEpochMs - 60 * 60_000L,
    ),
)

public fun playtestListTrails(): List<ThreadTrail> = listOf(
    ThreadTrail(PLAYTEST_LIST_CODEX, trailThumb(codexCalls)!!),
    ThreadTrail(PLAYTEST_LIST_CLAUDE, trailThumb(claudeCalls)!!),
    ThreadTrail(PLAYTEST_LIST_OPENCODE, trailThumb(opencodeCalls)!!),
    ThreadTrail(PLAYTEST_LIST_GEMINI, trailThumb(geminiCalls, limit = MAX_LIST_DOTS)!!),
)

private const val PLAYTEST_LIST_HOST: String = "playtest"

private val claudeAsk =
    Ask(
        question = "把 TokenStore 拆成 Keychain / Keystore 两个 actual？",
        options = listOf(
            AskOption(
                label = "拆成两个",
                tone = AskTone.Primary,
                action = AskAction.Prompt("split"),
                recommended = true,
            ),
            AskOption(
                label = "先内存",
                tone = AskTone.Neutral,
                action = AskAction.Prompt("memory"),
            ),
        ),
        index = 1,
        total = 2,
    )

private val opencodeAsk =
    Ask(
        question = "发现 3 个快照不一致，覆盖还是保留？",
        options = listOf(
            AskOption(
                label = "保留",
                tone = AskTone.Primary,
                action = AskAction.Prompt("keep"),
                recommended = true,
            ),
            AskOption(
                label = "覆盖",
                tone = AskTone.Destructive,
                action = AskAction.Prompt("overwrite"),
            ),
        ),
        index = 2,
        total = 2,
    )

private val codexCalls = listOf(
    AcpToolCall("c1", "note", "other", AcpToolStatus.Completed, null),
    AcpToolCall("c2", "plan", "other", AcpToolStatus.Completed, null),
    AcpToolCall("c3", "Read ReconnectPolicy.kt", "read", AcpToolStatus.Completed, null),
    AcpToolCall("c4", "Read Pairing.kt", "read", AcpToolStatus.Completed, null),
    AcpToolCall("c5", "allTests", "shell", AcpToolStatus.Failed, "96/128"),
)

private val claudeCalls = listOf(
    AcpToolCall("k1", "think", "other", AcpToolStatus.Completed, null),
    AcpToolCall("k2", "Read TokenStore.kt", "read", AcpToolStatus.Completed, null),
    AcpToolCall("k3", "Read Keychain.kt", "read", AcpToolStatus.Completed, null),
    AcpToolCall("k4", "gradle test", "shell", AcpToolStatus.Failed, "1 failed"),
)

private val opencodeCalls = listOf(
    AcpToolCall("o1", "note", "other", AcpToolStatus.Completed, null),
    AcpToolCall("o2", "plan", "other", AcpToolStatus.Completed, null),
    AcpToolCall("o3", "ansi snapshot", "shell", AcpToolStatus.Failed, "3 mismatches"),
)

private val geminiCalls = listOf(
    AcpToolCall("g0", "note", "other", AcpToolStatus.Completed, null),
    AcpToolCall("g1", "Read Room.kt", "read", AcpToolStatus.Completed, null),
    AcpToolCall("g2", "Read Schema.kt", "read", AcpToolStatus.Completed, null),
    AcpToolCall("g3", "Read Delight.kt", "read", AcpToolStatus.Completed, null),
    AcpToolCall("g4", "Read Migration.kt", "read", AcpToolStatus.Completed, null),
    AcpToolCall("g5", "Edit Schema.kt", "edit", AcpToolStatus.Completed, null),
)

private fun listThread(
    id: String,
    title: String,
    agent: String,
    status: AgentStatus,
    ask: Ask?,
    summary: String?,
    updated: Long,
) = AgentThread(
    id = id,
    kind = AgentKind.Pane,
    hostId = PLAYTEST_LIST_HOST,
    projectKey = null,
    projectLabel = "试玩",
    title = title,
    agentType = agent,
    status = status,
    ask = ask,
    summary = summary,
    updatedEpochMs = updated,
    paneId = null,
    acpSessionId = null,
)
