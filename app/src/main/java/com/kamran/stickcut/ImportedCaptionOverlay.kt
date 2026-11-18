package com.kamran.stickcut

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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

// Shows the caption of this moment (if any) over the whole frame.
@Composable
fun ImportedCaptionOverlay(
    state: ImportedCaptionState,
    pieces: List<CaptionCue>,
    positionMs: Long,
    modifier: Modifier = Modifier
) {
    val painter = remember { ImportedCaptionPainter() }
    val piece = CaptionWrap.activeAt(pieces, positionMs)
    if (piece != null) {
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
