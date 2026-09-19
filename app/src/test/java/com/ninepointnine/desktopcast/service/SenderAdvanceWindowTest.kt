package com.ninepointnine.desktopcast.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderAdvanceWindowTest {
    @Test
    fun newMediaCompletesImmediatelyAndCancelsExpiry() {
        val clock = TestScheduler()
        val expired = mutableListOf<Boolean>()
        val window = SenderAdvanceWindow(clock, 1_500L, expired::add)

        window.begin()
        assertTrue(window.complete())
        clock.advance(1_500L)

        assertFalse(window.isActive)
        assertTrue(expired.isEmpty())
    }

    @Test
    fun stopIsDeferredUntilTheHandoffWindowExpires() {
        val clock = TestScheduler()
        val expired = mutableListOf<Boolean>()
        val window = SenderAdvanceWindow(clock, 1_500L, expired::add)

        window.begin()
        assertTrue(window.deferStop())
        clock.advance(1_499L)
        assertTrue(expired.isEmpty())
        clock.advance(1L)

        assertEquals(listOf(true), expired)
        assertFalse(window.isActive)
    }

    @Test
    fun unansweredProjectionRemainsVisibleUntilAnExplicitOutcome() {
        val clock = TestScheduler()
        val expired = mutableListOf<Boolean>()
        val window = SenderAdvanceWindow(clock, 1_500L, expired::add)

        window.begin()
        clock.advance(60_000L)

        assertTrue(window.isActive)
        assertFalse(window.stopObserved)
        assertTrue(expired.isEmpty())

        window.cancel()
        assertFalse(window.isActive)
    }

    @Test
    fun stopStartsTheHandoffTimeoutInsteadOfProjectionBegin() {
        val clock = TestScheduler()
        val expired = mutableListOf<Boolean>()
        val window = SenderAdvanceWindow(clock, 1_500L, expired::add)

        window.begin()
        clock.advance(10_000L)
        assertTrue(window.deferStop())
        clock.advance(1_499L)
        assertTrue(expired.isEmpty())
        clock.advance(1L)

        assertEquals(listOf(true), expired)
    }

    private class TestScheduler : QueueScheduler {
        private var nowMs = 0L
        private val tasks = mutableListOf<Task>()

        override fun schedule(delayMs: Long, action: () -> Unit): QueueCancellation {
            val task = Task(nowMs + delayMs, action)
            tasks += task
            return QueueCancellation { task.cancelled = true }
        }

        fun advance(deltaMs: Long) {
            nowMs += deltaMs
            tasks.filter { !it.cancelled && it.atMs <= nowMs }
                .sortedBy(Task::atMs)
                .forEach {
                    it.cancelled = true
                    it.action()
                }
        }

        private data class Task(
            val atMs: Long,
            val action: () -> Unit,
            var cancelled: Boolean = false,
        )
    }
}
