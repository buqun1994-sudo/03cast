package com.ninepointnine.desktopcast.service

import com.ninepointnine.desktopcast.session.CastProtocol
import org.junit.Assert.*
import org.junit.Test

class NetworkPlaybackQueueTest {
    private class Clock : QueueScheduler {
        data class Task(val at: Long, val action: () -> Unit, var cancelled: Boolean = false)
        var now = 0L
        val tasks = mutableListOf<Task>()
        override fun schedule(delayMs: Long, action: () -> Unit): QueueCancellation {
            val task = Task(now + delayMs, action)
            tasks += task
            return QueueCancellation { task.cancelled = true }
        }
        fun advance(ms: Long) {
            val until = now + ms
            while (true) {
                val task = tasks.filter { !it.cancelled && it.at <= until }.minByOrNull { it.at } ?: break
                task.cancelled = true
                now = task.at
                task.action()
            }
            now = until
        }
    }
    private class Player : PlaybackQueuePlayer {
        var projection = emptyList<PlaybackQueueItem>()
        val selections = mutableListOf<Pair<String, Boolean>>()
        val retries = mutableListOf<Boolean>()
        var onSync: (() -> Unit)? = null
        override fun sync(items: List<PlaybackQueueItem>) { projection = items; onSync?.invoke() }
        override fun select(item: PlaybackQueueItem, playing: Boolean) { selections += item.id to playing }
        override fun retry(playing: Boolean) { retries += playing }
    }
    private val clock = Clock()
    private val player = Player()
    private var ends = 0
    private val failures = mutableListOf<String>()
    private val states = mutableListOf<PlaybackQueueState>()
    private val queue = NetworkPlaybackQueue(player, clock, { states += it }, { ends++ }, { failures += it })
    private fun item(id: String) = PlaybackQueueItem(id, CastProtocol.DLNA, "https://example.test/$id")
    private fun ready() = queue.onPlayerStatus(queue.state.current?.playbackId, true, false, queue.state.playWhenReady)
    private fun end() = queue.onPlayerEnded(queue.state.current?.playbackId)

    @Test fun loadingNewVideoKeepsHistoryAndDiscardsObsoleteFuture() {
        queue.load(item("a")); ready(); queue.setNext(item("obsolete")); queue.load(item("b"))
        assertEquals(listOf("a", "b"), queue.state.items.map { it.id })
        assertEquals(listOf("a", "b"), player.projection.map { it.id })
        assertEquals("b", queue.state.currentItemId)
    }
    @Test fun automaticTransitionKeepsOneQueueAndPublishesNewMetadata() {
        queue.load(item("a")); ready(); queue.setNext(item("b").copy(title = "Second"))
        queue.onPlayerTransition(queue.state.next?.playbackId); ready()
        assertEquals("b", queue.state.currentItemId)
        assertEquals("Second", states.last().current?.title)
        assertNull(queue.state.next)
        assertEquals(0, ends)
    }
    @Test fun endSelectsKnownNextWithoutEndingSession() {
        queue.load(item("a")); ready(); queue.setNext(item("b")); end()
        assertEquals("b", queue.state.currentItemId)
        assertEquals(0, ends)
    }
    @Test fun lateNextDuringGraceContinuesPlayback() {
        queue.load(item("a")); ready(); end(); clock.advance(1_000)
        queue.setNext(item("b")); ready(); clock.advance(10_000)
        assertEquals("b", queue.state.currentItemId)
        assertEquals(0, ends)
        assertFalse(queue.state.awaitingNext)
    }
    @Test fun terminalItemEndsOnceAfterGraceAndIgnoresDuplicateEos() {
        queue.load(item("a")); ready(); end(); end()
        clock.advance(1_499); assertEquals(0, ends)
        clock.advance(1); assertEquals(1, ends)
    }
    @Test fun clearCancelsEndAndAllRetries() {
        queue.load(item("a")); ready(); end(); queue.clear(); clock.advance(60_000)
        assertEquals(0, ends); assertTrue(player.retries.isEmpty()); assertTrue(queue.state.items.isEmpty())
    }
    @Test fun unknownOrOldEventsCannotChangeNewItem() {
        queue.load(item("a")); val old = queue.state.current!!.playbackId
        queue.load(item("a")); val fresh = queue.state.current!!.playbackId
        assertNotEquals(old, fresh)
        queue.onPlayerEnded(old); queue.onPlayerError(old, "old"); queue.onPlayerTransition(old)
        queue.onPlayerStatus(old, true, false, false)
        assertEquals(fresh, queue.state.current?.playbackId)
        assertEquals(PlaybackQueueItemStatus.PREPARING, queue.state.current?.status)
        assertTrue(failures.isEmpty()); assertEquals(0, ends)
    }
    @Test fun cancelledOldTimerCannotFailNewPlayback() {
        queue.load(item("a")); val old = clock.tasks.last()
        queue.load(item("b")); ready(); old.action(); clock.advance(10_000)
        assertTrue(player.retries.isEmpty()); assertTrue(failures.isEmpty())
    }
    @Test fun preparationTimeoutUsesBoundedBackoffAndFailsAfterThreeRetries() {
        queue.load(item("a")); clock.advance(22_500)
        assertEquals(3, player.retries.size)
        assertEquals(1, failures.size)
        assertEquals(PlaybackQueueItemStatus.FAILED, queue.state.current?.status)
    }
    @Test fun sourceTimelineDoesNotFalselyCancelCurrentPreparationTimeout() {
        queue.load(item("a")); queue.onSourceReady(queue.state.current!!.playbackId)
        clock.advance(5_250)
        assertEquals(1, player.retries.size)
    }
    @Test fun nextSourceReadinessIsObservableWithoutMarkingItPlaying() {
        queue.load(item("a")); ready(); queue.setNext(item("b"))
        assertEquals(PlaybackQueueItemStatus.QUEUED, queue.state.next?.status)
        queue.onSourceReady(queue.state.next!!.playbackId)
        assertEquals(PlaybackQueueItemStatus.READY, queue.state.next?.status)
        assertEquals("a", queue.state.currentItemId)
    }
    @Test fun nextDoesNotTimeOutWhileWaitingForCurrentToFinish() {
        queue.load(item("a")); ready(); queue.setNext(item("b")); clock.advance(30_000)
        assertTrue(player.retries.isEmpty()); assertTrue(failures.isEmpty())
        queue.onPlayerTransition(queue.state.next!!.playbackId); clock.advance(5_250)
        assertEquals(1, player.retries.size)
    }
    @Test fun permanentFailureSkipsToNextAndRemovesFailedSourceFromPlayer() {
        queue.load(item("a")); queue.setNext(item("b"))
        queue.onPlayerError(queue.state.current!!.playbackId, "404", false)
        assertEquals("b", queue.state.currentItemId)
        assertEquals(listOf("b"), player.projection.map { it.id })
        assertTrue(player.retries.isEmpty()); assertTrue(failures.isEmpty())
    }
    @Test fun pauseSurvivesRetry() {
        queue.load(item("a"), playing = false); clock.advance(5_250)
        assertEquals(listOf(false), player.retries)
        assertFalse(queue.state.playWhenReady)
    }
    @Test fun selectionBeforeRetryCancelsRetryForPreviousVideo() {
        queue.load(item("a")); queue.setNext(item("b"))
        queue.onPlayerError(queue.state.current!!.playbackId, "network")
        queue.next(); ready(); clock.advance(10_000)
        assertTrue(player.retries.isEmpty())
    }
    @Test fun queueHistorySupportsPreviousAndNextWithoutWrapping() {
        queue.load(item("a")); queue.load(item("b")); ready()
        assertTrue(queue.previous()); assertEquals("a", queue.state.currentItemId)
        assertFalse(queue.previous()); assertTrue(queue.next()); assertFalse(queue.next())
    }
    @Test fun removalOfCurrentChoosesFollowingInsteadOfJumpingToOldHistory() {
        queue.load(item("a")); queue.load(item("b")); queue.setNext(item("c"))
        queue.remove("b")
        assertEquals("c", queue.state.currentItemId)
        assertEquals(listOf("a", "c"), player.projection.map { it.id })
    }
    @Test fun removalOfTerminalCurrentLeavesPlayerProjectionEmptyUntilNextArrives() {
        queue.load(item("a")); ready(); queue.load(item("b")); ready(); queue.remove("b")
        assertNull(queue.state.current)
        assertTrue(player.projection.isEmpty())
        queue.setNext(item("c"))
        assertEquals("c", queue.state.currentItemId)
        assertEquals(listOf("c"), player.projection.map { it.id })
    }
    @Test fun playerTransitionOnlyAcceptsCurrentNextItem() {
        queue.load(item("a")); queue.load(item("b")); ready()
        assertFalse(queue.state.items.any { it.id == "a" && it.status == PlaybackQueueItemStatus.PLAYING })
        queue.onPlayerTransition(queue.state.items.first { it.id == "a" }.playbackId)
        assertEquals("b", queue.state.currentItemId)
    }
    @Test fun repeatedSetNextKeepsItsGenerationAndOnlyOneCopy() {
        queue.load(item("a")); queue.setNext(item("b")); val key = queue.state.next!!.playbackId
        queue.setNext(item("b")); assertEquals(key, queue.state.next!!.playbackId)
        assertEquals(2, player.projection.size)
    }
    @Test fun reusedCurrentIdWithNewStartPositionCreatesNewGeneration() {
        queue.load(item("a")); val old = queue.state.current!!.playbackId
        queue.append(item("a").copy(startPositionSeconds = 2f))
        assertNotEquals(old, queue.state.current!!.playbackId)
        assertEquals(2f, queue.state.current!!.startPositionSeconds, 0f)
        assertEquals(2, player.selections.size)
    }
    @Test fun synchronousPlaylistCallbacksCannotOverwriteIntendedCurrent() {
        queue.load(item("a")); val old = queue.state.current!!.playbackId
        player.onSync = { queue.onPlayerTransition(old); queue.onPlayerEnded(old) }
        queue.load(item("b"))
        assertEquals("b", queue.state.currentItemId); assertEquals(0, ends)
    }
    @Test fun thirtyVideoReplacementsKeepCurrentAndPlayerProjectionBounded() {
        repeat(30) { queue.load(item("$it")); ready() }
        assertEquals((20..29).map { "$it" }, queue.state.items.map { it.id })
        assertEquals(queue.state.items.map { it.playbackId }, player.projection.map { it.playbackId })
        assertEquals(1, queue.state.items.count { it.status == PlaybackQueueItemStatus.PLAYING })
        assertEquals(0, ends)
    }
}
