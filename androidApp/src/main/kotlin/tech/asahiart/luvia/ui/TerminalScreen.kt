package tech.asahiart.luvia.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tech.asahiart.luvia.TerminalKey
import tech.asahiart.luvia.ui.theme.LuviaTheme

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
    modifier: Modifier = Modifier,
) {
    val extended = LuviaTheme.extended
    var literal by remember { mutableStateOf("") }
    val controlling = model?.control == TerminalControl.Controlling
    Column(
        modifier
            .fillMaxSize()
            .background(extended.terminalBg)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back to chat", tint = extended.terminalFg)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    model?.title ?: "Terminal",
                    color = extended.terminalFg,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    when (model?.control) {
                        TerminalControl.Controlling -> "Controlling"
                        TerminalControl.Requesting -> "Requesting control"
                        TerminalControl.Conflict -> "Control held elsewhere"
                        TerminalControl.Observing, null -> "Observing"
                    },
                    color = extended.terminalFg.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (pendingDecisions > 0) {
                Text(
                    "$pendingDecisions waiting",
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.tertiaryContainer)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (model?.canControl == true && model.control != TerminalControl.Requesting) {
                if (controlling) {
                    FilledTonalButton(onClick = onReleaseControl, modifier = Modifier.padding(start = 8.dp)) {
                        Text("Release")
                    }
                } else {
                    Button(onClick = onRequestControl, modifier = Modifier.padding(start = 8.dp)) {
                        Text("Take control")
                    }
                }
            }
        }
        val panes = model?.panes.orEmpty()
        if (panes.size > 1) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                panes.forEachIndexed { index, pane ->
                    val selected = pane.paneId == model?.paneId
                    val shape = when (index) {
                        0 -> RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp, topEnd = 6.dp, bottomEnd = 6.dp)
                        panes.lastIndex -> RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 20.dp, bottomEnd = 20.dp)
                        else -> RoundedCornerShape(6.dp)
                    }
                    val colors = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
                    val ink = if (selected) MaterialTheme.colorScheme.onPrimary else extended.terminalFg
                    Text(
                        pane.title,
                        modifier = Modifier
                            .clip(shape)
                            .background(colors)
                            .clickable { onSelectPane(pane.paneId) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        color = ink,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
        val message = model?.conflictMessage ?: model?.errorText
        if (!message.isNullOrBlank()) {
            Text(
                message,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        val lines = terminalDisplayLines(
            text = model?.text.orEmpty(),
            isAnsi = model?.isAnsi == true,
            defaultForeground = extended.terminalFg,
            defaultBackground = extended.terminalBg,
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (lines.isEmpty()) {
                Text("Waiting for output.", color = extended.terminalFg.copy(alpha = 0.6f), fontFamily = LuviaTheme.mono)
            } else {
                lines.forEach { line ->
                    Text(line, fontFamily = LuviaTheme.mono, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            KeyChip("Esc") { onSendKey(TerminalKey.Escape) }
            KeyChip("Tab") { onSendKey(TerminalKey.Tab) }
            KeyChip("Ctrl-C") { onSendKey(TerminalKey.CtrlC) }
            KeyChip("Up") { onSendKey(TerminalKey.Up) }
            KeyChip("Down") { onSendKey(TerminalKey.Down) }
            KeyChip("Enter") { onSendKey(TerminalKey.Enter) }
        }
        if (controlling) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = literal,
                    onValueChange = { literal = it },
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = extended.terminalFg,
                        fontFamily = LuviaTheme.mono,
                    ),
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
                    Text("Send")
                }
            }
        } else {
            Box(Modifier.navigationBarsPadding().height(8.dp))
        }
    }
}

@Composable
private fun KeyChip(label: String, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        color = LuviaTheme.extended.terminalFg,
        fontFamily = LuviaTheme.mono,
        style = MaterialTheme.typography.labelLarge,
    )
}
