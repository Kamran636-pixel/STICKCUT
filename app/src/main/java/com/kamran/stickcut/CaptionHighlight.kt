package com.kamran.stickcut

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// Step 83: which word is highlighted while a caption is shown, and how.
// The look is chosen in Caption Studio. Preview and export will share this maths.
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

object CaptionHighlight {

    // Index of the word being spoken at localMs inside a chunk.
    // This is an ESTIMATE: the time of the chunk is shared between the words by
    // their letter count, because the caption file has no per-word times.
    fun activeWord(words: List<String>, localMs: Long, durationMs: Long): Int {
        if (words.isEmpty()) return -1
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
