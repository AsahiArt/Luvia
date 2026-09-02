package tech.asahiart.luvia.ui

import androidx.compose.material3.MaterialTheme
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

@Preview(name = "Phone", device = Devices.PHONE, showBackground = true)
@Preview(name = "Foldable", device = Devices.FOLDABLE, showBackground = true)
@Preview(name = "Tablet", device = Devices.TABLET, showBackground = true)
annotation class FormFactorPreviews

@FormFactorPreviews
@Composable
private fun HostListPreview() {
    MaterialTheme {
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
                    connected = true,
                ),
                HostUiModel(
                    id = "laptop",
                    name = "Laptop",
                    address = "192.168.1.24",
                    sessionName = null,
                    connection = ConnectionBadge.Stale,
                    errorMessage = "Host key changed",
                ),
            ),
            selectedHostId = "studio",
            onSelect = {},
            onAddHost = {},
        )
    }
}

@FormFactorPreviews
@Composable
private fun HostDetailPreview() {
    MaterialTheme {
        HostDetailPane(
            host = previewHost(),
            section = HostSection.Agents,
            onSection = {},
            terminal = null,
            onRequestControl = {},
            onSendText = {},
            agentsContent = { modifier ->
                AgentsSection(
                    host = previewHost(),
                    state = previewAgentsState(),
                    onRefresh = {},
                    onOpenAgent = {},
                    onCloseAgent = {},
                    onPrompt = {},
                    onSendKeys = {},
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
    MaterialTheme {
        PairHostPane(
            command = "luvia-host pair --name 'Pixel 9' --role controller --key 'ssh-ed25519 AAAA...'",
            authorizedKeysLine = "ssh-ed25519 AAAA...",
            fingerprint = "SHA256:abcdefghijklmnopqrstuvwxyz0123456789ABCDE",
            errorMessage = null,
            completing = false,
            onBegin = { _, _ -> },
            onCopyCommand = {},
            onComplete = {},
            onCancel = {},
        )
    }
}

@Preview(name = "Pairing label", showBackground = true)
@Composable
private fun PairLabelPreview() {
    MaterialTheme {
        PairHostPane(
            command = null,
            authorizedKeysLine = null,
            fingerprint = null,
            errorMessage = null,
            completing = false,
            onBegin = { _, _ -> },
            onCopyCommand = {},
            onComplete = {},
            onCancel = {},
        )
    }
}

@FormFactorPreviews
@Composable
private fun AgentsListPreview() {
    MaterialTheme {
        AgentsSection(
            host = previewHost(),
            state = previewAgentsState(),
            onRefresh = {},
            onOpenAgent = {},
            onCloseAgent = {},
            onPrompt = {},
            onSendKeys = {},
            onCheckUnconfirmed = {},
        )
    }
}

@FormFactorPreviews
@Composable
private fun AgentDetailBlockedPreview() {
    MaterialTheme {
        AgentsSection(
            host = previewHost(),
            state = previewAgentsState().copy(
                agentDetail = AgentDetailUi(
                    paneId = "7",
                    summary = previewBlockedAgent(),
                    detail = AgentGetResult(
                        pane = "7",
                        name = "pi",
                        agent = "claude",
                        status = AgentStatus.Blocked,
                        authority = null,
                        stateSource = null,
                        session = null,
                        cwd = "/Users/misaka/Developer/AsahiArt/Luvia",
                        revision = 12,
                    ),
                    transcript = AgentReadResult(
                        pane = "7",
                        text = "Allow network access to api.example.com?\nProceed? (y/n)",
                        revision = 44,
                    ),
                ),
            ),
            onRefresh = {},
            onOpenAgent = {},
            onCloseAgent = {},
            onPrompt = {},
            onSendKeys = {},
            onCheckUnconfirmed = {},
        )
    }
}

@FormFactorPreviews
@Composable
private fun ReviewFileListPreview() {
    MaterialTheme {
        ReviewSection(
            host = previewHost(),
            state = previewAgentsState().copy(
                review = ReviewUiState(
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
        )
    }
}

@FormFactorPreviews
@Composable
private fun TasksListPreview() {
    MaterialTheme {
        TasksSection(
            host = previewHost(),
            state = previewAgentsState().copy(
                tasks = TasksUiState(
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

private fun previewAgentsState() = HostUhpUiState(
    connected = true,
    isObserver = false,
    capabilities = HostCapabilitiesUi(
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
