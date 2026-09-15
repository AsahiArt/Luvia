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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.insertTextAtCursor
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.asahiart.luvia.SearchMatch
import tech.asahiart.luvia.HostUhpState

@Composable
fun SearchSection(
    host: HostUiModel,
    state: HostUhpState,
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
    state: HostUhpState,
    onRefresh: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onActivate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val search = state.search
    val canActivate = state.canMutate && state.capabilities.searchActivate
    val focus = LocalFocusManager.current
    val submit = {
        if (search.query.isNotBlank() && !search.loading) {
            focus.clearFocus()
            onSearch()
        }
    }
    val bottomInset = systemBottomInset()
    Column(modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Search", style = MaterialTheme.typography.titleSmall)
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = search.query,
                    onValueChange = onQueryChange,
                    label = { Text("Query") },
                    enabled = !search.loading,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                    modifier =
                        Modifier
                            .weight(1f)
                            .semantics {
                                contentDescription = "Query"
                                setText {
                                    onQueryChange(it.text)
                                    true
                                }
                                insertTextAtCursor {
                                    onQueryChange(search.query + it.text)
                                    true
                                }
                            },
                )
                Button(
                    onClick = submit,
                    enabled = search.query.isNotBlank() && !search.loading,
                ) { Text("Search") }
            }
            search.scopeLabel?.takeIf { it.isNotBlank() }?.let { scope ->
                Text(
                    "In $scope",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            search.errorText?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        PullToRefreshBox(
            isRefreshing = search.loading,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(bottom = bottomInset),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (search.searched && search.matches.isEmpty() && search.errorText == null && !search.loading) {
                    item {
                        Text("No matches.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                search.result?.let { result ->
                    if (result.partial || result.total > result.shown) {
                        item {
                            Text(
                                buildString {
                                    append("Showing ${result.shown} of ${result.total}")
                                    if (result.partial) append(" (partial)")
                                    if (result.total > 200) {
                                        append(". Narrow the query or focus a project workspace in Layout.")
                                    }
                                },
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
