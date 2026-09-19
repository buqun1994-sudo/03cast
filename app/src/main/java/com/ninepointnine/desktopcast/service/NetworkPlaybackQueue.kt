package com.ninepointnine.desktopcast.service

/** Sole owner of network item order, selection, retry and natural-end grace. Runs serially on main. */
internal class NetworkPlaybackQueue(
    private val player: PlaybackQueuePlayer,
    private val scheduler: QueueScheduler,
    private val onStateChanged: (PlaybackQueueState) -> Unit,
    private val onQueueEnded: () -> Unit,
    private val onQueueFailed: (String) -> Unit,
) {
    var state = PlaybackQueueState()
        private set
    private var generation = 0L
    private var timerGeneration = 0L
    private var preparationTask: QueueCancellation? = null
    private var endTask: QueueCancellation? = null
    private var changingPlaylist = false

    fun load(item: PlaybackQueueItem, playing: Boolean = true) {
        cancelTasks()
        // Explicit /play or SetAVTransportURI selects now; it is never interpreted as prefetch.
        state.current?.takeIf { it.status in setOf(PlaybackQueueItemStatus.PLAYING, PlaybackQueueItemStatus.PAUSED, PlaybackQueueItemStatus.PREPARING) }?.let {
            reduce(PlaybackQueueCommand.MarkStatus(it.id, PlaybackQueueItemStatus.READY))
        }
        val next = item.copy(generation = ++generation, status = PlaybackQueueItemStatus.PREPARING, retryCount = 0)
        reduce(PlaybackQueueCommand.Start(next))
        reduce(PlaybackQueueCommand.Select(next.id, playing))
        sync()
        player.select(state.current!!, playing)
        publish()
        watchPreparation()
    }

    fun append(item: PlaybackQueueItem) {
        val known = state.items.firstOrNull { it.id == item.id }
        if (known?.samePresentation(item) == true) return
        if (known != null && known.id == state.currentItemId && !known.sameSource(item)) {
            // A sender reused an item id for a different source. Start a new
            // playback generation so late callbacks from the old source are
            // ignored and the new source is explicitly selected.
            load(item)
            return
        }
        val replacement = if (known != null && known.sameSource(item)) {
            item.copy(
                generation = known.generation,
                status = known.status,
                retryCount = known.retryCount,
            )
        } else {
            item.copy(generation = ++generation)
        }
        reduce(PlaybackQueueCommand.Offer(replacement))
        sync()
        publish()
        if (state.awaitingNext) continueAfterEnd(item.id)
    }

    fun setNext(item: PlaybackQueueItem?) {
        val known = state.next
        val next = if (item == null) null else if (known?.id == item.id && known.sameSource(item)) {
            item.copy(generation = known.generation, status = known.status, retryCount = known.retryCount)
        } else item.copy(generation = ++generation)
        reduce(PlaybackQueueCommand.SetNext(next))
        sync()
        publish()
        if (state.awaitingNext && next != null) continueAfterEnd(next.id)
    }

    fun select(itemId: String): Boolean {
        val target = state.items.firstOrNull { it.id == itemId && it.selectable } ?: return false
        cancelTasks()
        reduce(PlaybackQueueCommand.Select(target.id))
        sync()
        player.select(state.current!!, true)
        publish()
        watchPreparation()
        return true
    }

    fun next(): Boolean = state.next?.let { select(it.id) } ?: false
    fun previous(): Boolean = state.previous?.let { select(it.id) } ?: false

    fun remove(itemId: String) {
        if (state.items.none { it.id == itemId }) return
        val removingCurrent = itemId == state.currentItemId
        val next = state.next
        if (removingCurrent && next != null) select(next.id)
        reduce(PlaybackQueueCommand.Remove(itemId))
        sync()
        publish()
        if (removingCurrent && next == null) {
            cancelTasks()
            awaitNext()
        }
    }

    fun setPlaying(playing: Boolean) {
        reduce(PlaybackQueueCommand.SetPlaying(playing))
        publish()
    }

    fun onPlayerTransition(playbackId: String?) {
        if (changingPlaylist) return
        val item = state.items.firstOrNull { it.playbackId == playbackId && it.selectable } ?: return
        if (item.id == state.currentItemId || item.id != state.next?.id) return
        cancelTasks()
        state.current?.let { reduce(PlaybackQueueCommand.MarkStatus(it.id, PlaybackQueueItemStatus.ENDED)) }
        reduce(PlaybackQueueCommand.Select(item.id, state.playWhenReady))
        publish()
        watchPreparation()
    }

    fun onSourceReady(playbackId: String) {
        if (changingPlaylist) return
        val item = state.items.firstOrNull { it.playbackId == playbackId } ?: return
        if (item.id != state.currentItemId && item.status == PlaybackQueueItemStatus.QUEUED) {
            reduce(PlaybackQueueCommand.MarkStatus(item.id, PlaybackQueueItemStatus.READY))
            publish()
        }
    }

    fun onPlayerStatus(playbackId: String?, ready: Boolean, buffering: Boolean, playing: Boolean) {
        if (changingPlaylist) return
        val current = state.current?.takeIf { it.playbackId == playbackId } ?: return
        if (state.awaitingNext || current.status == PlaybackQueueItemStatus.FAILED) return
        reduce(PlaybackQueueCommand.SetPlaying(playing))
        val status = when {
            ready -> if (playing) PlaybackQueueItemStatus.PLAYING else PlaybackQueueItemStatus.PAUSED
            buffering -> PlaybackQueueItemStatus.PREPARING
            else -> return
        }
        if (ready) cancelPreparation() else if (preparationTask == null) watchPreparation()
        reduce(PlaybackQueueCommand.MarkStatus(current.id, status))
        publish()
    }

    fun onPlayerError(playbackId: String?, message: String, retryable: Boolean = true) {
        if (changingPlaylist) return
        val item = state.current?.takeIf { it.playbackId == playbackId } ?: return
        if (item.status == PlaybackQueueItemStatus.FAILED || state.awaitingNext) return
        cancelPreparation()
        if (retryable && item.retryCount < RETRY_DELAYS_MS.size) {
            reduce(PlaybackQueueCommand.MarkRetry(item.id))
            val ticket = timerGeneration
            preparationTask = scheduler.schedule(RETRY_DELAYS_MS[item.retryCount]) {
                if (ticket != timerGeneration || state.current?.playbackId != playbackId) return@schedule
                preparationTask = null
                player.retry(state.playWhenReady)
                watchPreparation()
            }
            publish()
        } else {
            reduce(PlaybackQueueCommand.MarkStatus(item.id, PlaybackQueueItemStatus.FAILED))
            sync()
            if (!next()) {
                publish()
                onQueueFailed(message)
            }
        }
    }

    fun onPlayerEnded(playbackId: String?) {
        if (changingPlaylist || state.awaitingNext) return
        val current = state.current?.takeIf { it.playbackId == playbackId } ?: return
        cancelPreparation()
        reduce(PlaybackQueueCommand.MarkStatus(current.id, PlaybackQueueItemStatus.ENDED))
        if (!next()) awaitNext()
    }

    fun clear() {
        cancelTasks()
        reduce(PlaybackQueueCommand.Clear)
        publish()
    }

    private fun awaitNext() {
        reduce(PlaybackQueueCommand.AwaitNext)
        publish()
        val ticket = timerGeneration
        endTask?.cancel()
        endTask = scheduler.schedule(NEXT_ITEM_WAIT_MS) {
            if (ticket == timerGeneration && state.awaitingNext) {
                endTask = null
                onQueueEnded()
            }
        }
    }

    private fun watchPreparation() {
        cancelPreparation()
        val current = state.current ?: return
        if (current.status != PlaybackQueueItemStatus.PREPARING) return
        val ticket = timerGeneration
        preparationTask = scheduler.schedule(PREPARATION_TIMEOUT_MS) {
            if (ticket != timerGeneration || state.current?.playbackId != current.playbackId) return@schedule
            preparationTask = null
            onPlayerError(current.playbackId, "Network media preparation timed out")
        }
    }

    private fun cancelPreparation() {
        timerGeneration++
        preparationTask?.cancel()
        preparationTask = null
    }
    private fun cancelTasks() {
        cancelPreparation()
        endTask?.cancel()
        endTask = null
    }
    private fun reduce(command: PlaybackQueueCommand) { state = PlaybackQueueReducer.reduce(state, command) }
    private fun publish() { onStateChanged(state) }
    private fun sync() {
        changingPlaylist = true
        val projection = if (state.current == null) {
            emptyList()
        } else {
            state.items.filter { it.selectable }
        }
        try { player.sync(projection) } finally { changingPlaylist = false }
    }

    private fun continueAfterEnd(itemId: String) {
        if (!state.awaitingNext) return
        if (state.currentItemId == null) select(itemId) else next()
    }

    companion object {
        const val PREPARATION_TIMEOUT_MS = 5_000L
        // A short grace accepts an already-in-flight next item without holding
        // AirPlay's terminal state long enough to suppress sender-side advance.
        const val NEXT_ITEM_WAIT_MS = 1_500L
        val RETRY_DELAYS_MS = listOf(250L, 750L, 1_500L)
    }
}
