package tech.asahiart.luvia

public fun Failure.userMessage(): String {
    if (this is Failure.ProtocolError &&
        reason.contains("pairing code is for a different device key")
    ) {
        return "This pairing code is for a different device key. Run the command shown in this app on the host, then scan the QR it prints — not a code generated for another phone."
    }
    val raw =
        when (this) {
            is Failure.ProtocolError -> reason
            is Failure.InvalidRequest -> message
            is Failure.InvalidParams -> message
            is Failure.Forbidden -> message
            is Failure.NotFound -> message
            is Failure.Transport -> reason
            is Failure.Bridge -> reason
            is Failure.Remote -> message
            is Failure.Frame -> reason
            is Failure.Closed -> "Not connected to this host."
            is Failure.ControlConflict -> message
            is Failure.StaleServer -> message
            is Failure.StaleRoute -> message
            is Failure.TerminalGone ->
                "This pane is no longer available. Pick a live pane below, or refresh."
            is Failure.ResyncRequired -> message
            is Failure.RevisionConflict -> message
            is Failure.ContentRevisionConflict ->
                "The agent screen changed. Refresh and send the keys again."
            is Failure.AgentPromptBusy ->
                "The agent is still handling a previous message. Wait for it to finish."
            is Failure.FrameTooLarge -> message
            is Failure.ServerBusy -> message
            is Failure.UnknownMajor -> "The host speaks an unsupported protocol ($name $major)."
            is Failure.CapabilityMissing -> "The host does not support $method."
            is Failure.IndeterminateMutation ->
                "The host may already have applied this change. Do not retry automatically."
        }
    return raw.sanitizeHostError()
}

public fun Failure.pairingMessage(): String {
    val reason = (this as? Failure.ProtocolError)?.reason ?: return userMessage()
    val draftStays = "The draft is still valid."
    return when {
        reason.contains("must start with luvia1") ->
            "This is not a luvia1: pairing code. Scan again or paste a different code. $draftStays"
        reason.contains("different device key") ->
            "This pairing code is for a different Device key. Run the command shown in this app on the Host, then scan the QR it prints. $draftStays"
        reason.contains("no host key fingerprints") ->
            "This pairing code has an empty host-key (hk) set and cannot be trusted. Scan again or paste a different code. $draftStays"
        reason.startsWith("pairing code") ->
            "This pairing code is malformed. Scan again or paste a different code. $draftStays"
        else -> userMessage()
    }
}

public fun Failure.connectMessage(): String {
    val raw =
        when (this) {
            is Failure.Transport -> reason
            is Failure.ProtocolError -> reason
            is Failure.Bridge -> reason
            else -> return userMessage()
        }
    val lower = raw.lowercase()
    return when {
        lower.contains("host key mismatch") || lower.contains("re-pair") ->
            "Host key changed. Re-pair this Host."
        lower.contains("public-key authentication") ||
            lower.contains("authentication failed") ||
            lower.contains("permission denied") ->
            "SSH refused. Check the Grant on the Host."
        lower.contains("incomplete host endpoint") ||
            lower.contains("connect timeout") ||
            lower.contains("no route") ||
            lower.contains("connection refused") ||
            lower.contains("ssh transport failure") ||
            lower.contains("disconnected") ->
            "No address reachable. Check network or Tailscale."
        else -> userMessage()
    }
}

public fun Failure.ControlConflict.conflictMessage(): String {
    val base = message.trim().ifBlank { "Another client holds Terminal control." }
    return if (base.contains("Observe still works", ignoreCase = true)) {
        base
    } else {
        "$base Observe still works."
    }
}

public fun Failure.isLostMutation(): Boolean =
    this is Failure.IndeterminateMutation ||
        this is Failure.Transport ||
        this is Failure.Bridge ||
        this is Failure.Closed

internal fun String.sanitizeHostError(): String =
    when {
        contains("not a git repository", ignoreCase = true) ->
            "This workspace is not a git repository. Focus a project workspace in Layout, then refresh."
        contains("terminal identity no longer exists", ignoreCase = true) ->
            "This pane is no longer available. Pick a live pane below, or refresh."
        else -> this
    }
