package com.kamran.stickcut

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import kotlin.math.ceil

enum class CaptionAnimation(val label: String) {
    NONE("None"),
    FADE("Fade"),
    POP("Pop"),
    SLIDE_UP("Slide Up"),
    TYPEWRITER("Typewriter"),
    WORD_POP("Word Pop")
}

enum class CaptionPosition(val label: String) {
    TOP("Top"),
    MIDDLE("Middle"),
    BOTTOM("Bottom")
}

enum class CaptionFont(val label: String) {
    SANS("Sans"),
    SANS_BOLD("Sans Bold"),
    SERIF("Serif"),
    MONO("Monospace"),
    CONDENSED("Condensed"),
    CUSTOM("My font (.ttf)")
}

// Colours are stored as ARGB numbers (0xAARRGGBB) so they are easy to save.
data class CaptionStyle(
    val enabled: Boolean = false,
    val font: CaptionFont = CaptionFont.SANS_BOLD,
    // Path of an imported .ttf / .otf file inside the app, used when font == CUSTOM.
    val customFontPath: String? = null,
    // Text height as a percentage of the video height.
    val sizePercent: Float = 6f,
    val textColor: Long = 0xFFFFFFFFL,
    val outlineColor: Long = 0xFF000000L,
    // Outline thickness as a fraction of the text size.
    val outlineWidth: Float = 0.12f,
    val background: Boolean = false,
    val backgroundColor: Long = 0x99000000L,
    // Colour of the active word in Word Pop.
    val highlightColor: Long = 0xFFFFD400L,
    val position: CaptionPosition = CaptionPosition.BOTTOM,
    val animation: CaptionAnimation = CaptionAnimation.POP,
    // How many words appear together, and the longest line (in letters).
    val maxWords: Int = 3,
    val maxChars: Int = 24,
    // Only changes Latin letters. Bengali has no capital letters.
    val uppercase: Boolean = false
) {
    fun normalized(): CaptionStyle = copy(
        sizePercent = sizePercent.coerceIn(MIN_SIZE, MAX_SIZE),
        outlineWidth = outlineWidth.coerceIn(0f, 0.3f),
        maxWords = maxWords.coerceIn(1, 8),
        maxChars = maxChars.coerceIn(8, 60)
    )

    companion object {
        const val MIN_SIZE = 3f
        const val MAX_SIZE = 12f
    }
}

// Keeps the caption style while the app is open.
object CaptionStore {
    var style by mutableStateOf(CaptionStyle())
}

// One short piece of narration shown on screen at one time.
data class CaptionChunk(
    val sceneIndex: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<String>
) {
    val durationMs: Long get() = endMs - startMs
}

data class CaptionResult(
    val chunks: List<CaptionChunk>,
    val warnings: List<String>
)

object CaptionEngine {

    // Chunks shorter than this are reported, because they flash by too fast to read.
    const val MIN_READABLE_MS = 250L

    private val spaces = Regex("\\s+")

    // Breaks the narration of every scene into short chunks.
    // Time inside a scene is shared by letter count (we only know scene times,
    // not the exact moment of each word), so chunks are always back to back
    // and the last one ends exactly with its scene.
    fun build(scenes: List<Scene>, style: CaptionStyle): CaptionResult {
        val s = style.normalized()
        val chunks = mutableListOf<CaptionChunk>()
        var tooFast = 0

        scenes.forEach { scene ->
            val words = scene.text.trim().split(spaces).filter { it.isNotEmpty() }
            if (words.isEmpty()) return@forEach

            val groups = mutableListOf<List<String>>()
            var current = mutableListOf<String>()
            var letters = 0
            words.forEach { word ->
                val withWord = if (current.isEmpty()) word.length else letters + 1 + word.length
                if (current.isNotEmpty() && (current.size >= s.maxWords || withWord > s.maxChars)) {
                    groups.add(current)
                    current = mutableListOf(word)
                    letters = word.length
                } else {
                    current.add(word)
                    letters = withWord
                }
            }
            if (current.isNotEmpty()) groups.add(current)

            val weights = groups.map { it.joinToString(" ").length.coerceAtLeast(1) }
            val totalWeight = weights.sum().toLong()
            val duration = scene.endMs - scene.startMs

            var cumulative = 0L
            var previous = scene.startMs
            groups.forEachIndexed { i, group ->
                cumulative += weights[i]
                val boundary = if (i == groups.lastIndex) {
                    scene.endMs
                } else {
                    scene.startMs + Math.round(duration.toDouble() * cumulative / totalWeight)
                }
                val chunk = CaptionChunk(
                    sceneIndex = scene.index,
                    startMs = previous,
                    endMs = boundary,
                    text = group.joinToString(" "),
                    words = group
                )
                if (chunk.durationMs < MIN_READABLE_MS) tooFast++
                chunks.add(chunk)
                previous = boundary
            }
        }

        val warnings = mutableListOf<String>()
        if (tooFast > 0) {
            warnings.add(
                "$tooFast caption piece(s) stay on screen for less than 0.25 second. " +
                    "Use more words per piece, or lengthen those scenes."
            )
        }
        return CaptionResult(chunks, warnings)
    }

    // The text exactly as it should be drawn (capital letters if chosen).
    fun displayText(text: String, style: CaptionStyle): String {
        return if (style.uppercase) text.uppercase(Locale.ROOT) else text
    }

    // The caption chunk that is visible at a moment of the video, or null.
    fun chunkAt(chunks: List<CaptionChunk>, positionMs: Long): CaptionChunk? {
        if (chunks.isEmpty()) return null
        var low = 0
        var high = chunks.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val chunk = chunks[mid]
            when {
                positionMs < chunk.startMs -> high = mid - 1
                positionMs >= chunk.endMs -> low = mid + 1
                else -> return chunk
            }
        }
        return null
    }
}

// How a caption looks at one moment.
// offsetY is a fraction of the video height (positive = lower).
// visibleChars: how many letters are shown (Typewriter). activeWord: Word Pop highlight, else -1.
data class CaptionFrame(
    val alpha: Float,
    val scale: Float,
    val offsetY: Float,
    val visibleChars: Int,
    val activeWord: Int
)

// Pure animation maths, shared by the preview and the export.
object CaptionAnimator {

    const val INTRO_MS = 180L
    private const val OUTRO_MS = 100L

    private fun ease(p: Float): Float {
        val q = 1f - p
        return 1f - q * q
    }

    private fun popScale(p: Float): Float {
        return if (p < 0.6f) {
            0.6f + 0.55f * (p / 0.6f)
        } else {
            1.15f - 0.15f * ((p - 0.6f) / 0.4f)
        }
    }

    fun frameAt(chunk: CaptionChunk, style: CaptionStyle, localMs: Long): CaptionFrame {
        val duration = chunk.durationMs.coerceAtLeast(1L)
        val t = localMs.coerceIn(0L, duration)
        val intro = minOf(INTRO_MS, duration / 3L).coerceAtLeast(1L)
        val outro = minOf(OUTRO_MS, duration / 4L).coerceAtLeast(1L)
        val p = (t.toFloat() / intro.toFloat()).coerceIn(0f, 1f)
        val total = chunk.text.length

        var alpha = 1f
        var scale = 1f
        var offsetY = 0f
        var visible = total
        var active = -1

        when (style.animation) {
            CaptionAnimation.NONE -> {
            }
            CaptionAnimation.FADE -> {
                val fadeIn = t.toFloat() / intro.toFloat()
                val fadeOut = (duration - t).toFloat() / outro.toFloat()
                alpha = minOf(fadeIn, fadeOut, 1f).coerceIn(0f, 1f)
            }
            CaptionAnimation.POP -> {
                scale = popScale(p)
                alpha = (p * 2f).coerceIn(0f, 1f)
            }
            CaptionAnimation.SLIDE_UP -> {
                offsetY = 0.04f * (1f - ease(p))
                alpha = p
            }
            CaptionAnimation.TYPEWRITER -> {
                val typingMs = duration * 0.7f
                val fraction = if (typingMs <= 0f) 1f else minOf(1f, t.toFloat() / typingMs)
                visible = ceil(total * fraction).toInt().coerceIn(0, total)
            }
            CaptionAnimation.WORD_POP -> {
                scale = popScale(p)
                alpha = (p * 2f).coerceIn(0f, 1f)
                val lengths = chunk.words.map { it.length.coerceAtLeast(1) }
                val sum = lengths.sum()
                var accumulated = 0
                active = lengths.lastIndex
                for (i in lengths.indices) {
                    accumulated += lengths[i]
                    if (t.toFloat() / duration.toFloat() * sum < accumulated) {
                        active = i
                        break
                    }
                }
            }
        }
        return CaptionFrame(alpha, scale, offsetY, visible, active)
    }
}
