package tech.asahiart.luvia.ui

import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import tech.asahiart.luvia.thread.AgentThread
import tech.asahiart.luvia.thread.AskOption
import tech.asahiart.luvia.thread.NowState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tech.asahiart.luvia.HostUhpState
import tech.asahiart.luvia.thread.threads

/** Host → Project. A Host's projects come from its workspaces and Agents; never merged across Hosts. */
@Composable
fun ProjectsPane(
    hosts: List<HostUiModel>,
    states: Map<String, HostUhpState>,
    onOpenProject: (hostId: String, workspaceId: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Text(
            "Projects",
            style = MaterialTheme.typography.headlineMedium,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
        )
        if (hosts.isEmpty()) {
            EmptyState(title = "No Hosts", message = "Pair a Host to see its projects.")
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            for (host in hosts) {
                val state = states[host.id]
                item(key = "h:${host.id}") {
                    Row(
                        Modifier.padding(top = 20.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(host.name, style = MaterialTheme.typography.titleSmall)
                        Text(host.connection.label(), style = MaterialTheme.typography.labelMedium, color = host.connection.color())
                        if (host.isObserver) {
                            Text("Observer", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                val threads = state?.threads(host.id).orEmpty()
                val choices = state?.projectChoices().orEmpty()
                if (choices.isEmpty()) {
                    item(key = "e:${host.id}") {
                        ProjectLine(
                            title = "All threads",
                            detail = "${threads.size} threads",
                            needsYou = threads.count { it.needsYou },
                            onClick = { onOpenProject(host.id, null) },
                        )
                    }
                } else {
                    items(choices, key = { "p:${host.id}:${it.id}" }) { choice ->
                        val mine = threads.filter { it.projectKey == choice.id }
                        ProjectLine(
                            title = choice.label,
                            detail = "${mine.size} threads",
                            needsYou = mine.count { it.needsYou },
                            onClick = { onOpenProject(host.id, choice.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectLine(title: String, detail: String, needsYou: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (needsYou > 0) {
            Text("● $needsYou", style = MaterialTheme.typography.labelMedium, color = tech.asahiart.luvia.ui.theme.LuviaTheme.extended.agentBlocked)
        }
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

/** Two top-level destinations: Now and Projects. */
@Composable
fun HomePane(
    now: NowState,
    hosts: List<HostUiModel>,
    states: Map<String, HostUhpState>,
    onOpenThread: (AgentThread) -> Unit,
    onAnswer: (AgentThread, AskOption) -> Unit,
    onOpenHosts: () -> Unit,
    onAddHost: () -> Unit,
    onRefreshAll: () -> Unit,
    onOpenProject: (hostId: String, workspaceId: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier.fillMaxSize()) {
        if (tab == 0) {
            NowPane(
                now = now,
                hasHosts = hosts.isNotEmpty(),
                onOpenThread = onOpenThread,
                onAnswer = onAnswer,
                onOpenHosts = onOpenHosts,
                onAddHost = onAddHost,
                onRefreshAll = onRefreshAll,
                modifier = Modifier.weight(1f),
            )
        } else {
            ProjectsPane(hosts = hosts, states = states, onOpenProject = onOpenProject, modifier = Modifier.weight(1f))
        }
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            NavigationBarItem(
                selected = tab == 0,
                onClick = { tab = 0 },
                icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                label = { Text(if (now.needsYouCount > 0) "Now · ${now.needsYouCount}" else "Now") },
            )
            NavigationBarItem(
                selected = tab == 1,
                onClick = { tab = 1 },
                icon = { Icon(Icons.Filled.List, contentDescription = null) },
                label = { Text("Projects") },
            )
        }
    }
}
