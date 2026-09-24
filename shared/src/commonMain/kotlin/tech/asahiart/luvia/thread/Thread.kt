package tech.asahiart.luvia.thread

import tech.asahiart.luvia.AcpPermissionKind
import tech.asahiart.luvia.AcpRunState
import tech.asahiart.luvia.AcpState
import tech.asahiart.luvia.AcpTranscriptItem
import tech.asahiart.luvia.AgentKey
import tech.asahiart.luvia.AgentKind
import tech.asahiart.luvia.AgentStatus
import tech.asahiart.luvia.AgentSummary
import tech.asahiart.luvia.HostUhpState
import tech.asahiart.luvia.command.SlashCommand
import tech.asahiart.luvia.command.SlashCommandCatalog
import tech.asahiart.luvia.stripAnsi

/** A pane Agent or an ACP session, shown the same way. */
public data class Thread(
    public val id: String,
    public val kind: AgentKind,
    public val hostId: String,
    public val projectKey: String?,
    public val projectLabel: String?,
    public val title: String,
    public val agentType: String?,
    public val status: AgentStatus,
    public val ask: Ask?,
    public val summary: String?,
    public val updatedEpochMs: Long?,
    public val paneId: String? = null,
    public val acpSessionId: String? = null,
) {
    public val needsYou: Boolean get() = ask != null

    public fun commands(): List<SlashCommand> = SlashCommandCatalog.forAgent(agentType ?: title)
}

public enum class AskTone { Primary, Neutral, Destructive }

public sealed class AskAction {
    /** Delivered as one Agent prompt. */
    public data class Prompt(public val text: String) : AskAction()

    public data class Keys(public val keys: List<AgentKey>) : AskAction()

    public data class AcpOption(public val requestId: String, public val optionId: String) : AskAction()
}

public data class AskOption(
    public val label: String,
    public val tone: AskTone,
    public val action: AskAction,
)

/** A decision an Agent is waiting on. [question] is null when nothing cached explains it. */
public data class Ask(
    public val question: String?,
    public val options: List<AskOption>,
)

public fun HostUhpState.threads(hostId: String): List<Thread> {
    val panes = agents.map { it.toThread(hostId, cachedTranscriptFor(it.paneId)) }
    val acpThread = acp.takeIf { it.open }?.toThread(hostId) ?: return panes
    return panes + acpThread
}

private fun HostUhpState.cachedTranscriptFor(paneId: String): String? =
    agentDetail.transcript?.takeIf { agentDetail.paneId == paneId }?.text

internal fun AgentSummary.toThread(hostId: String, transcript: String?): Thread {
    val last = lastNonEmptyLine(transcript?.let(::stripAnsi))
    return Thread(
        id = paneId,
        kind = AgentKind.Pane,
        hostId = hostId,
        projectKey = workspaceId ?: cwd,
        projectLabel = workspaceName ?: project ?: workspace,
        title = name?.takeIf { it.isNotBlank() } ?: agent?.takeIf { it.isNotBlank() } ?: "Agent",
        agentType = agent,
        status = status,
        ask = if (status == AgentStatus.Blocked) paneAsk(last) else null,
        summary = last,
        updatedEpochMs = null,
        paneId = paneId,
    )
}

internal fun paneAsk(question: String?): Ask =
    Ask(
        question = question,
        options = listOf(
            AskOption("Yes", AskTone.Primary, AskAction.Prompt("y")),
            AskOption("No", AskTone.Neutral, AskAction.Prompt("n")),
            AskOption("Enter", AskTone.Neutral, AskAction.Keys(listOf(AgentKey.ENTER))),
            AskOption("Esc", AskTone.Neutral, AskAction.Keys(listOf(AgentKey.ESC))),
        ),
    )

internal fun AcpState.toThread(hostId: String): Thread {
    val request = permission?.takeIf { run == AcpRunState.AwaitingPermission }
    val lastMessage = transcript.lastOrNull { it is AcpTranscriptItem.Message } as AcpTranscriptItem.Message?
    val cwd = info?.cwd?.takeIf { it.isNotBlank() }
    return Thread(
        id = info?.sessionId?.let { "acp:$it" } ?: "acp",
        kind = AgentKind.Acp,
        hostId = hostId,
        projectKey = cwd,
        projectLabel = cwd?.trimEnd('/')?.substringAfterLast('/'),
        title = info?.agentName?.takeIf { it.isNotBlank() } ?: "Agent",
        agentType = info?.agentId,
        status = run.toAgentStatus(),
        ask = request?.let { req ->
            Ask(
                question = req.title,
                options = req.options.map { option ->
                    AskOption(option.name, option.kind.tone(), AskAction.AcpOption(req.requestId, option.optionId))
                },
            )
        },
        summary = lastNonEmptyLine(lastMessage?.text),
        updatedEpochMs = null,
        acpSessionId = info?.sessionId,
    )
}

private fun AcpPermissionKind.tone(): AskTone =
    when (this) {
        AcpPermissionKind.AllowOnce, AcpPermissionKind.AllowAlways -> AskTone.Primary
        AcpPermissionKind.RejectOnce, AcpPermissionKind.RejectAlways -> AskTone.Destructive
        AcpPermissionKind.Other -> AskTone.Neutral
    }

internal fun AcpRunState.toAgentStatus(): AgentStatus =
    when (this) {
        AcpRunState.AwaitingPermission -> AgentStatus.Blocked
        AcpRunState.Working, AcpRunState.Starting -> AgentStatus.Working
        AcpRunState.Ready, AcpRunState.Idle -> AgentStatus.Idle
        AcpRunState.Exited -> AgentStatus.Done
    }

internal fun lastNonEmptyLine(text: String?): String? =
    text?.lineSequence()?.map { it.trim() }?.lastOrNull { it.isNotBlank() }
