package tech.asahiart.luvia.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.asahiart.luvia.AgentEntry
import tech.asahiart.luvia.AgentKind
import tech.asahiart.luvia.AgentStatus
import tech.asahiart.luvia.ui.theme.LuviaTheme

@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = color,
        modifier = modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
fun ConnectionStatusPill(connection: ConnectionBadge, modifier: Modifier = Modifier) {
    StatusPill(text = connection.label(), color = connection.color(), modifier = modifier)
}

@Composable
fun AgentStatusPill(status: AgentStatus, modifier: Modifier = Modifier) {
    StatusPill(text = status.name, color = status.color(), modifier = modifier)
}

@Composable
fun TypeBadge(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun HostAvatar(
    name: String,
    connection: ConnectionBadge,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.BottomEnd) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = CircleShape,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    name.trim().take(1).ifEmpty { "H" }.uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Surface(
            color = connection.color(),
            shape = CircleShape,
            modifier = Modifier
                .size(12.dp)
                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
        ) {}
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(
                message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (action != null && onAction != null) {
                Button(onClick = onAction) { Text(action) }
            }
        }
    }
}

@Composable
fun AgentRow(
    entry: AgentEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    nowEpochMs: Long = 0L,
) {
    val statusColor = entry.status.color()
    val blocked = entry.status == AgentStatus.Blocked
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (blocked) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(88.dp)
                    .background(statusColor),
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        entry.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (blocked) {
                        StatusPill("Blocked", LuviaTheme.extended.agentBlocked)
                    }
                    TypeBadge(
                        label = if (entry.kind == AgentKind.Acp) "ACP" else "Pane",
                        color = if (entry.kind == AgentKind.Acp) {
                            LuviaTheme.extended.connecting
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (!entry.projectLabel.isNullOrBlank()) {
                    Text(
                        entry.projectLabel.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!entry.lastLine.isNullOrBlank()) {
                        Text(
                            entry.lastLine.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    val age = entry.updatedEpochMs?.let { relativeAge(it, nowEpochMs) }
                    if (age != null) {
                        Text(
                            age,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun KeyChip(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = modifier) { Text(label) }
}

@Composable
internal fun AgentStatus.color(): Color {
    val ext = LuviaTheme.extended
    return when (this) {
        AgentStatus.Blocked -> ext.agentBlocked
        AgentStatus.Working -> ext.agentWorking
        AgentStatus.Idle, AgentStatus.Done -> ext.agentIdle
        AgentStatus.Unknown -> ext.agentUnknown
    }
}

@Composable
internal fun ConnectionBadge.color(): Color {
    val ext = LuviaTheme.extended
    return when (this) {
        ConnectionBadge.Live -> ext.live
        ConnectionBadge.Connecting -> ext.connecting
        ConnectionBadge.Stale -> ext.stale
        ConnectionBadge.Offline -> ext.offline
    }
}

internal fun ConnectionBadge.label(): String = when (this) {
    ConnectionBadge.Live -> "Live"
    ConnectionBadge.Connecting -> "Connecting"
    ConnectionBadge.Stale -> "Stale"
    ConnectionBadge.Offline -> "Offline"
}

internal fun relativeAge(epochMs: Long, nowEpochMs: Long): String? {
    if (epochMs <= 0L || nowEpochMs <= 0L) return null
    val seconds = ((nowEpochMs - epochMs) / 1000L).coerceAtLeast(0L)
    return when {
        seconds < 60L -> "${seconds}s"
        seconds < 3600L -> "${seconds / 60L}m"
        seconds < 86_400L -> "${seconds / 3600L}h"
        else -> "${seconds / 86_400L}d"
    }
}

internal fun HostUiModel.shouldShowOfflineEmpty(uhpConnected: Boolean): Boolean =
    !uhpConnected && !hasSnapshot

internal fun HostUiModel.offlineEmptyMessage(): String =
    if (connection == ConnectionBadge.Connecting) "Connecting…" else "This Host has not connected yet."

