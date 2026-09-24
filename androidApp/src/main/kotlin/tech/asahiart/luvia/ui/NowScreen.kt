package tech.asahiart.luvia.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.asahiart.luvia.ConnectionFreshness
import tech.asahiart.luvia.thread.AgentThread
import tech.asahiart.luvia.thread.AskOption
import tech.asahiart.luvia.thread.NowGroup
import tech.asahiart.luvia.thread.NowState

/** Home: who needs you, who is working, what just finished — across every Host. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPane(
    now: NowState,
    hasHosts: Boolean,
    onOpenThread: (AgentThread) -> Unit,
    onAnswer: (AgentThread, AskOption) -> Unit,
    onOpenHosts: () -> Unit,
    onAddHost: () -> Unit,
    onRefreshAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Now",
                style = MaterialTheme.typography.headlineMedium,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenHosts, modifier = Modifier.semantics { contentDescription = "Hosts and settings" }) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(32.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("◉", color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        }
        if (!hasHosts) {
            EmptyState(
                title = "No Hosts",
                message = "Pair a machine running Luvus to see its Agents here.",
                action = "Add host",
                onAction = onAddHost,
            )
            return@Column
        }
        PullToRefreshBox(isRefreshing = false, onRefresh = onRefreshAll, modifier = Modifier.weight(1f)) {
            if (now.isQuiet) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("All clear", style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Serif)
                        Text(
                            "No Agent needs you right now.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    section("Needs you", now.needsYou) { group, thread ->
                        Column(Modifier.padding(bottom = 12.dp)) {
                            ThreadLine(thread, divider = false, onClick = { onOpenThread(thread) })
                            thread.ask?.let { ask ->
                                AttentionCard(
                                    ask = ask,
                                    canAnswer = !group.isObserver && group.freshness != ConnectionFreshness.Offline,
                                    isObserver = group.isObserver,
                                    onAnswer = { onAnswer(thread, it) },
                                )
                            }
                        }
                    }
                    section("In progress", now.inProgress) { _, thread ->
                        ThreadLine(thread, onClick = { onOpenThread(thread) })
                    }
                    section("Just finished", now.recentlyDone) { _, thread ->
                        ThreadLine(thread, onClick = { onOpenThread(thread) })
                    }
                }
            }
        }
    }
}

private fun LazyListScope.section(
    title: String,
    groups: List<NowGroup>,
    row: @Composable (NowGroup, AgentThread) -> Unit,
) {
    if (groups.isEmpty()) return
    item(key = "h:$title") {
        Text(
            "$title  ${groups.sumOf { it.threads.size }}",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
        )
    }
    for (group in groups) {
        item(key = "g:$title:${group.hostId}") { HostGroupHeader(group) }
        items(group.threads, key = { "t:$title:${group.hostId}:${it.id}" }) { thread -> row(group, thread) }
    }
}

@Composable
fun HostGroupHeader(group: NowGroup, modifier: Modifier = Modifier) {
    val badge = when (group.freshness) {
        ConnectionFreshness.Live -> ConnectionBadge.Live
        ConnectionFreshness.Stale -> ConnectionBadge.Stale
        ConnectionFreshness.Offline -> ConnectionBadge.Offline
    }
    val glyph = when (group.freshness) {
        ConnectionFreshness.Live -> "●"
        ConnectionFreshness.Stale -> "◌ stale"
        ConnectionFreshness.Offline -> "○ offline"
    }
    Row(modifier.padding(top = 8.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(group.hostName, style = MaterialTheme.typography.labelLarge)
        Text(glyph, style = MaterialTheme.typography.labelLarge, color = badge.color())
        if (group.isObserver) {
            Text("Observer", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ThreadLine(thread: AgentThread, onClick: () -> Unit, modifier: Modifier = Modifier, divider: Boolean = true) {
    Column(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                listOfNotNull(thread.title, thread.projectLabel).joinToString(" · "),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            StatusGlyph(thread.status)
        }
        thread.summary?.takeIf { thread.ask == null }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (divider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}
