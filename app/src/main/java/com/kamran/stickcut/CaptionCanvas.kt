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
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import java.io.File

// Draws the captions on a video frame (used by the exporter). Step 85.
// It uses the same maths as the preview: CaptionEngine, CaptionAnimator,
// CaptionHighlight and ImportedCaptionChunks.
//
// What is drawn:
//   - imported captions (.srt/.vtt/.txt) in "Animated" mode: same style as Caption Studio,
//   - imported captions in "Classic presets" mode: same painter as the preview,
//   - otherwise the old captions made from the scene texts.
// The settings are copied when this object is created, so they cannot change
// in the middle of an export.
class CaptionCanvas(
    captionStyle: CaptionStyle,
    scenes: List<Scene>,
    private val width: Int,
    private val height: Int
) {
    private val imported: ImportedCaptionState = ImportedCaptionStore.state
    private val useImported: Boolean = imported.isActive
    private val classicMode: Boolean = useImported && !ImportedCaptionMode.useStudio
    private val highlightMode: CaptionHighlightMode = CaptionHighlightStore.mode

    // Imported captions ignore the old "Captions: ON/OFF" button, like the preview.
    private val s = if (useImported) {
        captionStyle.normalized().copy(enabled = true)
    } else {
        captionStyle.normalized()
    }

    private val chunks: List<CaptionChunk> = when {
        classicMode -> emptyList()
        useImported -> ImportedCaptionChunks.build(imported.cues, s)
        s.enabled -> CaptionEngine.build(scenes, s).chunks
        else -> emptyList()
    }

    private val pieces: List<CaptionCue> =
        if (classicMode) ImportedCaptionPainter.piecesFor(imported.cues) else emptyList()
    private val classicPainter: ImportedCaptionPainter? =
        if (classicMode) ImportedCaptionPainter() else null

    val enabled: Boolean
        get() = if (classicMode) pieces.isNotEmpty() else s.enabled && chunks.isNotEmpty()

    private val textSizePx = height * s.sizePercent / 100f
    private val outlinePx = textSizePx * s.outlineWidth * 2f
    private val boxPad = textSizePx * 0.25f
    private val chosenTypeface: Typeface = makeTypeface()

    private val fillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = textSizePx
        typeface = chosenTypeface
        color = s.textColor.toInt()
    }
    private val strokePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = textSizePx
        typeface = chosenTypeface
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

    // Colours the words the same way as the preview (CaptionOverlay).
    private fun highlightedText(shown: String, words: List<String>, activeIndex: Int): CharSequence {
        val effectiveMode = if (highlightMode != CaptionHighlightMode.OFF) {
            highlightMode
        } else {
            CaptionHighlightMode.COLOR
        }
        val highlight = s.highlightColor.toInt()
        val spannable = SpannableString(shown)
        var start = 0
        words.forEachIndexed { index, word ->
            val end = start + word.length
            if (end <= spannable.length) {
                val isActive = index == activeIndex
                when (effectiveMode) {
                    CaptionHighlightMode.PROGRESSIVE -> {
                        if (index <= activeIndex) {
                            spannable.setSpan(
                                ForegroundColorSpan(highlight), start, end,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }
                    }
                    CaptionHighlightMode.BACKGROUND -> {
                        if (isActive) {
                            spannable.setSpan(
                                BackgroundColorSpan(highlight), start, end,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            spannable.setSpan(
                                ForegroundColorSpan(android.graphics.Color.BLACK), start, end,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }
                    }
                    CaptionHighlightMode.UNDERLINE -> {
                        if (isActive) {
                            spannable.setSpan(
                                ForegroundColorSpan(highlight), start, end,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            spannable.setSpan(
                                UnderlineSpan(), start, end,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }
                    }
                    else -> {
                        if (isActive) {
                            spannable.setSpan(
                                ForegroundColorSpan(highlight), start, end,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }
                    }
                }
            }
            start = end + 1
        }
        return spannable
    }

    fun draw(canvas: Canvas, timeMs: Long) {
        if (!enabled) return

        // Classic presets (same painter as the preview).
        if (classicMode) {
            val piece = CaptionWrap.activeAt(pieces, timeMs) ?: return
            classicPainter?.draw(canvas, width, height, imported, piece.text)
            return
        }

        val chunk = CaptionEngine.chunkAt(chunks, timeMs) ?: return
        val localMs = timeMs - chunk.startMs
        val frame = CaptionAnimator.frameAt(chunk, s, localMs)
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

        // Same rule as the preview: the Word highlight setting works with any
        // animation except Typewriter. If it is Off, Word Pop keeps its yellow word.
        val useHighlight = s.animation != CaptionAnimation.TYPEWRITER &&
            (highlightMode != CaptionHighlightMode.OFF || s.animation == CaptionAnimation.WORD_POP)
        val activeIndex = if (!useHighlight) {
            -1
        } else if (highlightMode != CaptionHighlightMode.OFF) {
            CaptionHighlight.activeWord(chunk.words, localMs, chunk.durationMs)
        } else {
            frame.activeWord
        }

        val key = "$shown|$useHighlight|$activeIndex"
        if (key != cachedKey || cachedFill == null || cachedStroke == null) {
            val fillText: CharSequence = if (useHighlight) {
                highlightedText(shown, words, activeIndex)
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
