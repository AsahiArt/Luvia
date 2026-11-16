package tech.asahiart.luvia

/**
 * The fields [controlInputIsCurrent] reads before a terminal write.
 * A matching snapshot refresh keeps the same ticket. A pane, generation, terminal, role, or epoch
 * change does not.
 */
public data class TerminalInputTicket(
    val held: Boolean,
    val observing: Boolean,
    val controller: Boolean,
    val selectedHostId: String?,
    val observingHostId: String?,
    val locatorPaneId: String?,
    val locatorGeneration: String?,
    val locatorTerminalId: String?,
    val observingPaneId: String?,
    val observingGeneration: String?,
    val observingTerminalId: String?,
    val acquireEpoch: Long,
    val installedEpoch: Long,
)

public fun terminalInputCurrent(ticket: TerminalInputTicket): Boolean =
    ticket.held &&
        ticket.observing &&
        ticket.controller &&
        !ticket.selectedHostId.isNullOrBlank() &&
        ticket.selectedHostId == ticket.observingHostId &&
        !ticket.locatorPaneId.isNullOrBlank() &&
        ticket.locatorPaneId == ticket.observingPaneId &&
        !ticket.locatorGeneration.isNullOrBlank() &&
        ticket.locatorGeneration == ticket.observingGeneration &&
        !ticket.locatorTerminalId.isNullOrBlank() &&
        ticket.locatorTerminalId == ticket.observingTerminalId &&
        ticket.acquireEpoch == ticket.installedEpoch
