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
import androidx.compose.ui.unit.dp
import tech.asahiart.luvia.TaskSummary
import tech.asahiart.luvia.HostUhpState
import tech.asahiart.luvia.TaskStatus
import tech.asahiart.luvia.isRetryable
import tech.asahiart.luvia.UnconfirmedKind

@Composable
fun TasksSection(
    host: HostUiModel,
    state: HostUhpState,
    onRefresh: () -> Unit,
    onAddTask: (title: String, paths: List<String>) -> Unit,
    onCompleteTask: (String) -> Unit,
    onClaimTask: (String) -> Unit = {},
    onDeleteTask: (String) -> Unit = {},
    onRetryTask: (String) -> Unit = {},
    onCheckUnconfirmed: () -> Unit,
    onShowAddChange: (Boolean) -> Unit,
    onCompleteIdChange: (String?) -> Unit,
    onDeleteIdChange: (String?) -> Unit = {},
    onAddDraftChange: (String, String) -> Unit,
    onSelectWorkspace: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    when {
        !state.connected -> {
            UhpEmptyPane(title = "Tasks", message = "Connect to this host", modifier = modifier)
        }
        !state.capabilities.taskList -> {
            UhpEmptyPane(title = "Tasks", message = "Tasks are not available on this host.", modifier = modifier)
        }
        state.needsProjectPick() -> {
            Column(modifier.fillMaxSize().padding(16.dp)) {
                ProjectChips(state = state, onSelect = onSelectWorkspace)
                UhpEmptyPane(
                    title = "Tasks",
                    message = "Select a project.",
                    modifier = Modifier.weight(1f),
                )
            }
        }
        else -> {
            TaskListPane(
                state = state,
                onRefresh = onRefresh,
                onAddTask = onAddTask,
                onCompleteTask = onCompleteTask,
                onClaimTask = onClaimTask,
                onDeleteTask = onDeleteTask,
                onRetryTask = onRetryTask,
                onCheckUnconfirmed = onCheckUnconfirmed,
                onShowAddChange = onShowAddChange,
                onCompleteIdChange = onCompleteIdChange,
                onDeleteIdChange = onDeleteIdChange,
                onAddDraftChange = onAddDraftChange,
                onSelectWorkspace = onSelectWorkspace,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun TaskListPane(
    state: HostUhpState,
    onRefresh: () -> Unit,
    onAddTask: (title: String, paths: List<String>) -> Unit,
    onCompleteTask: (String) -> Unit,
    onClaimTask: (String) -> Unit,
    onDeleteTask: (String) -> Unit,
    onRetryTask: (String) -> Unit,
    onCheckUnconfirmed: () -> Unit,
    onShowAddChange: (Boolean) -> Unit,
    onCompleteIdChange: (String?) -> Unit,
    onDeleteIdChange: (String?) -> Unit,
    onAddDraftChange: (String, String) -> Unit,
    onSelectWorkspace: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val showAdd = state.tasks.showAdd
    val completeId = state.tasks.completeId
    val deleteId = state.tasks.deleteId
    val projectTasks = state.projectTasks()
    val grouped = projectTasks.groupBy { it.status.ifBlank { "unknown" } }
    Column(modifier.fillMaxSize()) {
        ProjectChips(state = state, onSelect = onSelectWorkspace)
        PullToRefreshBox(
            isRefreshing = state.tasks.loading,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f).fillMaxSize(),
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Tasks", style = MaterialTheme.typography.titleSmall)
                        state.projectLabel()?.let { project ->
                            Text(
                                project,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (state.canMutate && state.capabilities.taskAdd && state.tasks.unconfirmed == null) {
                        Button(onClick = { onShowAddChange(true) }, enabled = !state.tasks.mutating) { Text("Add Task") }
                    }
                }
            }
            if (state.tasks.boardChanged) {
                item {
                    Text(
                        "Updated by someone else. Showing latest.",
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                state.tasks.errorText?.let { error ->
                    item { Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
            state.tasks.unconfirmed?.let { kind ->
                item { UnconfirmedBanner(kind = kind, onCheck = onCheckUnconfirmed) }
            }
            if (projectTasks.isEmpty() && !state.tasks.loading) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("No Tasks on the board.", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            state.projectLabel()?.let { "No Tasks in $it." }
                                ?: "Add a Task to put work on this Host.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            grouped.forEach { (status, tasks) ->
                item {
                    Text(
                        status.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(tasks, key = { it.id }) { task ->
                    val retrying = state.tasks.unconfirmed == UnconfirmedKind.RetryTask &&
                        state.tasks.unconfirmedTaskId == task.id
                    TaskRow(
                        task = task,
                        canComplete = state.canMutate &&
                            state.capabilities.taskDone &&
                            state.tasks.unconfirmed == null &&
                            task.isCompletable(),
                        canClaim = state.canMutate &&
                            state.capabilities.taskClaim &&
                            state.tasks.unconfirmed == null &&
                            task.isClaimable(),
                        canDelete = state.canMutate &&
                            state.capabilities.taskDelete &&
                            state.tasks.unconfirmed == null,
                        canRetry = state.canMutate &&
                            state.capabilities.taskRetry &&
                            state.tasks.unconfirmed == null &&
                            task.status.toTaskStatus().isRetryable,
                        completing = state.tasks.mutating || retrying,
                        retrying = retrying,
                        onComplete = { onCompleteIdChange(task.id) },
                        onClaim = { onClaimTask(task.id) },
                        onDelete = { onDeleteIdChange(task.id) },
                        onRetry = { onRetryTask(task.id) },
                    )
                }
            }
        }
        }
    }
    if (showAdd) {
        AddTaskSheet(
            title = state.tasks.addTitle,
            pathsText = state.tasks.addPaths,
            sending = state.tasks.mutating,
            errorText = state.tasks.errorText,
            unconfirmed = state.tasks.unconfirmed,
            onCheckUnconfirmed = onCheckUnconfirmed,
            onDraftChange = onAddDraftChange,
            onDismiss = { onShowAddChange(false) },
            onSubmit = onAddTask,
        )
    }
    completeId?.let { id ->
        val title = state.tasks.tasks.firstOrNull { it.id == id }?.title ?: id
        AlertDialog(
            onDismissRequest = { onCompleteIdChange(null) },
            title = { Text("Complete Task?") },
            text = { Text("Mark \"$title\" complete on the Host.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onCompleteIdChange(null)
                        onCompleteTask(id)
                    },
                ) { Text("Complete") }
            },
            dismissButton = {
                TextButton(onClick = { onCompleteIdChange(null) }) { Text("Cancel") }
            },
        )
    }
    deleteId?.let { id ->
        val title = state.tasks.tasks.firstOrNull { it.id == id }?.title ?: id
        AlertDialog(
            onDismissRequest = { onDeleteIdChange(null) },
            title = { Text("Delete Task?") },
            text = { Text("Delete \"$title\" from the Host. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteIdChange(null)
                        onDeleteTask(id)
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { onDeleteIdChange(null) }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun TaskRow(
    task: TaskSummary,
    canComplete: Boolean,
    canClaim: Boolean,
    canDelete: Boolean,
    canRetry: Boolean,
    completing: Boolean,
    retrying: Boolean,
    onComplete: () -> Unit,
    onClaim: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(task.title, fontWeight = FontWeight.SemiBold)
                Text(
                    task.id,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (retrying) {
                    Text(
                        "Retrying…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (canClaim) {
                    FilledTonalButton(onClick = onClaim, enabled = !completing) { Text("Claim") }
                }
                if (canComplete) {
                    FilledTonalButton(onClick = onComplete, enabled = !completing) { Text("Complete") }
                }
                if (canRetry) {
                    FilledTonalButton(onClick = onRetry, enabled = !completing) { Text("Retry") }
                }
                if (canDelete) {
                    TextButton(onClick = onDelete, enabled = !completing) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
private fun AddTaskSheet(
    title: String,
    pathsText: String,
    sending: Boolean,
    errorText: String?,
    unconfirmed: UnconfirmedKind?,
    onCheckUnconfirmed: () -> Unit,
    onDraftChange: (String, String) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: (title: String, paths: List<String>) -> Unit,
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
            Text("Add Task", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            errorText?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            unconfirmed?.let { kind ->
                UnconfirmedBanner(kind = kind, onCheck = onCheckUnconfirmed)
            }
            OutlinedTextField(
                value = title,
                onValueChange = { onDraftChange(it, pathsText) },
                label = { Text("Title") },
                enabled = !sending && unconfirmed == null,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = pathsText,
                onValueChange = { onDraftChange(title, it) },
                label = { Text("Paths (optional)") },
                enabled = !sending && unconfirmed == null,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Comma-separated globs") },
            )
            Button(
                enabled = title.isNotBlank() && !sending && unconfirmed == null,
                onClick = {
                    val paths = pathsText.split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    onSubmit(title.trim(), paths)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add Task") }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun TaskSummary.isCompletable(): Boolean {
    val status = status.lowercase()
    return status != "done" && status != "merged" && status != "failed"
}

private fun TaskSummary.isClaimable(): Boolean {
    val status = status.lowercase()
    return status == "queued" || status == "open" || status.isEmpty()
}

private fun String.toTaskStatus(): TaskStatus = when (lowercase()) {
    "queued" -> TaskStatus.Queued
    "claimed" -> TaskStatus.Claimed
    "running" -> TaskStatus.Running
    "blocked" -> TaskStatus.Blocked
    "review" -> TaskStatus.Review
    "done" -> TaskStatus.Done
    "merging" -> TaskStatus.Merging
    "merged" -> TaskStatus.Merged
    "failed" -> TaskStatus.Failed
    else -> TaskStatus.Unknown
}
