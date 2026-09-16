package tech.asahiart.luvia

public data class AcpAgentKind(
    public val id: String,
    public val name: String,
    public val command: String,
    public val available: Boolean,
)

public data class AcpSessionInfo(
    public val sessionId: String,
    public val agentId: String,
    public val agentName: String,
    public val protocolVersion: Int,
    public val cwd: String,
)

public enum class AcpPermissionKind {
    AllowOnce,
    AllowAlways,
    RejectOnce,
    RejectAlways,
    Other,
}

public data class AcpPermissionOption(
    public val optionId: String,
    public val name: String,
    public val kind: AcpPermissionKind,
)

public data class AcpPermissionRequest(
    public val requestId: String,
    public val title: String,
    public val description: String?,
    public val toolTitle: String?,
    public val toolKind: String?,
    public val options: List<AcpPermissionOption>,
)

public enum class AcpToolStatus {
    Pending,
    InProgress,
    Completed,
    Failed,
    Unknown,
}

public data class AcpToolCall(
    public val toolCallId: String,
    public val title: String,
    public val kind: String?,
    public val status: AcpToolStatus,
    public val summary: String?,
)

public data class AcpToolCallUpdate(
    public val toolCallId: String,
    public val title: String?,
    public val kind: String?,
    public val status: AcpToolStatus?,
    public val summary: String?,
)

public enum class AcpPlanStatus {
    Pending,
    InProgress,
    Completed,
}

public data class AcpPlanEntry(
    public val content: String,
    public val priority: String?,
    public val status: AcpPlanStatus,
)

public enum class AcpStopReason {
    EndTurn,
    MaxTokens,
    MaxTurnRequests,
    Refusal,
    Cancelled,
    Unknown,
}

public sealed class AcpEvent {
    public data class AgentMessage(public val text: String) : AcpEvent()

    public data class AgentThought(public val text: String) : AcpEvent()

    public data class UserMessage(public val text: String) : AcpEvent()

    public data class ToolCall(public val call: AcpToolCall) : AcpEvent()

    public data class ToolCallUpdate(public val update: AcpToolCallUpdate) : AcpEvent()

    public data class Plan(public val entries: List<AcpPlanEntry>) : AcpEvent()

    public data class Permission(public val request: AcpPermissionRequest) : AcpEvent()

    public data class TurnEnded(public val stopReason: AcpStopReason) : AcpEvent()

    public data class Exited(public val code: Int?, public val message: String) : AcpEvent()

    public data class Failed(public val failure: Failure) : AcpEvent()
}
