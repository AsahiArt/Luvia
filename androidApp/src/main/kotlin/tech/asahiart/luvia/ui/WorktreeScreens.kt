@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package tech.asahiart.luvia.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.asahiart.luvia.WorktreeEntry
import tech.asahiart.luvia.HostUhpState

@Composable
fun WorktreesSection(
    host: HostUiModel,
    state: HostUhpState,
    onRefresh: () -> Unit,
    onShowCreateChange: (Boolean) -> Unit,
    onCreateBranchChange: (String) -> Unit,
    onCreate: () -> Unit,
    onOpen: (String) -> Unit,
    onRemoveIdChange: (String?) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        host.shouldShowOfflineEmpty(state.connected) -> {
            UhpEmptyPane(title = "Worktrees", message = host.offlineEmptyMessage(), modifier = modifier)
        }
        !state.capabilities.worktreeList -> {
            UhpEmptyPane(title = "Worktrees", message = "Worktrees are not available on this host.", modifier = modifier)
        }
        else -> {
            WorktreeListPane(
                state = state,
                onRefresh = onRefresh,
                onShowCreateChange = onShowCreateChange,
                onCreateBranchChange = onCreateBranchChange,
                onCreate = onCreate,
                onOpen = onOpen,
                onRemoveIdChange = onRemoveIdChange,
                onRemove = onRemove,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun WorktreeListPane(
    state: HostUhpState,
    onRefresh: () -> Unit,
    onShowCreateChange: (Boolean) -> Unit,
    onCreateBranchChange: (String) -> Unit,
    onCreate: () -> Unit,
    onOpen: (String) -> Unit,
    onRemoveIdChange: (String?) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val worktrees = state.worktrees
    val canCreate = state.canMutate && state.capabilities.worktreeCreate
    val canOpen = state.canMutate && state.capabilities.worktreeOpen
    val canRemove = state.canMutate && state.capabilities.worktreeRemove
    PullToRefreshBox(
        isRefreshing = worktrees.loading,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Worktrees", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (canCreate) {
                        Button(
                            onClick = { onShowCreateChange(true) },
                            enabled = !worktrees.mutating,
                        ) { Text("Create") }
                    }
                }
            }
            worktrees.errorText?.let { error ->
                item { Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            if (worktrees.worktrees.isEmpty() && !worktrees.loading && worktrees.errorText == null) {
                item {
                    Text("No worktrees on this Host.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(worktrees.worktrees, key = { it.path }) { entry ->
                WorktreeRow(
                    entry = entry,
                    canOpen = canOpen && !worktrees.mutating,
                    canRemove = canRemove && !worktrees.mutating,
                    onOpen = { onOpen(entry.path) },
                    onRemove = { onRemoveIdChange(entry.path) },
                )
            }
        }
    }
    if (worktrees.showCreate) {
        CreateWorktreeSheet(
            branch = worktrees.createBranch,
            sending = worktrees.mutating,
            errorText = worktrees.errorText,
            onBranchChange = onCreateBranchChange,
            onDismiss = { onShowCreateChange(false) },
            onSubmit = onCreate,
        )
    }
    worktrees.removePath?.let { path ->
        AlertDialog(
            onDismissRequest = { onRemoveIdChange(null) },
            title = { Text("Remove worktree?") },
            text = { Text("Remove \"$path\" from this Host.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemoveIdChange(null)
                        onRemove(path)
                    },
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { onRemoveIdChange(null) }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun WorktreeRow(
    entry: WorktreeEntry,
    canOpen: Boolean,
    canRemove: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                entry.path,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = buildString {
                entry.branch?.takeIf { it.isNotBlank() }?.let { append(it) }
                if (entry.main) {
                    if (isNotEmpty()) append(" · ")
                    append("main")
                }
                entry.head?.takeIf { it.isNotBlank() }?.let {
                    if (isNotEmpty()) append(" · ")
                    append(it)
                }
            }
            if (detail.isNotBlank()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canOpen) {
                    FilledTonalButton(onClick = onOpen) { Text("Open") }
                }
                if (canRemove) {
                    TextButton(onClick = onRemove) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun CreateWorktreeSheet(
    branch: String,
    sending: Boolean,
    errorText: String?,
    onBranchChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { if (!sending) onDismiss() },
        sheetState = sheetState,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Create worktree", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            errorText?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = branch,
                onValueChange = onBranchChange,
                label = { Text("Branch") },
                enabled = !sending,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(
                enabled = branch.isNotBlank() && !sending,
                onClick = onSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Create") }
            Spacer(Modifier.height(16.dp))
        }
    }
}
