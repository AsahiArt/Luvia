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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.asahiart.luvia.AgentStatus
import tech.asahiart.luvia.R
import tech.asahiart.luvia.thread.AgentThread
import tech.asahiart.luvia.thread.AskOption
import tech.asahiart.luvia.thread.ListTrailDot
import tech.asahiart.luvia.thread.ListTrailTone
import tech.asahiart.luvia.thread.HostSessionFilter
import tech.asahiart.luvia.thread.SessionFilter
import tech.asahiart.luvia.thread.SessionPathOption
import tech.asahiart.luvia.thread.SessionPathText
import tech.asahiart.luvia.thread.SessionRoster
import tech.asahiart.luvia.thread.SessionScope
import tech.asahiart.luvia.thread.sessionPathTexts
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

private const val VISIBLE_PATHS: Int = 3

data class SessionScopeChoices(
    val allOnline: Int,
    val currentHost: Int,
    val paths: List<SessionPathOption>,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SessionListScreen(
    hosts: List<HostUiModel>,
    selected: HostUiModel?,
    threads: List<AgentThread>,
    filter: HostSessionFilter,
    onFilter: (HostSessionFilter) -> Unit,
    scopeChoices: SessionScopeChoices? = null,
    onlineHostIds: Set<String>? = null,
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
    homeDir: String? = null,
    sessionsLoading: Boolean = false,
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
                        if (selected != null && selected.connection != ConnectionBadge.Live) {
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
                scopeChoices = scopeChoices ?: SessionScopeChoices(roster.agents, roster.agents, emptyList()),
                onlineHostIds = onlineHostIds,
                onNewSession = onNewSession,
                roster = roster,
                trails = trails,
                homeDir = homeDir,
                sessionsLoading = sessionsLoading,
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
    filter: HostSessionFilter,
    scopeChoices: SessionScopeChoices,
    onlineHostIds: Set<String>?,
    onNewSession: () -> Unit,
    roster: SessionRoster,
    trails: Map<String, TrailThumb>,
    homeDir: String?,
    sessionsLoading: Boolean,
    nowEpochMs: Long,
    searching: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onClearSearch: () -> Unit,
    onFilter: (HostSessionFilter) -> Unit,
    onOpenThread: (AgentThread) -> Unit,
    onAnswer: (AgentThread, AskOption) -> Unit,
) {
    val pathTexts = sessionPathTexts(scopeChoices.paths.map { it.cwd }, homeDir)
    val scopeText = scopeSummary(filter.scope, selected?.name, pathTexts, homeDir)
    val liveHostIds = onlineHostIds ?: run {
        val current = selected
        if (current != null && current.connection == ConnectionBadge.Live) setOf(current.id) else emptySet()
    }
    Column(Modifier.fillMaxSize().padding(padding)) {
        StatsRow(
            roster,
            scopeText,
            monospace = filter.scope is SessionScope.Path,
        )
        if (
            selected != null &&
            (selected.connection == ConnectionBadge.Offline || selected.connection == ConnectionBadge.Stale)
        ) {
            Text(
                stringResource(R.string.host_offline_line, selected.name),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
        SessionFilterRow(
            filter = filter,
            hostName = selected?.name,
            choices = scopeChoices,
            roster = roster,
            pathTexts = pathTexts,
            homeDir = homeDir,
            onFilter = onFilter,
        )
        val showCards = pending.isNotEmpty() &&
            filter.status != SessionFilter.Idle &&
            filter.status != SessionFilter.Running
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
        if (shown.isEmpty() && sessionsLoading && !(searching && query.isNotBlank())) {
            SessionLoading()
        } else if (shown.isEmpty()) {
            SessionEmpty(
                selected = selected,
                searching = searching && query.isNotBlank(),
                scopeCount = roster.agents,
                filter = filter,
                scopeText = scopeText,
                onFilter = onFilter,
                onNewSession = onNewSession,
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
                items(shown, key = { "${it.hostId}:${it.id}" }) { thread ->
                    SessionRow(
                        thread = thread,
                        age = sessionAgeLabel(thread.updatedEpochMs, nowEpochMs),
                        thumb = trails["${thread.hostId}:${thread.id}"] ?: trails[thread.id],
                        running = thread.hostId in liveHostIds && thread.status == AgentStatus.Working,
                        cached = thread.hostId !in liveHostIds,
                        onClick = { onOpenThread(thread) },
                    )
                    HorizontalDivider(Modifier.padding(start = 72.dp))
                }
            }
        }
    }
}

@Composable
private fun SessionFilterRow(
    filter: HostSessionFilter,
    hostName: String?,
    choices: SessionScopeChoices,
    roster: SessionRoster,
    pathTexts: List<SessionPathText>,
    homeDir: String?,
    onFilter: (HostSessionFilter) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScopeFilterChip(filter, hostName, choices, pathTexts, homeDir, onFilter)
        Box(
            Modifier
                .padding(horizontal = 8.dp)
                .width(1.dp)
                .height(24.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusFilterChip(
                selected = filter.status == SessionFilter.All,
                label = stringResource(R.string.filter_all_count, roster.agents),
                onClick = { onFilter(filter.copy(status = SessionFilter.All)) },
            )
            StatusFilterChip(
                selected = filter.status == SessionFilter.NeedsYou,
                label = stringResource(R.string.filter_needs_you_count, roster.waiting),
                onClick = { onFilter(filter.copy(status = SessionFilter.NeedsYou)) },
            )
            StatusFilterChip(
                selected = filter.status == SessionFilter.Running,
                label = stringResource(R.string.filter_running_count, roster.running),
                onClick = { onFilter(filter.copy(status = SessionFilter.Running)) },
            )
            StatusFilterChip(
                selected = filter.status == SessionFilter.Idle,
                label = stringResource(R.string.filter_idle_count, roster.idle),
                onClick = { onFilter(filter.copy(status = SessionFilter.Idle)) },
            )
            Spacer(Modifier.width(16.dp))
        }
    }
}

@Composable
private fun StatusFilterChip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        leadingIcon = if (selected) {
            {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            }
        } else {
            null
        },
    )
}

@Composable
private fun ScopeFilterChip(
    filter: HostSessionFilter,
    hostName: String?,
    choices: SessionScopeChoices,
    pathTexts: List<SessionPathText>,
    homeDir: String?,
    onFilter: (HostSessionFilter) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    var showAllPaths by rememberSaveable { mutableStateOf(false) }
    val pathScope = filter.scope as? SessionScope.Path
    val selectedPath = pathScope?.let { pathText(it.cwd, pathTexts, homeDir) }
    Box {
        FilterChip(
            modifier = Modifier.widthIn(max = 160.dp),
            selected = filter.scope != SessionScope.AllOnline,
            onClick = { open = true },
            label = {
                Text(
                    selectedPath?.chip ?: scopeLabel(filter.scope, hostName),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = if (pathScope != null) FontFamily.Monospace else null,
                    modifier = Modifier
                        .widthIn(max = 72.dp)
                        .semantics {
                            if (selectedPath != null) contentDescription = selectedPath.relative
                        },
                )
            },
            leadingIcon = if (pathScope != null) {
                {
                    Icon(
                        painterResource(R.drawable.ic_folder),
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                }
            } else {
                null
            },
            trailingIcon = {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.scope_menu),
                )
            },
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = {
                open = false
                showAllPaths = false
            },
        ) {
            ScopeMenuItem(
                label = stringResource(R.string.scope_all),
                count = choices.allOnline,
                selected = filter.scope == SessionScope.AllOnline,
                mono = false,
                onClick = {
                    open = false
                    onFilter(filter.copy(scope = SessionScope.AllOnline))
                },
            )
            MenuGroupLabel(stringResource(R.string.scope_host_group))
            ScopeMenuItem(
                label = hostName ?: stringResource(R.string.scope_current_host),
                count = choices.currentHost,
                selected = filter.scope == SessionScope.CurrentHost,
                mono = false,
                onClick = {
                    open = false
                    onFilter(filter.copy(scope = SessionScope.CurrentHost))
                },
            )
            if (choices.paths.isNotEmpty()) {
                MenuGroupLabel(stringResource(R.string.scope_path_group))
                val visible = visiblePaths(choices.paths, pathScope, showAllPaths)
                visible.forEach { path ->
                    ScopeMenuItem(
                        label = pathText(path.cwd, pathTexts, homeDir).relative,
                        count = path.count,
                        selected = pathScope != null && sameCwd(pathScope.cwd, path.cwd),
                        mono = true,
                        onClick = {
                            open = false
                            onFilter(filter.copy(scope = SessionScope.Path(path.cwd)))
                        },
                    )
                }
                if (!showAllPaths && choices.paths.size > VISIBLE_PATHS) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.scope_more)) },
                        onClick = { showAllPaths = true },
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuGroupLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 48.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ScopeMenuItem(
    label: String,
    count: Int,
    selected: Boolean,
    mono: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    softWrap = false,
                    overflow = if (mono) TextOverflow.MiddleEllipsis else TextOverflow.Ellipsis,
                    style = if (mono) {
                        MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                    } else {
                        MaterialTheme.typography.bodyLarge
                    },
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    count.toString(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        leadingIcon = {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (selected) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(24.dp))
                }
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun SessionEmpty(
    selected: HostUiModel?,
    searching: Boolean,
    scopeCount: Int,
    filter: HostSessionFilter,
    scopeText: String,
    onFilter: (HostSessionFilter) -> Unit,
    onNewSession: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            selected == null -> {
                Text(stringResource(R.string.empty_pair_first), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.empty_sessions_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            searching -> {
                Text(stringResource(R.string.empty_filter), style = MaterialTheme.typography.titleMedium)
            }
            scopeCount == 0 && filter.scope is SessionScope.Path -> {
                Text(
                    scopeText,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.MiddleEllipsis,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 280.dp),
                )
                Text(
                    stringResource(R.string.empty_path_tail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onNewSession,
                    modifier = Modifier.padding(top = 16.dp),
                ) { Text(stringResource(R.string.new_session)) }
                TextButton(onClick = { onFilter(filter.copy(scope = SessionScope.AllOnline)) }) {
                    Text(stringResource(R.string.filter_see_all))
                }
            }
            scopeCount == 0 -> {
                Text(
                    emptyScopeMessage(filter.scope, scopeText),
                    style = MaterialTheme.typography.titleMedium,
                )
                Button(
                    onClick = onNewSession,
                    modifier = Modifier.padding(top = 16.dp),
                ) { Text(stringResource(R.string.new_session)) }
                if (filter.scope != SessionScope.AllOnline) {
                    TextButton(onClick = { onFilter(filter.copy(scope = SessionScope.AllOnline)) }) {
                        Text(stringResource(R.string.filter_see_all))
                    }
                }
            }
            filter.status != SessionFilter.All -> {
                Text(
                    stringResource(R.string.empty_status_in_scope, scopeText, statusWord(filter.status)),
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = { onFilter(filter.copy(status = SessionFilter.All)) }) {
                    Text(stringResource(R.string.filter_clear_status))
                }
            }
            else -> {
                Text(stringResource(R.string.empty_filter), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun SessionLoading() {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            stringResource(R.string.sessions_loading),
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun scopeLabel(scope: SessionScope, hostName: String?): String =
    when (scope) {
        SessionScope.AllOnline -> stringResource(R.string.scope_all)
        SessionScope.CurrentHost -> hostName ?: stringResource(R.string.scope_current_host)
        is SessionScope.Path -> scope.cwd.trim().trimEnd('/').ifEmpty { scope.cwd }
    }

@Composable
private fun emptyScopeMessage(scope: SessionScope, scopeText: String): String =
    when (scope) {
        SessionScope.AllOnline -> stringResource(R.string.empty_scope_online)
        SessionScope.CurrentHost -> stringResource(R.string.empty_scope_host, scopeText)
        is SessionScope.Path -> stringResource(R.string.empty_scope_path, scopeText)
    }

private fun sameCwd(left: String, right: String): Boolean {
    val a = left.trim().trimEnd('/').ifEmpty { return false }
    val b = right.trim().trimEnd('/').ifEmpty { return false }
    return a == b
}

private fun pathText(cwd: String, texts: List<SessionPathText>, homeDir: String?): SessionPathText =
    texts.firstOrNull { sameCwd(it.cwd, cwd) } ?: sessionPathTexts(listOf(cwd), homeDir).first()

@Composable
private fun scopeSummary(
    scope: SessionScope,
    hostName: String?,
    texts: List<SessionPathText>,
    homeDir: String?,
): String = when (scope) {
    is SessionScope.Path -> pathText(scope.cwd, texts, homeDir).relative
    else -> scopeLabel(scope, hostName)
}

private fun visiblePaths(
    paths: List<SessionPathOption>,
    selected: SessionScope.Path?,
    showAll: Boolean,
): List<SessionPathOption> {
    if (showAll || paths.size <= VISIBLE_PATHS) return paths
    val head = paths.take(VISIBLE_PATHS)
    val extra = selected?.let { scope -> paths.firstOrNull { sameCwd(it.cwd, scope.cwd) } } ?: return head
    return if (head.any { sameCwd(it.cwd, extra.cwd) }) head else head + extra
}

@Composable
private fun statusWord(status: SessionFilter): String =
    when (status) {
        SessionFilter.All -> stringResource(R.string.filter_all)
        SessionFilter.NeedsYou -> stringResource(R.string.filter_needs_you_short)
        SessionFilter.Running -> stringResource(R.string.filter_running)
        SessionFilter.Idle -> stringResource(R.string.filter_idle)
    }

@Composable
private fun StatsRow(roster: SessionRoster, scopeLabel: String, monospace: Boolean) {
    val summary = stringResource(R.string.scope_summary, scopeLabel, roster.agents)
    val count = stringResource(R.string.scope_session_count, roster.agents)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).semantics { contentDescription = summary },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                scopeLabel,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.MiddleEllipsis,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (monospace) FontFamily.Monospace else null,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                count,
                maxLines = 1,
                softWrap = false,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatDot(LuviaTheme.extended.live, stringResource(R.string.stats_running, roster.running))
            Spacer(Modifier.width(12.dp))
            StatDot(MaterialTheme.colorScheme.tertiary, stringResource(R.string.stats_waiting, roster.waiting))
        }
    }
}

@Composable
private fun StatDot(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge)
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
                    Text(host.connection.chromeLabel(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Text(selected.connection.chromeLabel(), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
