package se.euther.euthervox.audio

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFrameQueueTest {
    @Test
    fun fullQueueAppliesBackpressureUntilPlaybackConsumesAFrame() = runBlocking {
        val queue = PlaybackFrameQueue(capacity = 1)
        assertTrue(queue.enqueue(byteArrayOf(1)))

        val secondEnqueue = async { queue.enqueue(byteArrayOf(2)) }
        yield()
        assertFalse(secondEnqueue.isCompleted)

        assertArrayEquals(byteArrayOf(1), queue.frames.receive())
        assertTrue(secondEnqueue.await())
        assertArrayEquals(byteArrayOf(2), queue.frames.receive())
    }

    @Test
    fun closedQueueRejectsNewFramesWithoutThrowing() = runBlocking {
        val queue = PlaybackFrameQueue(capacity = 1)
        queue.close()

        assertFalse(queue.enqueue(byteArrayOf(1)))
    }
}
