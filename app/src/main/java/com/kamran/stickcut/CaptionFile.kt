package com.kamran.stickcut

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// Caption file import (.srt, .vtt, .txt with times).
// This is separate from the older per-scene captions (Word Pop, Typewriter...).
// ---------------------------------------------------------------------------

// One caption: shown from startMs to endMs.
data class CaptionCue(
    val id: Long,
    val startMs: Long,
    val endMs: Long,
    val text: String
)

data class CaptionParseResult(
    val cues: List<CaptionCue>,
    // "SRT", "VTT", "TXT ranges", "TXT inline" or "empty".
    val format: String,
    val errors: List<String>
)

private class RawCue(val startMs: Long, val endMs: Long?, val text: String)

object CaptionFileParser {

    // A caption can never be shorter than this.
    const val MIN_DURATION_MS = 400L

    // Very long text is cut so it cannot slow down or crash the app.
    const val MAX_TEXT_CHARS = 1000

    private const val WORDS_PER_SECOND = 2.5
    private const val MAX_ERRORS = 8

    // hh:mm:ss.mmm, mm:ss.mmm, with a comma or a period before the milliseconds.
    private const val TIME = "(?:\\d{1,3}:)?\\d{1,2}:\\d{1,2}(?:[.,]\\d{1,3})?"

    private val timeRegex =
        Regex("^(?:(\\d{1,3}):)?(\\d{1,2}):(\\d{1,2})(?:[.,](\\d{1,3}))?$")

    // SRT / VTT: "00:00:01,000 --> 00:00:03,500" (settings after the time are ignored).
    private val arrowRegex = Regex("^\\s*(\\S+)\\s*-->\\s*(\\S+).*$")

    // TXT range: "00:00:01 - 00:00:03" (hyphen, en dash or em dash).
    private val rangeRegex =
        Regex("^\\s*($TIME)\\s*[-\u2013\u2014]\\s*($TIME)\\s*(.*)$")

    // TXT inline: "[00:00:01] text", "(00:00:01) text", "00:00:01 text".
    private val inlineRegex = Regex(
        "^\\s*(?:\\[\\s*($TIME)\\s*\\]|\\(\\s*($TIME)\\s*\\)|($TIME))\\s*[:\\-\u2013\u2014]?\\s*(.*)$"
    )

    private val tagRegex = Regex("<[^>]*>|\\{[^}]*\\}")
    private val spaceRegex = Regex("\\s+")

    // "00:00:01,500" -> 1500. Returns null when the time is not valid.
    fun parseTime(token: String): Long? {
        val match = timeRegex.matchEntire(token.trim()) ?: return null
        val hoursText = match.groupValues[1]
        val hours = hoursText.toLongOrNull() ?: 0L
        val minutes = match.groupValues[2].toLong()
        val seconds = match.groupValues[3].toLong()
        val fraction = match.groupValues[4]
        val millis = if (fraction.isEmpty()) 0L else fraction.padEnd(3, '0').toLong()
        if (seconds > 59L) return null
        if (hoursText.isNotEmpty() && minutes > 59L) return null
        return ((hours * 60L + minutes) * 60L + seconds) * 1000L + millis
    }

    // 1500 -> "00:00:01.500"
    fun formatTime(ms: Long): String {
        val t = ms.coerceAtLeast(0L)
        val hours = t / 3_600_000L
        val minutes = (t / 60_000L) % 60L
        val seconds = (t / 1000L) % 60L
        val millis = t % 1000L
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, millis)
    }

    private fun addError(errors: MutableList<String>, message: String) {
        if (errors.size < MAX_ERRORS) {
            errors.add(message)
        } else if (errors.size == MAX_ERRORS) {
            errors.add("More problems were found. Only the first $MAX_ERRORS are shown.")
        }
    }

    fun cleanText(text: String): String {
        return text.replace(tagRegex, "").replace(spaceRegex, " ").trim()
    }

    private fun estimateMs(text: String): Long {
        val words = text.split(' ').count { it.isNotEmpty() }
        val ms = (words / WORDS_PER_SECOND * 1000.0).toLong()
        return max(MIN_DURATION_MS, ms)
    }

    // Reads the whole text of a caption file. audioDurationMs is used for the
    // end of the last caption when the file gives no end time.
    fun parse(raw: String, audioDurationMs: Long? = null): CaptionParseResult {
        val errors = mutableListOf<String>()
        val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        if (text.isBlank()) {
            return CaptionParseResult(emptyList(), "empty", listOf("The file is empty."))
        }
        val lines = text.split('\n')
        val firstLine = lines.firstOrNull { it.isNotBlank() }?.trim() ?: ""

        val hasArrow = lines.any { arrowRegex.matches(it) }
        val rangeLines = lines.count { rangeRegex.matches(it) }

        val format: String
        val raws: List<RawCue>
        if (hasArrow) {
            format = if (firstLine.startsWith("WEBVTT")) "VTT" else "SRT"
            raws = readArrowBlocks(lines, errors)
        } else if (rangeLines > 0) {
            format = "TXT ranges"
            raws = readTimedLines(lines, true, errors)
        } else {
            format = "TXT inline"
            raws = readTimedLines(lines, false, errors)
        }

        val cues = finish(raws, audioDurationMs, errors)
        if (cues.isEmpty()) {
            addError(
                errors,
                "No captions were found. Use .srt, .vtt, or .txt with times like 00:00:01 - 00:00:03."
            )
        }
        return CaptionParseResult(cues, format, errors)
    }

    private fun readArrowBlocks(lines: List<String>, errors: MutableList<String>): List<RawCue> {
        val result = mutableListOf<RawCue>()
        var i = 0
        while (i < lines.size) {
            val match = arrowRegex.matchEntire(lines[i])
            if (match == null) {
                i++
                continue
            }
            val lineNumber = i + 1
            val start = parseTime(match.groupValues[1])
            val end = parseTime(match.groupValues[2])
            i++
            val body = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank() && arrowRegex.matchEntire(lines[i]) == null) {
                if (body.isNotEmpty()) body.append(' ')
                body.append(lines[i].trim())
                i++
            }
            if (start == null || end == null) {
                addError(
                    errors,
                    "Line $lineNumber: cannot read the time \"${match.groupValues[1]} --> ${match.groupValues[2]}\"."
                )
                continue
            }
            result.add(RawCue(start, end, body.toString()))
        }
        return result
    }

    // ranges = true: "start - end" lines. ranges = false: one start time per line.
    private fun readTimedLines(
        lines: List<String>,
        ranges: Boolean,
        errors: MutableList<String>
    ): List<RawCue> {
        val result = mutableListOf<RawCue>()
        var open = false
        var start = 0L
        var end: Long? = null
        var body = StringBuilder()

        fun flush() {
            if (open) result.add(RawCue(start, end, body.toString()))
            open = false
            body = StringBuilder()
        }

        for ((index, line) in lines.withIndex()) {
            val lineNumber = index + 1
            val timed: Triple<String, String?, String>? = if (ranges) {
                rangeRegex.matchEntire(line)?.let {
                    Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3])
                }
            } else {
                inlineRegex.matchEntire(line)?.let { m ->
                    val token = m.groupValues[1].ifEmpty { m.groupValues[2].ifEmpty { m.groupValues[3] } }
                    Triple(token, null, m.groupValues[4])
                }
            }

            if (timed != null) {
                flush()
                val startMs = parseTime(timed.first)
                val endText = timed.second
                val endMs = if (endText != null) parseTime(endText) else null
                if (startMs == null || (endText != null && endMs == null)) {
                    addError(errors, "Line $lineNumber: cannot read the time in \"${line.trim()}\".")
                    continue
                }
                start = startMs
                end = endMs
                open = true
                body.append(timed.third.trim())
                continue
            }

            if (line.isBlank()) {
                flush()
                continue
            }
            if (open) {
                if (body.isNotEmpty()) body.append(' ')
                body.append(line.trim())
            } else {
                addError(errors, "Line $lineNumber: text without a time was skipped.")
            }
        }
        flush()
        return result
    }

    // Cleans the text, sorts by start time and fills in missing end times.
    private fun finish(
        raws: List<RawCue>,
        audioDurationMs: Long?,
        errors: MutableList<String>
    ): List<CaptionCue> {
        var shortened = false
        val cleaned = raws.mapNotNull { raw ->
            var text = cleanText(raw.text)
            if (text.isEmpty()) return@mapNotNull null
            if (text.length > MAX_TEXT_CHARS) {
                text = text.take(MAX_TEXT_CHARS).trimEnd()
                shortened = true
            }
            RawCue(raw.startMs, raw.endMs, text)
        }.sortedBy { it.startMs }
        if (shortened) {
            addError(errors, "A very long caption was shortened to $MAX_TEXT_CHARS letters.")
        }

        val cues = ArrayList<CaptionCue>()
        for ((i, raw) in cleaned.withIndex()) {
            val start = raw.startMs
            val nextStart = cleaned.getOrNull(i + 1)?.startMs
            val wanted: Long = raw.endMs ?: when {
                nextStart != null && nextStart > start -> nextStart
                nextStart == null && audioDurationMs != null && audioDurationMs > start -> audioDurationMs
                nextStart == null -> start + estimateMs(raw.text)
                else -> start + MIN_DURATION_MS
            }
            // Never a negative or zero length.
            val end = if (wanted <= start) start + MIN_DURATION_MS else wanted
            cues.add(CaptionCue((i + 1).toLong(), start, end, raw.text))
        }
        return cues
    }
}

// ---------------------------------------------------------------------------
// Line breaking, shared by the preview and the exported video.
// ---------------------------------------------------------------------------
object CaptionWrap {

    // A long caption is cut into pieces of about this many letters.
    const val PIECE_CHARS = 84

    // A piece may end early after . ! ? when it already has this many letters.
    const val FLUSH_CHARS = 42

    private const val MAX_VISUAL_LINES = 8

    // . ! ? and the Bengali full stop, Arabic question mark, ellipsis.
    private val sentenceEnds = charArrayOf('.', '!', '?', '\u0964', '\u061F', '\u2026')

    fun splitIntoLines(text: String): List<String> {
        val words = text.split(' ').filter { it.isNotEmpty() }
        val lines = ArrayList<String>()
        val current = StringBuilder()
        for (word in words) {
            if (current.isNotEmpty() && current.length + 1 + word.length > PIECE_CHARS) {
                lines.add(current.toString())
                current.clear()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(word)
            if (current.length >= FLUSH_CHARS && word.last() in sentenceEnds) {
                lines.add(current.toString())
                current.clear()
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines
    }

    // A long caption becomes several shorter ones. The time is shared by the
    // number of letters in each piece.
    fun splitCue(cue: CaptionCue): List<CaptionCue> {
        val lines = splitIntoLines(cue.text)
        if (lines.size <= 1) return listOf(cue)
        val total = (cue.endMs - cue.startMs).coerceAtLeast(1L)
        val letters = lines.sumOf { it.length }.coerceAtLeast(1)
        val pieces = ArrayList<CaptionCue>()
        var start = cue.startMs
        var used = 0
        for ((i, line) in lines.withIndex()) {
            used += line.length
            val end = if (i == lines.lastIndex) cue.endMs else cue.startMs + total * used / letters
            pieces.add(CaptionCue(cue.id, start, end, line))
            start = end
        }
        return pieces
    }

    fun splitAll(cues: List<CaptionCue>): List<CaptionCue> {
        return cues.flatMap { splitCue(it) }
    }

    // The caption showing at this moment. If two overlap, the later one wins.
    // pieces must be sorted by start time (splitAll keeps that order).
    fun activeAt(pieces: List<CaptionCue>, timeMs: Long): CaptionCue? {
        var found: CaptionCue? = null
        for (piece in pieces) {
            if (piece.startMs > timeMs) break
            if (timeMs < piece.endMs) found = piece
        }
        return found
    }

    // How many letters fit on one line of a video of this width.
    fun maxCharsFor(width: Int, fontPx: Float): Int {
        return max(8, floor(width * 0.9f / (fontPx * 0.58f)).toInt())
    }

    // The lines really drawn on the video. Longer than 42 letters: two lines,
    // balanced near the middle. Never wider than maxChars.
    fun visualLines(text: String, maxChars: Int): List<String> {
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()
        val limit = max(8, maxChars)
        if (clean.length > FLUSH_CHARS && clean.contains(' ')) {
            val balanced = balance(clean)
            if (balanced.all { it.length <= limit }) return balanced
        }
        return greedy(clean, limit)
    }

    private fun balance(text: String): List<String> {
        val middle = text.length / 2
        var best = -1
        var bestDistance = Int.MAX_VALUE
        for (i in text.indices) {
            if (text[i] == ' ') {
                val distance = abs(i - middle)
                if (distance < bestDistance) {
                    best = i
                    bestDistance = distance
                }
            }
        }
        if (best <= 0) return listOf(text)
        return listOf(text.substring(0, best).trim(), text.substring(best + 1).trim())
    }

    private fun greedy(text: String, limit: Int): List<String> {
        val lines = ArrayList<String>()
        val current = StringBuilder()
        for (word in text.split(' ').filter { it.isNotEmpty() }) {
            var rest = word
            // A single word longer than a line is cut so it cannot leave the frame.
            while (rest.length > limit) {
                if (current.isNotEmpty()) {
                    lines.add(current.toString())
                    current.clear()
                }
                lines.add(rest.substring(0, limit))
                rest = rest.substring(limit)
            }
            if (current.isNotEmpty() && current.length + 1 + rest.length > limit) {
                lines.add(current.toString())
                current.clear()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(rest)
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines.take(MAX_VISUAL_LINES)
    }
}

// ---------------------------------------------------------------------------
// The three looks, the sizes and the size maths.
// ---------------------------------------------------------------------------
enum class CaptionLook(
    val label: String,
    val textColor: Long,
    val hasOutline: Boolean,
    val hasBox: Boolean,
    val defaultLineSpacing: Float
) {
    CLASSIC_OUTLINE("Classic Outline", 0xFFFFFFFFL, true, false, 1.16f),
    BOXED("Boxed", 0xFFFFFFFFL, false, true, 1.5f),
    YELLOW_CLASSIC("Yellow Classic", 0xFFFFD400L, true, false, 1.16f);

    companion object {
        // An unknown name safely becomes Classic Outline.
        fun fromName(name: String?): CaptionLook {
            return values().firstOrNull { it.name == name } ?: CLASSIC_OUTLINE
        }
    }
}

enum class CaptionSizeChoice(val label: String, val heightShare: Float) {
    SMALL("Small", 0.042f),
    MEDIUM("Medium", 0.052f),
    LARGE("Large", 0.064f);

    companion object {
        fun fromName(name: String?): CaptionSizeChoice {
            return values().firstOrNull { it.name == name } ?: MEDIUM
        }
    }
}

// Colours are ARGB numbers (0xAARRGGBB).
object CaptionLayoutMath {

    // Black at 90% and 60%.
    const val OUTLINE_ARGB = 0xE6000000L
    const val BOX_ARGB = 0x99000000L

    // Ranges chosen for StickCut (they are not copied from any other app):
    // the custom text size is 2.5% to 10% of the video height,
    // the custom line spacing is 1.0 to 2.5.
    const val MIN_CUSTOM_SCALE = 0.025f
    const val MAX_CUSTOM_SCALE = 0.10f
    const val MIN_LINE_SPACING = 1.0f
    const val MAX_LINE_SPACING = 2.5f

    // Text height in pixels. A valid custom value wins over Small / Medium / Large.
    fun fontPx(videoHeight: Int, state: ImportedCaptionState): Float {
        val custom = state.customScale
        val share = if (custom != null && custom in MIN_CUSTOM_SCALE..MAX_CUSTOM_SCALE) {
            custom
        } else {
            state.size.heightShare
        }
        return videoHeight * share
    }

    fun outlinePx(fontPx: Float): Float {
        return max(2, (fontPx / 9f).roundToInt()).toFloat()
    }

    fun boxPaddingPx(fontPx: Float): Float {
        return max(3f, fontPx * 0.16f)
    }

    // A positive custom value wins over the default of the look.
    fun lineSpacing(state: ImportedCaptionState): Float {
        val custom = state.lineSpacing
        return if (custom != null && custom > 0f) {
            custom.coerceIn(MIN_LINE_SPACING, MAX_LINE_SPACING)
        } else {
            state.look.defaultLineSpacing
        }
    }

    fun bottomMarginPx(videoHeight: Int): Float {
        return videoHeight * 0.07f
    }
}

// ---------------------------------------------------------------------------
// What is kept in memory while the app is open.
// ---------------------------------------------------------------------------
data class ImportedCaptionState(
    val fileName: String = "",
    // The captions the user can edit.
    val cues: List<CaptionCue> = emptyList(),
    // The captions exactly as imported (for "Restore original").
    val originalCues: List<CaptionCue> = emptyList(),
    val enabled: Boolean = true,
    val look: CaptionLook = CaptionLook.CLASSIC_OUTLINE,
    val size: CaptionSizeChoice = CaptionSizeChoice.MEDIUM,
    // Custom text height as a share of the video height (null = use the size).
    val customScale: Float? = null,
    // Custom line spacing (null = use the default of the look).
    val lineSpacing: Float? = null
) {
    // True when captions should be drawn.
    val isActive: Boolean get() = enabled && cues.isNotEmpty()
}

object ImportedCaptionStore {
    var state by mutableStateOf(ImportedCaptionState())

    fun load(fileName: String, cues: List<CaptionCue>) {
        state = state.copy(fileName = fileName, cues = cues, originalCues = cues, enabled = true)
    }

    // Removes the captions but keeps the chosen look and size.
    fun clear() {
        state = state.copy(fileName = "", cues = emptyList(), originalCues = emptyList())
    }

    fun restoreOriginal() {
        state = state.copy(cues = state.originalCues)
    }

    fun setEnabled(value: Boolean) {
        state = state.copy(enabled = value)
    }

    fun setLook(value: CaptionLook) {
        state = state.copy(look = value)
    }

    fun setSize(value: CaptionSizeChoice) {
        state = state.copy(size = value, customScale = null)
    }

    fun setCustomScale(value: Float?) {
        state = state.copy(customScale = value)
    }

    fun setLineSpacing(value: Float?) {
        state = state.copy(lineSpacing = value)
    }

    // Changes one caption. The end is always after the start, and the list stays
    // in time order.
    fun updateCue(id: Long, startMs: Long, endMs: Long, text: String) {
        val start = startMs.coerceAtLeast(0L)
        val end = if (endMs <= start) start + CaptionFileParser.MIN_DURATION_MS else endMs
        val clean = CaptionFileParser.cleanText(text).take(CaptionFileParser.MAX_TEXT_CHARS)
        state = state.copy(
            cues = state.cues
                .map { if (it.id == id) CaptionCue(id, start, end, clean) else it }
                .sortedBy { it.startMs }
        )
    }

    fun addCue(startMs: Long) {
        val nextId = (state.cues.maxOfOrNull { it.id } ?: 0L) + 1L
        val start = startMs.coerceAtLeast(0L)
        val cue = CaptionCue(nextId, start, start + 2000L, "New caption")
        state = state.copy(cues = (state.cues + cue).sortedBy { it.startMs })
    }

    fun deleteCue(id: Long) {
        state = state.copy(cues = state.cues.filter { it.id != id })
    }
}
