package tech.asahiart.luvia.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import tech.asahiart.luvia.R
import tech.asahiart.luvia.TerminalKey
import tech.asahiart.luvia.prefersDarkInk
import tech.asahiart.luvia.thread.TerminalFailureBanner
import tech.asahiart.luvia.thread.paneSegmentsShareWidth
import tech.asahiart.luvia.thread.terminalFailureBanner
import tech.asahiart.luvia.thread.terminalFailureSummary
import tech.asahiart.luvia.thread.terminalFixAgentName
import tech.asahiart.luvia.thread.terminalGrid
import tech.asahiart.luvia.thread.terminalPaneNumber
import tech.asahiart.luvia.thread.terminalSegmentLabel
import tech.asahiart.luvia.thread.terminalStatusLine
import tech.asahiart.luvia.thread.testBarCells
import tech.asahiart.luvia.ui.theme.LuviaTheme
import tech.asahiart.luvia.ui.theme.composerInk
import tech.asahiart.luvia.ui.theme.inactivePaneInk
import tech.asahiart.luvia.ui.theme.lightTerminalBoard

private val BadgeRed = Color(0xFFE23D3D)
private val FixInk = Color(0xFF3B1210)

@Composable
fun TerminalScreen(
    model: TerminalUiModel?,
    unreadCount: Int,
    onBack: () -> Unit,
    onSelectPane: (String) -> Unit,
    onRequestControl: () -> Unit,
    onReleaseControl: () -> Unit,
    onSendText: (String) -> Unit,
    onSendKey: (TerminalKey) -> Unit,
    statusOverride: String? = null,
    connection: ConnectionBadge? = null,
    onAskFix: (String) -> Boolean = { false },
    modifier: Modifier = Modifier,
) {
    val extended = LuviaTheme.extended
    var literal by remember { mutableStateOf("") }
    var ctrlLatched by remember { mutableStateOf(false) }
    var columns by remember { mutableIntStateOf(0) }
    var rows by remember { mutableIntStateOf(0) }
    val controlling = model?.control == TerminalControl.Controlling && !connection.isLinkDown()
    val panes = model?.panes.orEmpty()
    val paneIndex = panes.indexOfFirst { it.paneId == model?.paneId }
    val banner = remember(model?.text) { terminalFailureBanner(model?.text) }
    var fixRefused by remember(banner?.excerpt) { mutableStateOf(false) }
    val density = LocalDensity.current
    val measureStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = LuviaTheme.mono)
    val measurer = rememberTextMeasurer()
    val cell = remember(measureStyle) {
        measurer.measure(AnnotatedString("M"), style = measureStyle).size
    }
    val linePx = with(density) {
        val specified = measureStyle.lineHeight
        if (specified.isSpecified) specified.roundToPx() else cell.height
    }.coerceAtLeast(1)
    Column(
        modifier
            .fillMaxSize()
            .background(extended.terminalBg)
            .statusBarsPadding()
            .imePadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.back_to_chat), tint = extended.terminalFg)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (paneIndex >= 0) {
                        stringResource(R.string.terminal_pane_title, terminalPaneNumber(paneIndex))
                    } else {
                        model?.title ?: stringResource(R.string.terminal)
                    },
                    color = extended.terminalFg,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    terminalSubtitle(model, connection, columns, rows),
                    color = extended.terminalFg,
                    fontWeight = if (!controlling) FontWeight.SemiBold else FontWeight.Medium,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TerminalControlChrome(
                model = model,
                connection = connection,
                controlling = controlling,
                onRequestControl = onRequestControl,
            )
        }
        if (panes.size > 1) {
            PaneSegments(
                panes = panes,
                selectedId = model?.paneId,
                onSelect = onSelectPane,
            )
        }
        val detail = (model?.conflictMessage ?: model?.errorText)?.let(::localizeUiMessage)
        val bannerNote = detail ?: statusOverride
        if (!bannerNote.isNullOrBlank() && bannerNote != stringResource(R.string.link_offline)) {
            Text(
                bannerNote,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (banner != null) {
            FailureBar(
                banner = banner,
                agent = model?.title.orEmpty(),
                refused = fixRefused,
                onFix = { fixRefused = !onAskFix(banner.excerpt) },
            )
        }
        val lines = terminalDisplayLines(
            text = model?.text.orEmpty(),
            isAnsi = model?.isAnsi == true,
            defaultForeground = extended.terminalFg,
            defaultBackground = extended.terminalBg,
            dimForeground = if (extended.lightTerminalBoard()) extended.terminalDim else null,
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { size ->
                    val next = viewportGrid(size, density, cell.width, linePx)
                    if (next.first != columns || next.second != rows) {
                        columns = next.first
                        rows = next.second
                    }
                },
        ) {
            Column(
                Modifier
                    .matchParentSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
            ) {
                if (lines.isEmpty()) {
                    Text(
                        stringResource(R.string.waiting_output),
                        modifier = Modifier.padding(horizontal = 12.dp),
                        color = extended.terminalFg,
                        fontFamily = LuviaTheme.mono,
                    )
                } else {
                    TerminalLines(lines, banner)
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TerminalChip("esc", ChipTone.Plain, Modifier.weight(1f)) { onSendKey(TerminalKey.Escape) }
            TerminalChip("tab", ChipTone.Plain, Modifier.weight(1f)) { onSendKey(TerminalKey.Tab) }
            TerminalChip(
                stringResource(R.string.key_ctrl),
                if (ctrlLatched) ChipTone.Primary else ChipTone.Plain,
                Modifier.weight(1f),
            ) {
                ctrlLatched = !ctrlLatched
            }
            TerminalChip("↑", ChipTone.Plain, Modifier.weight(1f)) { onSendKey(TerminalKey.Up) }
            TerminalChip("↓", ChipTone.Plain, Modifier.weight(1f)) { onSendKey(TerminalKey.Down) }
            TerminalChip("←", ChipTone.Plain, Modifier.weight(1f)) { onSendKey(TerminalKey.Left) }
            TerminalChip("→", ChipTone.Plain, Modifier.weight(1f)) { onSendKey(TerminalKey.Right) }
            TerminalChip("^C", ChipTone.Danger, Modifier.weight(1f)) {
                ctrlLatched = false
                onSendKey(TerminalKey.CtrlC)
            }
        }
        if (controlling) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val fieldFill = MaterialTheme.colorScheme.surfaceContainerHigh
                val fieldInk = extended.composerInk(fieldFill)
                BasicTextField(
                    value = literal,
                    onValueChange = { literal = it },
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(fieldFill)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = fieldInk,
                        fontFamily = LuviaTheme.mono,
                    ),
                    cursorBrush = SolidColor(fieldInk),
                )
                Button(
                    onClick = {
                        if (literal.isNotEmpty()) {
                            onSendText(literal)
                            literal = ""
                        }
                    },
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(stringResource(R.string.send))
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChatReturnCapsule(unreadCount = unreadCount, onClick = onBack)
            Spacer(Modifier.weight(1f))
            if (controlling) {
                OutlinedButton(
                    onClick = onReleaseControl,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, extended.terminalFg.copy(alpha = 0.72f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = extended.terminalFg),
                ) {
                    Text(stringResource(R.string.release_control_full))
                }
            }
        }
    }
}

@Composable
private fun terminalSubtitle(
    model: TerminalUiModel?,
    connection: ConnectionBadge?,
    columns: Int,
    rows: Int,
): String {
    if (connection.isOffline()) return stringResource(R.string.link_offline)
    if (connection == ConnectionBadge.Connecting) return stringResource(R.string.link_reconnecting)
    val syncedWord = stringResource(R.string.terminal_synced)
    return when (model?.control) {
        TerminalControl.Requesting -> stringResource(R.string.terminal_requesting)
        TerminalControl.Conflict -> stringResource(R.string.terminal_conflict)
        TerminalControl.Controlling ->
            terminalStatusLine(model.title, columns, rows, model.synced, syncedWord)
        TerminalControl.Observing, null ->
            if (model?.synced == true) {
                terminalStatusLine(model.title, columns, rows, true, syncedWord)
            } else {
                stringResource(R.string.terminal_observing)
            }
    }
}

@Composable
private fun TerminalControlChrome(
    model: TerminalUiModel?,
    connection: ConnectionBadge?,
    controlling: Boolean,
    onRequestControl: () -> Unit,
) {
    val linkDown = connection.isLinkDown()
    if (linkDown) {
        Text(
            if (connection.isOffline()) stringResource(R.string.link_offline) else stringResource(R.string.link_reconnecting),
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
    }
    if (model?.canControl != true || model.control == TerminalControl.Requesting) return
    if (controlling) {
        Row(
            Modifier
                .padding(start = 8.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.terminal_controlling),
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    } else {
        Button(
            onClick = onRequestControl,
            enabled = !linkDown,
            modifier = Modifier.padding(start = 8.dp),
        ) {
            Text(stringResource(R.string.take_control))
        }
    }
}

private val PaneSegmentMinWidth = 96.dp

@Composable
private fun PaneSegments(
    panes: List<TerminalPaneChoice>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    if (paneSegmentsShareWidth(panes.size)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            panes.forEachIndexed { index, pane ->
                PaneSegment(
                    index = index,
                    pane = pane,
                    selected = pane.paneId == selectedId,
                    onSelect = onSelect,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        return
    }
    val listState = rememberLazyListState()
    val selectedIndex = panes.indexOfFirst { it.paneId == selectedId }
    LaunchedEffect(selectedId, panes.size) {
        if (selectedIndex < 0) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .first { it.isNotEmpty() }
        val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == selectedIndex }
        if (!visible) listState.animateScrollToItem(selectedIndex)
    }
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(panes, key = { _, pane -> pane.paneId }) { index, pane ->
            PaneSegment(
                index = index,
                pane = pane,
                selected = pane.paneId == selectedId,
                onSelect = onSelect,
                modifier = Modifier.widthIn(min = PaneSegmentMinWidth),
            )
        }
    }
}

@Composable
private fun PaneSegment(
    index: Int,
    pane: TerminalPaneChoice,
    selected: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val fill = if (selected) scheme.primary else scheme.surfaceContainer
    val ink = if (selected) contrastInk(fill) else LuviaTheme.extended.inactivePaneInk()
    Box(
        modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(fill)
            .clickable { onSelect(pane.paneId) }
            .semantics { this.selected = selected }
            .padding(horizontal = 8.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            terminalSegmentLabel(index, pane.title),
            color = ink,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FailureBar(
    banner: TerminalFailureBanner,
    agent: String,
    refused: Boolean,
    onFix: () -> Unit,
) {
    val extended = LuviaTheme.extended
    val cells = testBarCells(banner.passed, banner.total, true)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(extended.failureCard)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                terminalFailureSummary(banner),
                color = extended.failureCardInk,
                fontFamily = LuviaTheme.mono,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp),
                horizontalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                cells.forEach { bad ->
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(Color.White.copy(alpha = if (bad) 0.35f else 0.92f)),
                    )
                }
            }
        }
        Button(
            onClick = onFix,
            modifier = Modifier.padding(start = 10.dp),
            shape = RoundedCornerShape(50),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = FixInk),
        ) {
            AgentAvatar(agentType = agent, title = agent, size = 20.dp)
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.ask_agent_fix, terminalFixAgentName(agent)),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
    if (refused) {
        Text(
            stringResource(R.string.fix_not_sent),
            modifier = Modifier.padding(start = 12.dp, top = 4.dp),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun TerminalLines(lines: List<AnnotatedString>, banner: TerminalFailureBanner?) {
    val wash = LuviaTheme.extended.failureWash
    val body = LuviaTheme.extended.terminalFg
    val outlined = banner?.outlinedLines?.toSet().orEmpty()
    var index = 0
    while (index < lines.size) {
        if (index !in outlined) {
            TerminalLine(lines[index], body, Modifier.padding(horizontal = 12.dp))
            index++
            continue
        }
        val start = index
        while (index < lines.size && index in outlined) index++
        Column(Modifier.fillMaxWidth().background(wash)) {
            for (lineIndex in start until index) {
                TerminalLine(lines[lineIndex], body, Modifier.padding(horizontal = 12.dp, vertical = 1.dp))
            }
        }
    }
}

@Composable
private fun TerminalLine(text: AnnotatedString, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.fillMaxWidth(),
        color = color,
        fontFamily = LuviaTheme.mono,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun ChatReturnCapsule(unreadCount: Int, onClick: () -> Unit) {
    val extended = LuviaTheme.extended
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("💬", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.back_to_chat),
                color = extended.terminalFg,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        if (unreadCount > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-6).dp)
                    .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                    .clip(CircleShape)
                    .background(BadgeRed)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (unreadCount > 99) "99+" else unreadCount.toString(),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

private fun viewportGrid(
    size: IntSize,
    density: Density,
    cellWidth: Int,
    linePx: Int,
): Pair<Int, Int> {
    val padX = with(density) { 24.dp.roundToPx() }
    val padY = with(density) { 16.dp.roundToPx() }
    return terminalGrid(
        (size.width - padX).coerceAtLeast(0),
        (size.height - padY).coerceAtLeast(0),
        cellWidth,
        linePx,
    ) ?: (0 to 0)
}

private fun ConnectionBadge?.isOffline(): Boolean =
    this == ConnectionBadge.Offline || this == ConnectionBadge.Stale

private fun ConnectionBadge?.isLinkDown(): Boolean =
    isOffline() || this == ConnectionBadge.Connecting

private enum class ChipTone { Plain, Primary, Danger }

@Composable
private fun TerminalChip(
    label: String,
    tone: ChipTone,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val fill = if (tone == ChipTone.Primary) scheme.primary else scheme.surfaceContainerHigh
    val ink = when (tone) {
        ChipTone.Primary -> contrastInk(fill)
        ChipTone.Danger -> scheme.error
        ChipTone.Plain -> LuviaTheme.extended.terminalFg
    }
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(fill)
            .border(if (tone == ChipTone.Danger) 1.dp else 0.dp, if (tone == ChipTone.Danger) scheme.error else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .semantics { selected = tone == ChipTone.Primary }
            .padding(horizontal = 2.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = ink,
            fontFamily = LuviaTheme.mono,
            fontWeight = if (tone == ChipTone.Primary) FontWeight.Bold else FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}

private fun contrastInk(fill: Color): Color =
    if (prefersDarkInk(fill.red, fill.green, fill.blue)) Color(0xFF14201E) else Color(0xFFF7FBF9)
