@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package tech.asahiart.luvia.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import tech.asahiart.luvia.Automation
import tech.asahiart.luvia.AutomationHealthResult

@Composable
fun AutomationsSection(
    host: HostUiModel,
    state: HostUhpUiState,
    onRefresh: () -> Unit,
    onEnable: (String) -> Unit,
    onDisable: (String) -> Unit,
    onRun: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        !host.connected && !state.connected -> {
            UhpEmptyPane(title = "Automations", message = "Connect to this host", modifier = modifier)
        }
        !state.capabilities.automationList -> {
            UhpEmptyPane(title = "Automations", message = "Automations are not available on this host.", modifier = modifier)
        }
        else -> {
            AutomationListPane(
                state = state,
                onRefresh = onRefresh,
                onEnable = onEnable,
                onDisable = onDisable,
                onRun = onRun,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun AutomationListPane(
    state: HostUhpUiState,
    onRefresh: () -> Unit,
    onEnable: (String) -> Unit,
    onDisable: (String) -> Unit,
    onRun: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val automations = state.automations
    val canEnable = state.canMutate && state.capabilities.automationEnable
    val canDisable = state.canMutate && state.capabilities.automationDisable
    val canRun = state.canMutate && state.capabilities.automationRun
    PullToRefreshBox(
        isRefreshing = automations.loading,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text("Automations", style = MaterialTheme.typography.titleSmall)
            }
            automations.health?.let { health ->
                item { AutomationHealthLine(health) }
            }
            automations.errorText?.let { error ->
                item { Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            if (automations.automations.isEmpty() && !automations.loading) {
                item {
                    Text("No automations on this Host.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(automations.automations, key = { it.id }) { automation ->
                AutomationRow(
                    automation = automation,
                    canEnable = canEnable && !automations.mutating,
                    canDisable = canDisable && !automations.mutating,
                    canRun = canRun && !automations.mutating,
                    onEnable = { onEnable(automation.id) },
                    onDisable = { onDisable(automation.id) },
                    onRun = { onRun(automation.id) },
                )
            }
        }
    }
}

@Composable
private fun AutomationHealthLine(health: AutomationHealthResult) {
    val summary = health.summary
    Text(
        buildString {
            append(summary.definitions)
            append(" defined · ")
            append(summary.enabled)
            append(" enabled · ")
            append(summary.scheduled)
            append(" scheduled · ")
            append(summary.running)
            append(" running")
            if (summary.review > 0) {
                append(" · ")
                append(summary.review)
                append(" review")
            }
            if (summary.failed > 0) {
                append(" · ")
                append(summary.failed)
                append(" failed")
            }
            formatEpoch(summary.nextRunAt)?.let {
                append(" · next ")
                append(it)
            }
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AutomationRow(
    automation: Automation,
    canEnable: Boolean,
    canDisable: Boolean,
    canRun: Boolean,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onRun: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    automation.name.ifBlank { automation.id },
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (automation.enabled) "Enabled" else "Disabled",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Next ${formatEpoch(automation.nextRunAt) ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (automation.enabled && canDisable) {
                    FilledTonalButton(onClick = onDisable) { Text("Disable") }
                } else if (!automation.enabled && canEnable) {
                    FilledTonalButton(onClick = onEnable) { Text("Enable") }
                }
                if (canRun) {
                    TextButton(onClick = onRun) { Text("Run") }
                }
            }
        }
    }
}

private val utcFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)

private fun formatEpoch(value: Long?): String? {
    if (value == null || value <= 0L) return null
    val instant = if (value > 9_999_999_999L) Instant.ofEpochMilli(value) else Instant.ofEpochSecond(value)
    return utcFormatter.format(instant) + " UTC"
}
