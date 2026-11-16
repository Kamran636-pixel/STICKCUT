package com.kamran.stickcut

import kotlin.random.Random

enum class AnimationType(val label: String) {
    ZOOM_IN("Zoom In"),
    ZOOM_OUT("Zoom Out"),
    SLOW_ZOOM_IN("Slow Zoom In"),
    SLOW_ZOOM_OUT("Slow Zoom Out"),
    PAN_LEFT("Pan Left"),
    PAN_RIGHT("Pan Right"),
    PAN_UP("Pan Up"),
    PAN_DOWN("Pan Down"),
    KEN_BURNS("Ken Burns"),
    NONE("No Animation")
}

enum class SpeedPreset(val label: String, val factor: Float) {
    SLOW("Slow", 0.5f),
    NORMAL("Normal", 1.0f),
    FAST("Fast", 2.0f)
}

enum class AnimationMode(val label: String) {
    GLOBAL("Global"),
    PER_SCENE("Per Scene"),
    RANDOM("Random")
}

enum class TransitionType(val label: String) {
    FADE("Fade"),
    CROSS_DISSOLVE("Cross Dissolve"),
    SLIDE_LEFT("Slide Left"),
    SLIDE_RIGHT("Slide Right"),
    ZOOM("Zoom Transition"),
    NONE("No Transition")
}

data class AnimationConfig(
    val type: AnimationType = AnimationType.KEN_BURNS,
    // By default the movement lasts as long as the picture is on screen
    // (up to 10 seconds). A shorter value makes it stop earlier.
    val durationMs: Long = 10_000L,
    val speed: SpeedPreset = SpeedPreset.NORMAL,
    val intensity: Float = 1.0f
) {
    // Keeps every value inside its allowed range.
    fun normalized(): AnimationConfig = copy(
        durationMs = durationMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS),
        intensity = intensity.coerceIn(MIN_INTENSITY, MAX_INTENSITY)
    )

    companion object {
        const val MIN_DURATION_MS = 500L
        const val MAX_DURATION_MS = 10_000L
        // 0.1 means a 1.5% zoom, so a 2% depth is possible.
        const val MIN_INTENSITY = 0.1f
        const val MAX_INTENSITY = 2.0f
    }
}

data class TransitionConfig(
    val type: TransitionType = TransitionType.FADE,
    val durationMs: Long = 500L
) {
    fun normalized(): TransitionConfig = copy(
        durationMs = durationMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
    )

    companion object {
        const val MIN_DURATION_MS = 100L
        const val MAX_DURATION_MS = 2000L
    }
}

data class AnimationSettings(
    val mode: AnimationMode = AnimationMode.GLOBAL,
    val global: AnimationConfig = AnimationConfig(),
    // Key = scene index (the number shown in the Scene List).
    val perScene: Map<Int, AnimationConfig> = emptyMap(),
    val transition: TransitionConfig = TransitionConfig(),
    val randomSeed: Long = 1L,
    val randomPool: Set<AnimationType> = AnimationType.values()
        .filter { it != AnimationType.NONE }
        .toSet()
)

// How the picture is placed at one moment.
// scale >= 1 always. offsetX and offsetY are fractions of the frame
// width and height (positive = picture moves right / down).
data class AnimTransform(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float
) {
    companion object {
        val IDENTITY = AnimTransform(1f, 0f, 0f)
    }
}

data class PlannedScene(
    val sceneIndex: Int,
    val config: AnimationConfig,
    // How long the movement really lasts inside this scene.
    // After this time the picture holds still on its last position.
    val activeMs: Long,
    // Length of the transition that blends this scene with the previous one
    // (0 for the first scene or when transitions are off).
    val transitionInMs: Long
)

object AnimationEngine {

    private const val BASE_ZOOM = 0.15f
    private const val PAN_MARGIN = 0.12f
    private const val SLOW_FACTOR = 0.5f
    private const val MAX_TRANSITION_SHARE = 0.8f

    // The movement runs at an even speed from the very first frame,
    // so it is visible at once and never "starts late".
    private fun linear(progress: Float): Float = progress.coerceIn(0f, 1f)

    // The core calculation, shared by the preview and the exporter.
    // progress: 0 = start of the movement, 1 = end of the movement.
    // Zoom In, Zoom Out and Ken Burns use the picture's real size (scale 1.0)
    // at one end. Pans keep the picture a little larger than the frame
    // because they need room to slide. No edge and no black area ever shows.
    fun transformAt(config: AnimationConfig, progress: Float): AnimTransform {
        val c = config.normalized()
        val e = linear(progress)
        val slow = c.type == AnimationType.SLOW_ZOOM_IN || c.type == AnimationType.SLOW_ZOOM_OUT
        val amount = BASE_ZOOM * c.intensity * (if (slow) SLOW_FACTOR else 1f)
        val margin = PAN_MARGIN * c.intensity
        val maxShift = margin / 2f
        val sweep = 1f - 2f * e

        return when (c.type) {
            AnimationType.ZOOM_IN, AnimationType.SLOW_ZOOM_IN ->
                AnimTransform(1f + amount * e, 0f, 0f)

            AnimationType.ZOOM_OUT, AnimationType.SLOW_ZOOM_OUT ->
                AnimTransform(1f + amount * (1f - e), 0f, 0f)

            AnimationType.PAN_LEFT ->
                AnimTransform(1f + margin, maxShift * sweep, 0f)

            AnimationType.PAN_RIGHT ->
                AnimTransform(1f + margin, -maxShift * sweep, 0f)

            AnimationType.PAN_UP ->
                AnimTransform(1f + margin, 0f, maxShift * sweep)

            AnimationType.PAN_DOWN ->
                AnimTransform(1f + margin, 0f, -maxShift * sweep)

            // Starts at the picture's real size, grows slowly and drifts a little.
            // The drift is always smaller than the room the zoom creates,
            // so no edge can show.
            AnimationType.KEN_BURNS -> {
                val scale = 1f + amount * e
                val room = (scale - 1f) / 2f
                AnimTransform(scale, room * 0.5f, room * 0.25f)
            }

            AnimationType.NONE -> AnimTransform.IDENTITY
        }
    }

    // Picture placement at a time (ms) inside a scene.
    fun transformAtTime(config: AnimationConfig, planned: PlannedScene, timeInSceneMs: Long): AnimTransform {
        if (config.type == AnimationType.NONE || planned.activeMs <= 0L) {
            return transformAt(config, 1f)
        }
        val progress = (timeInSceneMs.toFloat() / planned.activeMs.toFloat()).coerceIn(0f, 1f)
        return transformAt(config, progress)
    }

    // Transition length that is safe between two neighbouring scenes.
    fun safeTransitionMs(requestedMs: Long, previousSceneMs: Long, nextSceneMs: Long): Long {
        if (requestedMs <= 0L) return 0L
        val limit = (minOf(previousSceneMs, nextSceneMs) * MAX_TRANSITION_SHARE).toLong()
        return minOf(requestedMs, limit).coerceAtLeast(0L)
    }

    private fun configFor(
        sceneIndex: Int,
        settings: AnimationSettings,
        randomPick: AnimationType?
    ): AnimationConfig {
        return when (settings.mode) {
            AnimationMode.GLOBAL -> settings.global
            AnimationMode.PER_SCENE -> settings.perScene[sceneIndex] ?: settings.global
            AnimationMode.RANDOM -> settings.global.copy(type = randomPick ?: settings.global.type)
        }.normalized()
    }

    // Builds the movement plan for every scene.
    fun plan(scenes: List<Scene>, settings: AnimationSettings): List<PlannedScene> {
        val random = Random(settings.randomSeed)
        val pool = settings.randomPool
            .filter { it != AnimationType.NONE }
            .sortedBy { it.ordinal }
        var previousPick: AnimationType? = null

        val transition = settings.transition.normalized()
        val planned = mutableListOf<PlannedScene>()

        scenes.forEachIndexed { position, scene ->
            var pick: AnimationType? = null
            if (settings.mode == AnimationMode.RANDOM && pool.isNotEmpty()) {
                val choices = if (pool.size > 1) pool.filter { it != previousPick } else pool
                pick = choices[random.nextInt(choices.size)]
                previousPick = pick
            }

            val config = configFor(scene.index, settings, pick)
            val speedFactor = config.speed.factor
            val wanted = (config.durationMs.toFloat() / speedFactor).toLong()
            val active = if (config.type == AnimationType.NONE) {
                0L
            } else {
                minOf(wanted, scene.durationMs).coerceAtLeast(0L)
            }

            val transitionIn = if (position == 0 || transition.type == TransitionType.NONE) {
                0L
            } else {
                safeTransitionMs(
                    transition.durationMs,
                    scenes[position - 1].durationMs,
                    scene.durationMs
                )
            }

            planned.add(
                PlannedScene(
                    sceneIndex = scene.index,
                    config = config,
                    activeMs = active,
                    transitionInMs = transitionIn
                )
            )
        }
        return planned
    }
}
