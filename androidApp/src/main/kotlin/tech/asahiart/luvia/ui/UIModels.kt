package tech.asahiart.luvia.ui

import androidx.compose.runtime.Immutable
import tech.asahiart.luvia.HostSection

@Immutable
data class HostUiModel(
    val id: String,
    val name: String,
    val address: String,
    val sessionName: String?,
    val connection: ConnectionBadge,
    val workingAgents: Int = 0,
    val blockedAgents: Int = 0,
    val completedAgents: Int = 0,
    val activeTask: String? = null,
    val lastUpdatedEpochMs: Long = 0L,
    val errorMessage: String? = null,
    val isObserver: Boolean = false,
    val connected: Boolean = false,
    val hasSnapshot: Boolean = false,
    val firstBlockedPaneId: String? = null,
)

enum class ConnectionBadge { Live, Connecting, Stale, Offline }

internal val HostSection.isPrimaryTab: Boolean
    get() =
        this == HostSection.Agents ||
            this == HostSection.Review ||
            this == HostSection.Tasks ||
            this == HostSection.Terminal

@Immutable
data class TerminalPaneChoice(
    val paneId: String,
    val title: String,
    val cwd: String? = null,
)

@Immutable
data class TerminalUiModel(
    val title: String,
    val text: String,
    val isAnsi: Boolean,
    val isTruncated: Boolean,
    val control: TerminalControl,
    val canControl: Boolean = true,
    val errorText: String? = null,
    val conflictMessage: String? = null,
    val paneId: String? = null,
    val panes: List<TerminalPaneChoice> = emptyList(),
)

enum class TerminalControl { Observing, Requesting, Controlling, Conflict }
