package io.relayfs.android.ui.diff

enum class DiffType {
    UNCHANGED,
    ADDED,
    DELETED
}

data class DiffLine(
    val type: DiffType,
    val text: String,
    val oldLineNumber: Int? = null,
    val newLineNumber: Int? = null
)

data class DiffSummary(
    val addedCount: Int,
    val deletedCount: Int,
    val unchangedCount: Int
)

data class DiffResult(
    val lines: List<DiffLine>,
    val summary: DiffSummary
)

object DiffAlgorithm {

    /**
     * Computes line-by-line diff using Longest Common Subsequence (LCS).
     */
    fun computeDiff(oldText: String, newText: String): DiffResult {
        val oldLines = if (oldText.isEmpty()) emptyList() else oldText.lines()
        val newLines = if (newText.isEmpty()) emptyList() else newText.lines()

        val n = oldLines.size
        val m = newLines.size

        // Build LCS DP table
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 1..n) {
            for (j in 1..m) {
                dp[i][j] = if (oldLines[i - 1] == newLines[j - 1]) {
                    dp[i - 1][j - 1] + 1
                } else {
                    maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }

        // Backtrack to build diff lines
        val result = mutableListOf<DiffLine>()
        var i = n
        var j = m

        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && oldLines[i - 1] == newLines[j - 1]) {
                result.add(
                    DiffLine(
                        type = DiffType.UNCHANGED,
                        text = oldLines[i - 1],
                        oldLineNumber = i,
                        newLineNumber = j
                    )
                )
                i--
                j--
            } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                result.add(
                    DiffLine(
                        type = DiffType.ADDED,
                        text = newLines[j - 1],
                        oldLineNumber = null,
                        newLineNumber = j
                    )
                )
                j--
            } else if (i > 0 && (j == 0 || dp[i][j - 1] < dp[i - 1][j])) {
                result.add(
                    DiffLine(
                        type = DiffType.DELETED,
                        text = oldLines[i - 1],
                        oldLineNumber = i,
                        newLineNumber = null
                    )
                )
                i--
            }
        }

        result.reverse()

        var added = 0
        var deleted = 0
        var unchanged = 0
        for (line in result) {
            when (line.type) {
                DiffType.ADDED -> added++
                DiffType.DELETED -> deleted++
                DiffType.UNCHANGED -> unchanged++
            }
        }

        return DiffResult(
            lines = result,
            summary = DiffSummary(addedCount = added, deletedCount = deleted, unchangedCount = unchanged)
        )
    }
}

