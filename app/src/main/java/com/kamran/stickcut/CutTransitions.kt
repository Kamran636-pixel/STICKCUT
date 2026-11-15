package com.kamran.stickcut

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.random.Random

// The eight transitions. A transition belongs to the INCOMING scene:
// "how the picture arrives". The first scene never has one.
enum class CutTransitionKind(val id: String, val label: String, val icon: String) {
    CUT("cut", "None", "✂"),
    FADE("fade", "Crossfade", "◐"),
    FADE_BLACK("fadeblack", "Fade to black", "●"),
    WIPE_LEFT("wipeleft", "Wipe left", "◀"),
    WIPE_RIGHT("wiperight", "Wipe right", "▶"),
    SLIDE_LEFT("slideleft", "Slide left", "⇠"),
    SLIDE_RIGHT("slideright", "Slide right", "⇢"),
    CIRCLE_OPEN("circleopen", "Circle open", "◎");

    companion object {
        // Returns null for an unknown id. The caller decides what to do,
        // nothing is guessed silently.
        fun fromId(id: String?): CutTransitionKind? = values().firstOrNull { it.id == id }
    }
}

// Settings for all cuts.
// perCut: key = a stable picture key (see CutTransitionEngine.sceneKey),
// NOT the position, so reordering or deleting scenes keeps the right setting.
data class CutTransitionState(
    val perCut: Map<String, CutTransitionKind> = emptyMap(),
    val durationMs: Long = CutTransitionEngine.DEFAULT_MS,
    val randomMix: Boolean = false,
    val randomPool: Set<CutTransitionKind> = CutTransitionKind.values()
        .filter { it != CutTransitionKind.CUT }
        .toSet()
)

object CutTransitionEngine {

    const val MIN_MS = 150L
    const val MAX_MS = 1000L
    const val STEP_MS = 50L
    const val DEFAULT_MS = 400L

    // A scene may use at most this share of its length for ONE transition,
    // so a short picture is never swallowed by its transitions.
    private const val MAX_SHARE = 0.4f

    // Stable key of a scene: the picture it shows.
    fun sceneKey(imageUri: Any): String = imageUri.toString()

    // What kind arrives at position `index`. The first scene is always CUT.
    fun kindAt(state: CutTransitionState, keys: List<String>, index: Int): CutTransitionKind {
        if (index <= 0 || index >= keys.size) return CutTransitionKind.CUT
        return state.perCut[keys[index]] ?: CutTransitionKind.CUT
    }

    // Duration snapped to 0.05 s steps inside 0.15 .. 1.00 s.
    fun snapDuration(ms: Long): Long {
        val clamped = ms.coerceIn(MIN_MS, MAX_MS)
        val steps = Math.round((clamped - MIN_MS).toDouble() / STEP_MS)
        return (MIN_MS + steps * STEP_MS).coerceIn(MIN_MS, MAX_MS)
    }

    fun withDuration(state: CutTransitionState, ms: Long): CutTransitionState =
        state.copy(durationMs = snapDuration(ms))

    // One cut only. Other cuts and other settings stay untouched.
    // The first scene is ignored (it has no incoming transition).
    fun withOne(
        state: CutTransitionState,
        keys: List<String>,
        index: Int,
        kind: CutTransitionKind
    ): CutTransitionState {
        if (index <= 0 || index >= keys.size) return state
        return state.copy(perCut = state.perCut + (keys[index] to kind))
    }

    // Same kind on every eligible cut (every scene except the first).
    fun withAll(
        state: CutTransitionState,
        keys: List<String>,
        kind: CutTransitionKind
    ): CutTransitionState {
        val updated = state.perCut.toMutableMap()
        for (i in 1 until keys.size) updated[keys[i]] = kind
        return state.copy(perCut = updated)
    }

    // Random mix over the chosen pool.
    // - empty pool: nothing changes
    // - one kind in the pool: that kind everywhere
    // - several kinds: two neighbouring cuts never get the same kind
    // The same seed always gives the same result.
    fun withRandomMix(
        state: CutTransitionState,
        keys: List<String>,
        seed: Long
    ): CutTransitionState {
        val pool = state.randomPool.toList().sortedBy { it.ordinal }
        if (pool.isEmpty() || keys.size < 2) return state
        val random = Random(seed)
        val updated = state.perCut.toMutableMap()
        var previous: CutTransitionKind? = null
        for (i in 1 until keys.size) {
            val choices = if (pool.size > 1 && previous != null) {
                pool.filter { it != previous }
            } else {
                pool
            }
            val pick = choices[random.nextInt(choices.size)]
            updated[keys[i]] = pick
            previous = pick
        }
        return state.copy(perCut = updated)
    }

    // Pool editing for Random Mix.
    fun withPoolToggled(state: CutTransitionState, kind: CutTransitionKind): CutTransitionState {
        val pool = state.randomPool.toMutableSet()
        if (!pool.add(kind)) pool.remove(kind)
        return state.copy(randomPool = pool)
    }

    // The real length of one transition, limited by both neighbouring scenes.
    // CUT always has length 0.
    fun effectiveMs(
        kind: CutTransitionKind,
        wantedMs: Long,
        outgoingSceneMs: Long,
        incomingSceneMs: Long
    ): Long {
        if (kind == CutTransitionKind.CUT) return 0L
        val shortest = minOf(outgoingSceneMs, incomingSceneMs)
        val cap = (shortest * MAX_SHARE).toLong()
        return minOf(snapDuration(wantedMs), cap).coerceAtLeast(0L)
    }
}

// Holds the current settings while the app runs (like AnimationStore).
// Saving into the project file comes in a later step.
object CutTransitionStore {
    var state by mutableStateOf(CutTransitionState())
}
