package com.jokerhub.orzmusic.core.playback

import com.jokerhub.orzmusic.core.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueTest {

    private fun song(id: String) = Song(id = id, title = "song-$id", fileFormat = "mod")

    private val a = song("a")
    private val b = song("b")
    private val c = song("c")

    @Test
    fun setFromSelectsTargetAndKeepsList() {
        val queue = PlaybackQueue()
        queue.setFrom(listOf(a, b, c), b)
        assertEquals(1, queue.index)
        assertEquals(b, queue.current)
        assertEquals(3, queue.items.size)
        assertTrue(queue.hasNext)
        assertTrue(queue.hasPrevious)
    }

    @Test
    fun setFromAppendsWhenTargetMissing() {
        val queue = PlaybackQueue()
        queue.setFrom(listOf(a, b), c)
        assertEquals(2, queue.index)
        assertEquals(c, queue.current)
        assertEquals(3, queue.items.size)
    }

    @Test
    fun nextAndPreviousWalkTheQueue() {
        val queue = PlaybackQueue()
        queue.setFrom(listOf(a, b, c), a)
        assertEquals(b, queue.next())
        assertEquals(c, queue.next())
        assertNull(queue.next())
        assertEquals(b, queue.previous())
        assertEquals(a, queue.previous())
        assertNull(queue.previous())
    }

    @Test
    fun removingBeforeCurrentKeepsCurrent() {
        val queue = PlaybackQueue()
        queue.setFrom(listOf(a, b, c), c)
        queue.remove("a")
        assertEquals(c, queue.current)
        assertEquals(1, queue.index)
    }

    @Test
    fun removingCurrentMovesToNextOrLast() {
        val queue = PlaybackQueue()
        queue.setFrom(listOf(a, b, c), b)
        queue.remove("b")
        assertEquals(c, queue.current)
        queue.remove("c")
        assertEquals(a, queue.current)
    }

    @Test
    fun activeListFallsBackWhenEmpty() {
        val queue = PlaybackQueue()
        assertEquals(listOf(a, b), queue.activeList(listOf(a, b)))
        queue.setFrom(listOf(a, b), a)
        assertEquals(2, queue.activeList(emptyList()).size)
        queue.clear()
        assertFalse(queue.hasNext)
        assertNull(queue.current)
    }
}
