package com.kamran.stickcut

data class ScriptEntry(
    val imageNumber: Long?,
    val startMs: Long?,
    val endMs: Long?,
    val text: String,
    val lineNumber: Int
)

enum class IssueLevel { ERROR, WARNING }

data class ScriptIssue(
    val level: IssueLevel,
    val lineNumber: Int?,
    val message: String
)

data class ScriptParseResult(
    val entries: List<ScriptEntry>,
    val issues: List<ScriptIssue>
) {
    val errorCount: Int get() = issues.count { it.level == IssueLevel.ERROR }
    val warningCount: Int get() = issues.count { it.level == IssueLevel.WARNING }
    val hasErrors: Boolean get() = errorCount > 0
    val totalEndMs: Long get() = entries.mapNotNull { it.endMs }.maxOrNull() ?: 0L
}

object ScriptParser {

    private const val TIME = "(\\d{1,2}):(\\d{2})(?::(\\d{2}))?(?:[.,](\\d{1,3}))?"
    private const val RANGE_CORE = "$TIME\\s*(?:-->|–|—|-|to)\\s*$TIME"

    private val lineRange = Regex("^[\\[(]?\\s*$RANGE_CORE\\s*[\\])]?:?$", RegexOption.IGNORE_CASE)
    private val inlineRange = Regex(RANGE_CORE, RegexOption.IGNORE_CASE)
    private val headerRegex = Regex(
        "^(?:#+\\s*)?(?:image|img|scene|slide|ছবি)\\s*[:#\\-_.]?\\s*(\\d+)(.*)$",
        RegexOption.IGNORE_CASE
    )

    private class Block(val imageNumber: Long?, val lineNumber: Int) {
        var startMs: Long? = null
        var endMs: Long? = null
        var hasTimeAttempt: Boolean = false
        val text = StringBuilder()
    }

    private fun normalizeDigits(input: String): String {
        val builder = StringBuilder(input.length)
        for (ch in input) {
            if (ch in '০'..'৯') {
                builder.append('0' + (ch - '০'))
            } else {
                builder.append(ch)
            }
        }
        return builder.toString()
    }

    private fun isRealHeader(rest: String): Boolean {
        val trimmed = rest.trim()
        if (trimmed.isEmpty()) return true
        if (trimmed.first() in "|:-–—[(") return true
        return inlineRange.containsMatchIn(rest)
    }

    private fun toMs(a: String, b: String, c: String, fraction: String): Long? {
        val hours: Long
        val minutes: Long
        val seconds: Long
        if (c.isNotEmpty()) {
            hours = a.toLong()
            minutes = b.toLong()
            seconds = c.toLong()
        } else {
            hours = 0L
            minutes = a.toLong()
            seconds = b.toLong()
        }
        if (seconds >= 60L) return null
        if (c.isNotEmpty() && minutes >= 60L) return null
        val millis = if (fraction.isEmpty()) 0L else fraction.padEnd(3, '0').toLong()
        return ((hours * 60L + minutes) * 60L + seconds) * 1000L + millis
    }

    private fun applyRange(
        block: Block,
        match: MatchResult,
        lineNumber: Int,
        issues: MutableList<ScriptIssue>
    ) {
        block.hasTimeAttempt = true
        val g = match.groupValues
        val start = toMs(g[1], g[2], g[3], g[4])
        val end = toMs(g[5], g[6], g[7], g[8])
        if (start == null || end == null) {
            issues.add(
                ScriptIssue(
                    IssueLevel.ERROR,
                    lineNumber,
                    "Invalid time \"${match.value.trim()}\" (seconds and minutes must be below 60)."
                )
            )
            return
        }
        block.startMs = start
        block.endMs = end
    }

    fun formatTimestamp(ms: Long): String {
        val totalSeconds = ms / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        val millis = ms % 1000L
        return "$minutes:${seconds.toString().padStart(2, '0')}.${millis.toString().padStart(3, '0')}"
    }

    fun parse(raw: String): ScriptParseResult {
        val lines = normalizeDigits(raw.removePrefix("\uFEFF")).lines()
        val entries = mutableListOf<ScriptEntry>()
        val issues = mutableListOf<ScriptIssue>()
        var current: Block? = null

        fun flush() {
            val block = current ?: return
            current = null
            val text = block.text.toString().trim()
            val label = if (block.imageNumber != null) "Image ${block.imageNumber}" else "This block"
            if (block.imageNumber == null) {
                issues.add(
                    ScriptIssue(IssueLevel.ERROR, block.lineNumber, "No image number found for this block.")
                )
            }
            if (!block.hasTimeAttempt) {
                issues.add(
                    ScriptIssue(IssueLevel.ERROR, block.lineNumber, "$label has no time (like 00:00 - 00:06).")
                )
            }
            if (text.isEmpty()) {
                issues.add(
                    ScriptIssue(IssueLevel.WARNING, block.lineNumber, "$label has no narration text.")
                )
            }
            entries.add(
                ScriptEntry(
                    imageNumber = block.imageNumber,
                    startMs = block.startMs,
                    endMs = block.endMs,
                    text = text,
                    lineNumber = block.lineNumber
                )
            )
        }

        for ((index, rawLine) in lines.withIndex()) {
            val lineNumber = index + 1
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val header = headerRegex.find(line)
            if (header != null && isRealHeader(header.groupValues[2])) {
                flush()
                val block = Block(header.groupValues[1].toLongOrNull(), lineNumber)
                current = block
                var rest = header.groupValues[2]
                val inline = inlineRange.find(rest)
                if (inline != null) {
                    applyRange(block, inline, lineNumber, issues)
                    rest = rest.removeRange(inline.range)
                }
                val cleaned = rest.trim().trim('|', ':', '-', '–', '—', '[', ']', '(', ')', ' ')
                if (cleaned.isNotEmpty()) {
                    block.text.append(cleaned)
                }
            } else if (lineRange.matches(line)) {
                val match = inlineRange.find(line)
                if (match != null) {
                    val existing = current
                    val block: Block = if (existing == null || existing.startMs != null || existing.hasTimeAttempt) {
                        flush()
                        val fresh = Block(null, lineNumber)
                        current = fresh
                        fresh
                    } else {
                        existing
                    }
                    applyRange(block, match, lineNumber, issues)
                }
            } else {
                val existing = current
                if (existing == null) {
                    issues.add(
                        ScriptIssue(
                            IssueLevel.WARNING,
                            lineNumber,
                            "Ignored text before the first \"Image N\" line."
                        )
                    )
                } else {
                    if (existing.text.isNotEmpty()) existing.text.append(' ')
                    existing.text.append(line)
                }
            }
        }
        flush()

        if (entries.isEmpty()) {
            issues.add(
                ScriptIssue(IssueLevel.ERROR, null, "No scenes found. Each scene needs: Image N, a time, and narration.")
            )
        }

        checkEntries(entries, issues)
        return ScriptParseResult(entries, issues)
    }

    private fun checkEntries(entries: List<ScriptEntry>, issues: MutableList<ScriptIssue>) {
        val byImage = entries.filter { it.imageNumber != null }.groupBy { it.imageNumber }
        for ((number, group) in byImage) {
            if (group.size > 1) {
                val lines = group.joinToString(", ") { it.lineNumber.toString() }
                issues.add(
                    ScriptIssue(
                        IssueLevel.ERROR,
                        group[1].lineNumber,
                        "Image $number appears more than once (lines $lines)."
                    )
                )
            }
        }

        var previous: ScriptEntry? = null
        for (entry in entries) {
            val start = entry.startMs
            val end = entry.endMs
            if (start == null || end == null) continue

            if (end <= start) {
                issues.add(
                    ScriptIssue(
                        IssueLevel.ERROR,
                        entry.lineNumber,
                        "End time ${formatTimestamp(end)} is not after start time ${formatTimestamp(start)}."
                    )
                )
            }

            val prev = previous
            if (prev == null) {
                if (start != 0L) {
                    issues.add(
                        ScriptIssue(
                            IssueLevel.WARNING,
                            entry.lineNumber,
                            "The first scene starts at ${formatTimestamp(start)}, not at 0:00.000."
                        )
                    )
                }
            } else {
                val prevEnd = prev.endMs
                if (prevEnd != null) {
                    if (start < prevEnd) {
                        issues.add(
                            ScriptIssue(
                                IssueLevel.ERROR,
                                entry.lineNumber,
                                "Time overlaps the previous scene (starts ${formatTimestamp(start)}, previous ends ${formatTimestamp(prevEnd)})."
                            )
                        )
                    } else if (start > prevEnd) {
                        issues.add(
                            ScriptIssue(
                                IssueLevel.WARNING,
                                entry.lineNumber,
                                "Gap of ${formatTimestamp(start - prevEnd)} before this scene (previous ends ${formatTimestamp(prevEnd)})."
                            )
                        )
                    }
                }
            }
            previous = entry
        }
    }

    // Compares one script with the images and the voiceover part it belongs to.
    fun crossCheck(
        entries: List<ScriptEntry>,
        availableImageNumbers: Set<Long>,
        audioDurationMs: Long?
    ): List<ScriptIssue> {
        val issues = mutableListOf<ScriptIssue>()

        for (entry in entries) {
            val number = entry.imageNumber ?: continue
            if (number !in availableImageNumbers) {
                issues.add(
                    ScriptIssue(
                        IssueLevel.ERROR,
                        entry.lineNumber,
                        "Image $number is in the script but no imported image has that number."
                    )
                )
            }
        }

        if (audioDurationMs != null) {
            val scriptEnd = entries.mapNotNull { it.endMs }.maxOrNull()
            if (scriptEnd != null) {
                val tolerance = 500L
                if (scriptEnd > audioDurationMs + tolerance) {
                    issues.add(
                        ScriptIssue(
                            IssueLevel.ERROR,
                            null,
                            "The script ends at ${formatTimestamp(scriptEnd)} but the voiceover is only ${formatTimestamp(audioDurationMs)} long."
                        )
                    )
                } else if (scriptEnd < audioDurationMs - tolerance) {
                    issues.add(
                        ScriptIssue(
                            IssueLevel.WARNING,
                            null,
                            "The voiceover continues for ${formatTimestamp(audioDurationMs - scriptEnd)} after the last scene ends (${formatTimestamp(scriptEnd)})."
                        )
                    )
                }
            }
        }
        return issues
    }
}
