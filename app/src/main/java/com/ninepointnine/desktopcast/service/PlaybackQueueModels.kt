package com.ninepointnine.desktopcast.service

import com.ninepointnine.desktopcast.session.CastContentKind
import com.ninepointnine.desktopcast.session.CastProtocol

/** In-memory receiver queue. READY means the source timeline is resolved, not fully buffered. */
internal data class PlaybackQueueItem(
    val id: String,
    val protocol: CastProtocol,
    val uri: String,
    val startPositionSeconds: Float = 0f,
    val mimeType: String? = null,
    val title: String = "",
    val detail: String = "",
    val metadata: String = "",
    val content: CastContentKind = CastContentKind.NETWORK_VIDEO,
    val allowHlsFallback: Boolean = false,
    val status: PlaybackQueueItemStatus = PlaybackQueueItemStatus.QUEUED,
    val retryCount: Int = 0,
    val generation: Long = 0,
) {
    val playbackId: String get() = "$generation:$id"
    val selectable: Boolean get() = status != PlaybackQueueItemStatus.FAILED

    fun sameSource(other: PlaybackQueueItem): Boolean =
        uri == other.uri && mimeType == other.mimeType &&
            startPositionSeconds == other.startPositionSeconds

    fun samePresentation(other: PlaybackQueueItem): Boolean =
        sameSource(other) && title == other.title && detail == other.detail &&
            metadata == other.metadata && content == other.content &&
            allowHlsFallback == other.allowHlsFallback &&
            startPositionSeconds == other.startPositionSeconds
}

internal enum class PlaybackQueueItemStatus { QUEUED, PREPARING, READY, PLAYING, PAUSED, ENDED, FAILED }

internal data class PlaybackQueueState(
    val revision: Long = 0L,
    val items: List<PlaybackQueueItem> = emptyList(),
    val currentItemId: String? = null,
    val playWhenReady: Boolean = true,
    val awaitingNext: Boolean = false,
) {
    val current: PlaybackQueueItem? get() = items.firstOrNull { it.id == currentItemId }
    val currentIndex: Int get() = items.indexOfFirst { it.id == currentItemId }
    val next: PlaybackQueueItem? get() = relative(+1)
    val previous: PlaybackQueueItem? get() = relative(-1)
    fun relative(direction: Int): PlaybackQueueItem? {
        val index = currentIndex
        if (index < 0) return null
        val indices = if (direction > 0) (index + 1 until items.size) else (index - 1 downTo 0)
        return indices.asSequence().map { items[it] }.firstOrNull { it.selectable }
    }
}

internal sealed interface PlaybackQueueCommand {
    data class Offer(val item: PlaybackQueueItem, val replaceCurrent: Boolean = false) : PlaybackQueueCommand
    data class Start(val item: PlaybackQueueItem) : PlaybackQueueCommand
    data class SetNext(val item: PlaybackQueueItem?) : PlaybackQueueCommand
    data class Select(val itemId: String, val playing: Boolean = true) : PlaybackQueueCommand
    data class MarkStatus(val itemId: String, val status: PlaybackQueueItemStatus) : PlaybackQueueCommand
    data class MarkRetry(val itemId: String) : PlaybackQueueCommand
    data class Remove(val itemId: String) : PlaybackQueueCommand
    data class SetPlaying(val playing: Boolean) : PlaybackQueueCommand
    data object AwaitNext : PlaybackQueueCommand
    data object Clear : PlaybackQueueCommand
}

/** Pure order/state rules; playback, time and protocol effects are outside this reducer. */
internal object PlaybackQueueReducer {
    const val MAX_QUEUE_ITEMS = 10
    fun reduce(state: PlaybackQueueState, command: PlaybackQueueCommand): PlaybackQueueState {
        val next = when (command) {
            is PlaybackQueueCommand.Offer -> {
                val index = state.items.indexOfFirst { it.id == command.item.id }
                val items = if (index < 0) state.items + command.item else state.items.toMutableList().apply {
                    this[index] = command.item
                }
                state.copy(items = items, currentItemId = if (command.replaceCurrent) command.item.id else state.currentItemId)
            }
            is PlaybackQueueCommand.Start -> {
                val history = state.currentIndex.takeIf { it >= 0 }
                    ?.let { state.items.take(it + 1).filterNot { existing -> existing.id == command.item.id } }
                    .orEmpty()
                state.copy(items = history + command.item, currentItemId = command.item.id, awaitingNext = false)
            }
            is PlaybackQueueCommand.SetNext -> {
                // AVTransport owns one replaceable NextURI slot; history remains navigable.
                val history = state.items.take(state.currentIndex + 1)
                val items = if (command.item == null || command.item.id == state.currentItemId) history else
                    history.filterNot { it.id == command.item.id } + command.item
                state.copy(items = items)
            }
            is PlaybackQueueCommand.Select -> if (state.items.any { it.id == command.itemId && it.selectable }) {
                state.copy(
                    currentItemId = command.itemId, playWhenReady = command.playing, awaitingNext = false,
                    items = state.items.map {
                        when {
                            it.id == command.itemId -> it.copy(status = PlaybackQueueItemStatus.PREPARING)
                            it.id == state.currentItemId && it.status in setOf(PlaybackQueueItemStatus.PLAYING, PlaybackQueueItemStatus.PAUSED, PlaybackQueueItemStatus.PREPARING) ->
                                it.copy(status = PlaybackQueueItemStatus.READY)
                            else -> it
                        }
                    },
                )
            } else state
            is PlaybackQueueCommand.MarkStatus -> state.copy(items = state.items.map {
                if (it.id == command.itemId) it.copy(status = command.status) else it
            })
            is PlaybackQueueCommand.MarkRetry -> state.copy(items = state.items.map {
                if (it.id == command.itemId) it.copy(status = PlaybackQueueItemStatus.PREPARING, retryCount = it.retryCount + 1) else it
            })
            is PlaybackQueueCommand.Remove -> state.copy(
                items = state.items.filterNot { it.id == command.itemId },
                currentItemId = state.currentItemId.takeUnless { it == command.itemId },
            )
            is PlaybackQueueCommand.SetPlaying -> state.copy(playWhenReady = command.playing)
            PlaybackQueueCommand.AwaitNext -> state.copy(awaitingNext = true)
            PlaybackQueueCommand.Clear -> PlaybackQueueState()
        }
        val bounded = trim(next)
        return if (bounded == state) state else bounded.copy(revision = state.revision + 1)
    }

    private fun trim(state: PlaybackQueueState): PlaybackQueueState {
        var items = state.items
        while (items.size > MAX_QUEUE_ITEMS) {
            val currentIndex = items.indexOfFirst { it.id == state.currentItemId }
            // Discard oldest history first; otherwise discard the farthest future item.
            val victim = if (currentIndex > 0) 0 else items.lastIndex
            items = items.filterIndexed { index, _ -> index != victim }
        }
        return state.copy(items = items)
    }
}

/** Port implemented once by the Media3 adapter. No player or Android types in queue decisions. */
internal interface PlaybackQueuePlayer {
    fun sync(items: List<PlaybackQueueItem>)
    fun select(item: PlaybackQueueItem, playing: Boolean)
    fun retry(playing: Boolean)
}

internal fun interface QueueCancellation { fun cancel() }
internal fun interface QueueScheduler {
    fun schedule(delayMs: Long, action: () -> Unit): QueueCancellation
}
