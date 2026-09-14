package io.relayfs.android.core.watcher.debounce

import io.relayfs.android.core.watcher.FileWatcherEvent
import io.relayfs.android.core.watcher.WatcherEventType
import io.relayfs.android.core.watcher.filter.PathFilter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DebounceEventQueueTest {

    @Test
    fun enqueue_rapidEventsWithinOneSecond_emitsOnlyOneConsolidatedBatchAfterQuietPeriod() = runTest {
        val batches = mutableListOf<List<String>>()
        val queue = DebounceEventQueue(
            scope = this,
            debouncePeriodMs = 3000L,
            pathFilter = PathFilter(),
            onDebouncedBatch = { batches.add(it) }
        )

        // 10 rapid events within 1000ms
        for (i in 1..10) {
            queue.enqueue("notes/rapid_file.md")
            advanceTimeBy(100)
            runCurrent()
        }

        // Only 1000ms elapsed so far; no batch should have been emitted
        assertEquals(0, batches.size)

        // Advance 2000ms (3000ms since first event, but only 2000ms since last event)
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(0, batches.size)

        // Advance remaining 1000ms (completing 3000ms quiet window after 10th event)
        advanceTimeBy(1000)
        runCurrent()

        assertEquals(1, batches.size)
        assertEquals(listOf("notes/rapid_file.md"), batches[0])
    }

    @Test
    fun enqueue_excludedPaths_completelyIgnored() = runTest {
        val batches = mutableListOf<List<String>>()
        val queue = DebounceEventQueue(
            scope = this,
            debouncePeriodMs = 3000L,
            pathFilter = PathFilter(),
            onDebouncedBatch = { batches.add(it) }
        )

        // Enqueue excluded files
        queue.enqueue("vault/.obsidian/workspace.json")
        queue.enqueue(".git/index")
        queue.enqueue("doc.sync_tmp")
        queue.enqueue(".trash/old.md")
        runCurrent()

        assertTrue(queue.getPendingPaths().isEmpty())

        advanceTimeBy(5000)
        runCurrent()

        assertTrue("No batches should be emitted for excluded paths", batches.isEmpty())
    }

    @Test
    fun enqueue_multipleDistinctFiles_aggregatedInSingleBatch() = runTest {
        val batches = mutableListOf<List<String>>()
        val queue = DebounceEventQueue(
            scope = this,
            debouncePeriodMs = 3000L,
            onDebouncedBatch = { batches.add(it) }
        )

        queue.enqueue("folder/file1.md")
        queue.enqueue("folder/file2.md")
        queue.enqueue("file3.txt")
        runCurrent()

        assertEquals(3, queue.getPendingPaths().size)

        advanceTimeBy(3000)
        runCurrent()

        assertEquals(1, batches.size)
        assertEquals(3, batches[0].size)
        assertTrue(batches[0].contains("folder/file1.md"))
        assertTrue(batches[0].contains("folder/file2.md"))
        assertTrue(batches[0].contains("file3.txt"))
        assertTrue(queue.getPendingPaths().isEmpty())
    }

    @Test
    fun enqueue_timerResetsOnNewEvents() = runTest {
        val batches = mutableListOf<List<String>>()
        val queue = DebounceEventQueue(
            scope = this,
            debouncePeriodMs = 3000L,
            onDebouncedBatch = { batches.add(it) }
        )

        queue.enqueue("edit.md")
        runCurrent()

        // After 2 seconds, another edit arrives
        advanceTimeBy(2000)
        runCurrent()
        queue.enqueue("edit.md")
        runCurrent()

        // At t = 3.5s (1.5s after 2nd edit), should still be quiet
        advanceTimeBy(1500)
        runCurrent()
        assertEquals(0, batches.size)

        // At t = 5.0s (3.0s after 2nd edit), quiet window completes and emits
        advanceTimeBy(1500)
        runCurrent()
        assertEquals(1, batches.size)
    }

    @Test
    fun flush_emitsImmediatelyWithoutWaitingForTimer() = runTest {
        val batches = mutableListOf<List<String>>()
        val queue = DebounceEventQueue(
            scope = this,
            debouncePeriodMs = 3000L,
            onDebouncedBatch = { batches.add(it) }
        )

        queue.enqueue("urgent.md")
        runCurrent()

        assertEquals(0, batches.size)

        queue.flush()
        runCurrent()

        assertEquals(1, batches.size)
        assertEquals(listOf("urgent.md"), batches[0])
        assertTrue(queue.getPendingPaths().isEmpty())
    }

    @Test
    fun cancel_clearsPendingPathsWithoutEmission() = runTest {
        val batches = mutableListOf<List<String>>()
        val queue = DebounceEventQueue(
            scope = this,
            debouncePeriodMs = 3000L,
            onDebouncedBatch = { batches.add(it) }
        )

        queue.enqueue("cancelled.md")
        runCurrent()

        queue.cancel()
        runCurrent()

        advanceTimeBy(5000)
        runCurrent()

        assertTrue(batches.isEmpty())
        assertTrue(queue.getPendingPaths().isEmpty())
    }

    @Test
    fun enqueue_directoryEvent_ignored() = runTest {
        val batches = mutableListOf<List<String>>()
        val queue = DebounceEventQueue(
            scope = this,
            debouncePeriodMs = 3000L,
            onDebouncedBatch = { batches.add(it) }
        )

        val dirEvent = FileWatcherEvent(
            relativePath = "subfolder",
            file = File("/root/subfolder"),
            eventType = WatcherEventType.CREATE,
            isDirectory = true
        )

        queue.enqueue(dirEvent)
        runCurrent()

        assertTrue(queue.getPendingPaths().isEmpty())
    }
}

