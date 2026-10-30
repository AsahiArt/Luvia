package tech.asahiart.luvia.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.asahiart.luvia.thread.AgentThread
import tech.asahiart.luvia.thread.AskOption
import tech.asahiart.luvia.thread.SessionFilter
import tech.asahiart.luvia.thread.pendingReplyCount
import tech.asahiart.luvia.ui.theme.LuviaTheme

@Composable
fun SessionListScreen(
    hosts: List<HostUiModel>,
    selected: HostUiModel?,
    threads: List<AgentThread>,
    filter: SessionFilter,
    onFilter: (SessionFilter) -> Unit,
    onSelectHost: (String) -> Unit,
    onPair: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenThread: (AgentThread) -> Unit,
    onAnswer: (AgentThread, AskOption) -> Unit,
    onNewSession: () -> Unit,
    onOpenTerminal: () -> Unit,
    terminalModifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val pending = threads.filter { it.needsYou }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { menu = true }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ConnectionDot(selected?.connection)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            selected?.name ?: "No computer",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            selected?.address?.takeIf { it.isNotBlank() } ?: selected?.connection?.name ?: "Pair a computer",
                            fontFamily = LuviaTheme.mono,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Switch computer")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    hosts.forEach { host ->
                        DropdownMenuItem(
                            text = { Text(host.name) },
                            onClick = {
                                menu = false
                                onSelectHost(host.id)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Pair a computer") },
                        onClick = {
                            menu = false
                            onPair()
                        },
                    )
                    if (selected != null) {
                        DropdownMenuItem(
                            text = { Text("Computer settings") },
                            onClick = {
                                menu = false
                                onOpenSettings()
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                ">_",
                modifier = terminalModifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpenTerminal)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                fontFamily = LuviaTheme.mono,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            IconButton(onClick = onNewSession) {
                Icon(Icons.Filled.Add, contentDescription = "New session")
            }
        }
        val waitingCount = pendingReplyCount(threads)
        if (waitingCount > 0) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.tertiaryContainer)
                    .clickable { onFilter(SessionFilter.NeedsYou) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "$waitingCount waiting for a reply",
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text("Show", color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(filter == SessionFilter.All, { onFilter(SessionFilter.All) }, { Text("All") })
            FilterChip(filter == SessionFilter.NeedsYou, { onFilter(SessionFilter.NeedsYou) }, { Text("Needs you") })
            FilterChip(filter == SessionFilter.Running, { onFilter(SessionFilter.Running) }, { Text("Running") })
            FilterChip(filter == SessionFilter.Idle, { onFilter(SessionFilter.Idle) }, { Text("Idle") })
        }
        if (pending.isNotEmpty() && filter != SessionFilter.Idle && filter != SessionFilter.Running) {
            val pagerState = rememberPagerState(pageCount = { pending.size })
            HorizontalPager(
                state = pagerState,
                contentPadding = PaddingValues(horizontal = 16.dp),
                pageSpacing = 12.dp,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            ) { page ->
                val thread = pending[page]
                DecisionPreview(
                    thread = thread,
                    index = page + 1,
                    total = pending.size,
                    enabled = selected?.isObserver != true,
                    onAnswer = { option -> onAnswer(thread, option) },
                    onOpen = { onOpenThread(thread) },
                )
            }
        }
        if (threads.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    if (selected == null) "Pair a computer to see sessions." else "No sessions for this filter.",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "New sessions use the + button. Pane agents still start on the computer.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(threads, key = { it.id }) { thread ->
                    SessionRow(thread, selected?.connection == ConnectionBadge.Live && thread.status == tech.asahiart.luvia.AgentStatus.Working) {
                        onOpenThread(thread)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionDot(connection: ConnectionBadge?) {
    val color = when (connection) {
        ConnectionBadge.Live -> LuviaTheme.extended.live
        ConnectionBadge.Connecting -> LuviaTheme.extended.connecting
        ConnectionBadge.Stale -> LuviaTheme.extended.stale
        ConnectionBadge.Offline, null -> LuviaTheme.extended.offline
    }
    Box(Modifier.size(10.dp).background(color, CircleShape))
}

@Composable
private fun SessionRow(thread: AgentThread, wavy: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(thread.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(thread.status.sessionLabel(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val subtitle = listOfNotNull(thread.projectLabel, thread.agentType).joinToString(" · ")
        if (subtitle.isNotBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        thread.summary?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
        if (wavy) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp).clip(CircleShape),
            )
        }
    }
}

@Composable
private fun DecisionPreview(
    thread: AgentThread,
    index: Int,
    total: Int,
    enabled: Boolean,
    onAnswer: (AskOption) -> Unit,
    onOpen: () -> Unit,
) {
    val ask = thread.ask ?: return
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .clickable(onClick = onOpen)
            .padding(16.dp),
    ) {
        Text("$index / $total", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
        Text(
            ask.question ?: thread.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ask.options.take(2).forEach { option ->
                Text(
                    option.label,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                        .clickable(enabled = enabled) { onAnswer(option) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
