package tech.asahiart.luvia.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable

@Serializable
private data object HostsRoute : NavKey

@Serializable
private data class HostRoute(val id: String) : NavKey

@Serializable
private data class TerminalRoute(val hostId: String) : NavKey

@Serializable
private data object PairHostRoute : NavKey

@Composable
fun LuviaNavigation(
    hosts: List<HostUiModel>,
    terminalForHost: (String) -> TerminalUiModel?,
    onPairHost: (host: String, user: String, port: Int, fingerprint: String) -> Unit,
    onRequestControl: (String) -> Unit,
    onSendTerminalText: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    MaterialTheme {
        val backStack = rememberNavBackStack(HostsRoute)
        BoxWithConstraints(modifier.fillMaxSize()) {
            val twoPane = maxWidth >= 600.dp
            if (twoPane) {
                Row(Modifier.fillMaxSize()) {
                    HostListPane(
                        hosts = hosts,
                        selectedHostId = (backStack.lastOrNull { it is HostRoute } as? HostRoute)?.id,
                        onSelect = { id ->
                            backStack.removeAll { it is HostRoute || it is TerminalRoute || it is PairHostRoute }
                            backStack.add(HostRoute(id))
                        },
                        onAddHost = {
                            backStack.removeAll { it is PairHostRoute }
                            backStack.add(PairHostRoute)
                        },
                        modifier = Modifier.width(320.dp).fillMaxHeight(),
                    )
                    VerticalDivider()
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        DetailNav(
                            backStack = backStack,
                            hosts = hosts,
                            terminalForHost = terminalForHost,
                            onPairHost = onPairHost,
                            onRequestControl = onRequestControl,
                            onSendTerminalText = onSendTerminalText,
                            showList = false,
                        )
                    }
                }
            } else {
                DetailNav(
                    backStack = backStack,
                    hosts = hosts,
                    terminalForHost = terminalForHost,
                    onPairHost = onPairHost,
                    onRequestControl = onRequestControl,
                    onSendTerminalText = onSendTerminalText,
                    showList = true,
                )
            }
        }
    }
}

@Composable
private fun DetailNav(
    backStack: NavBackStack<NavKey>,
    hosts: List<HostUiModel>,
    terminalForHost: (String) -> TerminalUiModel?,
    onPairHost: (host: String, user: String, port: Int, fingerprint: String) -> Unit,
    onRequestControl: (String) -> Unit,
    onSendTerminalText: (String, String) -> Unit,
    showList: Boolean,
) {
    NavDisplay(
        modifier = Modifier.fillMaxSize(),
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<HostsRoute> {
                if (showList) {
                    HostListPane(
                        hosts = hosts,
                        selectedHostId = (backStack.lastOrNull { it is HostRoute } as? HostRoute)?.id,
                        onSelect = { id ->
                            backStack.removeAll { it is HostRoute || it is TerminalRoute || it is PairHostRoute }
                            backStack.add(HostRoute(id))
                        },
                        onAddHost = {
                            backStack.removeAll { it is PairHostRoute }
                            backStack.add(PairHostRoute)
                        },
                    )
                } else {
                    EmptySelectionPane("Select a host", "Choose a paired host to inspect its sessions.")
                }
            }
            entry<HostRoute> { route ->
                val host = hosts.firstOrNull { it.id == route.id }
                if (host == null) {
                    EmptySelectionPane("Host unavailable", "The saved host was removed.")
                } else {
                    var section by remember(route.id) { mutableStateOf(HostSection.Overview) }
                    HostDetailPane(
                        host = host,
                        section = section,
                        onSection = { next ->
                            section = next
                            if (next == HostSection.Terminal) {
                                backStack.removeAll { it is TerminalRoute }
                                backStack.add(TerminalRoute(route.id))
                            } else {
                                backStack.removeAll { it is TerminalRoute }
                            }
                        },
                        terminal = terminalForHost(route.id),
                        onRequestControl = { onRequestControl(route.id) },
                        onSendText = { text -> onSendTerminalText(route.id, text) },
                    )
                }
            }
            entry<TerminalRoute> { route ->
                val terminal = terminalForHost(route.hostId)
                if (terminal == null) {
                    EmptySelectionPane("Terminal unavailable", "Select a live pane to observe or request control.")
                } else {
                    TerminalPane(
                        terminal = terminal,
                        onRequestControl = { onRequestControl(route.hostId) },
                        onSendText = { text -> onSendTerminalText(route.hostId, text) },
                    )
                }
            }
            entry<PairHostRoute> {
                var host by remember { mutableStateOf("") }
                var user by remember { mutableStateOf("") }
                var port by remember { mutableStateOf("22") }
                var fingerprint by remember { mutableStateOf("") }
                PairHostPane(
                    host = host,
                    user = user,
                    port = port,
                    fingerprint = fingerprint,
                    publicKey = "",
                    onHostChange = { host = it },
                    onUserChange = { user = it },
                    onPortChange = { port = it.filter(Char::isDigit).take(5) },
                    onFingerprintChange = { fingerprint = it },
                    onContinue = {
                        val parsedPort = port.toIntOrNull()
                        if (host.isNotBlank() && user.isNotBlank() && fingerprint.isNotBlank() && parsedPort != null) {
                            onPairHost(host.trim(), user.trim(), parsedPort, fingerprint.trim())
                        }
                    },
                    onCancel = { backStack.removeLastOrNull() },
                )
            }
        },
    )
}

@Composable
private fun EmptySelectionPane(title: String, message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
