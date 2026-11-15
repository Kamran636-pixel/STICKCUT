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
        val effectiveNumbers = imageNumbers(images)
        images.forEachIndexed { imageIndex, image ->
            val number = effectiveNumbers[imageIndex]
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

    // ---------- Timestamp-only mode (no script) ----------

    private const val MIN_SCENE_MS = 500L
    private const val FALLBACK_LAST_MS = 3000L
    private const val MAX_TRIM_MS = 1500L

    data class TimestampPlan(
        val ordered: List<ImportedImage>,
        val durationsMs: List<Long>,
        val imagesTotalMs: Long,
        val voiceTotalMs: Long,
        val notes: List<String>,
        val error: String?
    )

    private fun secText(ms: Long): String {
        return "${ms / 1000.0}s"
    }

    // True when EVERY image name carries a start time, like "324.0-326.4_x.jpg".
    fun isTimestampOnly(images: List<ImportedImage>): Boolean {
        if (images.isEmpty()) return false
        return images.all { ImageFilenameParser.parse(it.name).startMs != null }
    }

    // Each image lasts as long as its file name says (end time minus start time).
    // Without an end time it lasts until the next image's start time.
    // The last image without an end time fills the rest of the voiceover.
    // Images are shown one after another from 0, in start-time order.
    fun planTimestampImages(images: List<ImportedImage>, voiceTotalMs: Long): TimestampPlan {
        val parsed = images.map { ImageFilenameParser.parse(it.name) }
        val order = images.indices.sortedWith(Comparator { a, b ->
            val byTime = (parsed[a].startMs ?: 0L).compareTo(parsed[b].startMs ?: 0L)
            if (byTime != 0) byTime else a.compareTo(b)
        })
        val notes = mutableListOf<String>()
        val durations = LongArray(order.size)
        val lastPos = order.size - 1

        for (pos in order.indices) {
            val p = parsed[order[pos]]
            val start = p.startMs ?: 0L
            val end = p.endMs
            var d = when {
                end != null -> end - start
                pos < lastPos -> (parsed[order[pos + 1]].startMs ?: start) - start
                else -> -1L
            }
            if (d == -1L) {
                durations[pos] = -1L
                continue
            }
            if (d < MIN_SCENE_MS) {
                notes.add("${images[order[pos]].name}: the time is shorter than 0.5s, so 0.5s is used.")
                d = MIN_SCENE_MS
            }
            durations[pos] = d
        }

        if (order.isEmpty()) {
            return TimestampPlan(emptyList(), emptyList(), 0L, voiceTotalMs, notes, null)
        }

        if (durations[lastPos] == -1L) {
            var knownSum = 0L
            for (pos in 0 until lastPos) knownSum += durations[pos]
            val rest = voiceTotalMs - knownSum
            if (voiceTotalMs > 0L && rest >= MIN_SCENE_MS) {
                durations[lastPos] = rest
                notes.add("The last image has no end time, so it fills the rest of the voiceover.")
            } else {
                durations[lastPos] = FALLBACK_LAST_MS
                notes.add("The last image has no end time, so ${secText(FALLBACK_LAST_MS)} is used.")
            }
        }

        var total = 0L
        for (d in durations) total += d
        var error: String? = null

        if (voiceTotalMs > 0L) {
            if (total < voiceTotalMs) {
                val extra = voiceTotalMs - total
                durations[lastPos] = durations[lastPos] + extra
                notes.add(
                    "Image times add up to ${secText(total)} but the voiceover is ${secText(voiceTotalMs)}. " +
                        "The last image is extended by ${secText(extra)} so the video ends with the voice."
                )
            } else if (total > voiceTotalMs) {
                val over = total - voiceTotalMs
                if (over > MAX_TRIM_MS || durations[lastPos] - over < MIN_SCENE_MS) {
                    error = "Image times add up to ${secText(total)}, which is longer than the voiceover " +
                        "(${secText(voiceTotalMs)}). Remove some images or add more voiceover."
                } else {
                    durations[lastPos] = durations[lastPos] - over
                    notes.add("The last image is shortened by ${secText(over)} to end with the voiceover.")
                }
            }
        }

        return TimestampPlan(
            ordered = order.map { images[it] },
            durationsMs = durations.toList(),
            imagesTotalMs = total,
            voiceTotalMs = voiceTotalMs,
            notes = notes,
            error = error
        )
    }

    // Builds the scenes without any script: image i lasts as the plan says.
    fun buildFromTimestamps(
        parts: List<VoicePartInfo>,
        images: List<ImportedImage>
    ): SceneBuildResult {
        if (parts.isEmpty()) {
            return SceneBuildResult(emptyList(), listOf("Import at least one voiceover part."), 0L)
        }
        if (images.isEmpty()) {
            return SceneBuildResult(emptyList(), listOf("Import the images."), 0L)
        }
        val voiceTotal = parts.sumOf { it.durationMs }
        val plan = planTimestampImages(images, voiceTotal)
        val planError = plan.error
        if (planError != null) {
            return SceneBuildResult(emptyList(), listOf(planError), voiceTotal)
        }

        val partStart = LongArray(parts.size)
        var running = 0L
        parts.forEach { part ->
            partStart[part.position] = running
            running += part.durationMs
        }

        val scenes = mutableListOf<Scene>()
        var t = 0L
        for ((i, image) in plan.ordered.withIndex()) {
            val start = t
            val end = t + plan.durationsMs[i]
            t = end
            var partPosition = 0
            parts.forEach { part ->
                if (partStart[part.position] <= start) partPosition = part.position
            }
            scenes.add(
                Scene(
                    index = i + 1,
                    imageNumber = (i + 1).toLong(),
                    imageUri = image.uri,
                    imageName = image.name,
                    startMs = start,
                    endMs = end,
                    text = "",
                    partPosition = partPosition
                )
            )
        }
        if (scenes.isNotEmpty() && scenes.last().endMs != voiceTotal) {
            scenes[scenes.size - 1] = scenes.last().copy(endMs = voiceTotal)
        }
        return SceneBuildResult(scenes, emptyList(), voiceTotal)
    }

    // The number each image answers to in the script ("Image N").
    // Timestamp names such as "324.0-326.4_x.jpg" are numbered 1, 2, 3... by start time
    // (equal times keep the import order). Other names keep the number found in the name.
    // The result has the same order and size as the input list.
    fun imageNumbers(images: List<ImportedImage>): List<Long?> {
        val parsed = images.map { ImageFilenameParser.parse(it.name) }
        val stamped = parsed.indices
            .filter { parsed[it].startMs != null }
            .sortedWith(Comparator { a, b ->
                val byTime = (parsed[a].startMs ?: 0L).compareTo(parsed[b].startMs ?: 0L)
                if (byTime != 0) byTime else a.compareTo(b)
            })
        val rankOf = HashMap<Int, Long>()
        stamped.forEachIndexed { rank, imageIndex -> rankOf[imageIndex] = (rank + 1).toLong() }
        return parsed.indices.map { i ->
            if (parsed[i].startMs != null) rankOf[i] else parsed[i].sequenceNumber
        }
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
