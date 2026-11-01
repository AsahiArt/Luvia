package tech.asahiart.luvia.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import tech.asahiart.luvia.HostRole
import tech.asahiart.luvia.Outcome
import tech.asahiart.luvia.PairingCodes
import tech.asahiart.luvia.PairingUiState
import tech.asahiart.luvia.R
import tech.asahiart.luvia.ui.theme.LuviaTheme

private val PairField = Color(0xFF070B16)
private val PairInk = Color(0xFFF4F7FF)
private val PairMuted = Color(0xFFB7C0D4)
private val SystemBlue = Color(0xFF0A84FF)
private val PairError = Color(0xFFFFB4AB)

private val TileShapes = listOf(
    RoundedCornerShape(percent = 50),
    RoundedCornerShape(16.dp),
    RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 12.dp, bottomEnd = 12.dp),
    RoundedCornerShape(16.dp),
    CircleShape,
    RoundedCornerShape(20.dp),
)

@Composable
fun PairingScreen(
    pairing: PairingUiState,
    onBegin: (String, HostRole) -> Unit,
    onComplete: (String) -> Unit,
    onCancel: () -> Unit,
    onClearError: () -> Unit,
    onBack: (() -> Unit)?,
    initialLongCode: String = "",
) {
    val draft = pairing.draft
    val onField = draft != null
    val barInk = if (onField) PairInk else MaterialTheme.colorScheme.onSurface
    Column(
        Modifier
            .fillMaxSize()
            .background(if (onField) PairField else MaterialTheme.colorScheme.surface)
            .statusBarsPadding(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = {
                    onCancel()
                    onBack()
                }) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = barInk)
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
            if (!onField) {
                Text(
                    stringResource(R.string.pair_new_computer),
                    style = MaterialTheme.typography.titleMedium,
                    color = barInk,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (draft == null) {
            PairingStart(
                completing = pairing.completing,
                error = pairing.errorMessage,
                onBegin = onBegin,
                onClearError = onClearError,
            )
        } else {
            PairingRitual(
                pairing = pairing,
                onComplete = onComplete,
                onClearError = onClearError,
                initialLongCode = initialLongCode,
            )
        }
    }
}

@Composable
private fun PairingStart(
    completing: Boolean,
    error: String?,
    onBegin: (String, HostRole) -> Unit,
    onClearError: () -> Unit,
) {
    var label by remember { mutableStateOf(Build.MODEL.ifBlank { "手机" }) }
    var role by remember { mutableStateOf(HostRole.Controller) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.pair_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = label,
            onValueChange = {
                label = it
                onClearError()
            },
            label = { Text(stringResource(R.string.device_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoleChip(stringResource(R.string.role_controller), role == HostRole.Controller) { role = HostRole.Controller }
            RoleChip(stringResource(R.string.role_observer), role == HostRole.Observer) { role = HostRole.Observer }
        }
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Button(
            onClick = { onBegin(label, role) },
            enabled = !completing && label.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(28.dp),
        ) {
            Text(stringResource(R.string.start_pairing))
        }
    }
}

@Composable
private fun RoleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, shape = RoundedCornerShape(20.dp)) { Text(label) }
    } else {
        FilledTonalButton(onClick = onClick, shape = RoundedCornerShape(20.dp)) { Text(label) }
    }
}

@Composable
private fun PairingRitual(
    pairing: PairingUiState,
    onComplete: (String) -> Unit,
    onClearError: () -> Unit,
    initialLongCode: String,
) {
    val draft = pairing.draft ?: return
    var longCode by remember { mutableStateOf(initialLongCode) }
    var scanning by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val started by remember(draft.command) { mutableLongStateOf(System.currentTimeMillis()) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val remaining = (5 * 60_000L - (nowMs - started)).coerceAtLeast(0)
    val decoded = remember(longCode) {
        (PairingCodes.decode(longCode) as? Outcome.Ok)?.value
    }
    val fingerprintMatches = decoded?.deviceKeyFingerprint == draft.deviceKeyFingerprint
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanning = true
    }
    LaunchedEffect(draft.command) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }
    if (scanning) {
        Dialog(onDismissRequest = { scanning = false }) {
            Column(Modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(28.dp)).padding(12.dp)) {
                Text(stringResource(R.string.scan_luvia_code), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                QrScanner(
                    onQrCode = { raw ->
                        scanning = false
                        longCode = raw.trim()
                        onClearError()
                    },
                    modifier = Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(20.dp)),
                )
                TextButton(onClick = { scanning = false }) { Text(stringResource(R.string.close)) }
            }
        }
    }
    val openScanner = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) scanning = true else cameraPermission.launch(Manifest.permission.CAMERA)
    }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.pair_stage_title),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                style = MaterialTheme.typography.displaySmall,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Medium,
                color = PairInk,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.pair_code_caption),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = PairMuted,
                textAlign = TextAlign.Center,
            )
            Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                ParticleOrb(Modifier.size(220.dp))
            }
            ShortCodeTiles()
            Text(
                formatRemaining(remaining),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = LuviaTheme.mono,
                fontWeight = FontWeight.SemiBold,
                color = SystemBlue,
                textAlign = TextAlign.Center,
            )
            if (remaining == 0L) {
                Text(
                    stringResource(R.string.pair_window_elapsed),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = PairMuted,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.fingerprint), style = MaterialTheme.typography.labelMedium, color = PairMuted)
                    Text(
                        draft.deviceKeyFingerprint,
                        fontFamily = LuviaTheme.mono,
                        style = MaterialTheme.typography.bodyMedium,
                        color = PairInk,
                    )
                    decoded?.hostKeyFingerprints?.firstOrNull()?.let { hostKey ->
                        Text(
                            stringResource(R.string.host_key, hostKey),
                            fontFamily = LuviaTheme.mono,
                            style = MaterialTheme.typography.bodySmall,
                            color = PairMuted,
                        )
                    }
                }
                if (fingerprintMatches) {
                    Box(
                        Modifier.size(32.dp).background(SystemBlue, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.fingerprint_match), tint = Color.White)
                    }
                }
            }
            decoded?.let { code ->
                val address = code.addresses.firstOrNull().orEmpty()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            code.hostLabel.ifBlank { stringResource(R.string.computer) },
                            style = MaterialTheme.typography.titleMedium,
                            color = PairInk,
                        )
                        Text(
                            address,
                            fontFamily = LuviaTheme.mono,
                            style = MaterialTheme.typography.bodySmall,
                            color = PairMuted,
                        )
                    }
                    if (looksLikeTailscale(address)) {
                        Text(
                            "Tailscale",
                            modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.28f), CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = PairInk,
                        )
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    draft.command,
                    modifier = Modifier.weight(1f),
                    fontFamily = LuviaTheme.mono,
                    style = MaterialTheme.typography.bodySmall,
                    color = PairInk,
                    maxLines = 3,
                )
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(draft.command))
                    copied = true
                }) {
                    Text(if (copied) stringResource(R.string.copied) else stringResource(R.string.copy), color = SystemBlue)
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = longCode,
                onValueChange = {
                    longCode = it
                    onClearError()
                },
                label = { Text(stringResource(R.string.luvia_code)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = PairInk, fontFamily = LuviaTheme.mono),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = PairInk,
                    unfocusedTextColor = PairInk,
                    focusedBorderColor = SystemBlue,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.28f),
                    focusedLabelColor = PairMuted,
                    unfocusedLabelColor = PairMuted,
                    cursorColor = SystemBlue,
                ),
            )
            Spacer(Modifier.height(12.dp))
        }
        pairing.errorMessage?.let { message ->
            Text(
                localizeUiMessage(message),
                color = PairError,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = { if (!pairing.completing) onComplete(longCode.trim()) },
                enabled = longCode.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SystemBlue,
                    contentColor = Color.White,
                    disabledContainerColor = SystemBlue.copy(alpha = 0.38f),
                    disabledContentColor = Color.White.copy(alpha = 0.7f),
                ),
            ) {
                if (pairing.completing) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.pairing_connecting))
                } else {
                    Text(stringResource(R.string.confirm_pair))
                }
            }
            Button(
                onClick = openScanner,
                modifier = Modifier.height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.12f),
                    contentColor = PairInk,
                ),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)),
            ) {
                Text(stringResource(R.string.scan_instead))
            }
        }
    }
}

@Composable
private fun ShortCodeTiles() {
    val pulse by rememberInfiniteTransition(label = "pair-code").animateFloat(
        initialValue = 0f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pair-slot",
    )
    val hot = pulse.toInt().coerceIn(0, 5)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        repeat(6) { index ->
            if (index == 3) Spacer(Modifier.width(10.dp))
            val filled = index == hot
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(width = 46.dp, height = 60.dp)
                    .clip(TileShapes[index])
                    .background(if (filled) SystemBlue else Color.White.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                if (filled) {
                    Box(Modifier.size(8.dp).background(Color.White, CircleShape))
                }
            }
        }
    }
}

@Composable
private fun ParticleOrb(modifier: Modifier = Modifier) {
    val spin by rememberInfiniteTransition(label = "pair-orb").animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 14000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "orb-spin",
    )
    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * 0.42f
        drawCircle(SystemBlue.copy(alpha = 0.16f), radius * 0.96f, center)
        drawCircle(SystemBlue.copy(alpha = 0.30f), radius * 0.48f, center)
        val count = 72
        for (i in 0 until count) {
            val t = i.toFloat() / count
            val angle = i * 2.399f + spin
            val dist = radius * sqrt(t)
            val point = Offset(
                center.x + cos(angle) * dist,
                center.y + sin(angle) * dist * 0.86f,
            )
            val alpha = 0.35f + 0.65f * (1f - t)
            drawCircle(
                SystemBlue.copy(alpha = alpha),
                radius = if (i % 9 == 0) 3.2.dp.toPx() else 1.8.dp.toPx(),
                center = point,
            )
        }
    }
}
