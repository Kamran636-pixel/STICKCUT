package com.kamran.stickcut

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// Step 83: which word is highlighted while a caption is shown, and how.
// Step 88: if the words come with REAL times (TimedWordList), those are used.
// Preview and export share this maths.
enum class CaptionHighlightMode(val label: String) {
    OFF("Off"),
    COLOR("Colour"),
    PROGRESSIVE("Progressive"),
    BACKGROUND("Background"),
    UNDERLINE("Underline")
}

object CaptionHighlightStore {
    var mode by mutableStateOf(CaptionHighlightMode.OFF)
}

// A list of words that also remembers when each word starts and ends, counted from
// the start of its caption chunk. It behaves like a normal List<String>, so all the
// existing drawing code keeps working. Only CaptionHighlight.activeWord looks at the times.
class TimedWordList(
    private val items: List<String>,
    val startOffsetsMs: List<Long>,
    val endOffsetsMs: List<Long>
) : AbstractList<String>() {
    override val size: Int get() = items.size
    override fun get(index: Int): String = items[index]
}

object CaptionHighlight {

    // Index of the word being spoken at localMs inside a chunk.
    // With real word times: the last word that has started. A word stays highlighted
    // during a pause until the next one starts.
    // Without them it is an ESTIMATE: the time of the chunk is shared between the
    // words by their letter count.
    fun activeWord(words: List<String>, localMs: Long, durationMs: Long): Int {
        if (words.isEmpty()) return -1
        if (words is TimedWordList) {
            var index = 0
            for (i in 0 until words.size) {
                if (words.startOffsetsMs[i] <= localMs) index = i else break
            }
            return index
        }
        val duration = durationMs.coerceAtLeast(1L)
        val t = localMs.coerceIn(0L, duration)
        val lengths = words.map { it.length.coerceAtLeast(1) }
        val sum = lengths.sum()
        var accumulated = 0
        for (i in lengths.indices) {
            accumulated += lengths[i]
            if (t.toFloat() / duration.toFloat() * sum < accumulated) return i
        }
        return lengths.lastIndex
    }
}
