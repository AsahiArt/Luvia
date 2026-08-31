package tech.asahiart.luvia.ui

import androidx.compose.runtime.Immutable

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
    val updatedAt: String? = null,
)

enum class ConnectionBadge { Live, Connecting, Stale, Offline }
enum class HostSection { Overview, Agents, Tasks, Terminal }

@Immutable
data class TerminalUiModel(
    val title: String,
    val text: String,
    val isAnsi: Boolean,
    val isTruncated: Boolean,
    val control: TerminalControl,
)

enum class TerminalControl { Observing, Requesting, Controlling, Conflict }
