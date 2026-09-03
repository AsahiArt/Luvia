@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package tech.asahiart.luvia.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.asahiart.luvia.AgentSummary
import tech.asahiart.luvia.DiffFile
import tech.asahiart.luvia.DiffLayer
import tech.asahiart.luvia.DiffLine
import tech.asahiart.luvia.ReviewLine
import tech.asahiart.luvia.ReviewNote
import tech.asahiart.luvia.ReviewNoteState

@Composable
fun ReviewSection(
    host: HostUiModel,
    state: HostUhpUiState,
    onRefresh: () -> Unit,
    onOpenFile: (String, DiffLayer?) -> Unit,
    onCloseFile: () -> Unit,
    onAddNote: (file: String, line: ReviewLine, body: String, layer: DiffLayer?) -> Unit,
    onResolveNote: (String) -> Unit,
    onReopenNote: (String) -> Unit,
    onRemoveNote: (String) -> Unit,
    onSendNotes: (String) -> Unit,
    onNoteDraftChange: (String) -> Unit,
    onSendTargetChange: (String?) -> Unit,
    onCheckUnconfirmed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        !state.connected -> {
            UhpEmptyPane(title = "Review", message = "Connect to this host", modifier = modifier)
        }
        !state.capabilities.diffList -> {
            UhpEmptyPane(title = "Review", message = "Diff is not available on this host.", modifier = modifier)
        }
        state.review.selectedFile != null || state.review.selectedPath != null -> {
            ReviewFilePane(
                state = state,
                onBack = onCloseFile,
                onAddNote = onAddNote,
                onResolveNote = onResolveNote,
                onReopenNote = onReopenNote,
                onRemoveNote = onRemoveNote,
                onSendNotes = onSendNotes,
                onNoteDraftChange = onNoteDraftChange,
                onSendTargetChange = onSendTargetChange,
                onCheckUnconfirmed = onCheckUnconfirmed,
                modifier = modifier,
            )
        }
        else -> {
            ReviewFileListPane(
                state = state,
                onRefresh = onRefresh,
                onOpenFile = onOpenFile,
                onResolveNote = onResolveNote,
                onReopenNote = onReopenNote,
                onRemoveNote = onRemoveNote,
                onSendNotes = onSendNotes,
                onSendTargetChange = onSendTargetChange,
                onCheckUnconfirmed = onCheckUnconfirmed,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun ReviewFileListPane(
    state: HostUhpUiState,
    onRefresh: () -> Unit,
    onOpenFile: (String, DiffLayer?) -> Unit,
    onResolveNote: (String) -> Unit,
    onReopenNote: (String) -> Unit,
    onRemoveNote: (String) -> Unit,
    onSendNotes: (String) -> Unit,
    onSendTargetChange: (String?) -> Unit,
    onCheckUnconfirmed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val files = state.review.list?.files.orEmpty()
    val grouped = files.groupBy { it.layer }
    PullToRefreshBox(
        isRefreshing = state.review.loading,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                val list = state.review.list
                Text(
                    listOfNotNull(list?.repo, list?.branch).joinToString(" · ").ifBlank { "Diff" },
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            state.review.errorText?.let { error ->
                item { Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            state.review.unconfirmed?.let { kind ->
                item { UnconfirmedBanner(kind = kind, onCheck = onCheckUnconfirmed) }
            }
            state.review.lastSend?.let { send ->
                item {
                    Text(
                        buildString {
                            append("Send notes delivered ")
                            append(send.count)
                            send.target?.let {
                                append(" to ")
                                append(it)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (files.isEmpty() && !state.review.loading) {
                item {
                    Text("No Diff files.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            grouped.forEach { (layer, layerFiles) ->
                item {
                    Text(
                        layerLabel(layer),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(layerFiles, key = { "${it.layer}-${it.path}" }) { file ->
                    DiffFileRow(file = file, onClick = { onOpenFile(file.path, file.layer) })
                }
            }
            item {
                NotesDrawer(
                    notes = state.review.notes,
                    canMutate = state.canMutate && !state.review.sending && state.review.unconfirmed == null,
                    capabilities = state.capabilities,
                    agents = state.agents,
                    sending = state.review.sending,
                    sendTarget = state.review.sendTarget,
                    onSendTargetChange = onSendTargetChange,
                    onResolveNote = onResolveNote,
                    onReopenNote = onReopenNote,
                    onRemoveNote = onRemoveNote,
                    onSendNotes = onSendNotes,
                )
            }
        }
    }
}

@Composable
private fun DiffFileRow(file: DiffFile, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                file.path,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val plus = file.additions
                val minus = file.deletions
                if (plus != null) {
                    Text("+$plus", color = Color(0xFF2E7D32), style = MaterialTheme.typography.labelMedium)
                }
                if (minus != null) {
                    Text("-$minus", color = Color(0xFFC62828), style = MaterialTheme.typography.labelMedium)
                }
                file.status?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ReviewFilePane(
    state: HostUhpUiState,
    onBack: () -> Unit,
    onAddNote: (file: String, line: ReviewLine, body: String, layer: DiffLayer?) -> Unit,
    onResolveNote: (String) -> Unit,
    onReopenNote: (String) -> Unit,
    onRemoveNote: (String) -> Unit,
    onSendNotes: (String) -> Unit,
    onNoteDraftChange: (String) -> Unit,
    onSendTargetChange: (String?) -> Unit,
    onCheckUnconfirmed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val file = state.review.selectedFile
    var pendingLine by remember { mutableStateOf<DiffLine?>(null) }
    var noteSubmissionPending by remember { mutableStateOf(false) }
    LaunchedEffect(
        state.review.sending,
        state.review.noteDraft,
        state.review.errorText,
        state.review.unconfirmed,
    ) {
        if (noteSubmissionPending && !state.review.sending) {
            if (state.review.noteDraft.isEmpty() &&
                state.review.errorText == null &&
                state.review.unconfirmed == null
            ) {
                pendingLine = null
            }
            noteSubmissionPending = false
        }
    }
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(
                file?.path ?: state.review.selectedPath.orEmpty(),
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        state.review.errorText?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        state.review.unconfirmed?.let { kind ->
            UnconfirmedBanner(kind = kind, onCheck = onCheckUnconfirmed)
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            val hunks = file?.hunks.orEmpty()
            if (hunks.isEmpty()) {
                item {
                    Text("No hunks in this Diff.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            hunks.forEach { hunk ->
                item {
                    Text(
                        hunk.header.ifBlank { hunk.id },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
                items(hunk.lines.size) { index ->
                    val line = hunk.lines[index]
                    DiffLineRow(
                        line = line,
                        enabled = state.canMutate &&
                            state.capabilities.diffNoteAdd &&
                            !state.review.sending &&
                            state.review.unconfirmed == null,
                        onClick = { pendingLine = line },
                    )
                }
            }
            item {
                Spacer(Modifier.height(12.dp))
                NotesDrawer(
                    notes = state.review.notes.filter { note ->
                        note.path == null || note.path == file?.path || note.path == state.review.selectedPath
                    },
                    canMutate = state.canMutate && !state.review.sending && state.review.unconfirmed == null,
                    capabilities = state.capabilities,
                    agents = state.agents,
                    sending = state.review.sending,
                    sendTarget = state.review.sendTarget,
                    onSendTargetChange = onSendTargetChange,
                    onResolveNote = onResolveNote,
                    onReopenNote = onReopenNote,
                    onRemoveNote = onRemoveNote,
                    onSendNotes = onSendNotes,
                )
            }
        }
    }
    pendingLine?.let { line ->
        AddReviewNoteSheet(
            path = file?.path ?: state.review.selectedPath.orEmpty(),
            line = line,
            body = state.review.noteDraft,
            sending = state.review.sending,
            errorText = state.review.errorText,
            unconfirmed = state.review.unconfirmed,
            onBodyChange = onNoteDraftChange,
            onCheckUnconfirmed = onCheckUnconfirmed,
            onDismiss = {
                pendingLine = null
                if (state.review.unconfirmed == null) onNoteDraftChange("")
            },
            onSubmit = { body ->
                val reviewLine = line.toReviewLine() ?: return@AddReviewNoteSheet
                noteSubmissionPending = true
                onAddNote(
                    file?.path ?: state.review.selectedPath.orEmpty(),
                    reviewLine,
                    body,
                    file?.layer ?: state.review.selectedLayer,
                )
            },
        )
    }
}

@Composable
private fun DiffLineRow(line: DiffLine, enabled: Boolean, onClick: () -> Unit) {
    val color = when (line.kind.lowercase()) {
        "add", "+", "plus" -> Color(0xFF2E7D32)
        "del", "delete", "-", "minus", "remove" -> Color(0xFFC62828)
        else -> MaterialTheme.colorScheme.onSurface
    }
    val number = line.newLine ?: line.oldLine
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            number?.toString() ?: "",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(0.12f),
        )
        Text(
            line.text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = color,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AddReviewNoteSheet(
    path: String,
    line: DiffLine,
    body: String,
    sending: Boolean,
    errorText: String?,
    unconfirmed: UnconfirmedKind?,
    onBodyChange: (String) -> Unit,
    onCheckUnconfirmed: () -> Unit,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
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
            Text("Review note", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                path,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Line ${line.newLine ?: line.oldLine ?: 0}",
                style = MaterialTheme.typography.labelMedium,
            )
            errorText?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            unconfirmed?.let { kind ->
                UnconfirmedBanner(kind = kind, onCheck = onCheckUnconfirmed)
            }
            OutlinedTextField(
                value = body,
                onValueChange = onBodyChange,
                label = { Text("Review note") },
                enabled = !sending && unconfirmed == null,
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
            Button(
                enabled = body.isNotBlank() && !sending && unconfirmed == null,
                onClick = { onSubmit(body.trim()) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add Review note") }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun NotesDrawer(
    notes: List<ReviewNote>,
    canMutate: Boolean,
    capabilities: HostCapabilitiesUi,
    agents: List<AgentSummary>,
    sending: Boolean,
    sendTarget: String?,
    onSendTargetChange: (String?) -> Unit,
    onResolveNote: (String) -> Unit,
    onReopenNote: (String) -> Unit,
    onRemoveNote: (String) -> Unit,
    onSendNotes: (String) -> Unit,
) {
    val openNotes = notes.filter { it.state == null || it.state == ReviewNoteState.OPEN }
    val resolvedNotes = notes.filter { it.state == ReviewNoteState.RESOLVED }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Review notes", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (canMutate && capabilities.diffNoteSend && openNotes.isNotEmpty()) {
                Button(onClick = { onSendTargetChange("") }, enabled = !sending) { Text("Send notes") }
            }
        }
        if (notes.isEmpty()) {
            Text("No Review notes.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        if (openNotes.isNotEmpty()) {
            Text("Open", style = MaterialTheme.typography.labelLarge)
            openNotes.forEach { note ->
                ReviewNoteCard(
                    note = note,
                    canMutate = canMutate,
                    canResolve = capabilities.diffNoteResolve,
                    canReopen = false,
                    canRemove = capabilities.diffNoteRemove,
                    onResolve = { onResolveNote(note.id) },
                    onReopen = {},
                    onRemove = { onRemoveNote(note.id) },
                )
            }
        }
        if (resolvedNotes.isNotEmpty()) {
            Text("Resolved", style = MaterialTheme.typography.labelLarge)
            resolvedNotes.forEach { note ->
                ReviewNoteCard(
                    note = note,
                    canMutate = canMutate,
                    canResolve = false,
                    canReopen = capabilities.diffNoteReopen,
                    canRemove = capabilities.diffNoteRemove,
                    onResolve = {},
                    onReopen = { onReopenNote(note.id) },
                    onRemove = { onRemoveNote(note.id) },
                )
            }
        }
    }
    sendTarget?.let {
        SendNotesDialog(
            agents = agents,
            onDismiss = { onSendTargetChange(null) },
            onSend = { target ->
                onSendTargetChange(null)
                onSendNotes(target)
            },
        )
    }
}

@Composable
private fun ReviewNoteCard(
    note: ReviewNote,
    canMutate: Boolean,
    canResolve: Boolean,
    canReopen: Boolean,
    canRemove: Boolean,
    onResolve: () -> Unit,
    onReopen: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(note.body)
            val meta = buildString {
                note.path?.let { append(it) }
                note.startLine?.let {
                    if (isNotEmpty()) append(":")
                    append(it)
                }
                note.kind?.let {
                    if (isNotEmpty()) append(" · ")
                    append(it.name.lowercase())
                }
            }
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (note.deliveries.isNotEmpty()) {
                Text(
                    "Deliveries: " + note.deliveries.joinToString { it.target },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (canMutate) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canResolve) TextButton(onClick = onResolve) { Text("Resolve") }
                    if (canReopen) TextButton(onClick = onReopen) { Text("Reopen") }
                    if (canRemove) TextButton(onClick = onRemove) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun SendNotesDialog(
    agents: List<AgentSummary>,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
) {
    var selected by remember { mutableStateOf(agents.firstOrNull()?.paneId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send notes") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Deliver open Review notes to an Agent.")
                if (agents.isEmpty()) {
                    Text("No Agents available.", color = MaterialTheme.colorScheme.error)
                } else {
                    agents.forEach { agent ->
                        val selectedRow = selected == agent.paneId
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { selected = agent.paneId }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                agent.name ?: agent.agent ?: agent.paneId,
                                fontWeight = if (selectedRow) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f),
                            )
                            if (selectedRow) Text("Selected", style = MaterialTheme.typography.labelSmall)
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected != null,
                onClick = { selected?.let(onSend) },
            ) { Text("Send notes") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun layerLabel(layer: DiffLayer?): String = when (layer) {
    DiffLayer.STAGED -> "Staged"
    DiffLayer.WORKTREE -> "Worktree"
    DiffLayer.UNTRACKED -> "Untracked"
    DiffLayer.CONFLICT -> "Conflict"
    null -> "Other"
}

private fun DiffLine.toReviewLine(): ReviewLine? {
    val neu = newLine
    if (neu != null) return ReviewLine.New(neu.toInt())
    val old = oldLine
    if (old != null) return ReviewLine.Old(old.toInt())
    return null
}
