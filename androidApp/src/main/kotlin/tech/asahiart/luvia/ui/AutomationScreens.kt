@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package tech.asahiart.luvia.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import tech.asahiart.luvia.AgentSummary
import tech.asahiart.luvia.Automation
import tech.asahiart.luvia.AutomationDraft
import tech.asahiart.luvia.AutomationHealthResult
import tech.asahiart.luvia.AutomationPolicySpec
import tech.asahiart.luvia.AutomationRun
import tech.asahiart.luvia.AutomationTarget
import tech.asahiart.luvia.AutomationTaskSpec
import tech.asahiart.luvia.AutomationTrigger
import tech.asahiart.luvia.AutomationView
import tech.asahiart.luvia.HostUhpState
import tech.asahiart.luvia.WorkspaceSummary

private enum class TriggerKind { Once, Interval, Daily, Weekly }

@Composable
fun AutomationsSection(
    host: HostUiModel,
    state: HostUhpState,
    onRefresh: () -> Unit,
    onEnable: (String) -> Unit,
    onDisable: (String) -> Unit,
    onRun: (String) -> Unit,
    onCreate: (AutomationDraft) -> Unit,
    onUpdate: (String, AutomationDraft) -> Unit,
    onDelete: (String) -> Unit,
    onRebind: (id: String, pane: String, terminalId: String?) -> Unit,
    onLoadHistory: (String) -> Unit,
    onPreview: (AutomationTrigger) -> Unit,
    onClearPreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        host.shouldShowOfflineEmpty(state.connected) -> {
            UhpEmptyPane(title = "Automations", message = host.offlineEmptyMessage(), modifier = modifier)
        }
        !state.capabilities.automationList -> {
            UhpEmptyPane(
                title = "Automations",
                message = "Automations are not available on this host.",
                modifier = modifier,
            )
        }
        else -> {
            AutomationListPane(
                state = state,
                onRefresh = onRefresh,
                onEnable = onEnable,
                onDisable = onDisable,
                onRun = onRun,
                onCreate = onCreate,
                onUpdate = onUpdate,
                onDelete = onDelete,
                onRebind = onRebind,
                onLoadHistory = onLoadHistory,
                onPreview = onPreview,
                onClearPreview = onClearPreview,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun AutomationListPane(
    state: HostUhpState,
    onRefresh: () -> Unit,
    onEnable: (String) -> Unit,
    onDisable: (String) -> Unit,
    onRun: (String) -> Unit,
    onCreate: (AutomationDraft) -> Unit,
    onUpdate: (String, AutomationDraft) -> Unit,
    onDelete: (String) -> Unit,
    onRebind: (id: String, pane: String, terminalId: String?) -> Unit,
    onLoadHistory: (String) -> Unit,
    onPreview: (AutomationTrigger) -> Unit,
    onClearPreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val automations = state.automations
    val canMutate = state.canMutate
    val canEnable = canMutate && state.capabilities.automationEnable
    val canDisable = canMutate && state.capabilities.automationDisable
    val canRun = canMutate && state.capabilities.automationRun
    val healthById = automations.health?.automations?.associateBy { it.id }.orEmpty()
    var editor by remember { mutableStateOf<Automation?>(null) }
    var creating by remember { mutableStateOf(false) }
    var historyId by remember { mutableStateOf<String?>(null) }
    var rebindId by remember { mutableStateOf<String?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }

    Box(modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = automations.loading,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text("Automations", style = MaterialTheme.typography.titleSmall)
                }
                automations.health?.let { health ->
                    item { AutomationHealthLine(health) }
                }
                automations.errorText?.let { error ->
                    item {
                        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (automations.automations.isEmpty() && !automations.loading) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("No automations yet.", style = MaterialTheme.typography.headlineSmall)
                            Text(
                                if (canMutate) "Create one to run a task on a schedule." else "This host has no automations.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(automations.automations, key = { it.id }) { automation ->
                    AutomationCard(
                        automation = automation,
                        view = healthById[automation.id],
                        canEnable = canEnable && !automations.mutating,
                        canDisable = canDisable && !automations.mutating,
                        canRun = canRun && !automations.mutating,
                        canMutate = canMutate && !automations.mutating,
                        onEnable = { onEnable(automation.id) },
                        onDisable = { onDisable(automation.id) },
                        onRun = { onRun(automation.id) },
                        onEdit = { editor = automation },
                        onHistory = {
                            historyId = automation.id
                            onLoadHistory(automation.id)
                        },
                        onRebind = { rebindId = automation.id },
                        onDelete = { deleteId = automation.id },
                    )
                }
            }
        }
        if (canMutate) {
            ExtendedFloatingActionButton(
                text = { Text("New automation") },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = { creating = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }

    if (creating || editor != null) {
        AutomationEditorSheet(
            existing = editor,
            state = state,
            onDismiss = {
                creating = false
                editor = null
                onClearPreview()
            },
            onSave = { draft ->
                val existing = editor
                if (existing == null) onCreate(draft) else onUpdate(existing.id, draft)
            },
            onPreview = onPreview,
            onClearPreview = onClearPreview,
        )
    }
    historyId?.let { id ->
        AutomationHistorySheet(
            automation = automations.automations.firstOrNull { it.id == id },
            runs = automations.history[id].orEmpty(),
            loading = automations.historyLoading == id,
            onDismiss = { historyId = null },
        )
    }
    rebindId?.let { id ->
        AutomationRebindSheet(
            agents = state.agents,
            onDismiss = { rebindId = null },
            onPick = { pane ->
                onRebind(id, pane, null)
                rebindId = null
            },
        )
    }
    deleteId?.let { id ->
        val name = automations.automations.firstOrNull { it.id == id }?.name ?: id
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text("Delete automation?") },
            text = { Text("Delete \"$name\". This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteId = null
                        onDelete(id)
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteId = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun AutomationHealthLine(health: AutomationHealthResult) {
    val summary = health.summary
    Text(
        buildString {
            append(summary.definitions)
            append(" defined · ")
            append(summary.enabled)
            append(" enabled · ")
            append(summary.scheduled)
            append(" scheduled · ")
            append(summary.running)
            append(" running")
            if (summary.review > 0) {
                append(" · ")
                append(summary.review)
                append(" review")
            }
            if (summary.failed > 0) {
                append(" · ")
                append(summary.failed)
                append(" failed")
            }
            relativeTime(summary.nextRunAt)?.let {
                append(" · next ")
                append(it)
            }
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AutomationCard(
    automation: Automation,
    view: AutomationView?,
    canEnable: Boolean,
    canDisable: Boolean,
    canRun: Boolean,
    canMutate: Boolean,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onHistory: () -> Unit,
    onRebind: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val needsRebind = (automation.targetState ?: view?.targetState).equals("needs_rebind", ignoreCase = true)
    Card(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    automation.name.ifBlank { automation.id },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (canEnable || canDisable) {
                    Switch(
                        checked = automation.enabled,
                        onCheckedChange = { checked -> if (checked) onEnable() else onDisable() },
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Automation actions")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (canRun) {
                            DropdownMenuItem(
                                text = { Text("Run now") },
                                onClick = {
                                    menuOpen = false
                                    onRun()
                                },
                            )
                        }
                        if (canMutate) {
                            DropdownMenuItem(
                                text = { Text("Edit") },
                                onClick = {
                                    menuOpen = false
                                    onEdit()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("History") },
                            onClick = {
                                menuOpen = false
                                onHistory()
                            },
                        )
                        if (canMutate) {
                            DropdownMenuItem(
                                text = { Text("Rebind") },
                                onClick = {
                                    menuOpen = false
                                    onRebind()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                onClick = {
                                    menuOpen = false
                                    onDelete()
                                },
                            )
                        }
                    }
                }
            }
            Text(
                triggerSummary(automation.trigger),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AutomationStatusChip(targetChipLabel(automation.target), tonal = true)
                if (needsRebind) {
                    AutomationStatusChip(
                        "Needs rebind",
                        container = MaterialTheme.colorScheme.errorContainer,
                        content = MaterialTheme.colorScheme.onErrorContainer,
                        onClick = if (canMutate) onRebind else null,
                    )
                }
                view?.latestStatus?.takeIf { it.isNotBlank() }?.let { status ->
                    AutomationStatusChip(status.replaceFirstChar { it.titlecase(Locale.getDefault()) }, status = status)
                }
            }
            val next = relativeTime(automation.nextRunAt ?: view?.nextRunAt)
            val last = view?.latestStatus?.let { status ->
                buildString {
                    append(status.replaceFirstChar { it.titlecase(Locale.getDefault()) })
                    view.latestError?.takeIf { it.isNotBlank() }?.let {
                        append(" · ")
                        append(it)
                    }
                }
            }
            if (next != null || last != null) {
                Text(
                    buildString {
                        if (next != null) {
                            append("Next ")
                            append(next)
                        }
                        if (last != null) {
                            if (isNotEmpty()) append(" · ")
                            append("Last ")
                            append(last)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AutomationEditorSheet(
    existing: Automation?,
    state: HostUhpState,
    onDismiss: () -> Unit,
    onSave: (AutomationDraft) -> Unit,
    onPreview: (AutomationTrigger) -> Unit,
    onClearPreview: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val deviceTz = remember { ZoneId.systemDefault().id }
    val seed = existing?.trigger
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }
    var kind by remember { mutableStateOf(triggerKindOf(seed)) }
    var timezone by remember {
        mutableStateOf(
            when (seed) {
                is AutomationTrigger.Daily -> seed.timezone
                is AutomationTrigger.Weekly -> seed.timezone
                else -> deviceTz
            },
        )
    }
    var secondOfDay by remember {
        mutableIntStateOf(
            when (seed) {
                is AutomationTrigger.Daily -> seed.secondOfDay
                is AutomationTrigger.Weekly -> seed.secondOfDay
                else -> 9 * 3600
            },
        )
    }
    var weekdays by remember {
        mutableStateOf(
            (seed as? AutomationTrigger.Weekly)?.weekdays?.toSet() ?: setOf(1),
        )
    }
    var everySeconds by remember {
        mutableLongStateOf((seed as? AutomationTrigger.Interval)?.everySeconds ?: 1800L)
    }
    var onceAtUtc by remember {
        mutableLongStateOf(
            (seed as? AutomationTrigger.Once)?.atUtc
                ?: Instant.now().plusSeconds(3600).epochSecond,
        )
    }
    var newWorker by remember { mutableStateOf(existing?.target !is AutomationTarget.ActiveAgent) }
    val active = existing?.target as? AutomationTarget.ActiveAgent
    var paneId by remember { mutableStateOf(active?.paneId.orEmpty()) }
    var ifBusy by remember { mutableStateOf(active?.ifBusy ?: "wait") }
    val taskSeed = existing?.task
    var title by remember { mutableStateOf(taskSeed?.title.orEmpty()) }
    var prompt by remember { mutableStateOf(taskSeed?.prompt.orEmpty()) }
    var agentId by remember { mutableStateOf(taskSeed?.agentId.orEmpty()) }
    var workspaceId by remember { mutableStateOf(taskSeed?.workspaceId.orEmpty()) }
    var mode by remember { mutableStateOf(taskSeed?.mode.orEmpty()) }
    var access by remember { mutableStateOf(taskSeed?.access.orEmpty()) }
    var pathsText by remember { mutableStateOf(taskSeed?.paths?.joinToString(", ").orEmpty()) }
    var gate by remember { mutableStateOf(taskSeed?.gate.orEmpty()) }
    var showPolicy by remember { mutableStateOf(existing?.policy != null) }
    var misfire by remember { mutableStateOf(existing?.policy?.misfire ?: "run_latest") }
    var overlap by remember { mutableStateOf(existing?.policy?.overlap ?: "skip") }
    var grace by remember { mutableStateOf(existing?.policy?.misfireGraceSeconds?.toString().orEmpty()) }

    val trigger = remember(kind, timezone, secondOfDay, weekdays, everySeconds, onceAtUtc) {
        buildTrigger(kind, timezone, secondOfDay, weekdays.toList().sorted(), everySeconds, onceAtUtc)
    }
    var submitted by remember { mutableStateOf(false) }
    var sawMutating by remember { mutableStateOf(false) }
    LaunchedEffect(trigger) {
        delay(300)
        onPreview(trigger)
    }
    LaunchedEffect(state.automations.mutating) {
        if (state.automations.mutating) sawMutating = true
    }
    LaunchedEffect(submitted, sawMutating, state.automations.mutating, state.automations.editorError) {
        if (submitted && sawMutating && !state.automations.mutating && state.automations.editorError == null) {
            onDismiss()
        }
    }

    val agentChoices = remember(state.acp.agents, state.agents) {
        buildList {
            state.acp.agents.forEach { add(it.id to it.name) }
            state.agents.mapNotNull { agent ->
                val id = agent.agent?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                id to (agent.name?.takeIf { it.isNotBlank() } ?: id)
            }.forEach { add(it) }
        }.distinctBy { it.first }
    }
    val workspaces = state.layout.workspaces.ifEmpty {
        state.agents.mapNotNull { agent ->
            val id = agent.workspaceId ?: agent.workspace ?: return@mapNotNull null
            WorkspaceSummary(
                index = 0,
                name = agent.workspaceName ?: id,
                pinned = false,
                active = false,
                tabCount = 0,
            )
        }.distinctBy { it.name }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (existing == null) "New automation" else "Edit automation",
                style = MaterialTheme.typography.headlineSmall,
            )
            state.automations.editorError?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Enabled", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
            Text("Trigger", style = MaterialTheme.typography.titleSmall)
            val kinds = TriggerKind.entries
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                kinds.forEachIndexed { index, item ->
                    SegmentedButton(
                        selected = kind == item,
                        onClick = { kind = item },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = kinds.size),
                    ) { Text(item.name) }
                }
            }
            when (kind) {
                TriggerKind.Once -> {
                    OutlinedTextField(
                        value = formatEpoch(onceAtUtc) ?: "",
                        onValueChange = { parsed -> parseEpoch(parsed)?.let { onceAtUtc = it } },
                        label = { Text("At (UTC)") },
                        supportingText = { Text("yyyy-MM-dd HH:mm") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TriggerKind.Interval -> {
                    OutlinedTextField(
                        value = (everySeconds / 60).toString(),
                        onValueChange = { text ->
                            text.toLongOrNull()?.takeIf { it > 0 }?.let { everySeconds = it * 60 }
                        },
                        label = { Text("Every (minutes)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TriggerKind.Daily, TriggerKind.Weekly -> {
                    OutlinedTextField(
                        value = timezone,
                        onValueChange = { timezone = it },
                        label = { Text("Timezone") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = formatSecondOfDay(secondOfDay),
                        onValueChange = { parseSecondOfDay(it)?.let { seconds -> secondOfDay = seconds } },
                        label = { Text("Time") },
                        supportingText = { Text("HH:mm") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (kind == TriggerKind.Weekly) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WEEKDAYS.forEach { (day, label) ->
                                FilterChip(
                                    selected = day in weekdays,
                                    onClick = {
                                        weekdays = if (day in weekdays) {
                                            (weekdays - day).ifEmpty { setOf(day) }
                                        } else {
                                            weekdays + day
                                        }
                                    },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                }
            }
            NextOccurrencesStrip(
                preview = state.automations.preview,
                loading = state.automations.previewLoading,
            )
            Text("Target", style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = newWorker,
                    onClick = { newWorker = true },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("New worker") }
                SegmentedButton(
                    selected = !newWorker,
                    onClick = { newWorker = false },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("Active agent") }
            }
            AnimatedVisibility(visible = !newWorker) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.agents.isEmpty()) {
                        Text(
                            "No agent panes in the snapshot.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        state.agents.forEach { agent ->
                            val selected = paneId == agent.paneId
                            SurfaceChoice(
                                selected = selected,
                                title = agent.name ?: agent.agent ?: "Pane ${agent.paneId}",
                                subtitle = agent.paneId,
                                onClick = { paneId = agent.paneId },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = ifBusy == "wait", onClick = { ifBusy = "wait" }, label = { Text("If busy: wait") })
                        FilterChip(selected = ifBusy == "skip", onClick = { ifBusy = "skip" }, label = { Text("If busy: skip") })
                    }
                }
            }
            Text("Task", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("Prompt") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            if (agentChoices.isNotEmpty()) {
                Text("Agent", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    agentChoices.forEach { (id, label) ->
                        FilterChip(
                            selected = agentId == id,
                            onClick = { agentId = id },
                            label = { Text(label) },
                        )
                    }
                }
            } else {
                OutlinedTextField(
                    value = agentId,
                    onValueChange = { agentId = it },
                    label = { Text("Agent id") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (workspaces.isNotEmpty()) {
                Text("Workspace", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    workspaces.forEach { workspace ->
                        val id = workspace.name
                        FilterChip(
                            selected = workspaceId == id,
                            onClick = { workspaceId = id },
                            label = { Text(workspace.name.ifBlank { "Workspace ${workspace.index}" }) },
                        )
                    }
                }
            } else {
                OutlinedTextField(
                    value = workspaceId,
                    onValueChange = { workspaceId = it },
                    label = { Text("Workspace id") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = mode,
                onValueChange = { mode = it },
                label = { Text("Mode (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = access,
                onValueChange = { access = it },
                label = { Text("Access (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pathsText,
                onValueChange = { pathsText = it },
                label = { Text("Paths (optional)") },
                supportingText = { Text("Comma-separated globs") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = gate,
                onValueChange = { gate = it },
                label = { Text("Gate (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { showPolicy = !showPolicy }) {
                Text(if (showPolicy) "Hide policy" else "Policy")
            }
            AnimatedVisibility(visible = showPolicy) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Misfire", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("run_latest", "skip", "run_all").forEach { value ->
                            FilterChip(
                                selected = misfire == value,
                                onClick = { misfire = value },
                                label = { Text(value.replace('_', ' ')) },
                            )
                        }
                    }
                    Text("Overlap", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("skip", "queue").forEach { value ->
                            FilterChip(
                                selected = overlap == value,
                                onClick = { overlap = value },
                                label = { Text(value) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = grace,
                        onValueChange = { grace = it },
                        label = { Text("Misfire grace (seconds)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Button(
                enabled = name.isNotBlank() && title.isNotBlank() && prompt.isNotBlank() &&
                    agentId.isNotBlank() && !state.automations.mutating &&
                    (newWorker || paneId.isNotBlank()),
                onClick = {
                    val target = if (newWorker) {
                        AutomationTarget.NewWorker
                    } else {
                        AutomationTarget.ActiveAgent(
                            paneId = paneId,
                            terminalId = "",
                            ifBusy = ifBusy,
                        )
                    }
                    val policy = if (showPolicy) {
                        AutomationPolicySpec(
                            misfire = misfire,
                            overlap = overlap,
                            misfireGraceSeconds = grace.toLongOrNull(),
                        )
                    } else {
                        null
                    }
                    submitted = true
                    onSave(
                        AutomationDraft(
                            name = name.trim(),
                            enabled = enabled,
                            trigger = trigger,
                            target = target,
                            task = AutomationTaskSpec(
                                title = title.trim(),
                                prompt = prompt.trim(),
                                agentId = agentId.trim(),
                                workspaceId = workspaceId.trim(),
                                mode = mode.trim().ifEmpty { null },
                                access = access.trim().ifEmpty { null },
                                paths = pathsText.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                                gate = gate.trim().ifEmpty { null },
                            ),
                            policy = policy,
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (existing == null) "Create" else "Save") }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun NextOccurrencesStrip(preview: List<Long>?, loading: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Next occurrences", style = MaterialTheme.typography.labelLarge)
        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else if (preview.isNullOrEmpty()) {
            Text(
                "No upcoming times for this trigger.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            preview.take(5).forEach { at ->
                Text(
                    relativeTime(at)?.let { "$it · ${formatEpoch(at)}" } ?: formatEpoch(at) ?: at.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AutomationHistorySheet(
    automation: Automation?,
    runs: List<AutomationRun>,
    loading: Boolean,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                automation?.name?.ifBlank { "History" } ?: "History",
                style = MaterialTheme.typography.headlineSmall,
            )
            when {
                loading -> {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                runs.isEmpty() -> {
                    Text("No runs yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    runs.sortedByDescending { it.startedAt ?: it.scheduledAt ?: it.createdAt ?: 0L }.forEach { run ->
                        HistoryRunRow(run)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun HistoryRunRow(run: AutomationRun) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AutomationStatusChip(
                    (run.status ?: "unknown").replaceFirstChar { it.titlecase(Locale.getDefault()) },
                    status = run.status,
                )
                Spacer(Modifier.weight(1f))
                run.attempt?.let {
                    Text("Attempt $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val times = listOfNotNull(
                relativeTime(run.scheduledAt)?.let { "scheduled $it" },
                relativeTime(run.startedAt)?.let { "started $it" },
                relativeTime(run.finishedAt)?.let { "finished $it" },
            )
            if (times.isNotEmpty()) {
                Text(times.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            run.error?.takeIf { it.isNotBlank() }?.let { error ->
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            run.taskId?.takeIf { it.isNotBlank() }?.let { taskId ->
                Text("Task $taskId", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AutomationRebindSheet(
    agents: List<AgentSummary>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Rebind to pane", style = MaterialTheme.typography.headlineSmall)
            if (agents.isEmpty()) {
                Text("No agent panes available.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                agents.forEach { agent ->
                    SurfaceChoice(
                        selected = false,
                        title = agent.name ?: agent.agent ?: "Pane ${agent.paneId}",
                        subtitle = listOfNotNull(agent.paneId, agent.workspaceName ?: agent.workspace).joinToString(" · "),
                        onClick = { onPick(agent.paneId) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SurfaceChoice(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AutomationStatusChip(
    label: String,
    status: String? = null,
    tonal: Boolean = false,
    container: Color? = null,
    content: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = statusColors(status)
    val bg = container ?: if (tonal) MaterialTheme.colorScheme.secondaryContainer else colors.first
    val fg = content ?: if (tonal) MaterialTheme.colorScheme.onSecondaryContainer else colors.second
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        modifier = Modifier
            .background(bg, RoundedCornerShape(50))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun statusColors(status: String?): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (status?.lowercase()) {
        "succeeded", "delivered", "done" -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        "failed" -> scheme.errorContainer to scheme.onErrorContainer
        "running", "starting", "pending" -> scheme.primaryContainer to scheme.onPrimaryContainer
        "skipped", "cancelled" -> scheme.surfaceContainerHigh to scheme.onSurfaceVariant
        "review" -> scheme.secondaryContainer to scheme.onSecondaryContainer
        else -> scheme.surfaceContainerHigh to scheme.onSurfaceVariant
    }
}

private val WEEKDAYS = listOf(
    1 to "Mon",
    2 to "Tue",
    3 to "Wed",
    4 to "Thu",
    5 to "Fri",
    6 to "Sat",
    7 to "Sun",
)

private fun triggerKindOf(trigger: AutomationTrigger?): TriggerKind = when (trigger) {
    is AutomationTrigger.Once -> TriggerKind.Once
    is AutomationTrigger.Interval -> TriggerKind.Interval
    is AutomationTrigger.Weekly -> TriggerKind.Weekly
    is AutomationTrigger.Daily, null -> TriggerKind.Daily
}

private fun buildTrigger(
    kind: TriggerKind,
    timezone: String,
    secondOfDay: Int,
    weekdays: List<Int>,
    everySeconds: Long,
    onceAtUtc: Long,
): AutomationTrigger = when (kind) {
    TriggerKind.Once -> AutomationTrigger.Once(atUtc = onceAtUtc)
    TriggerKind.Interval -> AutomationTrigger.Interval(
        everySeconds = everySeconds.coerceAtLeast(60),
        anchorUtc = Instant.now().epochSecond,
    )
    TriggerKind.Daily -> AutomationTrigger.Daily(
        timezone = timezone.ifBlank { ZoneId.systemDefault().id },
        secondOfDay = secondOfDay,
    )
    TriggerKind.Weekly -> AutomationTrigger.Weekly(
        timezone = timezone.ifBlank { ZoneId.systemDefault().id },
        weekdays = weekdays.ifEmpty { listOf(1) },
        secondOfDay = secondOfDay,
    )
}

private fun triggerSummary(trigger: AutomationTrigger?): String = when (trigger) {
    is AutomationTrigger.Daily -> "Daily ${formatSecondOfDay(trigger.secondOfDay)} ${trigger.timezone}"
    is AutomationTrigger.Weekly -> {
        val days = trigger.weekdays.map { day -> WEEKDAYS.firstOrNull { it.first == day }?.second ?: day.toString() }
        "Weekly ${days.joinToString("/")} ${formatSecondOfDay(trigger.secondOfDay)}"
    }
    is AutomationTrigger.Interval -> {
        val minutes = trigger.everySeconds / 60
        if (minutes < 60) "Every $minutes min" else "Every ${minutes / 60} hr"
    }
    is AutomationTrigger.Once -> "Once ${formatLocal(trigger.atUtc) ?: formatEpoch(trigger.atUtc) ?: "—"}"
    null -> "No schedule"
}

private fun targetChipLabel(target: AutomationTarget?): String = when (target) {
    is AutomationTarget.ActiveAgent -> "Agent pane ${target.paneId}"
    is AutomationTarget.NewWorker, null -> "New worker"
}

private val utcFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)
private val localFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d HH:mm")

private fun epochInstant(value: Long): Instant =
    if (value > 9_999_999_999L) Instant.ofEpochMilli(value) else Instant.ofEpochSecond(value)

private fun formatEpoch(value: Long?): String? {
    if (value == null || value <= 0L) return null
    return utcFormatter.format(epochInstant(value)) + " UTC"
}

private fun formatLocal(value: Long?): String? {
    if (value == null || value <= 0L) return null
    return localFormatter.format(epochInstant(value).atZone(ZoneId.systemDefault()))
}

private fun parseEpoch(raw: String): Long? = runCatching {
    LocalDateTime.parse(raw.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        .toEpochSecond(ZoneOffset.UTC)
}.getOrNull()

private fun formatSecondOfDay(secondOfDay: Int): String {
    val clamped = secondOfDay.coerceIn(0, 24 * 3600 - 1)
    val hours = clamped / 3600
    val minutes = (clamped % 3600) / 60
    return "%02d:%02d".format(hours, minutes)
}

private fun parseSecondOfDay(raw: String): Int? {
    val parts = raw.trim().split(':')
    if (parts.size != 2) return null
    val hours = parts[0].toIntOrNull() ?: return null
    val minutes = parts[1].toIntOrNull() ?: return null
    if (hours !in 0..23 || minutes !in 0..59) return null
    return hours * 3600 + minutes * 60
}

private fun relativeTime(value: Long?): String? {
    if (value == null || value <= 0L) return null
    val instant = epochInstant(value)
    val now = Instant.now()
    val delta = instant.epochSecond - now.epochSecond
    val abs = kotlin.math.abs(delta)
    val label = when {
        abs < 60 -> "now"
        abs < 3600 -> "${abs / 60}m"
        abs < 86400 -> "${abs / 3600}h"
        else -> "${abs / 86400}d"
    }
    return when {
        label == "now" -> "now"
        delta > 0 -> "in $label"
        else -> "$label ago"
    }
}

