package com.kamran.stickcut

import android.graphics.Matrix
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbMatrix
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects

// Builds the video that gets exported.
// Every picture is shown for exactly its scene time and moves with the same
// animation maths as the preview. The voiceover parts play back to back.
object ExportComposer {

    // Colour matrix that scales red, green and blue by [factor] (0 = black, 1 = normal).
    private fun brightnessMatrix(factor: Float): FloatArray {
        val f = factor.coerceIn(0f, 1f)
        return floatArrayOf(
            f, 0f, 0f, 0f,
            0f, f, 0f, 0f,
            0f, 0f, f, 0f,
            0f, 0f, 0f, 1f
        )
    }

    // Text shown on the export screen about what the exported transition will look like.
    fun transitionNote(settings: AnimationSettings): String {
        return when (settings.transition.type) {
            TransitionType.NONE -> "Transition: none (direct cut)"
            TransitionType.FADE -> "Transition: Fade through black (${settings.transition.durationMs} ms)"
            else -> "Transition: ${settings.transition.type.label} is shown in the preview, " +
                "but the exported video uses Fade for now."
        }
    }

    @OptIn(UnstableApi::class)
    fun build(
        scenes: List<Scene>,
        audios: List<ImportedAudio>,
        settings: AnimationSettings,
        width: Int,
        height: Int,
        fps: Int
    ): Composition {
        val presentation = Presentation.createForWidthAndHeight(
            width,
            height,
            Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
        )
        val planned = AnimationEngine.plan(scenes, settings)
        val useTransition = settings.transition.type != TransitionType.NONE

        val imageItems = scenes.mapIndexed { position, scene ->
            val mediaItem = MediaItem.Builder()
                .setUri(scene.imageUri)
                .setImageDurationMs(scene.durationMs)
                .build()

            val durationMs = scene.durationMs
            val effects = mutableListOf<Effect>(presentation)

            // 1) Picture animation (zoom / pan).
            val plan = planned.getOrNull(position)
            if (plan != null && plan.config.type != AnimationType.NONE) {
                effects.add(
                    MatrixTransformation { presentationTimeUs ->
                        val localMs = (presentationTimeUs / 1000L).coerceIn(0L, durationMs)
                        val t = AnimationEngine.transformAtTime(plan.config, plan, localMs)
                        val matrix = Matrix()
                        // Picture zooms around its centre, then moves.
                        // The screen is 2 units wide and tall (-1..1), y points up.
                        matrix.postScale(t.scale, t.scale)
                        matrix.postTranslate(t.offsetX * 2f, -t.offsetY * 2f)
                        matrix
                    }
                )
            }

            // 2) Fade through black. The cut sits in the middle of the transition,
            // so this scene fades in during the first half of the transition and the
            // previous / next scene fades during the other half.
            val inHalfMs = if (useTransition && position > 0) {
                (planned.getOrNull(position)?.transitionInMs ?: 0L) / 2L
            } else {
                0L
            }
            val outHalfMs = if (useTransition && position < scenes.size - 1) {
                (planned.getOrNull(position + 1)?.transitionInMs ?: 0L) / 2L
            } else {
                0L
            }
            if (inHalfMs > 0L || outHalfMs > 0L) {
                effects.add(
                    RgbMatrix { presentationTimeUs, _ ->
                        val localMs = (presentationTimeUs / 1000L).coerceIn(0L, durationMs)
                        var factor = 1f
                        if (inHalfMs > 0L && localMs < inHalfMs) {
                            factor *= localMs.toFloat() / inHalfMs.toFloat()
                        }
                        if (outHalfMs > 0L && localMs > durationMs - outHalfMs) {
                            factor *= (durationMs - localMs).toFloat() / outHalfMs.toFloat()
                        }
                        brightnessMatrix(factor)
                    }
                )
            }

            EditedMediaItem.Builder(mediaItem)
                .setFrameRate(fps)
                .setEffects(Effects(emptyList(), effects))
                .build()
        }

        val sequences = mutableListOf(EditedMediaItemSequence(imageItems))
        if (audios.isNotEmpty()) {
            val audioItems = audios.map { audio ->
                EditedMediaItem.Builder(MediaItem.fromUri(audio.uri)).build()
            }
            sequences.add(EditedMediaItemSequence(audioItems))
        }
        return Composition.Builder(sequences).build()
    }
}
