package io.relayfs.android.ui.diff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffAlgorithmTest {

    @Test
    fun testIdenticalContentHasZeroDiffs() {
        val content = "Line 1\nLine 2\nLine 3"
        val diff = DiffAlgorithm.computeDiff(content, content)

        assertEquals(0, diff.summary.addedCount)
        assertEquals(0, diff.summary.deletedCount)
        assertEquals(3, diff.summary.unchangedCount)
        assertEquals(3, diff.lines.size)
        assertTrue(diff.lines.all { it.type == DiffType.UNCHANGED })
    }

    @Test
    fun testEmptyStrings() {
        val diff = DiffAlgorithm.computeDiff("", "")
        assertEquals(0, diff.summary.addedCount)
        assertEquals(0, diff.summary.deletedCount)
        assertEquals(0, diff.summary.unchangedCount)
        assertTrue(diff.lines.isEmpty())
    }

    @Test
    fun testAllLinesAdded() {
        val oldText = ""
        val newText = "First\nSecond\nThird"
        val diff = DiffAlgorithm.computeDiff(oldText, newText)

        assertEquals(3, diff.summary.addedCount)
        assertEquals(0, diff.summary.deletedCount)
        assertEquals(0, diff.summary.unchangedCount)
        assertTrue(diff.lines.all { it.type == DiffType.ADDED })
        assertEquals(listOf(1, 2, 3), diff.lines.map { it.newLineNumber })
    }

    @Test
    fun testAllLinesDeleted() {
        val oldText = "Alpha\nBeta\nGamma"
        val newText = ""
        val diff = DiffAlgorithm.computeDiff(oldText, newText)

        assertEquals(0, diff.summary.addedCount)
        assertEquals(3, diff.summary.deletedCount)
        assertEquals(0, diff.summary.unchangedCount)
        assertTrue(diff.lines.all { it.type == DiffType.DELETED })
        assertEquals(listOf(1, 2, 3), diff.lines.map { it.oldLineNumber })
    }

    @Test
    fun testSingleLineReplacement() {
        val oldText = "Hello World\nKeep this\nGoodbye"
        val newText = "Hello Universe\nKeep this\nGoodbye"
        val diff = DiffAlgorithm.computeDiff(oldText, newText)

        assertEquals(1, diff.summary.addedCount)
        assertEquals(1, diff.summary.deletedCount)
        assertEquals(2, diff.summary.unchangedCount)

        assertEquals(DiffType.DELETED, diff.lines[0].type)
        assertEquals("Hello World", diff.lines[0].text)

        assertEquals(DiffType.ADDED, diff.lines[1].type)
        assertEquals("Hello Universe", diff.lines[1].text)

        assertEquals(DiffType.UNCHANGED, diff.lines[2].type)
        assertEquals("Keep this", diff.lines[2].text)

        assertEquals(DiffType.UNCHANGED, diff.lines[3].type)
        assertEquals("Goodbye", diff.lines[3].text)
    }

    @Test
    fun testIntermediateInsertionAndDeletion() {
        val oldText = "A\nB\nC\nD"
        val newText = "A\nX\nC\nE"
        val diff = DiffAlgorithm.computeDiff(oldText, newText)

        assertEquals(2, diff.summary.addedCount) // X, E
        assertEquals(2, diff.summary.deletedCount) // B, D
        assertEquals(2, diff.summary.unchangedCount) // A, C
    }
}

