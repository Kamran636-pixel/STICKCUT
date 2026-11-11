package com.kamran.stickcut

import android.net.Uri

data class Scene(
    val index: Int,
    val imageNumber: Long,
    val imageUri: Uri,
    val imageName: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val partPosition: Int
) {
    val durationMs: Long get() = endMs - startMs
}

data class SceneBuildResult(
    val scenes: List<Scene>,
    val problems: List<String>,
    val totalMs: Long
) {
    val ok: Boolean get() = problems.isEmpty() && scenes.isNotEmpty()
}

object SceneBuilder {

    // Gaps up to this length are closed by extending the previous scene.
    // Bigger gaps are reported so nothing is silently guessed.
    private const val MAX_AUTO_CLOSE_GAP_MS = 1500L

    fun build(
        matches: List<ScriptMatch>,
        parts: List<VoicePartInfo>,
        images: List<ImportedImage>
    ): SceneBuildResult {
        val problems = mutableListOf<String>()

        if (parts.isEmpty()) {
            problems.add("Import at least one voiceover part.")
        }
        if (matches.isEmpty()) {
            problems.add("Import at least one script.")
        }
        if (images.isEmpty()) {
            problems.add("Import the images.")
        }
        if (problems.isNotEmpty()) {
            return SceneBuildResult(emptyList(), problems, 0L)
        }

        // Every voiceover part must have exactly one script.
        parts.forEach { part ->
            val count = matches.count { it.part?.position == part.position }
            if (count == 0) {
                problems.add("Voiceover part ${part.position + 1} (${part.name}) has no script.")
            }
        }

        // Scripts must be matched and free of errors.
        matches.forEach { match ->
            val errors = match.script.parse.errorCount +
                match.issues.count { it.level == IssueLevel.ERROR }
            if (match.part == null) {
                problems.add("Script \"${match.script.name}\" is not matched to a voiceover part.")
            }
            if (errors > 0) {
                problems.add("Script \"${match.script.name}\" has $errors error(s). Fix them first.")
            }
        }

        // Each image number must belong to exactly one imported image.
        val imageByNumber = mutableMapOf<Long, ImportedImage>()
        val duplicateNumbers = mutableSetOf<Long>()
        images.forEach { image ->
            val number = imageNumberOf(image.name)
            if (number != null) {
                if (imageByNumber.containsKey(number)) {
                    duplicateNumbers.add(number)
                } else {
                    imageByNumber[number] = image
                }
            }
        }
        if (duplicateNumbers.isNotEmpty()) {
            problems.add(
                "More than one image has the number ${duplicateNumbers.sorted().joinToString(", ")}. " +
                    "StickCut cannot tell which one to use."
            )
        }

        if (problems.isNotEmpty()) {
            return SceneBuildResult(emptyList(), problems, 0L)
        }

        // Start of every part on the final timeline.
        val partStart = LongArray(parts.size)
        var running = 0L
        parts.forEach { part ->
            partStart[part.position] = running
            running += part.durationMs
        }
        val totalMs = running

        val scenes = mutableListOf<Scene>()
        val orderedMatches = matches
            .filter { it.part != null }
            .sortedBy { it.part!!.position }

        for (match in orderedMatches) {
            val part = match.part ?: continue
            val offset = partStart[part.position]
            val partEnd = offset + part.durationMs
            val entries = match.script.parse.entries

            for (entry in entries) {
                val number = entry.imageNumber
                val start = entry.startMs
                val end = entry.endMs
                if (number == null || start == null || end == null) continue

                val image = imageByNumber[number]
                if (image == null) {
                    problems.add("Image $number from script \"${match.script.name}\" (line ${entry.lineNumber}) was not imported.")
                    continue
                }

                var absStart = offset + start
                var absEnd = offset + end
                if (absEnd > partEnd) absEnd = partEnd
                if (absStart >= absEnd) {
                    problems.add("Image $number (line ${entry.lineNumber} of \"${match.script.name}\") starts after the voiceover part ends.")
                    continue
                }
                if (absStart < offset) absStart = offset

                scenes.add(
                    Scene(
                        index = 0,
                        imageNumber = number,
                        imageUri = image.uri,
                        imageName = image.name,
                        startMs = absStart,
                        endMs = absEnd,
                        text = entry.text,
                        partPosition = part.position
                    )
                )
            }
        }

        if (problems.isNotEmpty()) {
            return SceneBuildResult(emptyList(), problems, totalMs)
        }

        val sorted = scenes.sortedBy { it.startMs }
        val fixed = mutableListOf<Scene>()

        for ((i, scene) in sorted.withIndex()) {
            val previous = fixed.lastOrNull()
            if (previous == null) {
                if (scene.startMs > 0L) {
                    if (scene.startMs <= MAX_AUTO_CLOSE_GAP_MS) {
                        fixed.add(scene.copy(startMs = 0L))
                    } else {
                        problems.add(
                            "The first scene (Image ${scene.imageNumber}) starts at ${ScriptParser.formatTimestamp(scene.startMs)}, " +
                                "leaving an empty start."
                        )
                        fixed.add(scene)
                    }
                } else {
                    fixed.add(scene)
                }
                continue
            }

            if (scene.startMs < previous.endMs) {
                problems.add(
                    "Image ${scene.imageNumber} overlaps Image ${previous.imageNumber} " +
                        "(starts ${ScriptParser.formatTimestamp(scene.startMs)}, previous ends ${ScriptParser.formatTimestamp(previous.endMs)})."
                )
                fixed.add(scene)
                continue
            }

            val gap = scene.startMs - previous.endMs
            if (gap == 0L) {
                fixed.add(scene)
            } else if (gap <= MAX_AUTO_CLOSE_GAP_MS) {
                fixed[fixed.size - 1] = previous.copy(endMs = scene.startMs)
                fixed.add(scene)
            } else {
                problems.add(
                    "Gap of ${ScriptParser.formatTimestamp(gap)} between Image ${previous.imageNumber} and Image ${scene.imageNumber} " +
                        "(at ${ScriptParser.formatTimestamp(previous.endMs)}). Add a scene or fix the times."
                )
                fixed.add(scene)
            }
            if (i == sorted.size - 1) {
                // handled below
            }
        }

        if (problems.isEmpty() && fixed.isNotEmpty()) {
            val last = fixed.last()
            val tail = totalMs - last.endMs
            if (tail > 0L) {
                if (tail <= MAX_AUTO_CLOSE_GAP_MS || true) {
                    fixed[fixed.size - 1] = last.copy(endMs = totalMs)
                }
            }
        }

        if (problems.isNotEmpty()) {
            return SceneBuildResult(emptyList(), problems, totalMs)
        }

        val numbered = fixed.mapIndexed { index, scene -> scene.copy(index = index + 1) }
        return SceneBuildResult(numbered, emptyList(), totalMs)
    }

    private val keywordRegex = Regex(
        "(?i)(?:image|img|scene|slide|pic|photo|frame|shot|page)[\\s_.\\-]*(\\d+)"
    )
    private val copyMarkerRegex = Regex("\\s*\\(\\d+\\)\\s*$")
    private val leadingRegex = Regex("^\\s*(\\d+)")
    private val anyNumberRegex = Regex("\\d+")

    // Same rule used by the importer: a clear number, or nothing. Never a guess.
    fun imageNumberOf(fileName: String): Long? {
        val base = fileName.substringBeforeLast('.').replace(copyMarkerRegex, "")

        val keyword = keywordRegex.find(base)
        if (keyword != null) return keyword.groupValues[1].toLongOrNull()

        val leading = leadingRegex.find(base)
        if (leading != null) return leading.groupValues[1].toLongOrNull()

        val groups = anyNumberRegex.findAll(base).toList()
        if (groups.size == 1) return groups[0].value.toLongOrNull()
        return null
    }
}
