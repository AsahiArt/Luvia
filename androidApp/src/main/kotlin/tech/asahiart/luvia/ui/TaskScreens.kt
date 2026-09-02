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

@Composable
fun TasksSection(
    host: HostUiModel,
    state: HostUhpUiState,
    onRefresh: () -> Unit,
    onAddTask: (title: String, paths: List<String>) -> Unit,
    onCompleteTask: (String) -> Unit,
    onCheckUnconfirmed: () -> Unit,
    onShowAddChange: (Boolean) -> Unit,
    onCompleteIdChange: (String?) -> Unit,
    onAddDraftChange: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        !state.connected -> {
            UhpEmptyPane(title = "Tasks", message = "Connect to this host", modifier = modifier)
        }
        !state.capabilities.taskList -> {
            UhpEmptyPane(title = "Tasks", message = "Tasks are not available on this host.", modifier = modifier)
        }
        else -> {
            TaskListPane(
                state = state,
                onRefresh = onRefresh,
                onAddTask = onAddTask,
                onCompleteTask = onCompleteTask,
                onCheckUnconfirmed = onCheckUnconfirmed,
                onShowAddChange = onShowAddChange,
                onCompleteIdChange = onCompleteIdChange,
                onAddDraftChange = onAddDraftChange,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun TaskListPane(
    state: HostUhpUiState,
    onRefresh: () -> Unit,
    onAddTask: (title: String, paths: List<String>) -> Unit,
    onCompleteTask: (String) -> Unit,
    onCheckUnconfirmed: () -> Unit,
    onShowAddChange: (Boolean) -> Unit,
    onCompleteIdChange: (String?) -> Unit,
    onAddDraftChange: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val showAdd = state.tasks.showAdd
    val completeId = state.tasks.completeId
    val grouped = state.tasks.tasks.groupBy { it.status.ifBlank { "unknown" } }
    PullToRefreshBox(
        isRefreshing = state.tasks.loading,
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
                    Text("Tasks", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (state.canMutate && state.capabilities.taskAdd) {
                        Button(onClick = { onShowAddChange(true) }, enabled = !state.tasks.mutating) { Text("Add Task") }
                    }
                }
            }
            if (state.tasks.boardChanged) {
                item {
                    Text(
                        "Board changed, review and try again",
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            state.tasks.errorText?.let { error ->
                item { Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            state.tasks.unconfirmed?.let { kind ->
                item { UnconfirmedBanner(kind = kind, onCheck = onCheckUnconfirmed) }
            }
            if (state.tasks.tasks.isEmpty() && !state.tasks.loading) {
                item { Text("No Tasks.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
                    TaskRow(
                        task = task,
                        canComplete = state.canMutate &&
                            state.capabilities.taskDone &&
                            task.isCompletable(),
                        completing = state.tasks.mutating,
                        onComplete = { onCompleteIdChange(task.id) },
                    )
                }
            }
        }
    }
    if (showAdd) {
        AddTaskSheet(
            title = state.tasks.addTitle,
            pathsText = state.tasks.addPaths,
            onDraftChange = onAddDraftChange,
            onDismiss = { onShowAddChange(false) },
            onSubmit = { title, paths ->
                onShowAddChange(false)
                onAddTask(title, paths)
            },
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
}

@Composable
private fun TaskRow(
    task: TaskSummary,
    canComplete: Boolean,
    completing: Boolean,
    onComplete: () -> Unit,
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
            }
            if (canComplete) {
                FilledTonalButton(onClick = onComplete, enabled = !completing) { Text("Complete") }
            }
        }
    }
}

@Composable
private fun AddTaskSheet(
    title: String,
    pathsText: String,
    onDraftChange: (String, String) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: (title: String, paths: List<String>) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Add Task", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = title,
                onValueChange = { onDraftChange(it, pathsText) },
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = pathsText,
                onValueChange = { onDraftChange(title, it) },
                label = { Text("Paths (optional)") },
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Comma-separated globs") },
            )
            Button(
                enabled = title.isNotBlank(),
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
