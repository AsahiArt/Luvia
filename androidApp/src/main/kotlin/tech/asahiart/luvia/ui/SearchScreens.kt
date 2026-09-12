@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package tech.asahiart.luvia.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.asahiart.luvia.SearchMatch

@Composable
fun SearchSection(
    host: HostUiModel,
    state: HostUhpUiState,
    onRefresh: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onActivate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        !host.connected && !state.connected -> {
            UhpEmptyPane(title = "Search", message = "Connect to this host", modifier = modifier)
        }
        !state.capabilities.searchQuery -> {
            UhpEmptyPane(title = "Search", message = "Search is not available on this host.", modifier = modifier)
        }
        else -> {
            SearchPane(
                state = state,
                onRefresh = onRefresh,
                onQueryChange = onQueryChange,
                onSearch = onSearch,
                onActivate = onActivate,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun SearchPane(
    state: HostUhpUiState,
    onRefresh: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onActivate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val search = state.search
    val canActivate = state.canMutate && state.capabilities.searchActivate
    PullToRefreshBox(
        isRefreshing = search.loading,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize().imePadding(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Search", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = search.query,
                        onValueChange = onQueryChange,
                        label = { Text("Query") },
                        enabled = !search.loading,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = onSearch,
                        enabled = search.query.isNotBlank() && !search.loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Search") }
                }
            }
            search.errorText?.let { error ->
                item { Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            if (search.searched && search.matches.isEmpty() && search.errorText == null && !search.loading) {
                item {
                    Text("No matches.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            search.result?.let { result ->
                if (result.partial || result.total > result.shown) {
                    item {
                        Text(
                            "Showing ${result.shown} of ${result.total}" + if (result.partial) " (partial)" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(search.matches, key = { it.id.ifBlank { it.label } }) { match ->
                SearchMatchRow(
                    match = match,
                    canActivate = canActivate,
                    onActivate = { onActivate(match.id) },
                )
            }
        }
    }
}

@Composable
private fun SearchMatchRow(
    match: SearchMatch,
    canActivate: Boolean,
    onActivate: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (canActivate) Modifier.clickable(onClick = onActivate) else Modifier),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    match.label.ifBlank { match.id },
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (match.kind.isNotBlank()) {
                    Text(
                        match.kind,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            match.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
