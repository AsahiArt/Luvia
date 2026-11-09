package tech.asahiart.luvia.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.asahiart.luvia.AcpPlanEntry
import tech.asahiart.luvia.AcpToolCall
import tech.asahiart.luvia.stripAnsi
import tech.asahiart.luvia.thread.AgentThread
import tech.asahiart.luvia.thread.Ask
import tech.asahiart.luvia.thread.outputRuns
import tech.asahiart.luvia.thread.AskOption
import tech.asahiart.luvia.thread.CodeTokenKind
import tech.asahiart.luvia.thread.DiffFileView
import tech.asahiart.luvia.thread.DiffLineView
import tech.asahiart.luvia.thread.PanePiece
import tech.asahiart.luvia.thread.ProseSpanKind
import tech.asahiart.luvia.thread.TimelineItem
import tech.asahiart.luvia.thread.ToolTrailView
import tech.asahiart.luvia.thread.diffFileTitle
import tech.asahiart.luvia.thread.diffModulePath
import tech.asahiart.luvia.thread.highlightCode
import tech.asahiart.luvia.thread.proseSpans
import tech.asahiart.luvia.thread.proseWithoutTrail
import tech.asahiart.luvia.thread.askHasFreeText
import tech.asahiart.luvia.thread.askSubmission
import tech.asahiart.luvia.thread.canSubmitAsk
import tech.asahiart.luvia.thread.initialAskSelection
import tech.asahiart.luvia.thread.structuredDiffFiles
import tech.asahiart.luvia.thread.testBarCells
import tech.asahiart.luvia.thread.toolTrailView
import tech.asahiart.luvia.thread.ToolTrailKind
import tech.asahiart.luvia.ui.theme.LuviaTheme
import tech.asahiart.luvia.ui.theme.lightTerminalBoard

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
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.22f)),
                )
            }
        }
        thread.ask?.let { ask ->
            DecisionCard(
                ask = ask,
                agentType = thread.agentType,
                sessionTitle = thread.title,
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
    val pieces = remember(text) { chatSurfacePieces(text) }
    if (pieces.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        pieces.forEachIndexed { index, piece ->
            when (piece) {
                is PanePiece.Prose -> ProsePieces(piece.text)
                is PanePiece.Tool -> ToolTrail(piece.asCall(index))
                is PanePiece.Status -> StatusLine(piece.text)
            }
        }
    }
}

@Composable
private fun ProsePieces(text: String) {
    val kept = proseWithoutTrail(text).trim()
    if (kept.isEmpty()) return
    val runs = outputRuns(kept)
    if (runs.isEmpty()) {
        Bubble(kept, mine = false)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        runs.forEach { run ->
            if (!run.mono) {
                Bubble(run.text, mine = false)
                return@forEach
            }
            val diffs = structuredDiffFiles(run.text)
            if (diffs.isEmpty()) MonoBlock(run.text) else diffs.forEach { DiffCard(it) }
        }
    }
}

private fun PanePiece.Tool.asCall(index: Int): AcpToolCall =
    AcpToolCall(
        toolCallId = "out-$index",
        title = title,
        kind = kind,
        status = status,
        summary = detail,
    )

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
            if (mine) {
                Text(
                    text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                ProseText(text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    }
}

@Composable
private fun ProseText(text: String, modifier: Modifier = Modifier) {
    val spans = proseSpans(text)
    if (spans.none { it.kind == ProseSpanKind.Code }) {
        Text(text, modifier = modifier, style = MaterialTheme.typography.bodyLarge)
        return
    }
    val chip = MaterialTheme.colorScheme.surfaceContainerHighest
    Text(
        buildAnnotatedString {
            spans.forEach { span ->
                if (span.kind == ProseSpanKind.Code) {
                    withStyle(
                        SpanStyle(
                            fontFamily = LuviaTheme.mono,
                            fontWeight = FontWeight.SemiBold,
                            background = chip,
                        ),
                    ) { append(" ${span.text} ") }
                } else {
                    append(span.text)
                }
            }
        },
        modifier = modifier,
        style = MaterialTheme.typography.bodyLarge,
    )
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
    val subject = trailSubject(view, call)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(28.dp).fillMaxHeight()) {
            Box(
                Modifier
                    .padding(top = 24.dp)
                    .width(2.dp)
                    .fillMaxHeight()
                    .align(Alignment.TopCenter)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            TrailNode(kind, color, Modifier.align(Alignment.TopCenter))
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
private fun TrailNode(kind: ToolTrailKind, color: Color, modifier: Modifier = Modifier) {
    val shape = if (kind == ToolTrailKind.Run) RoundedCornerShape(6.dp) else CircleShape
    val node = if (kind == ToolTrailKind.Run) 22.dp else 20.dp
    Box(modifier.size(node).background(color, shape), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(12.dp)) {
            val ink = Color.White
            when (kind) {
                ToolTrailKind.Read -> {
                    drawRoundRect(
                        ink,
                        topLeft = Offset(size.width * 0.18f, size.height * 0.06f),
                        size = Size(size.width * 0.64f, size.height * 0.88f),
                        cornerRadius = CornerRadius(1.6f, 1.6f),
                        style = Stroke(width = 1.4f),
                    )
                    drawLine(ink, Offset(size.width * 0.32f, size.height * 0.38f), Offset(size.width * 0.68f, size.height * 0.38f), strokeWidth = 1.2f)
                    drawLine(ink, Offset(size.width * 0.32f, size.height * 0.58f), Offset(size.width * 0.68f, size.height * 0.58f), strokeWidth = 1.2f)
                }
                ToolTrailKind.Edit -> {
                    drawLine(ink, Offset(size.width * 0.22f, size.height * 0.78f), Offset(size.width * 0.72f, size.height * 0.22f), strokeWidth = 1.8f)
                    drawLine(ink, Offset(size.width * 0.62f, size.height * 0.18f), Offset(size.width * 0.84f, size.height * 0.36f), strokeWidth = 1.8f)
                }
                ToolTrailKind.Run -> {
                    val path = Path().apply {
                        moveTo(size.width * 0.30f, size.height * 0.16f)
                        lineTo(size.width * 0.84f, size.height * 0.50f)
                        lineTo(size.width * 0.30f, size.height * 0.84f)
                        close()
                    }
                    drawPath(path, ink)
                }
                ToolTrailKind.Other -> {
                    drawCircle(ink, radius = size.minDimension * 0.18f)
                }
            }
        }
    }
}

private fun trailSubject(view: ToolTrailView, call: AcpToolCall): String {
    val path = view.path?.takeIf { it.isNotBlank() }
    if (path != null && (view.kind == ToolTrailKind.Read || view.kind == ToolTrailKind.Edit)) {
        return shortTrailPath(path, basenameOnly = view.kind == ToolTrailKind.Edit)
    }
    val command = commandSubject(call.title)
    if (command.isNotBlank()) return command
    return path?.let { diffFileTitle(it) } ?: call.title.ifBlank { "" }
}

private fun shortTrailPath(path: String, basenameOnly: Boolean): String {
    val name = diffFileTitle(path)
    if (basenameOnly) return name
    val module = diffModulePath(path) ?: return name
    val head = module.substringBefore('/')
    return if (head == module) "$module/$name" else "$head/…/$name"
}

private fun commandSubject(title: String): String {
    var body = title.trim().trimStart('•', '·', '●', '*').trim()
    if (body.startsWith("%")) body = body.removePrefix("%").trim()
    body = body.trimStart('•', '·', '●').trim()
    if (body.startsWith("$:")) body = body.removePrefix("$:").trim()
    val arrow = body.indexOf('→')
    if (arrow >= 0) body = body.substring(0, arrow)
    body = body.replace(Regex("""\s*·\s*\d+(?:\.\d+)?\s*(?:ms|s|秒)\s*$""", RegexOption.IGNORE_CASE), "")
    return body.trim().trimEnd('·', '•').trim()
}

@Composable
private fun DiffCard(file: DiffFileView) {
    val extended = LuviaTheme.extended
    val module = diffModulePath(file.path)
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
                diffFileTitle(file.path),
                fontFamily = LuviaTheme.mono,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
            if (!module.isNullOrBlank()) {
                Text(
                    module,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    fontFamily = LuviaTheme.mono,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
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
                DiffLine(line)
            }
        }
    }
}

@Composable
private fun DiffLine(line: DiffLineView) {
    val extended = LuviaTheme.extended
    val bg = when (line.marker) {
        "+" -> extended.diffAdd.copy(alpha = 0.16f)
        "-" -> extended.diffDel.copy(alpha = 0.16f)
        else -> Color.Transparent
    }
    val slot = when (line.marker) {
        "+" -> extended.diffAdd
        "-" -> extended.diffDel
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val number = line.newNumber ?: line.oldNumber
    Row(Modifier.background(bg).padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            number?.toString().orEmpty(),
            modifier = Modifier.width(36.dp).padding(start = 6.dp),
            fontFamily = LuviaTheme.mono,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            line.marker,
            modifier = Modifier.width(16.dp),
            fontFamily = LuviaTheme.mono,
            style = MaterialTheme.typography.bodySmall,
            color = slot,
        )
        Text(
            highlightedLine(line.text),
            fontFamily = LuviaTheme.mono,
            style = MaterialTheme.typography.bodySmall,
            softWrap = false,
        )
    }
}

@Composable
private fun highlightedLine(text: String) = buildAnnotatedString {
    val plain = MaterialTheme.colorScheme.onSurface
    val comment = MaterialTheme.colorScheme.onSurfaceVariant
    val keyword = if (LuviaTheme.extended.lightTerminalBoard()) Color(0xFF9A3412) else Color(0xFFFBBF24)
    val type = if (LuviaTheme.extended.lightTerminalBoard()) Color(0xFF0F766E) else Color(0xFF5EEAD4)
    val number = if (LuviaTheme.extended.lightTerminalBoard()) Color(0xFF9F1239) else Color(0xFFFDA4AF)
    val string = LuviaTheme.extended.diffAdd
    val tokens = highlightCode(text)
    if (tokens.isEmpty()) {
        append(text)
        return@buildAnnotatedString
    }
    tokens.forEach { token ->
        val color = when (token.kind) {
            CodeTokenKind.Keyword -> keyword
            CodeTokenKind.Type -> type
            CodeTokenKind.Number -> number
            CodeTokenKind.String -> string
            CodeTokenKind.Comment -> comment
            CodeTokenKind.Plain -> plain
        }
        withStyle(SpanStyle(color = color)) { append(token.text) }
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
            val label = name.trim().let { raw ->
                if (raw.startsWith("×") || raw.startsWith("✗") || raw.startsWith("✘")) raw else "× $raw"
            }
            Text(
                label,
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
    val label = stringResource(R.string.terminal)
    val lines = remember(preview) {
        stripAnsi(preview.orEmpty())
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
            .takeLast(3)
    }
    val board = Color(0xFF1C2422)
    val ink = Color(0xFFD7E7E2)
    Box(
        modifier
            .width(92.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(board)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        if (lines.isEmpty()) {
            Column(
                Modifier.align(Alignment.CenterStart).padding(start = 8.dp, end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ThumbnailBar(Color(0xFF7DCEA0), 0.72f)
                ThumbnailBar(Color(0xFF5DADE2), 0.46f)
                ThumbnailBar(Color(0xFFE5989B), 0.34f)
            }
        } else {
            Column(
                Modifier.align(Alignment.CenterStart).padding(start = 7.dp, end = 14.dp, top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                lines.forEach { line ->
                    Text(
                        line,
                        color = ink,
                        fontFamily = LuviaTheme.mono,
                        fontSize = 8.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Canvas(Modifier.align(Alignment.BottomEnd).padding(5.dp).size(8.dp)) {
            val path = Path().apply {
                moveTo(size.width * 0.15f, size.height * 0.35f)
                lineTo(size.width * 0.65f, size.height * 0.35f)
                lineTo(size.width * 0.65f, size.height * 0.85f)
                moveTo(size.width * 0.35f, size.height * 0.15f)
                lineTo(size.width * 0.88f, size.height * 0.15f)
                lineTo(size.width * 0.88f, size.height * 0.68f)
            }
            drawPath(path, Color(0xFF9FE1CB), style = Stroke(width = 1.2f))
        }
    }
}

@Composable
private fun ThumbnailBar(color: Color, fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth(fraction)
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color),
    )
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
        val meter = label.contains('$') || label.contains('↑') || label.contains('↓') || label.startsWith("Session") || label.startsWith("DONE")
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
    agentType: String?,
    sessionTitle: String,
    index: Int,
    total: Int,
    enabled: Boolean,
    onAnswer: (AskOption) -> Unit,
) {
    val agentName = agentType?.takeIf { it.isNotBlank() } ?: sessionTitle
    var selected by remember(ask.question, ask.options) { mutableStateOf(initialAskSelection(ask)) }
    var note by remember(ask.question, ask.options) { mutableStateOf("") }
    val liveNote = if (askHasFreeText(ask)) note else ""
    val submitEnabled = enabled && canSubmitAsk(selected, liveNote)
    val onCard = MaterialTheme.colorScheme.onTertiaryContainer
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        shadowElevation = 12.dp,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AgentAvatar(agentType, sessionTitle, brand = true, size = 28.dp)
                Text(
                    stringResource(R.string.decision_needs_you, agentName),
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = onCard,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        "$index / $total",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = onCard,
                    )
                }
            }
            Text(
                ask.question ?: stringResource(R.string.decision_waiting),
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.headlineSmall,
                color = onCard,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            ask.options.forEach { option ->
                val picked = option == selected
                Row(
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
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(18.dp)
                            .border(
                                2.dp,
                                if (picked) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
                                CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (picked) {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .background(MaterialTheme.colorScheme.tertiary, CircleShape),
                            )
                        }
                    }
                    Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(option.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (option.recommended) {
                            Surface(
                                color = MaterialTheme.colorScheme.tertiary,
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Text(
                                    stringResource(R.string.agent_recommends),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiary,
                                )
                            }
                        }
                    }
                    option.consequence?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    }
                }
            }
            if (askHasFreeText(ask)) {
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
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        val delivered = askSubmission(ask, selected, liveNote) ?: return@Button
                        onAnswer(delivered)
                        note = ""
                        selected = null
                    },
                    enabled = submitEnabled,
                    modifier = Modifier.alpha(if (submitEnabled) 1f else 0.38f),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Text(stringResource(R.string.select_and_continue))
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
                    MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.38f)
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
