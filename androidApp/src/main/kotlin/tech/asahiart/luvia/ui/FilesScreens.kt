@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package tech.asahiart.luvia.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.asahiart.luvia.FileTreeRow
import tech.asahiart.luvia.HostUhpState

@Composable
fun FilesSection(
    host: HostUiModel,
    state: HostUhpState,
    onRefresh: () -> Unit,
    onOpenFile: (String) -> Unit,
    onRevealFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        !host.connected && !state.connected -> {
            UhpEmptyPane(title = "Files", message = "Connect to this host", modifier = modifier)
        }
        !state.capabilities.filesTree -> {
            UhpEmptyPane(title = "Files", message = "Files are not available on this host.", modifier = modifier)
        }
        else -> {
            FileTreePane(
                state = state,
                onRefresh = onRefresh,
                onOpenFile = onOpenFile,
                onRevealFile = onRevealFile,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun FileTreePane(
    state: HostUhpState,
    onRefresh: () -> Unit,
    onOpenFile: (String) -> Unit,
    onRevealFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val files = state.files
    val canOpen = state.canMutate && state.capabilities.filesOpen && !files.mutating
    val canReveal = state.canMutate && state.capabilities.filesReveal && !files.mutating
    val canRefresh = state.canMutate && state.capabilities.filesRefresh
    PullToRefreshBox(
        isRefreshing = files.loading,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Files", style = MaterialTheme.typography.titleSmall)
                        if (files.root.isNotBlank()) {
                            Text(
                                files.root,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (canRefresh) {
                        FilledTonalButton(onClick = onRefresh, enabled = !files.loading && !files.mutating) {
                            Text("Refresh")
                        }
                    }
                }
            }
            files.errorText?.let { error ->
                item { Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            if (files.rows.isEmpty() && !files.loading) {
                item {
                    Text("No files in the tree.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(files.rows, key = { it.path.ifBlank { it.name } + it.depth }) { row ->
                FileTreeRowCard(
                    row = row,
                    canOpen = canOpen && !row.dir,
                    canReveal = canReveal,
                    onOpen = { onOpenFile(row.path) },
                    onReveal = { onRevealFile(row.path) },
                )
            }
        }
    }
}

@Composable
private fun FileTreeRowCard(
    row: FileTreeRow,
    canOpen: Boolean,
    canReveal: Boolean,
    onOpen: () -> Unit,
    onReveal: () -> Unit,
) {
    val clickable = canOpen
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (clickable) Modifier.clickable(onClick = onOpen) else Modifier),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = (12 + row.depth * 12).dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (row.dir) {
                        val mark = if (row.expanded) "▾ " else "▸ "
                        mark + row.name.ifBlank { row.path }
                    } else {
                        row.name.ifBlank { row.path }
                    },
                    fontWeight = if (row.dir) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.path.isNotBlank() && row.path != row.name) {
                    Text(
                        row.path,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (canReveal) {
                TextButton(onClick = onReveal) { Text("Reveal") }
            }
        }
    }
}
