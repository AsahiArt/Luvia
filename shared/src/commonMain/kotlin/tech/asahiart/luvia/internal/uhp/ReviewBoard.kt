package tech.asahiart.luvia.internal.uhp

import tech.asahiart.luvia.DiffLayer
import tech.asahiart.luvia.Failure
import tech.asahiart.luvia.LuviaSession
import tech.asahiart.luvia.Outcome
import tech.asahiart.luvia.ReviewLine
import tech.asahiart.luvia.UhpMethods
import tech.asahiart.luvia.UnconfirmedKind
import tech.asahiart.luvia.isLostMutation
import tech.asahiart.luvia.userMessage

internal class ReviewBoard(private val ctx: UhpContext) {
    fun load() {
        ctx.launch {
            val session = ctx.session()
            if (session == null) {
                ctx.update { it.copy(connected = false) }
                return@launch
            }
            ctx.update { it.copy(connected = true, review = it.review.copy(loading = true, errorText = null)) }
            val state = ctx.value()
            if (state.needsProjectPick()) {
                ctx.update { it.copy(review = it.review.copy(loading = false, list = null)) }
                return@launch
            }
            val index = state.projectWorkspaceIndex()
            if (state.canMutate && index != null && session.supports(UhpMethods.WORKSPACE_FOCUS)) {
                session.focusWorkspace(index)
            }
            if (state.canMutate && session.supports(UhpMethods.DIFF_REFRESH)) {
                session.refreshDiff()
            }
            val list =
                when (val result = session.listDiff()) {
                    is Outcome.Ok -> result.value
                    is Outcome.Err -> {
                        ctx.update {
                            it.copy(review = it.review.copy(loading = false, errorText = result.failure.userMessage()))
                        }
                        return@launch
                    }
                }
            val notes =
                if (session.supports(UhpMethods.DIFF_NOTE_LIST)) {
                    when (val result = session.listReviewNotes()) {
                        is Outcome.Ok -> result.value
                        is Outcome.Err -> emptyList()
                    }
                } else {
                    emptyList()
                }
            ctx.update {
                it.copy(
                    review = it.review.copy(
                        list = list,
                        notes = notes.ifEmpty { it.review.notes },
                        loading = false,
                    ),
                )
            }
            val selected = ctx.value().review
            val path = selected.selectedPath
            if (path != null) {
                fetchFile(path, selected.selectedLayer)
            }
        }
    }

    fun openFile(path: String, layer: DiffLayer?) {
        if (path.endsWith('/') || path.endsWith('\\')) {
            ctx.update {
                it.copy(
                    review = it.review.copy(
                        selectedPath = path,
                        selectedLayer = layer,
                        selectedFile = null,
                    ),
                )
            }
            return
        }
        ctx.update {
            it.copy(
                review = it.review.copy(
                    selectedPath = path,
                    selectedLayer = layer,
                    loading = true,
                    errorText = null,
                ),
            )
        }
        ctx.launch { fetchFile(path, layer) }
    }

    fun closeFile() {
        ctx.update {
            it.copy(review = it.review.copy(selectedPath = null, selectedLayer = null, selectedFile = null))
        }
    }

    fun setNoteDraft(text: String) {
        ctx.update { it.copy(review = it.review.copy(noteDraft = text)) }
    }

    fun setSendTarget(paneId: String?) {
        ctx.update { it.copy(review = it.review.copy(sendTarget = paneId)) }
    }

    fun addNote(file: String, line: ReviewLine, body: String, layer: DiffLayer?) {
        val session = ctx.session() ?: return
        val state = ctx.value()
        if (!state.canMutate || state.review.sending || state.review.unconfirmed != null) return
        if (!session.supports(UhpMethods.DIFF_NOTE_ADD)) return
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return
        ctx.update { it.copy(review = it.review.copy(sending = true, errorText = null)) }
        ctx.launch {
            when (val result = session.addReviewNote(file = file, line = line, body = trimmed, layer = layer)) {
                is Outcome.Ok -> {
                    ctx.update {
                        it.copy(
                            review = it.review.copy(
                                sending = false,
                                unconfirmed = null,
                                noteDraft = "",
                                errorText = null,
                            ),
                        )
                    }
                    refreshNotes(ctx.value().review.selectedPath)
                }
                is Outcome.Err -> applyMutationFailure(UnconfirmedKind.AddReviewNote, result.failure)
            }
        }
    }

    fun resolve(id: String) {
        mutate(UhpMethods.DIFF_NOTE_RESOLVE, UnconfirmedKind.ResolveReviewNote) { it.resolveReviewNote(id) }
    }

    fun reopen(id: String) {
        mutate(UhpMethods.DIFF_NOTE_REOPEN, UnconfirmedKind.ReopenReviewNote) { it.reopenReviewNote(id) }
    }

    fun remove(id: String) {
        mutate(UhpMethods.DIFF_NOTE_REMOVE, UnconfirmedKind.RemoveReviewNote) { it.removeReviewNote(id) }
    }

    fun send(to: String) {
        val session = ctx.session() ?: return
        val state = ctx.value()
        if (!state.canMutate || state.review.sending || state.review.unconfirmed != null) return
        if (!session.supports(UhpMethods.DIFF_NOTE_SEND)) return
        ctx.update { it.copy(review = it.review.copy(sending = true, errorText = null)) }
        ctx.launch {
            when (val result = session.sendReviewNotes(to, allOpen = true)) {
                is Outcome.Ok -> {
                    ctx.update {
                        it.copy(
                            review = it.review.copy(
                                sending = false,
                                unconfirmed = null,
                                lastSend = result.value,
                                errorText = null,
                            ),
                        )
                    }
                    refreshNotes(ctx.value().review.selectedPath)
                }
                is Outcome.Err -> applyMutationFailure(UnconfirmedKind.SendNotes, result.failure)
            }
        }
    }

    fun check() {
        ctx.launch {
            val session = ctx.session()
            if (session == null) {
                ctx.update { it.copy(review = it.review.copy(errorText = NOT_CONNECTED)) }
                return@launch
            }
            if (refreshNotes(ctx.value().review.selectedPath)) {
                ctx.update { it.copy(review = it.review.copy(unconfirmed = null, sending = false)) }
            }
        }
    }

    private fun mutate(
        method: String,
        kind: UnconfirmedKind,
        call: suspend (LuviaSession) -> Outcome<*>,
    ) {
        val session = ctx.session() ?: return
        val state = ctx.value()
        if (!state.canMutate || state.review.sending || state.review.unconfirmed != null) return
        if (!session.supports(method)) return
        ctx.update { it.copy(review = it.review.copy(sending = true, errorText = null)) }
        ctx.launch {
            when (val result = call(session)) {
                is Outcome.Ok -> {
                    ctx.update {
                        it.copy(review = it.review.copy(sending = false, unconfirmed = null, errorText = null))
                    }
                    refreshNotes(ctx.value().review.selectedPath)
                }
                is Outcome.Err -> applyMutationFailure(kind, result.failure)
            }
        }
    }

    private suspend fun fetchFile(path: String, layer: DiffLayer?) {
        val session = ctx.session() ?: return
        var diffLoadFailed = false
        if (session.supports(UhpMethods.DIFF_GET)) {
            when (val result = session.getDiff(path, layer, includePatch = true)) {
                is Outcome.Ok ->
                    ctx.update {
                        it.copy(
                            review = it.review.copy(
                                selectedFile = result.value,
                                selectedPath = path,
                                selectedLayer = layer,
                                loading = false,
                            ),
                        )
                    }
                is Outcome.Err -> {
                    diffLoadFailed = true
                    ctx.update {
                        it.copy(review = it.review.copy(loading = false, errorText = result.failure.userMessage()))
                    }
                }
            }
        }
        refreshNotes(path, clearErrorOnSuccess = !diffLoadFailed)
    }

    private suspend fun refreshNotes(file: String?, clearErrorOnSuccess: Boolean = true): Boolean {
        val session = ctx.session() ?: return false
        if (!session.supports(UhpMethods.DIFF_NOTE_LIST)) return false
        return when (val result = session.listReviewNotes(file = file)) {
            is Outcome.Ok -> {
                ctx.update {
                    it.copy(
                        review = it.review.copy(
                            notes = result.value,
                            errorText = if (clearErrorOnSuccess) null else it.review.errorText,
                        ),
                    )
                }
                true
            }
            is Outcome.Err -> {
                ctx.update { it.copy(review = it.review.copy(errorText = result.failure.userMessage())) }
                false
            }
        }
    }

    private fun applyMutationFailure(kind: UnconfirmedKind, failure: Failure) {
        ctx.update {
            it.copy(
                review =
                    if (failure.isLostMutation()) {
                        it.review.copy(sending = false, unconfirmed = kind, errorText = null)
                    } else {
                        it.review.copy(sending = false, errorText = failure.userMessage())
                    },
            )
        }
    }
}
