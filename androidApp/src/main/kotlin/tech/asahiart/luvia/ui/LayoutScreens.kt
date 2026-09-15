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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.asahiart.luvia.PaneListEntry
import tech.asahiart.luvia.WorkspaceSummary
import tech.asahiart.luvia.HostUhpState

@Composable
fun LayoutSection(
    host: HostUiModel,
    state: HostUhpState,
    onRefresh: () -> Unit,
    onFocusWorkspace: (Int) -> Unit,
    onCloseWorkspaceChange: (Int?) -> Unit,
    onCloseWorkspace: (Int) -> Unit,
    onFocusPane: (String) -> Unit,
    onClosePaneChange: (String?) -> Unit,
    onClosePane: (String) -> Unit,
    onRenamePaneChange: (String?, String) -> Unit,
    onRenamePane: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        !host.connected && !state.connected -> {
            UhpEmptyPane(title = "Layout", message = "Connect to this host", modifier = modifier)
        }
        !state.capabilities.workspaceList && !state.capabilities.paneList -> {
            UhpEmptyPane(title = "Layout", message = "Layout is not available on this host.", modifier = modifier)
        }
        else -> {
            LayoutPane(
                state = state,
                onRefresh = onRefresh,
                onFocusWorkspace = onFocusWorkspace,
                onCloseWorkspaceChange = onCloseWorkspaceChange,
                onCloseWorkspace = onCloseWorkspace,
                onFocusPane = onFocusPane,
                onClosePaneChange = onClosePaneChange,
                onClosePane = onClosePane,
                onRenamePaneChange = onRenamePaneChange,
                onRenamePane = onRenamePane,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun LayoutPane(
    state: HostUhpState,
    onRefresh: () -> Unit,
    onFocusWorkspace: (Int) -> Unit,
    onCloseWorkspaceChange: (Int?) -> Unit,
    onCloseWorkspace: (Int) -> Unit,
    onFocusPane: (String) -> Unit,
    onClosePaneChange: (String?) -> Unit,
    onClosePane: (String) -> Unit,
    onRenamePaneChange: (String?, String) -> Unit,
    onRenamePane: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val layout = state.layout
    val canFocusWorkspace = state.canMutate && state.capabilities.workspaceFocus
    val canCloseWorkspace = state.canMutate && state.capabilities.workspaceClose
    val canFocusPane = state.canMutate && state.capabilities.paneFocus
    val canClosePane = state.canMutate && state.capabilities.paneClose
    val canRenamePane = state.canMutate && state.capabilities.paneRename
    PullToRefreshBox(
        isRefreshing = layout.loading,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Text("Workspaces", style = MaterialTheme.typography.titleSmall) }
            layout.errorText?.let { error ->
                item { Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            if (layout.workspaces.isEmpty() && !layout.loading) {
                item {
                    Text("No workspaces in the snapshot.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(layout.workspaces, key = { "ws-${it.index}" }) { workspace ->
                WorkspaceRow(
                    workspace = workspace,
                    canFocus = canFocusWorkspace && !layout.mutating,
                    canClose = canCloseWorkspace && !layout.mutating,
                    onFocus = { onFocusWorkspace(workspace.index) },
                    onClose = { onCloseWorkspaceChange(workspace.index) },
                )
            }
            item {
                Text(
                    "Panes",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (layout.panes.isEmpty() && !layout.loading) {
                item {
                    Text("No panes listed.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(layout.panes, key = { it.pane }) { pane ->
                PaneRow(
                    pane = pane,
                    canFocus = canFocusPane && !layout.mutating,
                    canClose = canClosePane && !layout.mutating,
                    canRename = canRenamePane && !layout.mutating,
                    onFocus = { onFocusPane(pane.pane) },
                    onClose = { onClosePaneChange(pane.pane) },
                    onRename = { onRenamePaneChange(pane.pane, pane.agent.orEmpty()) },
                )
            }
        }
    }
    layout.closeWorkspace?.let { index ->
        val name = layout.workspaces.firstOrNull { it.index == index }?.name ?: index.toString()
        AlertDialog(
            onDismissRequest = { onCloseWorkspaceChange(null) },
            title = { Text("Close workspace?") },
            text = { Text("Close \"$name\" on this Host.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onCloseWorkspaceChange(null)
                        onCloseWorkspace(index)
                    },
                ) { Text("Close") }
            },
            dismissButton = {
                TextButton(onClick = { onCloseWorkspaceChange(null) }) { Text("Cancel") }
            },
        )
    }
    layout.closePane?.let { pane ->
        AlertDialog(
            onDismissRequest = { onClosePaneChange(null) },
            title = { Text("Close pane?") },
            text = { Text("Close pane $pane on this Host.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClosePaneChange(null)
                        onClosePane(pane)
                    },
                ) { Text("Close") }
            },
            dismissButton = {
                TextButton(onClick = { onClosePaneChange(null) }) { Text("Cancel") }
            },
        )
    }
    layout.renamePane?.let { pane ->
        AlertDialog(
            onDismissRequest = { onRenamePaneChange(null, "") },
            title = { Text("Rename pane") },
            text = {
                OutlinedTextField(
                    value = layout.renameDraft,
                    onValueChange = { onRenamePaneChange(pane, it) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = layout.renameDraft.isNotBlank() && !layout.mutating,
                    onClick = onRenamePane,
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { onRenamePaneChange(null, "") }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun WorkspaceRow(
    workspace: WorkspaceSummary,
    canFocus: Boolean,
    canClose: Boolean,
    onFocus: () -> Unit,
    onClose: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    workspace.name.ifBlank { "Workspace ${workspace.index}" },
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val badges = buildList {
                    if (workspace.active) add("Active")
                    if (workspace.pinned) add("Pinned")
                }
                if (badges.isNotEmpty()) {
                    Text(
                        badges.joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val place = listOfNotNull(workspace.cwd, workspace.branch).joinToString(" · ")
            if (place.isNotBlank()) {
                Text(
                    place,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canFocus && !workspace.active) {
                    FilledTonalButton(onClick = onFocus) { Text("Focus") }
                }
                if (canClose) {
                    TextButton(onClick = onClose) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun PaneRow(
    pane: PaneListEntry,
    canFocus: Boolean,
    canClose: Boolean,
    canRename: Boolean,
    onFocus: () -> Unit,
    onClose: () -> Unit,
    onRename: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    pane.agent?.takeIf { it.isNotBlank() } ?: pane.pane,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (pane.focused) {
                    Text(
                        "Focused",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val detail = listOfNotNull(
                pane.pane.takeIf { it != pane.agent },
                pane.cwd,
            ).joinToString(" · ")
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canFocus && !pane.focused) {
                    FilledTonalButton(onClick = onFocus) { Text("Focus") }
                }
                if (canRename) {
                    TextButton(onClick = onRename) { Text("Rename") }
                }
                if (canClose) {
                    TextButton(onClick = onClose) { Text("Close") }
                }
            }
        }
    }
}
