@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package tech.asahiart.luvia.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.foundation.text.selection.SelectionContainer
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
import tech.asahiart.luvia.HostUhpState
import tech.asahiart.luvia.HostCapabilities
import tech.asahiart.luvia.UnconfirmedKind
import tech.asahiart.luvia.ui.theme.LuviaTheme

@Composable
fun ReviewSection(
    host: HostUiModel,
    state: HostUhpState,
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
    onSelectWorkspace: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    when {
        host.shouldShowOfflineEmpty(state.connected) -> {
            UhpEmptyPane(title = "Review", message = host.offlineEmptyMessage(), modifier = modifier)
        }
        !state.capabilities.diffList -> {
            UhpEmptyPane(title = "Review", message = "Diff is not available on this host.", modifier = modifier)
        }
        state.needsProjectPick() -> {
            Column(modifier.fillMaxSize().padding(16.dp)) {
                ProjectChips(state = state, onSelect = onSelectWorkspace)
                UhpEmptyPane(
                    title = "Review",
                    message = "Select a project.",
                    modifier = Modifier.weight(1f),
                )
            }
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
                onSelectWorkspace = onSelectWorkspace,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun ReviewFileListPane(
    state: HostUhpState,
    onRefresh: () -> Unit,
    onOpenFile: (String, DiffLayer?) -> Unit,
    onResolveNote: (String) -> Unit,
    onReopenNote: (String) -> Unit,
    onRemoveNote: (String) -> Unit,
    onSendNotes: (String) -> Unit,
    onSendTargetChange: (String?) -> Unit,
    onCheckUnconfirmed: () -> Unit,
    onSelectWorkspace: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val files = state.review.list?.files.orEmpty()
    val layers = DiffLayer.entries
    var selectedLayer by remember {
        mutableStateOf(
            files.firstOrNull { it.layer == DiffLayer.WORKTREE }?.layer
                ?: files.firstOrNull()?.layer
                ?: DiffLayer.WORKTREE,
        )
    }
    var notesOnly by remember { mutableStateOf(false) }
    val openNotesByPath = state.review.notes
        .filter { it.state == null || it.state == ReviewNoteState.OPEN }
        .groupingBy { it.path.orEmpty() }
        .eachCount()
    fun noteCount(file: DiffFile): Int {
        val fromList = file.notes?.toInt() ?: 0
        val fromNotes = openNotesByPath[file.path] ?: 0
        return maxOf(fromList, fromNotes)
    }
    val visible = files.filter { file ->
        val layerMatch = file.layer == selectedLayer || (selectedLayer == DiffLayer.WORKTREE && file.layer == null)
        val notesMatch = !notesOnly || noteCount(file) > 0
        layerMatch && notesMatch
    }
    Column(modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.review.loading,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f).fillMaxSize(),
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                val list = state.review.list
                Text(
                    listOfNotNull(shortRepoName(list?.repo), list?.branch).joinToString(" · ").ifBlank { "Diff" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    layers.forEachIndexed { index, layer ->
                        SegmentedButton(
                            selected = selectedLayer == layer,
                            onClick = { selectedLayer = layer },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = layers.size),
                        ) {
                            Text(layerLabel(layer), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            item {
                FilterChip(
                    selected = notesOnly,
                    onClick = { notesOnly = !notesOnly },
                    label = { Text("Files with notes") },
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
            if (visible.isEmpty() && !state.review.loading) {
                item {
                    Text("No Diff files.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(visible, key = { "${it.layer}-${it.path}" }) { file ->
                val directory = isDiffDirectory(file.path)
                DiffFileRow(
                    file = file,
                    openNotes = noteCount(file),
                    onClick = if (directory) {
                        null
                    } else {
                        { onOpenFile(file.path, file.layer) }
                    },
                )
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
}


@Composable
private fun DiffFileRow(file: DiffFile, openNotes: Int, onClick: (() -> Unit)?) {
    val addColor = diffAddColor()
    val delColor = diffDelColor()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                file.path,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (onClick == null) {
                    Text("Directory", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val plus = file.additions
                val minus = file.deletions
                if (plus != null) {
                    Text("+$plus", color = addColor, style = MaterialTheme.typography.labelMedium)
                }
                if (minus != null) {
                    Text("-$minus", color = delColor, style = MaterialTheme.typography.labelMedium)
                }
                if (openNotes > 0) {
                    Text(
                        if (openNotes == 1) "1 open note" else "$openNotes open notes",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
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
    state: HostUhpState,
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
    var pendingNote by remember { mutableStateOf<PendingReviewNote?>(null) }
    var noteSubmissionPending by remember { mutableStateOf(false) }
    var expandedHunks by remember { mutableStateOf(setOf<String>()) }
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
                pendingNote = null
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
        val selectedPath = file?.path ?: state.review.selectedPath.orEmpty()
        val directory = isDiffDirectory(selectedPath)
        if (!directory) {
            state.review.errorText?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        state.review.unconfirmed?.let { kind ->
            UnconfirmedBanner(kind = kind, onCheck = onCheckUnconfirmed)
        }
        if (directory) {
            UhpEmptyPane(
                title = "This is a directory, not a file diff.",
                message = "Select a file to see hunks.",
                modifier = Modifier.weight(1f),
            )
        } else {
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
                val hunkKey = hunk.id.ifBlank { hunk.header }
                val expanded = hunkKey in expandedHunks
                item(key = "hunk-$hunkKey") {
                    Text(
                        (if (expanded) "▾ " else "▸ ") + hunk.header.ifBlank { hunk.id },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                expandedHunks = if (expanded) expandedHunks - hunkKey else expandedHunks + hunkKey
                            }
                            .padding(vertical = 6.dp),
                    )
                }
                if (expanded) {
                    items(hunk.lines.size) { index ->
                        val line = hunk.lines[index]
                        DiffLineRow(
                            line = line,
                            enabled = state.canMutate &&
                                state.capabilities.diffNoteAdd &&
                                !state.review.sending &&
                                state.review.unconfirmed == null,
                            onClick = {
                                val from = (index - 2).coerceAtLeast(0)
                                val to = (index + 2).coerceAtMost(hunk.lines.lastIndex)
                                pendingNote = PendingReviewNote(
                                    line = line,
                                    context = hunk.lines.subList(from, to + 1),
                                )
                            },
                        )
                    }
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
    }
    pendingNote?.let { pending ->
        AddReviewNoteSheet(
            path = file?.path ?: state.review.selectedPath.orEmpty(),
            line = pending.line,
            context = pending.context,
            body = state.review.noteDraft,
            sending = state.review.sending,
            errorText = state.review.errorText,
            unconfirmed = state.review.unconfirmed,
            onBodyChange = onNoteDraftChange,
            onCheckUnconfirmed = onCheckUnconfirmed,
            onDismiss = {
                pendingNote = null
                if (state.review.unconfirmed == null) onNoteDraftChange("")
            },
            onSubmit = { body ->
                val reviewLine = pending.line.toReviewLine() ?: return@AddReviewNoteSheet
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
        "add", "+", "plus" -> diffAddColor()
        "del", "delete", "-", "minus", "remove" -> diffDelColor()
        else -> MaterialTheme.colorScheme.onSurface
    }
    val number = line.newLine ?: line.oldLine
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            number?.toString() ?: "",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(48.dp),
        )
        Text(
            line.text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = color,
            softWrap = false,
            maxLines = 1,
        )
    }
}

@Composable
private fun AddReviewNoteSheet(
    path: String,
    line: DiffLine,
    context: List<DiffLine>,
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
            SelectionContainer {
                Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    context.forEach { ctx ->
                        val color = when (ctx.kind.lowercase()) {
                            "add", "+", "plus" -> diffAddColor()
                            "del", "delete", "-", "minus", "remove" -> diffDelColor()
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                        val mark = if (ctx === line || (ctx.newLine == line.newLine && ctx.oldLine == line.oldLine && ctx.text == line.text)) "› " else "  "
                        Text(
                            mark + ctx.text,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = color,
                            softWrap = false,
                            maxLines = 1,
                            fontWeight = if (mark.startsWith("›")) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
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
    capabilities: HostCapabilities,
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
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("No Review notes.", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Open a file and add a note on a diff line.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
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

private fun isDiffDirectory(path: String): Boolean =
    path.endsWith('/') || path.endsWith('\\')

private fun shortRepoName(repo: String?): String? {
    if (repo.isNullOrBlank()) return null
    val trimmed = repo.trimEnd('/', '\\')
    val name = trimmed.substringAfterLast('/').substringAfterLast('\\')
    return name.ifBlank { repo }
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

private data class PendingReviewNote(
    val line: DiffLine,
    val context: List<DiffLine>,
)

@Composable
private fun diffAddColor(): Color = LuviaTheme.extended.diffAdd

@Composable
private fun diffDelColor(): Color = LuviaTheme.extended.diffDel

@Composable
internal fun ProjectChips(
    state: HostUhpState,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val choices = state.projectChoices()
    val selected = state.projectWorkspaceId()
    val label = state.projectLabel() ?: "Select a project"
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "Project",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (choices.isEmpty()) {
            Text(label, style = MaterialTheme.typography.titleSmall)
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                choices.forEach { choice ->
                    FilterChip(
                        selected = choice.id == selected,
                        onClick = { onSelect(choice.id) },
                        label = { Text(choice.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
    }
}

