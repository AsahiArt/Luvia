package tech.asahiart.luvia.internal.uhp

import tech.asahiart.luvia.FileOpenTarget
import tech.asahiart.luvia.HostRuntime
import tech.asahiart.luvia.Outcome
import tech.asahiart.luvia.SearchKind
import tech.asahiart.luvia.SearchScope
import tech.asahiart.luvia.UhpMethods
import tech.asahiart.luvia.WorkspaceSummary
import tech.asahiart.luvia.activateSearch
import tech.asahiart.luvia.fileTree
import tech.asahiart.luvia.openFile
import tech.asahiart.luvia.querySearch
import tech.asahiart.luvia.refreshFiles
import tech.asahiart.luvia.revealFile
import tech.asahiart.luvia.userMessage

internal class FilesBoard(private val ctx: UhpContext) {
    fun load() {
        ctx.launch { refresh(invalidate = false) }
    }

    fun refresh() {
        ctx.launch { refresh(invalidate = true) }
    }

    fun open(path: String) {
        val session = ctx.session() ?: return
        val state = ctx.value()
        if (!state.canMutate || !session.supports(UhpMethods.FILES_OPEN) || path.isBlank()) return
        ctx.update { it.copy(files = it.files.copy(mutating = true, errorText = null)) }
        ctx.launch {
            when (val result = session.openFile(path, FileOpenTarget.TAB)) {
                is Outcome.Ok -> ctx.update { it.copy(files = it.files.copy(mutating = false)) }
                is Outcome.Err ->
                    ctx.update { it.copy(files = it.files.copy(mutating = false, errorText = result.failure.userMessage())) }
            }
        }
    }

    fun reveal(path: String) {
        val session = ctx.session() ?: return
        val state = ctx.value()
        if (!state.canMutate || !session.supports(UhpMethods.FILES_REVEAL) || path.isBlank()) return
        ctx.update { it.copy(files = it.files.copy(mutating = true, errorText = null)) }
        ctx.launch {
            when (val result = session.revealFile(path)) {
                is Outcome.Ok -> ctx.update { it.copy(files = it.files.copy(mutating = false)) }
                is Outcome.Err ->
                    ctx.update { it.copy(files = it.files.copy(mutating = false, errorText = result.failure.userMessage())) }
            }
        }
    }

    private suspend fun refresh(invalidate: Boolean) {
        val session = ctx.session()
        if (session == null) {
            ctx.update {
                it.copy(connected = false, files = it.files.copy(loading = false, errorText = NOT_CONNECTED))
            }
            return
        }
        if (!session.supports(UhpMethods.FILES_TREE)) return
        val canInvalidate = invalidate && ctx.value().canMutate && session.supports(UhpMethods.FILES_REFRESH)
        ctx.update { it.copy(connected = true, files = it.files.copy(loading = true, errorText = null)) }
        if (canInvalidate) {
            when (val refreshed = session.refreshFiles()) {
                is Outcome.Ok -> Unit
                is Outcome.Err -> {
                    ctx.update {
                        it.copy(files = it.files.copy(loading = false, errorText = refreshed.failure.userMessage()))
                    }
                    return
                }
            }
        }
        when (val result = session.fileTree()) {
            is Outcome.Ok ->
                ctx.update {
                    it.copy(
                        files = it.files.copy(
                            root = result.value.root,
                            rows = result.value.rows,
                            loading = false,
                            mutating = false,
                        ),
                    )
                }
            is Outcome.Err ->
                ctx.update { it.copy(files = it.files.copy(loading = false, errorText = result.failure.userMessage())) }
        }
    }
}

internal class SearchBoard(private val ctx: UhpContext) {
    fun setQuery(query: String) {
        ctx.update { it.copy(search = it.search.copy(query = query)) }
    }

    fun query() {
        ctx.launch { run() }
    }

    fun shown() {
        val query = ctx.value().search.query
        if (query.isNotBlank()) query()
    }

    fun activate(matchId: String) {
        val session = ctx.session() ?: return
        val state = ctx.value()
        if (!state.canMutate || !session.supports(UhpMethods.SEARCH_ACTIVATE)) return
        val match = state.search.matches.firstOrNull { it.id == matchId } ?: return
        val kind = searchKind(match.kind) ?: return
        ctx.update { it.copy(search = it.search.copy(loading = true, errorText = null)) }
        ctx.launch {
            when (val result = session.activateSearch(kind, match.target)) {
                is Outcome.Ok -> ctx.update { it.copy(search = it.search.copy(loading = false)) }
                is Outcome.Err ->
                    ctx.update { it.copy(search = it.search.copy(loading = false, errorText = result.failure.userMessage())) }
            }
        }
    }

    private suspend fun run() {
        val session = ctx.session()
        if (session == null) {
            ctx.update {
                it.copy(connected = false, search = it.search.copy(loading = false, errorText = NOT_CONNECTED))
            }
            return
        }
        if (!session.supports(UhpMethods.SEARCH_QUERY)) return
        val state = ctx.value()
        val query = state.search.query.trim()
        if (query.isEmpty()) return
        val runtime = ctx.runtime()
        val scopeLabel = searchScopeLabel(runtime, state.files.root)
        ctx.update {
            it.copy(
                connected = true,
                search = it.search.copy(loading = true, errorText = null, searched = true, scopeLabel = scopeLabel),
            )
        }
        when (val result = session.querySearch(query, scope = SearchScope.FILES, limit = 50)) {
            is Outcome.Ok ->
                ctx.update {
                    it.copy(
                        search = it.search.copy(
                            result = result.value,
                            matches = result.value.matches,
                            loading = false,
                            errorText = null,
                            searched = true,
                            scopeLabel = scopeLabel,
                        ),
                    )
                }
            is Outcome.Err ->
                ctx.update {
                    it.copy(
                        search = it.search.copy(
                            loading = false,
                            errorText = result.failure.userMessage(),
                            searched = true,
                            matches = emptyList(),
                            result = null,
                        ),
                    )
                }
        }
    }
}

internal fun searchScopeLabel(runtime: HostRuntime?, filesRoot: String): String? {
    if (filesRoot.isNotBlank()) return filesRoot
    val workspace = runtime?.snapshot?.workspaces?.firstOrNull { it.active }
        ?: runtime?.snapshot?.workspaces?.firstOrNull()
    val name = workspace?.name?.takeIf { it.isNotBlank() }
    val cwd = workspace?.cwd?.takeIf { it.isNotBlank() }
    return listOfNotNull(name, cwd).distinct().joinToString(" · ").takeIf { it.isNotBlank() }
}

internal fun preferredGitWorkspace(runtime: HostRuntime?, filesRoot: String): Int? {
    val workspaces = runtime?.snapshot?.workspaces.orEmpty()
    if (workspaces.isEmpty()) return null
    val active = workspaces.firstOrNull { it.active }
    fun WorkspaceSummary.isGit(): Boolean = !branch.isNullOrBlank()
    if (active != null && active.isGit()) return null
    if (filesRoot.isNotBlank()) {
        val match =
            workspaces.firstOrNull { workspace ->
                val cwd = workspace.cwd ?: return@firstOrNull false
                filesRoot == cwd || filesRoot.startsWith("$cwd/")
            }
        if (match != null) return match.index
    }
    val focusedCwd = runtime?.snapshot?.panes?.firstOrNull { it.focused }?.cwd
        ?: runtime?.snapshot?.agents?.firstOrNull { it.focused }?.cwd
    if (!focusedCwd.isNullOrBlank()) {
        val match =
            workspaces.firstOrNull { workspace ->
                val cwd = workspace.cwd ?: return@firstOrNull false
                focusedCwd == cwd || focusedCwd.startsWith("$cwd/")
            }
        if (match != null) return match.index
    }
    return workspaces.firstOrNull { it.isGit() }?.index
}

private fun searchKind(kind: String): SearchKind? =
    when (kind.lowercase()) {
        "session" -> SearchKind.SESSION
        "folder" -> SearchKind.FOLDER
        "tab" -> SearchKind.TAB
        "pane" -> SearchKind.PANE
        "agent" -> SearchKind.AGENT
        "file" -> SearchKind.FILE
        "output" -> SearchKind.OUTPUT
        else -> null
    }
