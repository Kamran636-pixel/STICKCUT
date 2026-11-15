package com.kamran.stickcut

enum class FilenameKind {
    TIMESTAMP_RANGE,
    DECIMAL_TIMESTAMP,
    SEQUENCE_NUMBER,
    UNKNOWN
}

data class ParsedImageFilename(
    val original: String,
    val startMs: Long?,
    val endMs: Long?,
    val sequenceNumber: Long?,
    val kind: FilenameKind,
    val warning: String?
) {
    val hasTimestamp: Boolean get() = startMs != null
}

object ImageFilenameParser {

    // Anything above 100 hours is treated as "not a timestamp". This also prevents overflow.
    private const val MAX_SECONDS = 360_000L

    private val supportedExtensions = setOf("jpg", "jpeg", "png", "webp", "bmp", "avif")

    // "324.0-326.4_hash": the FIRST number is the start, the second is only a hint.
    private val rangeRegex = Regex(
        "^\\s*(\\d{1,7}(?:\\.\\d{1,3})?)\\s*-\\s*(\\d{1,7}(?:\\.\\d{1,3})?)(?=\\z|[_\\s(\\-])"
    )
    // "324.5"
    private val decimalRegex = Regex("^\\s*(\\d{1,7}\\.\\d{1,3})(?=\\z|[_\\s(\\-])")
    // "001"
    private val integerRegex = Regex("^\\s*(\\d{1,15})\\s*$")
    // "image_001", "scene 12"
    private val keywordRegex = Regex(
        "(?i)(?:image|img|scene|slide|pic|photo|frame|shot|page)[\\s_.\\-]*(\\d{1,15})(?!\\d)"
    )
    private val leadingRegex = Regex("^\\s*(\\d{1,15})(?!\\d)")
    private val anyNumberRegex = Regex("\\d+")
    private val tokenRegex = Regex("[A-Za-z0-9]+")
    private val copyMarkerRegex = Regex("\\s*\\(\\d+\\)\\s*$")

    // Turns "324.5" into 324500. Returns null when the text is not a safe number.
    private fun secondsToMs(text: String): Long? {
        val parts = text.split('.')
        if (parts.isEmpty() || parts.size > 2) return null
        val whole = parts[0].toLongOrNull() ?: return null
        if (whole < 0L || whole > MAX_SECONDS) return null
        var fraction = 0L
        if (parts.size == 2) {
            val digits = parts[1]
            if (digits.isEmpty() || digits.length > 3) return null
            fraction = digits.padEnd(3, '0').toLongOrNull() ?: return null
        }
        return whole * 1000L + fraction
    }

    // Letters mixed with digits in a long word, like "cd218568", look like a random hash.
    private fun looksLikeHash(token: String): Boolean {
        return token.length >= 6 && token.any { it.isLetter() }
    }

    private fun sequenceOf(base: String): Long? {
        val integer = integerRegex.find(base)
        if (integer != null) return integer.groupValues[1].toLongOrNull()

        val keyword = keywordRegex.find(base)
        if (keyword != null) return keyword.groupValues[1].toLongOrNull()

        val leading = leadingRegex.find(base)
        if (leading != null) return leading.groupValues[1].toLongOrNull()

        val groups = anyNumberRegex.findAll(base).toList()
        if (groups.size == 1) {
            val group = groups[0]
            val position = group.range.first
            val token = tokenRegex.findAll(base)
                .firstOrNull { position >= it.range.first && position <= it.range.last }
                ?.value ?: ""
            if (!looksLikeHash(token) && group.value.length <= 15) {
                return group.value.toLongOrNull()
            }
        }
        return null
    }

    // Never throws. A name it cannot understand becomes UNKNOWN.
    fun parse(fileName: String): ParsedImageFilename {
        val dot = fileName.lastIndexOf('.')
        val extension = if (dot >= 0) fileName.substring(dot + 1).lowercase() else ""
        val withoutExtension = if (extension in supportedExtensions) {
            fileName.substring(0, dot)
        } else {
            fileName
        }
        val base = withoutExtension.replace(copyMarkerRegex, "")
        var warning: String? = null

        val range = rangeRegex.find(base)
        if (range != null) {
            val start = secondsToMs(range.groupValues[1])
            val end = secondsToMs(range.groupValues[2])
            if (start != null) {
                if (end != null && end > start) {
                    return ParsedImageFilename(
                        fileName, start, end, null, FilenameKind.TIMESTAMP_RANGE, null
                    )
                }
                return ParsedImageFilename(
                    fileName, start, null, null, FilenameKind.TIMESTAMP_RANGE,
                    "The end time is not after the start time, so it was ignored."
                )
            }
            warning = "The time in this name is larger than 100 hours."
        }

        val decimal = decimalRegex.find(base)
        if (decimal != null) {
            val start = secondsToMs(decimal.groupValues[1])
            if (start != null) {
                return ParsedImageFilename(
                    fileName, start, null, null, FilenameKind.DECIMAL_TIMESTAMP, warning
                )
            }
        }

        val sequence = sequenceOf(base)
        if (sequence != null) {
            return ParsedImageFilename(
                fileName, null, null, sequence, FilenameKind.SEQUENCE_NUMBER, warning
            )
        }

        return ParsedImageFilename(
            fileName, null, null, null, FilenameKind.UNKNOWN,
            warning ?: "No number or time could be found in this name."
        )
    }

    private fun splitParts(text: String): List<String> {
        return Regex("\\d+|\\D+").findAll(text.lowercase()).map { it.value }.toList()
    }

    // "2" comes before "10" (numbers are compared as numbers, not letters).
    fun naturalCompare(a: String, b: String): Int {
        val partsA = splitParts(a)
        val partsB = splitParts(b)
        val count = minOf(partsA.size, partsB.size)
        for (i in 0 until count) {
            val x = partsA[i]
            val y = partsB[i]
            val result = if (x.first().isDigit() && y.first().isDigit()) {
                val nx = x.trimStart('0')
                val ny = y.trimStart('0')
                if (nx.length != ny.length) nx.length.compareTo(ny.length) else nx.compareTo(ny)
            } else {
                x.compareTo(y)
            }
            if (result != 0) return result
        }
        return partsA.size.compareTo(partsB.size)
    }

    // Order: timestamp names first (by start time), then numbered names, then unknown names.
    private fun rank(parsed: ParsedImageFilename): Int {
        return when {
            parsed.startMs != null -> 0
            parsed.sequenceNumber != null -> 1
            else -> 2
        }
    }

    fun compare(a: ParsedImageFilename, b: ParsedImageFilename): Int {
        val rankA = rank(a)
        val rankB = rank(b)
        if (rankA != rankB) return rankA.compareTo(rankB)
        return when (rankA) {
            0 -> (a.startMs ?: 0L).compareTo(b.startMs ?: 0L)
            1 -> {
                val numberA = a.sequenceNumber ?: 0L
                val numberB = b.sequenceNumber ?: 0L
                if (numberA != numberB) numberA.compareTo(numberB) else naturalCompare(a.original, b.original)
            }
            else -> naturalCompare(a.original, b.original)
        }
    }

    // Stable sort: equal timestamps keep the order they were imported in.
    // Nothing is removed. Unknown names are placed at the end.
    fun <T> sortStable(items: List<T>, nameOf: (T) -> String): List<T> {
        val parsed = items.map { Pair(it, parse(nameOf(it))) }
        return parsed
            .sortedWith(Comparator { x, y -> compare(x.second, y.second) })
            .map { it.first }
    }
}
