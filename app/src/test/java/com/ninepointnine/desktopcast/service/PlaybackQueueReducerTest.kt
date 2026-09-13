package com.ninepointnine.desktopcast.service

import com.ninepointnine.desktopcast.session.CastProtocol
import org.junit.Assert.*
import org.junit.Test

class PlaybackQueueReducerTest {
    private fun item(id: String) = PlaybackQueueItem(id, CastProtocol.DLNA, "https://example.test/$id")
    private fun state(vararg ids: String, current: String = ids.first()) = PlaybackQueueState(
        items = ids.map(::item), currentItemId = current,
    )
    private fun PlaybackQueueState.apply(command: PlaybackQueueCommand) = PlaybackQueueReducer.reduce(this, command)

    @Test fun selectionRecordsPreparationWithoutClaimingPlayback() {
        val result = state("a", "b").apply(PlaybackQueueCommand.Select("b"))
        assertEquals("b", result.currentItemId)
        assertEquals(PlaybackQueueItemStatus.PREPARING, result.current?.status)
    }
    @Test fun offeredItemsPreserveOrderWithoutSelecting() {
        val result = state("a", "b").apply(PlaybackQueueCommand.Offer(item("c")))
        assertEquals(listOf("a", "b", "c"), result.items.map { it.id })
        assertEquals("a", result.currentItemId)
    }
    @Test fun setNextReplacesFutureAndRetainsHistory() {
        val result = state("a", "b", "c", current = "b").apply(PlaybackQueueCommand.SetNext(item("d")))
        assertEquals(listOf("a", "b", "d"), result.items.map { it.id })
    }
    @Test fun setNextMovesHistoryAfterCurrentWithoutDuplicatingIt() {
        val result = state("a", "b", "c", current = "b").apply(PlaybackQueueCommand.SetNext(item("a")))
        assertEquals(listOf("b", "a"), result.items.map { it.id })
        assertEquals("b", result.currentItemId)
    }
    @Test fun clearNextLeavesCurrentAndHistory() {
        val result = state("a", "b", "c", current = "b").apply(PlaybackQueueCommand.SetNext(null))
        assertEquals(listOf("a", "b"), result.items.map { it.id })
    }
    @Test fun currentCannotBecomeItsOwnNext() {
        val result = state("a", "b").apply(PlaybackQueueCommand.SetNext(item("a")))
        assertEquals(listOf("a"), result.items.map { it.id })
    }
    @Test fun failedItemsAreSkippedInBothDirections() {
        val result = state("a", "b", "c", current = "b")
            .apply(PlaybackQueueCommand.MarkStatus("a", PlaybackQueueItemStatus.FAILED))
        assertNull(result.previous)
        assertEquals("c", result.next?.id)
        assertEquals(result, result.apply(PlaybackQueueCommand.Select("a")))
    }
    @Test fun navigationDoesNotWrap() {
        assertNull(state("a", "b").previous)
        assertNull(state("a", "b", current = "b").next)
        assertNull(PlaybackQueueState().next)
    }
    @Test fun boundEvictsOldestHistoryAndProtectsCurrent() {
        var result = state("0")
        for (i in 1..20) result = result.apply(PlaybackQueueCommand.Offer(item("$i"), true))
        assertEquals((11..20).map { "$it" }, result.items.map { it.id })
        assertEquals("20", result.currentItemId)
    }
    @Test fun futureOverflowPreservesNearestNext() {
        var result = state("0")
        for (i in 1..20) result = result.apply(PlaybackQueueCommand.Offer(item("$i")))
        assertEquals((0..9).map { "$it" }, result.items.map { it.id })
    }
    @Test fun unknownCallbacksAreNoOps() {
        val result = state("a")
        assertEquals(result, result.apply(PlaybackQueueCommand.MarkStatus("old", PlaybackQueueItemStatus.READY)))
        assertEquals(result, result.apply(PlaybackQueueCommand.MarkRetry("old")))
    }
    @Test fun clearDoesNotResetRevisionToAnOldValue() {
        val result = state("a").apply(PlaybackQueueCommand.Select("a")).apply(PlaybackQueueCommand.Clear)
        assertEquals(2L, result.revision)
        assertNull(result.current)
        assertTrue(result.items.isEmpty())
    }
}
