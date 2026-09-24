package tech.asahiart.luvia.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import tech.asahiart.luvia.AcpRunState
import tech.asahiart.luvia.AgentKind
import tech.asahiart.luvia.AgentStatus
import tech.asahiart.luvia.HostUhpState
import tech.asahiart.luvia.TerminalKey
import tech.asahiart.luvia.command.KeyBarKey
import tech.asahiart.luvia.command.SlashCommand
import tech.asahiart.luvia.command.filter
import tech.asahiart.luvia.thread.Ask
import tech.asahiart.luvia.thread.AskOption
import tech.asahiart.luvia.thread.AskTone
import tech.asahiart.luvia.thread.AgentThread
import tech.asahiart.luvia.thread.TimelineItem
import tech.asahiart.luvia.ui.theme.LuviaTheme

/** Callbacks for [ThreadPane]; pane-only and ACP-only actions are ignored by the other kind. */
data class ThreadActions(
    val onBack: () -> Unit,
    val onDraftChange: (String) -> Unit,
    val onSendDraft: () -> Unit,
    val onSendCommand: (String) -> Unit,
    val onAnswer: (AskOption) -> Unit,
    val onKey: (KeyBarKey) -> Unit,
    val onCheckUnconfirmed: () -> Unit,
    val onOpenProject: () -> Unit = {},
    val onShowName: () -> Unit = {},
    val onShowFork: () -> Unit = {},
    val onCancelTurn: () -> Unit = {},
    val onEndSession: () -> Unit = {},
    val onObserveTerminal: (String) -> Unit = {},
    val onStopObserve: () -> Unit = {},
    val onRequestControl: () -> Unit = {},
    val onSendTerminalText: (String) -> Unit = {},
    val onSendTerminalKey: (TerminalKey) -> Unit = {},
)

@Composable
fun ThreadPane(
    thread: AgentThread?,
    timeline: List<TimelineItem>,
    state: HostUhpState,
    draft: String,
    actions: ThreadActions,
    terminal: TerminalUiModel?,
    modifier: Modifier = Modifier,
) {
    if (thread == null) {
        EmptyState(title = "Conversation ended", message = "This Agent is no longer running.")
        return
    }
    val isPane = thread.kind == AgentKind.Pane
    val sending = isPane && state.agentDetail.sending
    val unconfirmed = if (isPane) state.agentDetail.unconfirmed else null
    val errorText = if (isPane) state.agentDetail.errorText else state.acp.errorText
    val idle = state.canMutate && !sending && unconfirmed == null
    val canSend = idle && (!isPane || state.capabilities.agentPrompt)
    val canKeys = isPane && idle && state.capabilities.agentKeys
    var showTerminal by remember(thread.id) { mutableStateOf(false) }

    Column(modifier.fillMaxSize().imePadding()) {
        ThreadTopBar(thread, state, actions)
        errorText?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (!isPane && state.acp.run == AcpRunState.Exited) {
            AcpExitBanner(state.acp.exitMessage, onClose = actions.onEndSession)
        }
        unconfirmed?.let {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                UnconfirmedBanner(kind = it, onCheck = actions.onCheckUnconfirmed)
            }
        }
        if (!isPane && state.acp.run == AcpRunState.Starting) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Starting agent…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else Timeline(
            items = timeline,
            canAnswer = idle,
            isObserver = state.isObserver,
            onAnswer = actions.onAnswer,
            modifier = Modifier.weight(1f),
        )
        Composer(
            thread = thread,
            draft = draft,
            canSend = canSend,
            canKeys = canKeys,
            isObserver = state.isObserver,
            onDraftChange = actions.onDraftChange,
            onSend = actions.onSendDraft,
            onCommand = { command ->
                actions.onSendCommand(command.name)
                actions.onDraftChange("")
                if (command.needsTerminal && isPane) showTerminal = true
            },
            onKey = actions.onKey,
            onOpenTerminal = if (isPane && thread.paneId != null) {
                { showTerminal = true }
            } else {
                null
            },
        )
    }

    val paneId = thread.paneId
    if (showTerminal && paneId != null) {
        TerminalControl(
            paneId = paneId,
            title = thread.title,
            terminal = terminal,
            actions = actions,
            onDismiss = { showTerminal = false },
        )
    }
}

@Composable
private fun ThreadTopBar(thread: AgentThread, state: HostUhpState, actions: ThreadActions) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = actions.onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    thread.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                StatusGlyph(thread.status)
            }
            thread.projectLabel?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.clickable(onClick = actions.onOpenProject),
                )
            }
        }
        val canName = thread.kind == AgentKind.Pane && state.canMutate && state.capabilities.agentName
        val canFork = thread.kind == AgentKind.Pane && state.canMutate && state.capabilities.agentFork
        val isAcp = thread.kind == AgentKind.Acp
        if (canName || canFork || isAcp) {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (canName) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; actions.onShowName() })
                    }
                    if (canFork) {
                        DropdownMenuItem(text = { Text("Fork") }, onClick = { menu = false; actions.onShowFork() })
                    }
                    if (isAcp) {
                        DropdownMenuItem(text = { Text("Cancel turn") }, onClick = { menu = false; actions.onCancelTurn() })
                        DropdownMenuItem(
                            text = { Text("End session", color = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; actions.onEndSession() },
                        )
                    }
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

/** Status as shape plus color so it reads without color vision. */
@Composable
fun StatusGlyph(status: AgentStatus, modifier: Modifier = Modifier) {
    val glyph = when (status) {
        AgentStatus.Blocked -> "●"
        AgentStatus.Working -> "◐"
        AgentStatus.Idle -> "○"
        AgentStatus.Done -> "✓"
        AgentStatus.Unknown -> "◌"
    }
    Text(
        "$glyph ${statusLabel(status)}",
        style = MaterialTheme.typography.labelMedium,
        color = status.color(),
        modifier = modifier,
    )
}

internal fun statusLabel(status: AgentStatus): String = when (status) {
    AgentStatus.Blocked -> "Needs you"
    AgentStatus.Working -> "Working"
    AgentStatus.Idle -> "Idle"
    AgentStatus.Done -> "Done"
    AgentStatus.Unknown -> "Unknown"
}

@Composable
private fun Timeline(
    items: List<TimelineItem>,
    canAnswer: Boolean,
    isObserver: Boolean,
    onAnswer: (AskOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(items.size, items.lastOrNull()) {
        if (items.isNotEmpty()) listState.animateScrollToItem(items.lastIndex)
    }
    if (items.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                "Nothing yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    SelectionContainer(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.id }) { item ->
                when (item) {
                    is TimelineItem.Output -> OutputBlock(item.text, item.streaming)
                    is TimelineItem.Mine -> MineBubble(item.text, unconfirmed = false)
                    is TimelineItem.Unconfirmed -> MineBubble(item.text, unconfirmed = true)
                    is TimelineItem.Thought -> AcpThoughtRow(item.id, item.text)
                    is TimelineItem.Tool -> AcpToolRow(item.call)
                    is TimelineItem.Plan -> AcpPlanCard(item.entries)
                    is TimelineItem.Status -> StatusDivider(item)
                    is TimelineItem.AskCard -> AttentionCard(item.ask, canAnswer, isObserver, onAnswer)
                }
            }
        }
    }
}

/** Agent text in body sans; fenced code, indented code and box drawing stay mono so TUI output keeps its shape. */
@Composable
private fun OutputBlock(text: String, streaming: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (run in outputRuns(text)) {
            if (run.mono) {
                Text(
                    run.text,
                    fontFamily = LuviaTheme.mono,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    softWrap = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            } else {
                Text(
                    withNerdGlyphs(run.text),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (streaming) StreamingCursor()
    }
}

internal data class OutputRun(val text: String, val mono: Boolean)

internal fun outputRuns(text: String): List<OutputRun> {
    val runs = mutableListOf<OutputRun>()
    var fenced = false
    for (line in text.lines()) {
        if (line.trimStart().startsWith("```")) {
            fenced = !fenced
            continue
        }
        val mono = fenced || looksPreformatted(line)
        val last = runs.lastOrNull()
        runs += if (last != null && last.mono == mono) {
            runs.removeAt(runs.lastIndex)
            last.copy(text = last.text + "\n" + line)
        } else {
            OutputRun(line, mono)
        }
    }
    return runs.map { it.copy(text = it.text.trim('\n')) }.filter { it.text.isNotBlank() }
}

private fun isNerdGlyph(cp: Int): Boolean =
    cp in 0xE000..0xF8FF || cp in 0xF0000..0x10FFFD

/** Body sans has no Powerline / Nerd Font glyphs; those code points use the terminal font. */
internal fun withNerdGlyphs(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        val end = i + Character.charCount(cp)
        if (isNerdGlyph(cp)) {
            withStyle(SpanStyle(fontFamily = LuviaTheme.mono)) { append(text, i, end) }
        } else {
            append(text, i, end)
        }
        i = end
    }
}

private fun looksPreformatted(line: String): Boolean =
    line.startsWith("    ") || line.startsWith("\t") ||
        line.any { it in '\u2500'..'\u259F' }

@Composable
private fun AcpExitBanner(message: String?, onClose: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = LuviaTheme.extended.stale.copy(alpha = 0.16f),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                message?.takeIf { it.isNotBlank() } ?: "The agent has exited.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClose) { Text("Close") }
        }
    }
}

@Composable
private fun MineBubble(text: String, unconfirmed: Boolean) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(horizontalAlignment = Alignment.End) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Text(
                    withNerdGlyphs(text),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
            if (unconfirmed) {
                Text(
                    "Unconfirmed — not resent",
                    style = MaterialTheme.typography.labelSmall,
                    color = LuviaTheme.extended.stale,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusDivider(item: TimelineItem.Status) {
    val label = item.status?.let(::statusLabel) ?: "Turn ended"
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** The one card that uses the accent: an Agent waiting on you. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AttentionCard(
    ask: Ask,
    canAnswer: Boolean,
    isObserver: Boolean,
    onAnswer: (AskOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val accent = LuviaTheme.extended.agentBlocked
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = accent.copy(alpha = 0.10f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                ask.question ?: "Waiting for your decision",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (option in ask.options) {
                    val click = {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        onAnswer(option)
                    }
                    when (option.tone) {
                        AskTone.Primary -> Button(
                            onClick = click,
                            enabled = canAnswer,
                            colors = ButtonDefaults.buttonColors(containerColor = accent),
                        ) { Text(option.label) }
                        AskTone.Destructive -> OutlinedButton(
                            onClick = click,
                            enabled = canAnswer,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text(option.label) }
                        AskTone.Neutral -> FilledTonalButton(onClick = click, enabled = canAnswer) { Text(option.label) }
                    }
                }
            }
            if (isObserver) {
                Text(
                    "Observer — can't answer",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Composer(
    thread: AgentThread,
    draft: String,
    canSend: Boolean,
    canKeys: Boolean,
    isObserver: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onCommand: (SlashCommand) -> Unit,
    onKey: (KeyBarKey) -> Unit,
    onOpenTerminal: (() -> Unit)?,
) {
    val commands = remember(thread.agentType, thread.title) { thread.commands() }
    val query = draft.takeIf { it.startsWith("/") && ' ' !in it }
    val matches = remember(query, commands) { query?.let { filter(commands, it) }.orEmpty() }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        if (matches.isNotEmpty()) {
            CommandPalette(matches, enabled = canSend, onPick = onCommand)
        }
        if (canKeys) KeyBar(onKey)
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                enabled = !isObserver,
                placeholder = {
                    Text(if (isObserver) "Observer — read only" else "Message, or / for commands")
                },
                shape = RoundedCornerShape(24.dp),
                maxLines = 5,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            )
            IconButton(onClick = onSend, enabled = canSend && draft.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
            if (onOpenTerminal != null) {
                IconButton(onClick = onOpenTerminal, modifier = Modifier.semantics { contentDescription = "Terminal control" }) {
                    Text(">_", fontFamily = LuviaTheme.mono, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun CommandPalette(commands: List<SlashCommand>, enabled: Boolean, onPick: (SlashCommand) -> Unit) {
    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        LazyColumn(Modifier.heightIn(max = 240.dp)) {
            items(commands, key = { it.name }) { command ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled) { onPick(command) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(command.name, fontFamily = LuviaTheme.mono, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        command.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (command.needsTerminal) {
                        Text(
                            "↗ terminal",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyBar(onKey: (KeyBarKey) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (key in KeyBarKey.entries) {
            KeyChip(label = key.label()) { onKey(key) }
        }
    }
}

internal fun KeyBarKey.label(): String = when (this) {
    KeyBarKey.Esc -> "Esc"
    KeyBarKey.Tab -> "Tab"
    KeyBarKey.ShiftTab -> "⇧Tab"
    KeyBarKey.CtrlC -> "^C"
    KeyBarKey.Up -> "↑"
    KeyBarKey.Down -> "↓"
    KeyBarKey.Enter -> "⏎"
}

/** Full-screen Terminal control. Observes only while shown (ADR 0001). */
@Composable
private fun TerminalControl(
    paneId: String,
    title: String,
    terminal: TerminalUiModel?,
    actions: ThreadActions,
    onDismiss: () -> Unit,
) {
    DisposableEffect(paneId) {
        actions.onObserveTerminal(paneId)
        onDispose { actions.onStopObserve() }
    }
    val fg = LuviaTheme.extended.terminalFg
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = LuviaTheme.extended.terminalBg, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().imePadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("Done", color = fg) }
                    Text(title, color = fg, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Spacer(Modifier.size(48.dp))
                }
                if (terminal == null) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("Connecting to terminal…", color = fg.copy(alpha = 0.7f))
                    }
                } else {
                    TerminalPane(
                        terminal = terminal,
                        onRequestControl = actions.onRequestControl,
                        onSendText = actions.onSendTerminalText,
                        onSendKey = actions.onSendTerminalKey,
                        boundToPane = true,
                        modifier = Modifier.weight(1f).padding(8.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun AgentNameDialogs(
    state: HostUhpState,
    onNameDraft: (String) -> Unit,
    onName: () -> Unit,
    onDismissName: () -> Unit,
    onForkDraft: (String) -> Unit,
    onFork: () -> Unit,
    onDismissFork: () -> Unit,
) {
    val detail = state.agentDetail
    if (detail.showName) {
        AlertDialog(
            onDismissRequest = onDismissName,
            title = { Text("Rename Agent") },
            text = { OutlinedTextField(value = detail.nameDraft, onValueChange = onNameDraft, singleLine = true) },
            confirmButton = {
                TextButton(onClick = onName, enabled = detail.nameDraft.isNotBlank() && !detail.sending) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = onDismissName) { Text("Cancel") } },
        )
    }
    if (detail.showFork) {
        AlertDialog(
            onDismissRequest = onDismissFork,
            title = { Text("Fork Agent") },
            text = {
                OutlinedTextField(
                    value = detail.forkDraft,
                    onValueChange = onForkDraft,
                    singleLine = true,
                    placeholder = { Text("Name (optional)") },
                )
            },
            confirmButton = { TextButton(onClick = onFork, enabled = !detail.sending) { Text("Fork") } },
            dismissButton = { TextButton(onClick = onDismissFork) { Text("Cancel") } },
        )
    }
}
