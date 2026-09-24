@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package tech.asahiart.luvia.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.asahiart.luvia.AcpPermissionKind
import tech.asahiart.luvia.AcpPermissionOption
import tech.asahiart.luvia.AcpPermissionRequest
import tech.asahiart.luvia.AcpPlanEntry
import tech.asahiart.luvia.AcpPlanStatus
import tech.asahiart.luvia.AcpRunState
import tech.asahiart.luvia.AcpState
import tech.asahiart.luvia.AcpStopReason
import tech.asahiart.luvia.AcpToolCall
import tech.asahiart.luvia.AcpToolStatus
import tech.asahiart.luvia.AcpTranscriptItem
import tech.asahiart.luvia.AcpTranscriptRole
import tech.asahiart.luvia.HostUhpState
import tech.asahiart.luvia.ui.theme.LuviaTheme

@Composable
fun AcpLaunchSheet(
    state: AcpState,
    onDismiss: () -> Unit,
    onSelectAgent: (String?) -> Unit,
    onCwdChange: (String) -> Unit,
    onLaunch: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val canLaunch = !state.launchAgentId.isNullOrBlank() && state.launchCwd.isNotBlank()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Launch an agent",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                "Runs on the host over ACP. Approvals arrive here as buttons.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.agentsLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            state.agents.forEach { agent ->
                val selected = agent.id == state.launchAgentId
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (agent.available) {
                                Modifier.clickable { onSelectAgent(agent.id) }
                            } else {
                                Modifier
                            },
                        ),
                    color = if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        Color.Transparent
                    },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    ListItem(
                        headlineContent = { Text(agent.name) },
                        supportingContent = {
                            Text(
                                agent.command,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        },
                        trailingContent = {
                            if (!agent.available) {
                                Text(
                                    "Not installed",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.alpha(if (agent.available) 1f else 0.5f),
                    )
                }
            }
            OutlinedTextField(
                value = state.launchCwd,
                onValueChange = onCwdChange,
                label = { Text("Directory") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
            )
            Button(
                onClick = onLaunch,
                enabled = canLaunch,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Launch") }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun LaunchAgentCard(
    onLaunch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Start something",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "Launch a coding agent on this host and steer it from here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onLaunch) { Text("Launch agent") }
        }
    }
}

@Composable
internal fun AcpPlanCard(entries: List<AcpPlanEntry>) {
    var expanded by remember { mutableStateOf(true) }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Plan",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse plan" else "Expand plan",
                )
            }
            if (expanded) {
                entries.forEach { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        PlanStatusIcon(entry.status)
                        Column(Modifier.weight(1f)) {
                            Text(entry.content, style = MaterialTheme.typography.bodyMedium)
                            entry.priority?.takeIf { it.isNotBlank() }?.let { priority ->
                                Text(
                                    priority,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanStatusIcon(status: AcpPlanStatus) {
    when (status) {
        AcpPlanStatus.Completed -> Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = LuviaTheme.extended.live,
            modifier = Modifier.size(18.dp),
        )
        AcpPlanStatus.InProgress, AcpPlanStatus.Pending -> Box(
            Modifier
                .size(18.dp)
                .border(
                    width = 2.dp,
                    color = if (status == AcpPlanStatus.InProgress) {
                        LuviaTheme.extended.connecting
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    shape = CircleShape,
                ),
        )
    }
}

@Composable
internal fun StreamingCursor() {
    val infinite = rememberInfiniteTransition(label = "acp-cursor")
    val alpha by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "acp-cursor-alpha",
    )
    Box(
        Modifier
            .padding(bottom = 2.dp)
            .size(width = 7.dp, height = 14.dp)
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.onSurface),
    )
}

@Composable
internal fun AcpThoughtRow(id: String, text: String) {
    var expanded by remember(id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Thinking",
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse thinking" else "Expand thinking",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        if (expanded && text.isNotEmpty()) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun AcpToolRow(call: AcpToolCall) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            toolKindIcon(call.kind),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                call.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            call.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                Text(
                    summary,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            Modifier
                .size(8.dp)
                .background(toolStatusColor(call.status), CircleShape),
        )
    }
}

@Composable
internal fun AcpTurnDivider(stopReason: AcpStopReason) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(Modifier.weight(1f))
        Text(
            stopReason.label(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        HorizontalDivider(Modifier.weight(1f))
    }
}

private fun toolKindIcon(kind: String?): ImageVector = when (kind?.lowercase()) {
    "read" -> AcpIcons.Description
    "edit" -> Icons.Filled.Edit
    "execute" -> Icons.Filled.PlayArrow
    "search" -> Icons.Filled.Search
    "fetch" -> AcpIcons.ArrowDownward
    else -> Icons.Filled.Build
}

@Composable
private fun toolStatusColor(status: AcpToolStatus): Color = when (status) {
    AcpToolStatus.Pending, AcpToolStatus.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant
    AcpToolStatus.InProgress -> LuviaTheme.extended.connecting
    AcpToolStatus.Completed -> LuviaTheme.extended.live
    AcpToolStatus.Failed -> MaterialTheme.colorScheme.error
}

private fun AcpStopReason.label(): String = when (this) {
    AcpStopReason.EndTurn -> "Turn ended"
    AcpStopReason.MaxTokens -> "Max tokens"
    AcpStopReason.MaxTurnRequests -> "Max turn requests"
    AcpStopReason.Refusal -> "Refusal"
    AcpStopReason.Cancelled -> "Cancelled"
    AcpStopReason.Unknown -> "Turn ended"
}

private object AcpIcons {
    val Stop: ImageVector by lazy {
        filledIcon("Stop") {
            moveTo(6f, 6f)
            horizontalLineToRelative(12f)
            verticalLineToRelative(12f)
            horizontalLineToRelative(-12f)
            close()
        }
    }
    val ArrowDownward: ImageVector by lazy {
        filledIcon("ArrowDownward") {
            moveTo(20f, 12f)
            lineToRelative(-1.41f, -1.41f)
            lineTo(13f, 16.17f)
            verticalLineTo(4f)
            horizontalLineToRelative(-2f)
            verticalLineTo(16.17f)
            lineToRelative(-5.58f, -5.59f)
            lineTo(4f, 12f)
            lineToRelative(8f, 8f)
            close()
        }
    }
    val Description: ImageVector by lazy {
        filledIcon("Description") {
            moveTo(14f, 2f)
            horizontalLineTo(6f)
            curveTo(4.9f, 2f, 4.01f, 2.9f, 4.01f, 4f)
            lineTo(4f, 20f)
            curveTo(4f, 21.1f, 4.89f, 22f, 5.99f, 22f)
            horizontalLineTo(18f)
            curveTo(19.1f, 22f, 20f, 21.1f, 20f, 20f)
            verticalLineTo(8f)
            lineTo(14f, 2f)
            close()
            moveTo(16f, 18f)
            horizontalLineTo(8f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(8f)
            verticalLineToRelative(2f)
            close()
            moveTo(16f, 14f)
            horizontalLineTo(8f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(8f)
            verticalLineToRelative(2f)
            close()
            moveTo(13f, 9f)
            verticalLineTo(3.5f)
            lineTo(18.5f, 9f)
            horizontalLineTo(13f)
            close()
        }
    }
}

private fun filledIcon(
    name: String,
    builder: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black), pathBuilder = builder)
    }.build()
