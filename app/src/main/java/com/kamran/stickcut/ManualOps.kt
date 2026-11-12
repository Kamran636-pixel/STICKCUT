package com.kamran.stickcut

import android.net.Uri

// Result of one manual edit.
// oldToNew: old scene number -> new scene number, only for scenes that still exist.
data class EditResult(
    val scenes: List<Scene>,
    val oldToNew: Map<Int, Int>,
    val error: String? = null
) {
    val ok: Boolean get() = error == null
}

// Manual editing rules. Scenes always stay back-to-back:
// no gaps, no overlaps, first starts at 0, last ends at the total length.
object ManualOps {

    const val MIN_SCENE_MS = 500L

    private fun partPositionAt(ms: Long, audios: List<ImportedAudio>, fallback: Int): Int {
        if (audios.isEmpty()) return fallback
        var start = 0L
        audios.forEachIndexed { index, audio ->
            val end = start + audio.durationMs
            if (ms < end) return index
            start = end
        }
        return audios.lastIndex
    }

    // Renumbers scenes 1..N and recomputes which voiceover part each one starts in.
    private fun finish(list: List<Scene>, audios: List<ImportedAudio>): List<Scene> {
        return list.mapIndexed { position, scene ->
            scene.copy(
                index = position + 1,
                partPosition = partPositionAt(scene.startMs, audios, scene.partPosition)
            )
        }
    }

    private fun identity(scenes: List<Scene>): Map<Int, Int> {
        val map = mutableMapOf<Int, Int>()
        scenes.forEachIndexed { position, _ -> map[position + 1] = position + 1 }
        return map
    }

    private fun fail(scenes: List<Scene>, message: String): EditResult {
        return EditResult(scenes, identity(scenes), message)
    }

    // Moves the cut between scene at [position] and the next one.
    // Position is 0-based. The new time is kept inside the safe range.
    fun setBoundary(
        scenes: List<Scene>,
        audios: List<ImportedAudio>,
        position: Int,
        newMs: Long
    ): EditResult {
        if (position < 0 || position >= scenes.size - 1) {
            return fail(scenes, "There is no cut at that place.")
        }
        val first = scenes[position]
        val second = scenes[position + 1]
        val low = first.startMs + MIN_SCENE_MS
        val high = second.endMs - MIN_SCENE_MS
        if (low > high) {
            return fail(scenes, "These two scenes are too short to move the cut.")
        }
        val time = newMs.coerceIn(low, high)
        val list = scenes.toMutableList()
        list[position] = first.copy(endMs = time)
        list[position + 1] = second.copy(startMs = time)
        return EditResult(finish(list, audios), identity(scenes))
    }

    // Splits one scene in two at [atMs]. The first half keeps the narration text,
    // the second half starts with empty text.
    fun split(
        scenes: List<Scene>,
        audios: List<ImportedAudio>,
        position: Int,
        atMs: Long
    ): EditResult {
        if (position < 0 || position >= scenes.size) {
            return fail(scenes, "That scene does not exist.")
        }
        val scene = scenes[position]
        if (atMs < scene.startMs + MIN_SCENE_MS || atMs > scene.endMs - MIN_SCENE_MS) {
            return fail(scenes, "Cannot split here. Each half must be at least 0.5 second long.")
        }
        val list = scenes.toMutableList()
        list[position] = scene.copy(endMs = atMs)
        list.add(position + 1, scene.copy(startMs = atMs, text = ""))

        val map = mutableMapOf<Int, Int>()
        scenes.forEachIndexed { old, _ ->
            map[old + 1] = if (old <= position) old + 1 else old + 2
        }
        return EditResult(finish(list, audios), map)
    }

    // Joins the scene at [position] with the next one. The first scene's picture is kept.
    fun merge(
        scenes: List<Scene>,
        audios: List<ImportedAudio>,
        position: Int
    ): EditResult {
        if (position < 0 || position >= scenes.size - 1) {
            return fail(scenes, "There is no next scene to join with.")
        }
        val first = scenes[position]
        val second = scenes[position + 1]
        val text = listOf(first.text, second.text)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val list = scenes.toMutableList()
        list[position] = first.copy(endMs = second.endMs, text = text)
        list.removeAt(position + 1)

        val map = mutableMapOf<Int, Int>()
        scenes.forEachIndexed { old, _ ->
            map[old + 1] = when {
                old < position -> old + 1
                old == position || old == position + 1 -> position + 1
                else -> old
            }
        }
        return EditResult(finish(list, audios), map)
    }

    // Removes a scene. The scene before it (or after it, for the first scene)
    // grows to fill the time, so no gap is left.
    fun delete(
        scenes: List<Scene>,
        audios: List<ImportedAudio>,
        position: Int
    ): EditResult {
        if (scenes.size < 2) {
            return fail(scenes, "At least one scene must remain.")
        }
        if (position < 0 || position >= scenes.size) {
            return fail(scenes, "That scene does not exist.")
        }
        val removed = scenes[position]
        val list = scenes.toMutableList()
        if (position > 0) {
            list[position - 1] = list[position - 1].copy(endMs = removed.endMs)
        } else {
            list[1] = list[1].copy(startMs = removed.startMs)
        }
        list.removeAt(position)

        val map = mutableMapOf<Int, Int>()
        scenes.forEachIndexed { old, _ ->
            if (old < position) {
                map[old + 1] = old + 1
            } else if (old > position) {
                map[old + 1] = old
            }
        }
        return EditResult(finish(list, audios), map)
    }

    fun replaceImage(
        scenes: List<Scene>,
        position: Int,
        imageUri: Uri,
        imageName: String,
        imageNumber: Long
    ): EditResult {
        if (position < 0 || position >= scenes.size) {
            return fail(scenes, "That scene does not exist.")
        }
        val list = scenes.toMutableList()
        list[position] = list[position].copy(
            imageUri = imageUri,
            imageName = imageName,
            imageNumber = imageNumber
        )
        return EditResult(list, identity(scenes))
    }

    // Swaps only the pictures of two scenes. Times and narration stay where they are.
    fun swapImages(scenes: List<Scene>, first: Int, second: Int): EditResult {
        if (first == second || first !in scenes.indices || second !in scenes.indices) {
            return fail(scenes, "Choose two different scenes.")
        }
        val a = scenes[first]
        val b = scenes[second]
        val list = scenes.toMutableList()
        list[first] = a.copy(imageUri = b.imageUri, imageName = b.imageName, imageNumber = b.imageNumber)
        list[second] = b.copy(imageUri = a.imageUri, imageName = a.imageName, imageNumber = a.imageNumber)
        return EditResult(list, identity(scenes))
    }

    // Moves the per-scene animation choices to the new scene numbers after an edit.
    fun remapSettings(settings: AnimationSettings, oldToNew: Map<Int, Int>): AnimationSettings {
        if (settings.perScene.isEmpty()) return settings
        val moved = mutableMapOf<Int, AnimationConfig>()
        settings.perScene.forEach { (oldIndex, config) ->
            val newIndex = oldToNew[oldIndex]
            if (newIndex != null && !moved.containsKey(newIndex)) {
                moved[newIndex] = config
            }
        }
        return settings.copy(perScene = moved)
    }

    // Checks the timeline rules. An empty list means everything is fine.
    fun validate(scenes: List<Scene>, totalMs: Long): List<String> {
        val problems = mutableListOf<String>()
        if (scenes.isEmpty()) {
            problems.add("There are no scenes.")
            return problems
        }
        if (scenes.first().startMs != 0L) {
            problems.add("The first scene must start at 0:00.")
        }
        scenes.forEachIndexed { position, scene ->
            if (scene.durationMs < MIN_SCENE_MS) {
                problems.add("Scene ${position + 1} is shorter than 0.5 second.")
            }
            if (position < scenes.size - 1 && scene.endMs != scenes[position + 1].startMs) {
                problems.add("Scene ${position + 1} and ${position + 2} do not meet exactly.")
            }
        }
        if (scenes.last().endMs != totalMs) {
            problems.add("The last scene must end exactly at the total length.")
        }
        return problems
    }
}
