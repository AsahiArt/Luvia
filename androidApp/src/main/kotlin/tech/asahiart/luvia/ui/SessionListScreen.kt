package tech.asahiart.luvia.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.asahiart.luvia.AgentStatus
import tech.asahiart.luvia.R
import tech.asahiart.luvia.thread.AgentThread
import tech.asahiart.luvia.thread.AskOption
import tech.asahiart.luvia.thread.ListTrailDot
import tech.asahiart.luvia.thread.ListTrailTone
import tech.asahiart.luvia.thread.SessionFilter
import tech.asahiart.luvia.thread.SessionRoster
import tech.asahiart.luvia.thread.TrailThumb
import tech.asahiart.luvia.thread.listTrail
import tech.asahiart.luvia.thread.AGENT_BRAND_ASSET_CLAUDE
import tech.asahiart.luvia.thread.AGENT_BRAND_ASSET_GROK
import tech.asahiart.luvia.thread.AGENT_BRAND_ASSET_OPENAI
import tech.asahiart.luvia.thread.AGENT_BRAND_ASSET_PI
import tech.asahiart.luvia.thread.agentBrandLogo
import tech.asahiart.luvia.thread.agentColorSlot
import tech.asahiart.luvia.thread.agentMark
import tech.asahiart.luvia.thread.recommendedOption
import tech.asahiart.luvia.thread.searchSessions
import tech.asahiart.luvia.thread.sessionAgeLabel
import tech.asahiart.luvia.thread.sessionRoster
import tech.asahiart.luvia.ui.theme.LuviaTheme

private enum class HomeTab { Sessions, Machines, Settings }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SessionListScreen(
    hosts: List<HostUiModel>,
    selected: HostUiModel?,
    threads: List<AgentThread>,
    filter: SessionFilter,
    onFilter: (SessionFilter) -> Unit,
    onSelectHost: (String) -> Unit,
    onPair: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenThread: (AgentThread) -> Unit,
    onAnswer: (AgentThread, AskOption) -> Unit,
    onNewSession: () -> Unit,
    onOpenTerminal: () -> Unit,
    onRetry: () -> Unit = {},
    roster: SessionRoster = sessionRoster(threads),
    trails: Map<String, TrailThumb> = emptyMap(),
    latencyMs: Long? = null,
    nowEpochMs: Long = System.currentTimeMillis(),
    terminalModifier: Modifier = Modifier,
) {
    var tabName by rememberSaveable { mutableStateOf(HomeTab.Sessions.name) }
    var menu by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val tab = runCatching { HomeTab.valueOf(tabName) }.getOrDefault(HomeTab.Sessions)
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    BackHandler(enabled = tab != HomeTab.Sessions || searching) {
        when {
            searching -> {
                searching = false
                query = ""
            }
            else -> tabName = HomeTab.Sessions.name
        }
    }
    val shown = searchSessions(threads, if (searching) query else "")
    val pending = shown.filter { it.needsYou }
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        when (tab) {
                            HomeTab.Sessions -> stringResource(R.string.sessions_title)
                            HomeTab.Machines -> stringResource(R.string.tab_machines)
                            HomeTab.Settings -> stringResource(R.string.tab_settings)
                        },
                    )
                },
                navigationIcon = {
                    HostChip(
                        hosts = hosts,
                        selected = selected,
                        menu = menu,
                        onMenu = { menu = it },
                        onSelectHost = onSelectHost,
                        onPair = onPair,
                        onOpenSettings = onOpenSettings,
                    )
                },
                actions = {
                    if (tab == HomeTab.Sessions) {
                        if (selected != null && selected.connection != ConnectionBadge.Live && !selected.pairingRevoked) {
                            TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                        }
                        IconButton(onClick = { searching = !searching }) {
                            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search))
                        }
                        IconButton(onClick = onNewSession) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.new_session))
                        }
                    }
                },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            NavigationBar(windowInsets = WindowInsets.navigationBars) {
                NavigationBarItem(
                    selected = tab == HomeTab.Sessions,
                    onClick = { tabName = HomeTab.Sessions.name },
                    icon = { Icon(painterResource(R.drawable.ic_nav_sessions), contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_sessions)) },
                )
                NavigationBarItem(
                    selected = tab == HomeTab.Machines,
                    onClick = { tabName = HomeTab.Machines.name },
                    icon = { Icon(painterResource(R.drawable.ic_nav_machines), contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_machines)) },
                )
                NavigationBarItem(
                    selected = tab == HomeTab.Settings,
                    onClick = { tabName = HomeTab.Settings.name },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_settings)) },
                )
            }
        },
    ) { padding ->
        when (tab) {
            HomeTab.Sessions -> SessionsBody(
                padding = padding,
                selected = selected,
                shown = shown,
                pending = pending,
                filter = filter,
                roster = roster,
                trails = trails,
                latencyMs = latencyMs,
                nowEpochMs = nowEpochMs,
                searching = searching,
                query = query,
                onQuery = { query = it },
                onClearSearch = {
                    searching = false
                    query = ""
                },
                onFilter = onFilter,
                onOpenThread = onOpenThread,
                onAnswer = onAnswer,
            )
            HomeTab.Machines -> MachinesBody(
                padding = padding,
                hosts = hosts,
                selected = selected,
                onSelectHost = {
                    onSelectHost(it)
                    tabName = HomeTab.Sessions.name
                },
                onPair = onPair,
                onOpenTerminal = onOpenTerminal,
                terminalModifier = terminalModifier,
            )
            HomeTab.Settings -> SettingsBody(
                padding = padding,
                selected = selected,
                onOpenSettings = onOpenSettings,
                onPair = onPair,
            )
        }
    }
}

@Composable
private fun HostChip(
    hosts: List<HostUiModel>,
    selected: HostUiModel?,
    menu: Boolean,
    onMenu: (Boolean) -> Unit,
    onSelectHost: (String) -> Unit,
    onPair: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box {
        AssistChip(
            onClick = { onMenu(true) },
            label = {
                Text(
                    selected?.name ?: stringResource(R.string.no_computer),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingIcon = { ConnectionDot(selected?.connection) },
            trailingIcon = {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.switch_computer))
            },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { onMenu(false) }) {
            hosts.forEach { host ->
                DropdownMenuItem(
                    text = { Text(host.name) },
                    onClick = {
                        onMenu(false)
                        onSelectHost(host.id)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.pair_computer)) },
                onClick = {
                    onMenu(false)
                    onPair()
                },
            )
            if (selected != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.computer_settings)) },
                    onClick = {
                        onMenu(false)
                        onOpenSettings()
                    },
                )
            }
        }
    }
}

@Composable
private fun SessionsBody(
    padding: PaddingValues,
    selected: HostUiModel?,
    shown: List<AgentThread>,
    pending: List<AgentThread>,
    filter: SessionFilter,
    roster: SessionRoster,
    trails: Map<String, TrailThumb>,
    latencyMs: Long?,
    nowEpochMs: Long,
    searching: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onClearSearch: () -> Unit,
    onFilter: (SessionFilter) -> Unit,
    onOpenThread: (AgentThread) -> Unit,
    onAnswer: (AgentThread, AskOption) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(padding)) {
        StatsRow(roster, latencyMs)
        if (searching) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text(stringResource(R.string.search_sessions)) },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = onClearSearch) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
                    }
                },
            )
        }
        val showCards = pending.isNotEmpty() && filter != SessionFilter.Idle && filter != SessionFilter.Running
        if (showCards) {
            Text(
                stringResource(R.string.needs_you_section, pending.size),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            val pagerState = rememberPagerState(pageCount = { pending.size })
            HorizontalPager(
                state = pagerState,
                contentPadding = PaddingValues(start = 16.dp, end = 72.dp),
                pageSpacing = 12.dp,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) { page ->
                val thread = pending[page]
                DecisionPreview(
                    thread = thread,
                    age = sessionAgeLabel(thread.updatedEpochMs, nowEpochMs),
                    enabled = selected?.isObserver != true,
                    onAnswer = { option -> onAnswer(thread, option) },
                    onOpen = { onOpenThread(thread) },
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = filter == SessionFilter.All,
                onClick = { onFilter(SessionFilter.All) },
                label = { Text(stringResource(R.string.filter_all_count, roster.agents)) },
            )
            FilterChip(
                selected = filter == SessionFilter.NeedsYou,
                onClick = { onFilter(SessionFilter.NeedsYou) },
                label = { Text(stringResource(R.string.filter_needs_you_count, roster.waiting)) },
            )
            FilterChip(
                selected = filter == SessionFilter.Running,
                onClick = { onFilter(SessionFilter.Running) },
                label = { Text(stringResource(R.string.filter_running_count, roster.running)) },
            )
            FilterChip(
                selected = filter == SessionFilter.Idle,
                onClick = { onFilter(SessionFilter.Idle) },
                label = { Text(stringResource(R.string.filter_idle_count, roster.idle)) },
            )
        }
        if (shown.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center) {
                Text(
                    if (selected == null) stringResource(R.string.empty_pair_first) else stringResource(R.string.empty_filter),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.empty_sessions_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
                items(shown, key = { it.id }) { thread ->
                    SessionRow(
                        thread = thread,
                        age = sessionAgeLabel(thread.updatedEpochMs, nowEpochMs),
                        thumb = trails[thread.id],
                        running = selected?.connection == ConnectionBadge.Live && thread.status == AgentStatus.Working,
                        cached = selected != null && selected.connection != ConnectionBadge.Live,
                        onClick = { onOpenThread(thread) },
                    )
                    HorizontalDivider(Modifier.padding(start = 72.dp))
                }
            }
        }
    }
}

@Composable
private fun StatsRow(roster: SessionRoster, latencyMs: Long?) {
    val agents = stringResource(R.string.stats_agents, roster.agents)
    val latency = if (latencyMs != null) stringResource(R.string.stats_latency, latencyMs.toInt()) else null
    val head = if (latency != null) "$agents · $latency" else agents
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(head, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        StatDot(LuviaTheme.extended.live, stringResource(R.string.stats_running, roster.running))
        Spacer(Modifier.width(12.dp))
        StatDot(MaterialTheme.colorScheme.tertiary, stringResource(R.string.stats_waiting, roster.waiting))
    }
}

@Composable
private fun StatDot(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun MachinesBody(
    padding: PaddingValues,
    hosts: List<HostUiModel>,
    selected: HostUiModel?,
    onSelectHost: (String) -> Unit,
    onPair: () -> Unit,
    onOpenTerminal: () -> Unit,
    terminalModifier: Modifier,
) {
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text(
                stringResource(R.string.machines_placeholder),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (hosts.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.empty_pair_first),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        items(hosts, key = { it.id }) { host ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelectHost(host.id) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ConnectionDot(host.connection)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(host.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        host.connectionCopy(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (host.id == selected?.id) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item {
            TextButton(onClick = onPair, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.pair_computer))
            }
            if (selected != null) {
                TextButton(
                    onClick = onOpenTerminal,
                    modifier = terminalModifier.padding(horizontal = 8.dp),
                ) {
                    Text(stringResource(R.string.open_terminal))
                }
            }
        }
    }
}

@Composable
private fun SettingsBody(
    padding: PaddingValues,
    selected: HostUiModel?,
    onOpenSettings: () -> Unit,
    onPair: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(stringResource(R.string.settings_placeholder), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        if (selected != null) {
            Text(selected.name, style = MaterialTheme.typography.titleLarge)
            Text(selected.connectionCopy(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onOpenSettings, modifier = Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.computer_settings))
            }
        } else {
            Text(stringResource(R.string.pair_a_computer), style = MaterialTheme.typography.titleMedium)
            Button(onClick = onPair, modifier = Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.pair_computer))
            }
        }
    }
}

@Composable
private fun ConnectionDot(connection: ConnectionBadge?) {
    val color = when (connection) {
        ConnectionBadge.Live -> LuviaTheme.extended.live
        ConnectionBadge.Connecting -> LuviaTheme.extended.connecting
        ConnectionBadge.Stale -> LuviaTheme.extended.stale
        ConnectionBadge.Offline, null -> LuviaTheme.extended.offline
    }
    Box(Modifier.size(10.dp).background(color, CircleShape))
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SessionRow(
    thread: AgentThread,
    age: String?,
    thumb: TrailThumb?,
    running: Boolean,
    cached: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        val trail = listTrail(thread, thumb)
        Row(verticalAlignment = Alignment.Top) {
            AgentAvatar(thread.agentType, thread.title)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        thread.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    age?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val status = if (cached) stringResource(R.string.cached) else thread.status.sessionLabel()
                val subtitle = listOfNotNull(thread.agentType, status).joinToString(" · ")
                if (subtitle.isNotBlank() || trail.dots.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (subtitle.isNotBlank()) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = statusColor(thread.status, cached),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        if (trail.dots.isNotEmpty()) {
                            TrailDots(
                                dots = trail.dots,
                                tones = trail.tones,
                                faded = thread.status == AgentStatus.Idle,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
                val detail = trail.label
                if (!detail.isNullOrBlank()) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = if (trail.useMono) LuviaTheme.mono else FontFamily.Default,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (running) {
                    val fraction = trail.progressPermille?.let { it / 1000f }
                    if (fraction != null) {
                        LinearWavyProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    } else {
                        LinearWavyProgressIndicator(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun statusColor(status: AgentStatus, cached: Boolean): androidx.compose.ui.graphics.Color {
    if (cached) return MaterialTheme.colorScheme.onSurfaceVariant
    return when (status) {
        AgentStatus.Working -> LuviaTheme.extended.live
        AgentStatus.Blocked -> MaterialTheme.colorScheme.tertiary
        AgentStatus.Done -> LuviaTheme.extended.live
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
internal fun AgentAvatar(
    agentType: String?,
    title: String,
    brand: Boolean = true,
    size: Dp = 40.dp,
) {
    val logo = if (brand) agentBrandLogo(agentType, title) else null
    val drawable = logo?.let { brandDrawable(it.assetId) }
    if (logo != null && drawable != null) {
        val glyph = size * logo.inset.toFloat()
        if (logo.preservesColor) {
            Box(Modifier.size(size).clip(CircleShape), contentAlignment = Alignment.Center) {
                Image(
                    painterResource(drawable),
                    contentDescription = brandContentDescription(logo.assetId),
                    modifier = Modifier.size(glyph),
                )
            }
        } else {
            Box(
                Modifier.size(size).background(brandColor(logo.backgroundHex), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(drawable),
                    contentDescription = brandContentDescription(logo.assetId),
                    tint = brandColor(logo.foregroundHex),
                    modifier = Modifier.size(glyph),
                )
            }
        }
        return
    }
    val mark = agentMark(agentType, title)
    val (bg, fg) = when (agentColorSlot(agentType, title)) {
        0 -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        1 -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        2 -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(Modifier.size(size).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        Text(
            mark,
            color = fg,
            fontWeight = FontWeight.Bold,
            style = if (size < 32.dp) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge,
        )
    }
}

private fun brandDrawable(assetId: String): Int? = when (assetId) {
    AGENT_BRAND_ASSET_CLAUDE -> R.drawable.ic_agent_claude
    AGENT_BRAND_ASSET_OPENAI -> R.drawable.ic_agent_openai
    AGENT_BRAND_ASSET_GROK -> R.drawable.ic_agent_grok
    AGENT_BRAND_ASSET_PI -> R.drawable.ic_agent_pi
    else -> null
}

private fun brandContentDescription(assetId: String): String = when (assetId) {
    AGENT_BRAND_ASSET_CLAUDE -> "Claude"
    AGENT_BRAND_ASSET_OPENAI -> "OpenAI"
    AGENT_BRAND_ASSET_GROK -> "Grok"
    AGENT_BRAND_ASSET_PI -> "Pi"
    else -> assetId
}

private fun brandColor(hex: String): Color {
    val raw = hex.removePrefix("#")
    val rgb = raw.toLong(16).toInt()
    return Color(
        red = ((rgb shr 16) and 0xFF) / 255f,
        green = ((rgb shr 8) and 0xFF) / 255f,
        blue = (rgb and 0xFF) / 255f,
    )
}

@Composable
private fun TrailDots(
    dots: List<ListTrailDot>,
    tones: List<ListTrailTone>,
    faded: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.alpha(if (faded) 0.4f else 1f),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        dots.forEachIndexed { index, dot ->
            val tone = tones.getOrNull(index) ?: ListTrailTone.Pending
            val color = when {
                dot == ListTrailDot.Decide -> MaterialTheme.colorScheme.tertiary
                tone == ListTrailTone.Failed -> MaterialTheme.colorScheme.error
                tone == ListTrailTone.Ok -> LuviaTheme.extended.live
                else -> MaterialTheme.colorScheme.outline
            }
            Box(Modifier.size(8.dp).background(color, CircleShape))
        }
    }
}

@Composable
private fun DecisionPreview(
    thread: AgentThread,
    age: String?,
    enabled: Boolean,
    onAnswer: (AskOption) -> Unit,
    onOpen: () -> Unit,
) {
    val ask = thread.ask ?: return
    val primary = recommendedOption(ask) ?: ask.options.firstOrNull()
    val secondary = ask.options.firstOrNull { it != primary }
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AgentAvatar(thread.agentType, thread.title, size = 36.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    listOfNotNull(thread.title, age).joinToString(" · "),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                ask.question ?: thread.title,
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (primary != null || secondary != null) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    primary?.let { option ->
                        Button(
                            onClick = { onAnswer(option) },
                            enabled = enabled,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(option.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    secondary?.let { option ->
                        FilledTonalButton(
                            onClick = { onAnswer(option) },
                            enabled = enabled,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(option.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
