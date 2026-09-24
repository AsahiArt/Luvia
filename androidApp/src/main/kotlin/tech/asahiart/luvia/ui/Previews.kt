package tech.asahiart.luvia.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import tech.asahiart.luvia.AgentGetResult
import tech.asahiart.luvia.AgentReadResult
import tech.asahiart.luvia.AgentStatus
import tech.asahiart.luvia.AgentSummary
import tech.asahiart.luvia.DiffFile
import tech.asahiart.luvia.DiffLayer
import tech.asahiart.luvia.DiffListResult
import tech.asahiart.luvia.TaskSummary
import tech.asahiart.luvia.ui.theme.LuviaTheme
import tech.asahiart.luvia.HostUhpState
import tech.asahiart.luvia.HostCapabilities
import tech.asahiart.luvia.AgentDetailState
import tech.asahiart.luvia.HostSection
import tech.asahiart.luvia.ReviewState
import tech.asahiart.luvia.TasksState

@Preview(name = "Phone", device = Devices.PHONE, showBackground = true)
@Preview(name = "Foldable", device = Devices.FOLDABLE, showBackground = true)
@Preview(name = "Tablet", device = Devices.TABLET, showBackground = true)
annotation class FormFactorPreviews

@FormFactorPreviews
@Composable
private fun HostListPreview() {
    LuviaTheme {
        HostListPane(
            hosts = listOf(
                HostUiModel(
                    id = "studio",
                    name = "Studio",
                    address = "studio.tailnet.ts.net",
                    sessionName = "main",
                    connection = ConnectionBadge.Live,
                    workingAgents = 2,
                    blockedAgents = 1,
                    completedAgents = 5,
                    lastUpdatedEpochMs = System.currentTimeMillis() - 3_000,
                    connected = true,
                ),
                HostUiModel(
                    id = "laptop",
                    name = "Laptop",
                    address = "192.168.1.24",
                    sessionName = null,
                    connection = ConnectionBadge.Stale,
                    lastUpdatedEpochMs = System.currentTimeMillis() - 120_000,
                    errorMessage = "Host key changed. Re-pair this Host.",
                ),
            ),
            selectedHostId = "studio",
            onSelect = {},
            onAddHost = {},
        )
    }
}

@Preview(name = "Empty hosts", showBackground = true)
@Composable
private fun EmptyHostsPreview() {
    LuviaTheme {
        HostListPane(
            hosts = emptyList(),
            selectedHostId = null,
            onSelect = {},
            onAddHost = {},
        )
    }
}

@FormFactorPreviews
@Composable
private fun HostDetailPreview() {
    LuviaTheme {
        HostDetailPane(
            host = previewHost(),
            section = HostSection.Agents,
            onSection = {},
            agentsContent = { modifier ->
                AgentsSection(
                    host = previewHost(),
                    state = previewAgentsState(),
                    onRefresh = {},
                    onOpenAgent = {},
                    onCheckUnconfirmed = {},
                    modifier = modifier,
                )
            },
        )
    }
}

@Preview(name = "Pairing command", showBackground = true)
@Composable
private fun PairCommandPreview() {
    LuviaTheme {
        PairHostPane(
            command = "luvia-host pair --name 'Pixel 9' --role controller --key 'ssh-ed25519 AAAA...'",
            authorizedKeysLine = "ssh-ed25519 AAAA...",
            fingerprint = "SHA256:abcdefghijklmnopqrstuvwxyz0123456789ABCDE",
            errorMessage = null,
            completing = false,
            onBegin = { _, _ -> },
            onCopyCommand = {},
            onComplete = { _, _, _, _ -> },
            onCancel = {},
        )
    }
}

@Preview(name = "Pairing label", showBackground = true)
@Composable
private fun PairLabelPreview() {
    LuviaTheme {
        PairHostPane(
            command = null,
            authorizedKeysLine = null,
            fingerprint = null,
            errorMessage = null,
            completing = false,
            onBegin = { _, _ -> },
            onCopyCommand = {},
            onComplete = { _, _, _, _ -> },
            onCancel = {},
        )
    }
}

@FormFactorPreviews
@Composable
private fun AgentsListPreview() {
    LuviaTheme {
        AgentsSection(
            host = previewHost(),
            state = previewAgentsState(),
            onRefresh = {},
            onOpenAgent = {},
            onCheckUnconfirmed = {},
        )
    }
}

@FormFactorPreviews
@Composable
private fun ReviewFileListPreview() {
    LuviaTheme {
        ReviewSection(
            host = previewHost(),
            state = previewAgentsState().copy(
                review = ReviewState(
                    list = DiffListResult(
                        repo = "luvia",
                        branch = "main",
                        generation = 3,
                        fingerprint = null,
                        omitted = 0,
                        refreshing = false,
                        files = listOf(
                            DiffFile(
                                path = "androidApp/src/main/kotlin/tech/asahiart/luvia/ui/AgentScreens.kt",
                                layer = DiffLayer.WORKTREE,
                                status = "modified",
                                additions = 120,
                                deletions = 8,
                            ),
                            DiffFile(
                                path = "shared/src/commonMain/kotlin/tech/asahiart/luvia/Client.kt",
                                layer = DiffLayer.STAGED,
                                status = "modified",
                                additions = 12,
                                deletions = 2,
                            ),
                        ),
                    ),
                ),
            ),
            onRefresh = {},
            onOpenFile = { _, _ -> },
            onCloseFile = {},
            onAddNote = { _, _, _, _ -> },
            onResolveNote = {},
            onReopenNote = {},
            onRemoveNote = {},
            onSendNotes = {},
            onCheckUnconfirmed = {},
            onNoteDraftChange = {},
            onSendTargetChange = {},
        )
    }
}

@FormFactorPreviews
@Composable
private fun TasksListPreview() {
    LuviaTheme {
        TasksSection(
            host = previewHost(),
            state = previewAgentsState().copy(
                tasks = TasksState(
                    tasks = listOf(
                        TaskSummary(id = "t1", title = "Build the UHP-first phone surface", status = "running"),
                        TaskSummary(id = "t2", title = "Answer Blocked Agent prompts", status = "blocked"),
                        TaskSummary(id = "t3", title = "Pair the first Device", status = "done"),
                    ),
                ),
            ),
            onRefresh = {},
            onAddTask = { _, _ -> },
            onCompleteTask = {},
            onCheckUnconfirmed = {},
            onShowAddChange = {},
            onCompleteIdChange = {},
            onAddDraftChange = { _, _ -> },
        )
    }
}

@Preview(name = "Connecting host", showBackground = true)
@Composable
private fun ConnectingHostPreview() {
    LuviaTheme {
        HostDetailPane(
            host = previewHost().copy(
                connection = ConnectionBadge.Connecting,
                connected = true,
                hasSnapshot = true,
            ),
            section = HostSection.Agents,
            onSection = {},
            state = previewAgentsState(),
            attentionCount = 1,
            agentsContent = { modifier ->
                AgentsSection(
                    host = previewHost().copy(connection = ConnectionBadge.Connecting, hasSnapshot = true),
                    state = previewAgentsState(),
                    onRefresh = {},
                    onOpenAgent = {},
                    onCheckUnconfirmed = {},
                    modifier = modifier,
                )
            },
        )
    }
}

@Preview(name = "Workspace pick", showBackground = true)
@Composable
private fun WorkspacePickPreview() {
    LuviaTheme {
        EmptyState(
            title = "Select a project",
            message = "Review and Tasks use the project you pick, not the TUI focus.",
        )
    }
}


private fun previewHost() = HostUiModel(
    id = "studio",
    name = "Studio",
    address = "studio.tailnet.ts.net",
    sessionName = "main",
    connection = ConnectionBadge.Live,
    workingAgents = 2,
    blockedAgents = 1,
    completedAgents = 5,
    activeTask = "Build the UHP-first phone surface",
    lastUpdatedEpochMs = System.currentTimeMillis() - 8_000,
    connected = true,
)

private fun previewBlockedAgent() = AgentSummary(
    paneId = "7",
    name = "pi",
    status = AgentStatus.Blocked,
    agent = "claude",
    workspaceName = "luvia",
    branch = "main",
    cwd = "/Users/misaka/Developer/AsahiArt/Luvia",
    focused = true,
)

private fun previewAgentsState() = HostUhpState(
    connected = true,
    isObserver = false,
    capabilities = HostCapabilities(
        agentRead = true,
        agentPrompt = true,
        agentKeys = true,
        missionSnapshot = true,
        diffList = true,
        diffGet = true,
        diffNoteList = true,
        diffNoteAdd = true,
        diffNoteSend = true,
        diffNoteResolve = true,
        diffNoteReopen = true,
        diffNoteRemove = true,
        taskList = true,
        taskAdd = true,
        taskDone = true,
        taskGet = true,
    ),
    agents = listOf(
        previewBlockedAgent(),
        AgentSummary(
            paneId = "8",
            name = "worker",
            status = AgentStatus.Working,
            agent = "codex",
            workspaceName = "luvia",
            branch = "main",
        ),
    ),
)
