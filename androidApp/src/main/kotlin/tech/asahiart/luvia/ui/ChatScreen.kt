package tech.asahiart.luvia.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.asahiart.luvia.AcpPlanEntry
import tech.asahiart.luvia.AcpToolCall
import tech.asahiart.luvia.AcpToolStatus
import tech.asahiart.luvia.thread.AgentThread
import tech.asahiart.luvia.thread.Ask
import tech.asahiart.luvia.thread.AskOption
import tech.asahiart.luvia.thread.AskTone
import tech.asahiart.luvia.thread.TimelineItem
import tech.asahiart.luvia.thread.diffLines
import tech.asahiart.luvia.thread.parseRunMeter
import tech.asahiart.luvia.thread.recommendedOption
import tech.asahiart.luvia.thread.toolTrailKind
import tech.asahiart.luvia.thread.ToolTrailKind
import tech.asahiart.luvia.ui.theme.LuviaTheme
import kotlin.math.roundToInt

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
    sending: Boolean,
    isObserver: Boolean,
) {
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
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text(thread.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(thread.projectLabel, thread.status.sessionLabel()).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
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
            Spacer(Modifier.width(8.dp))
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(visible, key = { it.id }) { item ->
                when (item) {
                    is TimelineItem.Output -> Bubble(item.text, mine = false)
                    is TimelineItem.Mine -> Bubble(item.text, mine = true)
                    is TimelineItem.Thought -> ThoughtRow(item.text)
                    is TimelineItem.Tool -> ToolTrail(item.call)
                    is TimelineItem.Plan -> PlanBlock(item.entries)
                    is TimelineItem.Status -> StatusLine(item.status?.sessionLabel() ?: item.stopReason?.name ?: "Update")
                    is TimelineItem.Unconfirmed -> UnconfirmedRow(item.text)
                    is TimelineItem.AskCard -> Unit
                }
            }
        }
        thread.ask?.let { ask ->
            DecisionCard(
                ask = ask,
                index = queueIndex,
                total = queueTotal,
                enabled = !isObserver && !sending,
                onAnswer = onAnswer,
            )
        }
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
            enabled = !isObserver && !sending && draft.isNotBlank(),
            hint = if (isObserver) "Observer can read, not send" else "Message",
        )
    }
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
        Text(if (open) "Thought" else "Thought · tap to expand", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (open) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ToolTrail(call: AcpToolCall) {
    val kind = toolTrailKind(call.kind, call.title)
    val color = when (kind) {
        ToolTrailKind.Read -> MaterialTheme.colorScheme.secondary
        ToolTrailKind.Edit -> MaterialTheme.colorScheme.primary
        ToolTrailKind.Run -> MaterialTheme.colorScheme.error
        ToolTrailKind.Other -> MaterialTheme.colorScheme.outline
    }
    val shape = if (kind == ToolTrailKind.Run) RoundedCornerShape(4.dp) else CircleShape
    val lines = diffLines(call.summary)
    val meter = parseRunMeter(call.summary)
    Row(Modifier.fillMaxWidth()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(12.dp).background(color, shape))
            Box(Modifier.width(2.dp).height(8.dp).background(MaterialTheme.colorScheme.outlineVariant))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(call.title.ifBlank { kind.name }, style = MaterialTheme.typography.titleSmall)
            call.kind?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (lines != null) {
                DiffBlock(lines)
            }
            if (meter != null) {
                TestMeter(meter.passed, meter.total, call.status == AcpToolStatus.Failed)
            }
            if (lines == null && meter == null) {
                call.summary?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = LuviaTheme.mono)
                }
            }
        }
    }
}

@Composable
private fun DiffBlock(lines: List<String>) {
    Column(
        Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(8.dp),
    ) {
        lines.take(40).forEachIndexed { index, line ->
            val color = when {
                line.startsWith("+") -> LuviaTheme.extended.diffAdd
                line.startsWith("-") -> LuviaTheme.extended.diffDel
                else -> MaterialTheme.colorScheme.onSurface
            }
            Row {
                Text(
                    "${index + 1}",
                    modifier = Modifier.width(28.dp),
                    fontFamily = LuviaTheme.mono,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(line, fontFamily = LuviaTheme.mono, style = MaterialTheme.typography.bodySmall, color = color)
            }
        }
    }
}

@Composable
private fun TestMeter(passed: Int, total: Int, failedStatus: Boolean) {
    val cells = 64.coerceAtMost(total)
    val failed = (total - passed).coerceAtLeast(0)
    val failedCells = if (total == 0) 0 else ((failed.toFloat() / total) * cells).roundToInt().coerceIn(0, cells)
    Column(Modifier.padding(top = 8.dp)) {
        Text("$passed / $total", fontFamily = LuviaTheme.mono, style = MaterialTheme.typography.labelMedium)
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(cells.coerceAtMost(32)) { index ->
                val bad = index >= cells - failedCells
                Box(
                    Modifier
                        .size(width = 6.dp, height = 14.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (bad || (failedStatus && failedCells == 0 && index == cells - 1)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
                )
            }
        }
        if (cells > 32) {
            Text("${cells - 32} more", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Text("Plan", style = MaterialTheme.typography.labelLarge)
        entries.forEach { entry ->
            Text("· ${entry.content}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun StatusLine(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Text(label, modifier = Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Text("Unconfirmed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onErrorContainer)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        Text(
            "The result was lost. This is not sent again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun DecisionCard(
    ask: Ask,
    index: Int,
    total: Int,
    enabled: Boolean,
    onAnswer: (AskOption) -> Unit,
) {
    val recommended = recommendedOption(ask)
    var selected by remember(ask.question, ask.options) { mutableStateOf(recommended ?: ask.options.firstOrNull()) }
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Box(
                Modifier
                    .padding(bottom = 8.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.35f))
                    .align(Alignment.CenterHorizontally),
            )
            Text(
                "$index / $total",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                ask.question ?: "A decision is waiting",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
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
                                "Agent recommends",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                    option.consequence?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (option.tone == AskTone.Destructive) {
                        Text("This option rejects the request.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (enabled) "Pick one, then continue." else "Observer can't answer.",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Button(
                    onClick = { selected?.let(onAnswer) },
                    enabled = enabled && selected != null,
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Text("Select and continue")
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
    enabled: Boolean,
    hint: String,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = draft,
            onValueChange = onDraft,
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            decorationBox = { inner ->
                Box {
                    if (draft.isEmpty()) {
                        Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    inner()
                }
            },
        )
        IconButton(onClick = onSend, enabled = enabled) {
            Icon(Icons.Filled.Send, contentDescription = "Send", tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
        }
    }
}
