package tech.asahiart.luvia.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import tech.asahiart.luvia.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.asahiart.luvia.AcpPlanEntry
import tech.asahiart.luvia.AcpToolCall
import tech.asahiart.luvia.stripAnsi
import tech.asahiart.luvia.thread.AgentThread
import tech.asahiart.luvia.thread.Ask
import tech.asahiart.luvia.thread.AskAction
import tech.asahiart.luvia.thread.outputRuns
import tech.asahiart.luvia.thread.AskOption
import tech.asahiart.luvia.thread.AskTone
import tech.asahiart.luvia.thread.DiffFileView
import tech.asahiart.luvia.thread.TimelineItem
import tech.asahiart.luvia.thread.recommendedOption
import tech.asahiart.luvia.thread.testBarCells
import tech.asahiart.luvia.thread.toolTrailView
import tech.asahiart.luvia.thread.ToolTrailKind
import tech.asahiart.luvia.ui.theme.LuviaTheme

@Composable
fun ChatScreen(
    thread: AgentThread,
    items: List<TimelineItem>,
    queueIndex: Int,
    queueTotal: Int,
    initialDraft: String,
    onSend: (String) -> Unit,
    onAnswer: (AskOption) -> Unit,
    onBack: () -> Unit,
    onOpenTerminal: () -> Unit,
    terminalModifier: Modifier = Modifier,
    terminalPreview: String? = null,
    sending: Boolean,
    isObserver: Boolean,
    connection: ConnectionBadge? = null,
    onRetry: () -> Unit = {},
) {
    val offline = connection != null && connection != ConnectionBadge.Live
    val listState = rememberLazyListState()
    var draft by remember(thread.id) { mutableStateOf(initialDraft) }
    val visible = items.filterNot { it is TimelineItem.AskCard }
    LaunchedEffect(visible.size) {
        if (visible.isNotEmpty()) listState.animateScrollToItem(visible.lastIndex)
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Column(Modifier.weight(1f)) {
                Text(thread.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val detail = if (offline && connection != null) {
                    connection.chromeLabel() + " · " + stringResource(R.string.cached)
                } else {
                    listOfNotNull(thread.projectLabel, thread.status.sessionLabel()).joinToString(" · ")
                }
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (offline) {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
            TerminalThumbnail(
                preview = terminalPreview,
                onClick = onOpenTerminal,
                modifier = terminalModifier.padding(end = 8.dp),
            )
        }
        if (offline && connection != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(connection.chromeLabel(), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.cached),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(visible, key = { it.id }) { item ->
                    when (item) {
                        is TimelineItem.Output -> OutputBlocks(item.text)
                        is TimelineItem.Mine -> Bubble(item.text, mine = true)
                        is TimelineItem.Thought -> ThoughtRow(item.text)
                        is TimelineItem.Tool -> ToolTrail(item.call)
                        is TimelineItem.Plan -> PlanBlock(item.entries)
                        is TimelineItem.Status ->
                            StatusLine(item.label?.takeIf { it.isNotBlank() } ?: item.status?.sessionLabel() ?: stringResource(R.string.status_update))
                        is TimelineItem.Unconfirmed -> UnconfirmedRow(item.text)
                        is TimelineItem.AskCard -> Unit
                    }
                }
            }
            if (thread.ask != null) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.14f)),
                )
            }
        }
        thread.ask?.let { ask ->
            DecisionCard(
                ask = ask,
                agentName = thread.agentType?.takeIf { it.isNotBlank() } ?: thread.title,
                index = queueIndex,
                total = queueTotal,
                enabled = !isObserver && !sending && !offline,
                onAnswer = onAnswer,
            )
        }
        val canType = !isObserver && !sending && !offline
        Composer(
            draft = draft,
            onDraft = { draft = it },
            onSend = {
                val text = draft.trim()
                if (text.isNotEmpty()) {
                    onSend(text)
                    draft = ""
                }
            },
            canSend = canType && draft.isNotBlank(),
            editable = canType,
            hint = when {
                offline -> stringResource(R.string.offline_cannot_send)
                isObserver -> stringResource(R.string.observer_cannot_send)
                else -> stringResource(R.string.message_hint)
            },
        )
    }
}

@Composable
private fun OutputBlocks(text: String) {
    val runs = outputRuns(text)
    if (runs.isEmpty()) {
        Bubble(text, mine = false)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        runs.forEach { run ->
            if (run.mono) MonoBlock(run.text) else Bubble(run.text, mine = false)
        }
    }
}

@Composable
private fun MonoBlock(text: String) {
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        fontFamily = LuviaTheme.mono,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun Bubble(text: String, mine: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (mine) 20.dp else 6.dp,
                bottomEnd = if (mine) 6.dp else 20.dp,
            ),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(
                text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun ThoughtRow(text: String) {
    var open by remember(text) { mutableStateOf(false) }
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { open = !open }
            .padding(vertical = 4.dp),
    ) {
        Text(
            if (open) stringResource(R.string.thought) else stringResource(R.string.thought_collapsed),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (open) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ToolTrail(call: AcpToolCall) {
    val view = toolTrailView(call)
    val kind = view.kind
    val color = when (kind) {
        ToolTrailKind.Read -> MaterialTheme.colorScheme.secondary
        ToolTrailKind.Edit -> MaterialTheme.colorScheme.primary
        ToolTrailKind.Run -> MaterialTheme.colorScheme.error
        ToolTrailKind.Other -> MaterialTheme.colorScheme.outline
    }
    val shape = if (kind == ToolTrailKind.Run) RoundedCornerShape(4.dp) else CircleShape
    val subject = view.path ?: call.title.ifBlank { kind.shortLabel() }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(16.dp).fillMaxHeight()) {
            Box(
                Modifier
                    .padding(top = 14.dp)
                    .width(2.dp)
                    .fillMaxHeight()
                    .align(Alignment.TopCenter)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .size(12.dp)
                    .align(Alignment.TopCenter)
                    .background(color, shape),
            )
        }
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(kind.shortLabel(), style = MaterialTheme.typography.titleSmall, color = color)
                Spacer(Modifier.width(8.dp))
                Text(
                    subject,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                view.duration?.let { duration ->
                    Text(
                        duration,
                        fontFamily = LuviaTheme.mono,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            view.diffs.forEach { DiffCard(it) }
            view.meter?.let { meter ->
                TestMeter(meter.passed, meter.total, view.markFailed, view.failedNames)
            }
            if (view.diffs.isEmpty() && view.meter == null) {
                val summary = call.summary?.trim().orEmpty()
                val hidden = summary.isEmpty() || summary == view.path || summary == view.duration
                if (!hidden) {
                    Text(summary, style = MaterialTheme.typography.bodySmall, fontFamily = LuviaTheme.mono)
                }
            }
        }
    }
}

@Composable
private fun DiffCard(file: DiffFileView) {
    val extended = LuviaTheme.extended
    Column(
        Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                file.path ?: stringResource(R.string.tool_edit),
                modifier = Modifier.weight(1f),
                fontFamily = LuviaTheme.mono,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "+${file.additions}",
                fontFamily = LuviaTheme.mono,
                style = MaterialTheme.typography.labelMedium,
                color = extended.diffAdd,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "−${file.deletions}",
                fontFamily = LuviaTheme.mono,
                style = MaterialTheme.typography.labelMedium,
                color = extended.diffDel,
            )
        }
        Column(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
            file.lines.take(40).forEach { line ->
                val bg = when (line.marker) {
                    "+" -> extended.diffAdd.copy(alpha = 0.14f)
                    "-" -> extended.diffDel.copy(alpha = 0.14f)
                    else -> Color.Transparent
                }
                val ink = when (line.marker) {
                    "+" -> extended.diffAdd
                    "-" -> extended.diffDel
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Row(Modifier.background(bg).padding(vertical = 1.dp)) {
                    Text(
                        line.oldNumber?.toString().orEmpty(),
                        modifier = Modifier.width(32.dp).padding(end = 4.dp),
                        fontFamily = LuviaTheme.mono,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        line.newNumber?.toString().orEmpty(),
                        modifier = Modifier.width(32.dp),
                        fontFamily = LuviaTheme.mono,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        line.marker,
                        modifier = Modifier.width(16.dp),
                        fontFamily = LuviaTheme.mono,
                        style = MaterialTheme.typography.bodySmall,
                        color = ink,
                    )
                    Text(line.text, fontFamily = LuviaTheme.mono, style = MaterialTheme.typography.bodySmall, color = ink)
                }
            }
        }
    }
}

@Composable
private fun TestMeter(passed: Int, total: Int, markFailed: Boolean, failedNames: List<String>) {
    val cells = testBarCells(passed, total, markFailed)
    Column(Modifier.padding(top = 8.dp).fillMaxWidth()) {
        Text("$passed / $total", fontFamily = LuviaTheme.mono, style = MaterialTheme.typography.labelMedium)
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            cells.forEach { bad ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
                )
            }
        }
        failedNames.forEach { name ->
            Text(
                name,
                modifier = Modifier.padding(top = 4.dp),
                fontFamily = LuviaTheme.mono,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun TerminalThumbnail(preview: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val extended = LuviaTheme.extended
    val label = stringResource(R.string.terminal)
    val lines = remember(preview) {
        stripAnsi(preview.orEmpty())
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
            .takeLast(2)
    }
    Column(
        modifier
            .width(88.dp)
            .height(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .background(extended.terminalBg)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        if (lines.isEmpty()) {
            Text(label, color = extended.terminalFg, fontFamily = LuviaTheme.mono, fontSize = 11.sp, maxLines = 1)
        } else {
            lines.forEach { line ->
                Text(
                    line,
                    color = extended.terminalFg,
                    fontFamily = LuviaTheme.mono,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PlanBlock(entries: List<AcpPlanEntry>) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.plan), style = MaterialTheme.typography.labelLarge)
        entries.forEach { entry ->
            Text("· ${entry.content}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun StatusLine(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        val meter = label.contains('$') || label.contains('↑') || label.contains('↓') || label.startsWith("Session")
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = if (meter) LuviaTheme.mono else null,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
    }
}

@Composable
private fun UnconfirmedRow(text: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(12.dp),
    ) {
        Text(stringResource(R.string.unconfirmed), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onErrorContainer)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        Text(
            stringResource(R.string.unconfirmed_detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun DecisionCard(
    ask: Ask,
    agentName: String,
    index: Int,
    total: Int,
    enabled: Boolean,
    onAnswer: (AskOption) -> Unit,
) {
    val recommended = recommendedOption(ask)
    var selected by remember(ask.question, ask.options) { mutableStateOf(recommended ?: ask.options.firstOrNull()) }
    var note by remember(ask.question, ask.options) { mutableStateOf("") }
    val onCard = MaterialTheme.colorScheme.onTertiaryContainer
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        shadowElevation = 8.dp,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        agentName.take(1).uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onTertiary,
                    )
                }
                Text(
                    "·",
                    modifier = Modifier.padding(horizontal = 8.dp),
                    color = onCard,
                )
                Text(
                    stringResource(R.string.decision_needs_you, agentName),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    color = onCard,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("·", modifier = Modifier.padding(horizontal = 8.dp), color = onCard)
                Text("$index/$total", style = MaterialTheme.typography.labelLarge, color = onCard)
            }
            Text(
                ask.question ?: stringResource(R.string.decision_waiting),
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.headlineSmall,
                color = onCard,
            )
            ask.options.forEach { option ->
                val picked = option == selected
                Column(
                    Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
                        .border(
                            width = if (picked) 2.dp else 0.dp,
                            color = if (picked) MaterialTheme.colorScheme.tertiary else Color.Transparent,
                            shape = RoundedCornerShape(16.dp),
                        )
                        .clickable(enabled = enabled) { selected = option }
                        .padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(option.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (option.recommended || option == recommended) {
                            Text(
                                stringResource(R.string.agent_recommends),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                    option.consequence?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (option.tone == AskTone.Destructive) {
                        Text(stringResource(R.string.option_rejects), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            BasicTextField(
                value = note,
                onValueChange = { note = it },
                enabled = enabled,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                decorationBox = { inner ->
                    Box {
                        if (note.isEmpty()) {
                            Text(
                                if (enabled) stringResource(R.string.note_hint) else stringResource(R.string.observer_cannot_answer),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        val choice = selected ?: return@Button
                        val extra = note.trim()
                        val action = choice.action
                        val delivered = if (extra.isNotEmpty() && action is AskAction.Prompt) {
                            val base = action.text.trim()
                            choice.copy(action = AskAction.Prompt(if (base.isEmpty()) extra else "$base\n$extra"))
                        } else {
                            choice
                        }
                        onAnswer(delivered)
                        note = ""
                    },
                    enabled = enabled && selected != null,
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Text(stringResource(R.string.continue_action))
                }
            }
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    canSend: Boolean,
    editable: Boolean,
    hint: String,
) {
    val ink = if (editable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(
                if (editable) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f)
                },
            )
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = draft,
            onValueChange = onDraft,
            enabled = editable,
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = ink),
            decorationBox = { inner ->
                Box {
                    if (draft.isEmpty()) {
                        Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    inner()
                }
            },
        )
        IconButton(onClick = onSend, enabled = canSend) {
            Icon(
                Icons.Filled.Send,
                contentDescription = stringResource(R.string.send),
                tint = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
        }
    }
}
