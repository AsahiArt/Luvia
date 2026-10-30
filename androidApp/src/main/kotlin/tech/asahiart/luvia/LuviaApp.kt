package tech.asahiart.luvia

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.asahiart.luvia.ui.ChatFirstApp
import tech.asahiart.luvia.ui.theme.prewarmTerminalFont

@Composable
fun LuviaApp(launchIntent: Intent? = null) {
    val context = LocalContext.current
    val viewModel: LuviaViewModel = viewModel(
        factory = remember(context) { LuviaViewModel.Factory(context.applicationContext) },
    )
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) {
            prewarmTerminalFont(context.applicationContext)
        }
    }
    val runtimes by viewModel.hosts.collectAsStateWithLifecycle()
    val notifications = remember { StatusNotificationController(context) }
    var askedNotificationPermission by rememberSaveable { mutableStateOf(false) }
    var notificationPermissionEpoch by remember { mutableIntStateOf(0) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        askedNotificationPermission = true
        if (granted) notificationPermissionEpoch++
    }
    LaunchedEffect(runtimes, notificationPermissionEpoch) {
        val online = runtimes.firstOrNull { it.link is HostLink.Online }
        if (online == null) {
            notifications.dismiss()
            return@LaunchedEffect
        }
        val notificationsGranted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!notificationsGranted) {
            if (!askedNotificationPermission) {
                askedNotificationPermission = true
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            return@LaunchedEffect
        }
        val agents = online.snapshot?.agents ?: online.profile.topology?.agents.orEmpty()
        val sessionName = (online.link as HostLink.Online).sessionName
            ?: online.snapshot?.sessionName
            ?: "session"
        notifications.show(
            AmbientStatus(
                hostId = online.profile.id,
                hostName = online.profile.alias,
                sessionName = sessionName,
                connection = online.freshness.name,
                workingAgents = agents.count { it.status == AgentStatus.Working },
                blockedAgents = agents.count { it.status == AgentStatus.Blocked },
                completedAgents = agents.count { it.status == AgentStatus.Done },
                sensitiveSnippet = null,
                isStale = online.freshness == ConnectionFreshness.Stale,
            ),
            allowSensitiveSnippet = false,
        )
    }
    DisposableEffect(notifications) {
        onDispose { notifications.dismiss() }
    }

    ChatFirstApp(hostCount = runtimes.size)
}
