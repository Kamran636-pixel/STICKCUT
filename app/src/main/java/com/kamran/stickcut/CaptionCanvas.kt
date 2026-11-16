package com.kamran.stickcut

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import java.io.File

// Draws the captions on a video frame (used by the exporter).
// It uses the same maths as the preview: CaptionEngine and CaptionAnimator.
class CaptionCanvas(
    style: CaptionStyle,
    scenes: List<Scene>,
    private val width: Int,
    private val height: Int
) {
    private val s = style.normalized()
    private val chunks: List<CaptionChunk> =
        if (s.enabled) CaptionEngine.build(scenes, s).chunks else emptyList()

    val enabled: Boolean get() = s.enabled && chunks.isNotEmpty()

    private val textSizePx = height * s.sizePercent / 100f
    private val outlinePx = textSizePx * s.outlineWidth * 2f
    private val boxPad = textSizePx * 0.25f
    private val typeface = makeTypeface()

    private val fillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = textSizePx
        typeface = this@CaptionCanvas.typeface
        color = s.textColor.toInt()
    }
    private val strokePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = textSizePx
        typeface = this@CaptionCanvas.typeface
        style = Paint.Style.STROKE
        strokeWidth = outlinePx
        strokeJoin = Paint.Join.ROUND
        color = s.outlineColor.toInt()
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = s.backgroundColor.toInt()
    }

    // The same text is drawn for many frames, so its layout is kept.
    private var cachedKey = ""
    private var cachedFill: StaticLayout? = null
    private var cachedStroke: StaticLayout? = null

    private fun makeTypeface(): Typeface {
        return try {
            when (s.font) {
                CaptionFont.SANS -> Typeface.SANS_SERIF
                CaptionFont.SANS_BOLD -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                CaptionFont.SERIF -> Typeface.SERIF
                CaptionFont.MONO -> Typeface.MONOSPACE
                CaptionFont.CONDENSED -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
                CaptionFont.CUSTOM -> {
                    val path = s.customFontPath
                    if (path != null && File(path).exists()) {
                        Typeface.createFromFile(path)
                    } else {
                        Typeface.SANS_SERIF
                    }
                }
            }
        } catch (e: Exception) {
            Typeface.SANS_SERIF
        }
    }

    private fun buildLayout(text: CharSequence, paint: TextPaint, layoutWidth: Int): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, layoutWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(3)
            .setIncludePad(false)
            .build()
    }

    fun draw(canvas: Canvas, timeMs: Long) {
        if (!enabled) return
        val chunk = CaptionEngine.chunkAt(chunks, timeMs) ?: return
        val frame = CaptionAnimator.frameAt(chunk, s, timeMs - chunk.startMs)
        if (frame.alpha <= 0.001f) return

        val words = chunk.words.map { CaptionEngine.displayText(it, s) }
        val fullText = words.joinToString(" ")
        val shown = if (s.animation == CaptionAnimation.TYPEWRITER) {
            fullText.take(frame.visibleChars)
        } else {
            fullText
        }
        if (shown.isEmpty()) return

        var layoutWidth = (width * 0.92f).toInt()
        if (s.background) layoutWidth -= (boxPad * 3f).toInt()
        if (layoutWidth < 50) layoutWidth = 50

        val activeWord = if (s.animation == CaptionAnimation.WORD_POP) frame.activeWord else -1
        val key = "$shown|$activeWord"
        if (key != cachedKey || cachedFill == null || cachedStroke == null) {
            val fillText: CharSequence = if (activeWord >= 0) {
                val spannable = SpannableString(shown)
                var start = 0
                words.forEachIndexed { index, word ->
                    val end = start + word.length
                    if (index == activeWord && end <= spannable.length) {
                        spannable.setSpan(
                            ForegroundColorSpan(s.highlightColor.toInt()),
                            start,
                            end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    start = end + 1
                }
                spannable
            } else {
                shown
            }
            cachedFill = buildLayout(fillText, fillPaint, layoutWidth)
            cachedStroke = buildLayout(shown, strokePaint, layoutWidth)
            cachedKey = key
        }
        val fillLayout = cachedFill ?: return
        val strokeLayout = cachedStroke ?: return

        var widest = 0f
        for (i in 0 until fillLayout.lineCount) {
            widest = maxOf(widest, fillLayout.getLineWidth(i))
        }
        val textHeight = fillLayout.height.toFloat()
        val boxWidth = if (s.background) widest + boxPad * 3f else widest
        val boxHeight = if (s.background) textHeight + boxPad * 2f else textHeight

        val edge = height * 0.08f
        val baseTop = when (s.position) {
            CaptionPosition.TOP -> edge
            CaptionPosition.MIDDLE -> (height - boxHeight) / 2f
            CaptionPosition.BOTTOM -> height - edge - boxHeight
        }
        val top = baseTop + height * frame.offsetY
        val centerX = width / 2f
        val centerY = top + boxHeight / 2f

        val saved = canvas.save()
        canvas.scale(frame.scale, frame.scale, centerX, centerY)
        if (frame.alpha < 0.999f) {
            val margin = outlinePx + boxPad
            canvas.saveLayerAlpha(
                centerX - boxWidth / 2f - margin,
                top - margin,
                centerX + boxWidth / 2f + margin,
                top + boxHeight + margin,
                (frame.alpha * 255f).toInt().coerceIn(0, 255)
            )
        }

        if (s.background) {
            canvas.drawRoundRect(
                RectF(centerX - boxWidth / 2f, top, centerX + boxWidth / 2f, top + boxHeight),
                boxPad,
                boxPad,
                backgroundPaint
            )
        }

        val textTop = if (s.background) top + boxPad else top
        canvas.save()
        canvas.translate(centerX - layoutWidth / 2f, textTop)
        if (s.outlineWidth > 0.001f) strokeLayout.draw(canvas)
        fillLayout.draw(canvas)
        canvas.restore()

        canvas.restoreToCount(saved)
    }
}
