package tech.asahiart.luvia.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.asahiart.luvia.R
import tech.asahiart.luvia.TerminalKey
import tech.asahiart.luvia.prefersDarkInk
import tech.asahiart.luvia.thread.TerminalFailureBanner
import tech.asahiart.luvia.thread.terminalFailureBanner
import tech.asahiart.luvia.thread.terminalSegmentLabel
import tech.asahiart.luvia.thread.testBarCells
import tech.asahiart.luvia.ui.theme.LuviaTheme
import tech.asahiart.luvia.ui.theme.composerInk
import tech.asahiart.luvia.ui.theme.failureLineChrome
import tech.asahiart.luvia.ui.theme.inactivePaneInk
import tech.asahiart.luvia.ui.theme.lightTerminalBoard

@Composable
fun TerminalScreen(
    model: TerminalUiModel?,
    pendingDecisions: Int,
    onBack: () -> Unit,
    onSelectPane: (String) -> Unit,
    onRequestControl: () -> Unit,
    onReleaseControl: () -> Unit,
    onSendText: (String) -> Unit,
    onSendKey: (TerminalKey) -> Unit,
    statusOverride: String? = null,
    connection: ConnectionBadge? = null,
    canAskFix: Boolean = false,
    onAskFix: (String) -> Boolean = { false },
    modifier: Modifier = Modifier,
) {
    val extended = LuviaTheme.extended
    var literal by remember { mutableStateOf("") }
    var ctrlLatched by remember { mutableStateOf(false) }
    val controlling = model?.control == TerminalControl.Controlling && !connection.isLinkDown()
    val linkDown = connection.isLinkDown()
    val panes = model?.panes.orEmpty()
    val paneIndex = panes.indexOfFirst { it.paneId == model?.paneId }
    val banner = remember(model?.text) { terminalFailureBanner(model?.text) }
    var fixRefused by remember(banner?.excerpt) { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxSize()
            .background(extended.terminalBg)
            .statusBarsPadding(),
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
                        stringResource(R.string.terminal_pane_title, paneIndex + 1)
                    } else {
                        model?.title ?: stringResource(R.string.terminal)
                    },
                    color = extended.terminalFg,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    terminalSubtitle(model, connection),
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
                enabled = canAskFix,
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
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (lines.isEmpty()) {
                Text(stringResource(R.string.waiting_output), color = extended.terminalFg, fontFamily = LuviaTheme.mono)
            } else {
                TerminalLines(lines, banner)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TerminalChip("esc", selected = false, shape = RoundedCornerShape(14.dp)) { onSendKey(TerminalKey.Escape) }
            TerminalChip("tab", selected = false, shape = RoundedCornerShape(14.dp)) { onSendKey(TerminalKey.Tab) }
            TerminalChip(stringResource(R.string.key_ctrl), selected = ctrlLatched, shape = RoundedCornerShape(14.dp)) {
                ctrlLatched = !ctrlLatched
            }
            TerminalChip("↑", selected = false, shape = RoundedCornerShape(14.dp)) { onSendKey(TerminalKey.Up) }
            TerminalChip("↓", selected = false, shape = RoundedCornerShape(14.dp)) { onSendKey(TerminalKey.Down) }
            TerminalChip("←", selected = false, shape = RoundedCornerShape(14.dp)) { onSendKey(TerminalKey.Left) }
            TerminalChip("→", selected = false, shape = RoundedCornerShape(14.dp)) { onSendKey(TerminalKey.Right) }
            TerminalChip("~C", selected = ctrlLatched, shape = RoundedCornerShape(14.dp)) {
                ctrlLatched = false
                onSendKey(TerminalKey.CtrlC)
            }
            TerminalChip("回车", selected = false, shape = RoundedCornerShape(14.dp)) { onSendKey(TerminalKey.Enter) }
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
            BadgedBox(
                badge = {
                    if (pendingDecisions > 0) {
                        Badge {
                            Text(if (pendingDecisions > 99) "99+" else pendingDecisions.toString())
                        }
                    }
                },
            ) {
                FilledTonalButton(onClick = onBack) {
                    Text(stringResource(R.string.back_to_chat))
                }
            }
            Spacer(Modifier.weight(1f))
            if (controlling) {
                OutlinedButton(onClick = onReleaseControl) {
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
): String {
    if (connection.isOffline()) return stringResource(R.string.link_offline)
    if (connection == ConnectionBadge.Connecting) return stringResource(R.string.link_reconnecting)
    return when (model?.control) {
        TerminalControl.Requesting -> stringResource(R.string.terminal_requesting)
        TerminalControl.Conflict -> stringResource(R.string.terminal_conflict)
        TerminalControl.Controlling -> {
            val name = model.title
            if (model.synced) "$name · ${stringResource(R.string.terminal_synced)}" else name
        }
        TerminalControl.Observing, null -> stringResource(R.string.terminal_observing)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaneSegments(
    panes: List<TerminalPaneChoice>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    val ink = LuviaTheme.extended.inactivePaneInk()
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        val segment = maxOf(160.dp, maxWidth / panes.size)
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.horizontalScroll(rememberScrollState()).width(segment * panes.size),
        ) {
            panes.forEachIndexed { index, pane ->
                SegmentedButton(
                    selected = pane.paneId == selectedId,
                    onClick = { onSelect(pane.paneId) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = panes.size),
                    colors = SegmentedButtonDefaults.colors(
                        inactiveContentColor = ink,
                        disabledInactiveContentColor = ink.copy(alpha = 0.38f),
                    ),
                    icon = {},
                    label = {
                        Text(
                            terminalSegmentLabel(index, pane.title),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun FailureBar(
    banner: TerminalFailureBanner,
    enabled: Boolean,
    refused: Boolean,
    onFix: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val duration = banner.duration
    val label = if (duration != null) {
        stringResource(
            R.string.terminal_failure_summary_timed,
            banner.passed,
            banner.total,
            banner.failed,
            duration,
        )
    } else {
        stringResource(R.string.terminal_failure_summary, banner.passed, banner.total, banner.failed)
    }
    val cells = testBarCells(banner.passed, banner.total, true)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.errorContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = scheme.onErrorContainer,
                fontFamily = LuviaTheme.mono,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp).height(6.dp),
                horizontalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                cells.forEach { bad ->
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(if (bad) scheme.error else scheme.onErrorContainer.copy(alpha = 0.45f)),
                    )
                }
            }
        }
        Button(
            onClick = onFix,
            enabled = enabled,
            modifier = Modifier.padding(start = 10.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = scheme.onErrorContainer,
                contentColor = scheme.errorContainer,
                disabledContainerColor = scheme.onErrorContainer.copy(alpha = 0.38f),
                disabledContentColor = scheme.errorContainer.copy(alpha = 0.38f),
            ),
        ) {
            Icon(Icons.Filled.Build, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.ask_agent_fix), maxLines = 1, softWrap = false)
        }
    }
    if (refused) {
        Text(
            stringResource(R.string.fix_not_sent),
            modifier = Modifier.padding(start = 12.dp, top = 4.dp),
            color = scheme.error,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun TerminalLines(lines: List<AnnotatedString>, banner: TerminalFailureBanner?) {
    val extended = LuviaTheme.extended
    val outlined = banner?.outlinedLines?.toSet().orEmpty()
    val chrome = extended.failureLineChrome(MaterialTheme.colorScheme.error)
    val body = extended.terminalFg
    var index = 0
    while (index < lines.size) {
        if (index !in outlined) {
            TerminalLine(lines[index], body)
            index++
            continue
        }
        val start = index
        while (index < lines.size && index in outlined) index++
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, chrome.stroke, RoundedCornerShape(8.dp))
                .background(chrome.fill)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            val failureText = chrome.text
            val recolor = extended.lightTerminalBoard() && failureText != body
            for (lineIndex in start until index) {
                val line = if (recolor) {
                    lines[lineIndex].recolorDefaults(setOf(body, extended.terminalDim), failureText)
                } else {
                    lines[lineIndex]
                }
                TerminalLine(line, failureText)
            }
        }
    }
}

@Composable
private fun TerminalLine(text: AnnotatedString, color: Color) {
    Text(
        text,
        color = color,
        fontFamily = LuviaTheme.mono,
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun ConnectionBadge?.isOffline(): Boolean =
    this == ConnectionBadge.Offline || this == ConnectionBadge.Stale

private fun ConnectionBadge?.isLinkDown(): Boolean =
    isOffline() || this == ConnectionBadge.Connecting

@Composable
private fun TerminalChip(
    label: String,
    selected: Boolean,
    shape: Shape,
    onClick: () -> Unit,
) {
    val fill = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val ink = if (prefersDarkInk(fill.red, fill.green, fill.blue)) Color(0xFF14201E) else Color(0xFFF7FBF9)
    Text(
        label,
        modifier = Modifier
            .padding(vertical = 6.dp)
            .clip(shape)
            .background(fill)
            .border(if (selected) 2.dp else 1.dp, ink.copy(alpha = if (selected) 1f else 0.55f), shape)
            .clickable(onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        color = ink,
        fontFamily = LuviaTheme.mono,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
        style = MaterialTheme.typography.labelLarge,
    )
}
