package com.kamran.stickcut

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.max

// ---------------------------------------------------------------------------
// Step 81: draws imported captions (from .srt/.vtt/.txt) over the video.
// The same ImportedCaptionPainter will be used by the exported video (Step 82),
// so the preview and the MP4 look the same.
// ---------------------------------------------------------------------------
class ImportedCaptionPainter {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        fill.typeface = Typeface.DEFAULT_BOLD
        fill.textAlign = Paint.Align.CENTER
        fill.style = Paint.Style.FILL

        outline.typeface = Typeface.DEFAULT_BOLD
        outline.textAlign = Paint.Align.CENTER
        outline.style = Paint.Style.STROKE
        outline.strokeJoin = Paint.Join.ROUND
        outline.color = CaptionLayoutMath.OUTLINE_ARGB.toInt()

        boxPaint.style = Paint.Style.FILL
        boxPaint.color = CaptionLayoutMath.BOX_ARGB.toInt()
    }

    // Draws one caption (already one piece of text) at the bottom of the frame.
    fun draw(canvas: Canvas, width: Int, height: Int, state: ImportedCaptionState, text: String) {
        if (width <= 0 || height <= 0) return
        val fontPx = CaptionLayoutMath.fontPx(height, state)
        val maxChars = CaptionWrap.maxCharsFor(width, fontPx)
        val lines = CaptionWrap.visualLines(text, maxChars)
        if (lines.isEmpty()) return

        fill.textSize = fontPx
        outline.textSize = fontPx
        fill.color = state.look.textColor.toInt()

        val lineHeight = fontPx * CaptionLayoutMath.lineSpacing(state)
        val metrics = fill.fontMetrics
        val blockHeight = lines.size * lineHeight
        val top = max(0f, height - CaptionLayoutMath.bottomMarginPx(height) - blockHeight)
        val centerX = width / 2f

        // Baseline of line i.
        fun baseline(i: Int): Float {
            return top + i * lineHeight +
                (lineHeight - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent
        }

        if (state.look.hasBox) {
            var widest = 0f
            for (line in lines) widest = max(widest, fill.measureText(line))
            val pad = CaptionLayoutMath.boxPaddingPx(fontPx)
            rect.set(
                centerX - widest / 2f - pad,
                baseline(0) + metrics.ascent - pad,
                centerX + widest / 2f + pad,
                baseline(lines.size - 1) + metrics.descent + pad
            )
            canvas.drawRoundRect(rect, pad * 0.5f, pad * 0.5f, boxPaint)
        }

        if (state.look.hasOutline) {
            outline.strokeWidth = CaptionLayoutMath.outlinePx(fontPx)
            for ((i, line) in lines.withIndex()) {
                canvas.drawText(line, centerX, baseline(i), outline)
            }
        }
        for ((i, line) in lines.withIndex()) {
            canvas.drawText(line, centerX, baseline(i), fill)
        }
    }

    companion object {
        // Long captions are cut into pieces. Sorted so that overlapping captions
        // still work with CaptionWrap.activeAt.
        fun piecesFor(cues: List<CaptionCue>): List<CaptionCue> {
            return CaptionWrap.splitAll(cues).sortedBy { it.startMs }
        }
    }
}

// Which kind of imported caption is used.
// false = Classic presets (Classic Outline, Boxed, Yellow Classic).
// true  = Animated: the Caption Studio style (animation, position, font, size).
object ImportedCaptionMode {
    var useStudio by mutableStateOf(false)
}

// Turns imported captions into the short chunks that the Caption Studio
// animations know how to draw (a few words at a time).
object ImportedCaptionChunks {

    private val spaces = Regex("\\s+")

    fun build(cues: List<CaptionCue>, style: CaptionStyle): List<CaptionChunk> {
        val s = style.normalized()
        val sorted = cues.sortedBy { it.startMs }
        val result = ArrayList<CaptionChunk>()
        for ((i, cue) in sorted.withIndex()) {
            val nextStart = sorted.getOrNull(i + 1)?.startMs
            // If the next caption starts before this one ends, the later one wins.
            val end = if (nextStart != null && nextStart > cue.startMs && nextStart < cue.endMs) {
                nextStart
            } else {
                cue.endMs
            }
            if (end <= cue.startMs) continue
            val words = cue.text.trim().split(spaces).filter { it.isNotEmpty() }
            if (words.isEmpty()) continue

            val groups = ArrayList<List<String>>()
            var current = ArrayList<String>()
            var letters = 0
            for (word in words) {
                val withWord = if (current.isEmpty()) word.length else letters + 1 + word.length
                if (current.isNotEmpty() && (current.size >= s.maxWords || withWord > s.maxChars)) {
                    groups.add(current)
                    current = arrayListOf(word)
                    letters = word.length
                } else {
                    current.add(word)
                    letters = withWord
                }
            }
            if (current.isNotEmpty()) groups.add(current)

            val weights = groups.map { it.joinToString(" ").length.coerceAtLeast(1) }
            val totalWeight = weights.sum().toLong()
            val duration = end - cue.startMs
            var cumulative = 0L
            var previous = cue.startMs
            for ((g, group) in groups.withIndex()) {
                cumulative += weights[g]
                val boundary = if (g == groups.lastIndex) {
                    end
                } else {
                    cue.startMs + Math.round(duration.toDouble() * cumulative / totalWeight)
                }
                result.add(
                    CaptionChunk(
                        sceneIndex = cue.id.toInt(),
                        startMs = previous,
                        endMs = boundary,
                        text = group.joinToString(" "),
                        words = group
                    )
                )
                previous = boundary
            }
        }
        return result
    }
}

// Shows the caption of this moment (if any) over the whole frame.
@Composable
fun ImportedCaptionOverlay(
    state: ImportedCaptionState,
    pieces: List<CaptionCue>,
    positionMs: Long,
    modifier: Modifier = Modifier
) {
    val painter = remember { ImportedCaptionPainter() }
    val studioStyle = CaptionStore.style
    val studio = ImportedCaptionMode.useStudio
    val chunks = remember(state.cues, studio, studioStyle.maxWords, studioStyle.maxChars) {
        if (studio) ImportedCaptionChunks.build(state.cues, studioStyle) else emptyList()
    }
    val piece = if (studio) null else CaptionWrap.activeAt(pieces, positionMs)

    if (studio) {
        val chunk = CaptionEngine.chunkAt(chunks, positionMs)
        if (chunk != null) {
            CaptionOverlay(
                chunk = chunk,
                style = studioStyle.copy(enabled = true),
                localMs = positionMs - chunk.startMs,
                modifier = modifier
            )
        }
    } else if (piece != null) {
        ComposeCanvas(modifier = modifier) {
            drawIntoCanvas { canvas ->
                painter.draw(
                    canvas.nativeCanvas,
                    size.width.toInt(),
                    size.height.toInt(),
                    state,
                    piece.text
                )
            }
        }
    }
}
