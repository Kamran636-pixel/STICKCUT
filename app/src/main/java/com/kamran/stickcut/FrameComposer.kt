package com.kamran.stickcut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import kotlin.math.hypot
import kotlin.math.max

// Draws ONE video frame at a given time onto an Android Canvas.
// It uses the same maths as the preview screen:
//   - AnimationEngine for the zoom,
//   - CutTransitionStore for the transition of every cut,
//   - SceneFadeStore for the fade in / fade out of the whole video,
//   - CaptionStore for the captions (drawn last, on top of everything).
// The video exporter calls draw() once for every frame.
class FrameComposer(
    private val context: Context,
    private val scenes: List<Scene>,
    animation: AnimationSettings,
    private val cuts: CutTransitionState,
    private val fades: SceneFadeState,
    private val width: Int,
    private val height: Int
) {
    private val planned = AnimationEngine.plan(scenes, animation)
    private val keys = scenes.map { CutTransitionEngine.sceneKey(it.imageUri) }
    val totalMs: Long = scenes.lastOrNull()?.endMs ?: 0L

    // Captions use the style chosen in Caption Studio. If they are switched off
    // there, nothing is drawn.
    private val captions = CaptionCanvas(CaptionStore.style, scenes, width, height)

    // Scenes whose picture could not be read. The exporter shows this as an error
    // instead of silently drawing a black frame.
    val failedScenes = linkedSetOf<Int>()

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fadePaint = Paint()
    private val matrix = Matrix()
    private val circlePath = Path()

    // Only a few decoded pictures are kept in memory (least recently used go first).
    private val cache = object : LinkedHashMap<Int, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>?): Boolean {
            val remove = size > MAX_CACHED
            if (remove && eldest != null) {
                try {
                    eldest.value.recycle()
                } catch (e: Exception) {
                    // ignore
                }
            }
            return remove
        }
    }

    private class Blend(val outIndex: Int, val inIndex: Int, val q: Float, val kind: CutTransitionKind)

    private class Layer(
        val alpha: Float,
        val tx: Float,
        val revealKind: CutTransitionKind?,
        val revealQ: Float
    )

    // Same rule as the preview: the transition is centred on the moment
    // the new scene starts.
    private fun findBlend(base: Int, timeMs: Long): Blend? {
        for (k in intArrayOf(base, base + 1)) {
            if (k < 1 || k >= scenes.size) continue
            val kind = CutTransitionEngine.kindAt(cuts, keys, k)
            if (kind == CutTransitionKind.CUT) continue
            val length = CutTransitionEngine.effectiveMs(
                kind,
                cuts.durationMs,
                scenes[k - 1].durationMs,
                scenes[k].durationMs
            )
            if (length <= 0L) continue
            val windowStart = scenes[k].startMs - length / 2L
            if (timeMs >= windowStart && timeMs < windowStart + length) {
                val q = (timeMs - windowStart).toFloat() / length.toFloat()
                return Blend(k - 1, k, q, kind)
            }
        }
        return null
    }

    // role: 0 = not in a transition, 1 = leaving scene, 2 = arriving scene
    private fun layerFor(role: Int, isBase: Boolean, blend: Blend?): Layer {
        if (blend == null || role == 0) {
            return Layer(if (isBase && blend == null) 1f else 0f, 0f, null, 1f)
        }
        val q = blend.q
        return when (blend.kind) {
            CutTransitionKind.FADE -> if (role == 1) {
                Layer(1f, 0f, null, 1f)
            } else {
                Layer(q, 0f, null, 1f)
            }

            CutTransitionKind.FADE_BLACK -> if (role == 1) {
                Layer(if (q < 0.5f) 1f - 2f * q else 0f, 0f, null, 1f)
            } else {
                Layer(if (q >= 0.5f) 2f * q - 1f else 0f, 0f, null, 1f)
            }

            CutTransitionKind.WIPE_LEFT,
            CutTransitionKind.WIPE_RIGHT,
            CutTransitionKind.CIRCLE_OPEN -> if (role == 1) {
                Layer(1f, 0f, null, 1f)
            } else {
                Layer(1f, 0f, blend.kind, q)
            }

            CutTransitionKind.SLIDE_LEFT -> if (role == 1) {
                Layer(1f, -q, null, 1f)
            } else {
                Layer(1f, 1f - q, null, 1f)
            }

            CutTransitionKind.SLIDE_RIGHT -> if (role == 1) {
                Layer(1f, q, null, 1f)
            } else {
                Layer(1f, -(1f - q), null, 1f)
            }

            CutTransitionKind.CUT -> Layer(if (isBase) 1f else 0f, 0f, null, 1f)
        }
    }

    // Reads a picture at a size that is enough for the frame (plus room for zoom),
    // not at its full size. This keeps memory small on low-end phones.
    private fun decode(uri: Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val needW = (width * 1.3f).toInt()
            val needH = (height * 1.3f).toInt()
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= needW && bounds.outHeight / (sample * 2) >= needH) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private fun bitmapFor(index: Int): Bitmap? {
        val cached = cache[index]
        if (cached != null && !cached.isRecycled) return cached
        val bitmap = decode(scenes[index].imageUri)
        if (bitmap == null) {
            failedScenes.add(scenes[index].index)
            return null
        }
        cache[index] = bitmap
        return bitmap
    }

    private fun drawLayer(canvas: Canvas, index: Int, timeMs: Long, layer: Layer) {
        if (layer.alpha <= 0f) return
        val bitmap = bitmapFor(index) ?: return
        val scene = scenes[index]
        val plan = planned[index]
        val t = AnimationEngine.transformAtTime(plan.config, plan, timeMs - scene.startMs)

        val w = width.toFloat()
        val h = height.toFloat()
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()

        // 1) Fill the frame with the picture (centred, edges cut if the shape differs).
        val fit = max(w / bw, h / bh)
        matrix.reset()
        matrix.postScale(fit, fit)
        matrix.postTranslate((w - bw * fit) / 2f, (h - bh * fit) / 2f)
        // 2) Zoom around the centre of the frame.
        matrix.postScale(t.scale, t.scale, w / 2f, h / 2f)
        // 3) Move (animation drift plus transition slide), as a share of the frame.
        matrix.postTranslate((t.offsetX + layer.tx) * w, t.offsetY * h)

        canvas.save()
        val reveal = layer.revealKind
        if (reveal != null) {
            val p = layer.revealQ.coerceIn(0f, 1f)
            when (reveal) {
                CutTransitionKind.WIPE_LEFT -> canvas.clipRect(w * (1f - p), 0f, w, h)
                CutTransitionKind.WIPE_RIGHT -> canvas.clipRect(0f, 0f, w * p, h)
                CutTransitionKind.CIRCLE_OPEN -> {
                    circlePath.reset()
                    circlePath.addCircle(w / 2f, h / 2f, p * hypot(w / 2f, h / 2f), Path.Direction.CW)
                    canvas.clipPath(circlePath)
                }
                else -> Unit
            }
        }
        bitmapPaint.alpha = (layer.alpha.coerceIn(0f, 1f) * 255f).toInt()
        canvas.drawBitmap(bitmap, matrix, bitmapPaint)
        canvas.restore()
    }

    // Draws the frame for time timeMs (milliseconds from the start of the video).
    fun draw(canvas: Canvas, timeMs: Long) {
        canvas.drawColor(android.graphics.Color.BLACK)
        if (scenes.isEmpty()) return

        val base = scenes.indexOfLast { it.startMs <= timeMs }.coerceAtLeast(0)
        val blend = findBlend(base, timeMs)

        val indices = listOf(base - 1, base, base + 1).filter { it in scenes.indices }
        indices.forEach { index ->
            val role = when {
                blend == null -> 0
                index == blend.outIndex -> 1
                index == blend.inIndex -> 2
                else -> 0
            }
            drawLayer(canvas, index, timeMs, layerFor(role, index == base, blend))
        }

        // Scene fades: black over the whole frame at the start and at the end.
        val gain = SceneFadeEngine.gainAt(fades, totalMs.coerceAtLeast(1L), timeMs)
        if (gain < 1f) {
            fadePaint.color = android.graphics.Color.argb(
                ((1f - gain).coerceIn(0f, 1f) * 255f).toInt(), 0, 0, 0
            )
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fadePaint)
        }

        // Captions go on top of everything, like in the preview.
        captions.draw(canvas, timeMs)
    }

    // Frees all pictures held in memory.
    fun release() {
        cache.values.forEach {
            try {
                it.recycle()
            } catch (e: Exception) {
                // ignore
            }
        }
        cache.clear()
    }

    companion object {
        private const val MAX_CACHED = 4
    }
}
