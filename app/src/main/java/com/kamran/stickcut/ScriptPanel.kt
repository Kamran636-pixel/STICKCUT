package com.kamran.stickcut

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.ByteArrayOutputStream

private val PanelBlue = Color(0xFF2D8CFF)
private val PanelCard = Color(0xFF0F2F70)
private val PanelDark = Color(0xFF0A1A44)
private val PanelNavy = Color(0xFF050B1F)
private val PanelWarn = Color(0xFFFFC857)
private val PanelOk = Color(0xFF6BE3A0)
private val PanelErr = Color(0xFFFF6B6B)
private val PanelSoft = Color(0xFFB8D4FF)

private const val MAX_SCRIPT_BYTES = 2_000_000

data class LoadedScript(
    val uri: Uri,
    val name: String,
    val parse: ScriptParseResult,
    val encodingSuspect: Boolean
)

data class VoicePartInfo(
    val position: Int,
    val number: Long?,
    val name: String,
    val durationMs: Long
)

data class ScriptMatch(
    val script: LoadedScript,
    val part: VoicePartInfo?,
    val issues: List<ScriptIssue>
)

private val scriptKeywordRegex =
    Regex("(?i)(?:part|par|script|audio|voice|track|scene)[\\s_.\\-]*(\\d+)")
private val scriptLeadingRegex = Regex("^\\s*(\\d+)")
private val scriptAnyNumberRegex = Regex("\\d+")

// Finds the part number in a file name like "part1.txt" or "script 2.txt".
// Returns null when there is no clear number. It never guesses.
fun scriptNumberOf(fileName: String): Long? {
    val base = fileName.substringBeforeLast('.')

    val keyword = scriptKeywordRegex.find(base)
    if (keyword != null) return keyword.groupValues[1].toLongOrNull()

    val leading = scriptLeadingRegex.find(base)
    if (leading != null) return leading.groupValues[1].toLongOrNull()

    val groups = scriptAnyNumberRegex.findAll(base).toList()
    if (groups.size == 1) return groups[0].value.toLongOrNull()
    return null
}

// Reads a text file chosen by the user. Returns null if it cannot be read
// or is too large to be a script.
fun readScriptText(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_SCRIPT_BYTES) return@use null
                out.write(buffer, 0, read)
            }
            String(out.toByteArray(), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        null
    }
}

fun loadScript(name: String, uri: Uri, text: String): LoadedScript {
    return LoadedScript(
        uri = uri,
        name = name,
        parse = ScriptParser.parse(text),
        encodingSuspect = text.contains('\uFFFD')
    )
}

// Matches every script with the voiceover part it belongs to and checks it
// against the imported images and the part's audio length.
fun matchScripts(
    scripts: List<LoadedScript>,
    parts: List<VoicePartInfo>,
    imageNumbers: Set<Long>
): List<ScriptMatch> {
    val claimed = mutableMapOf<Int, String>()

    return scripts.map { script ->
        val problems = mutableListOf<ScriptIssue>()
        var part: VoicePartInfo? = null
        val number = scriptNumberOf(script.name)

        if (parts.isEmpty()) {
            problems.add(
                ScriptIssue(IssueLevel.ERROR, null, "Import the voiceover part for this script first.")
            )
        } else if (parts.size == 1 && scripts.size == 1) {
            part = parts[0]
        } else if (number == null) {
            problems.add(
                ScriptIssue(
                    IssueLevel.ERROR,
                    null,
                    "The file name has no part number. Rename it like part1.txt so StickCut knows which voiceover part it belongs to."
                )
            )
        } else {
            val found = parts.filter { it.number == number }
            if (found.isEmpty()) {
                problems.add(
                    ScriptIssue(IssueLevel.ERROR, null, "No voiceover part has the number $number.")
                )
            } else if (found.size > 1) {
                problems.add(
                    ScriptIssue(
                        IssueLevel.ERROR,
                        null,
                        "More than one voiceover part has the number $number, so StickCut cannot tell which one this script belongs to."
                    )
                )
            } else {
                part = found[0]
            }
        }

        val matched = part
        if (matched != null) {
            val previous = claimed[matched.position]
            if (previous != null) {
                problems.add(
                    ScriptIssue(
                        IssueLevel.ERROR,
                        null,
                        "Voiceover part ${matched.position + 1} already uses the script \"$previous\"."
                    )
                )
            } else {
                claimed[matched.position] = script.name
            }
        }

        if (script.encodingSuspect) {
            problems.add(
                ScriptIssue(
                    IssueLevel.WARNING,
                    null,
                    "Some characters could not be read. Save the file as UTF-8 text and import it again."
                )
            )
        }

        problems.addAll(
            ScriptParser.crossCheck(
                entries = script.parse.entries,
                availableImageNumbers = imageNumbers,
                audioDurationMs = matched?.durationMs
            )
        )

        ScriptMatch(script, matched, problems)
    }
}

@Composable
fun ScriptPanel(
    matches: List<ScriptMatch>,
    parts: List<VoicePartInfo>,
    onRemove: (Int) -> Unit,
    onClear: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    val totalScenes = matches.sumOf { it.script.parse.entries.size }
    val errors = matches.sumOf { m ->
        m.script.parse.errorCount + m.issues.count { it.level == IssueLevel.ERROR }
    }
    val warnings = matches.sumOf { m ->
        m.script.parse.warningCount + m.issues.count { it.level == IssueLevel.WARNING }
    }
    val partsWithoutScript = parts.filter { part ->
        matches.none { it.part?.position == part.position }
    }
    val ready = matches.isNotEmpty() && errors == 0 && partsWithoutScript.isEmpty()
    val borderColor = if (errors > 0) PanelErr else if (ready) PanelOk else PanelWarn

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PanelDark)
            .border(1.dp, borderColor.copy(alpha = 0.7f), shape)
            .padding(12.dp)
    ) {
        Text(
            text = "Scripts: ${matches.size}  •  Scenes: $totalScenes",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Errors: $errors  •  Warnings: $warnings",
            color = if (errors > 0) PanelErr else PanelSoft,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp)
        )

        partsWithoutScript.forEach { part ->
            Text(
                text = "⚠ Voiceover part ${part.position + 1} (${part.name}) has no script yet.",
                color = PanelWarn,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        if (ready) {
            Text(
                text = "✓ Ready for Auto Edit",
                color = PanelOk,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        matches.forEachIndexed { index, match ->
            ScriptCard(match = match, onRemove = { onRemove(index) })
            Spacer(modifier = Modifier.height(8.dp))
        }

        PanelButton(text = "Clear all scripts", onClick = onClear)
    }
}

@Composable
private fun ScriptCard(match: ScriptMatch, onRemove: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val parse = match.script.parse

    val allIssues = (parse.issues + match.issues).sortedWith(
        compareBy<ScriptIssue>({ it.level != IssueLevel.ERROR }, { it.lineNumber ?: 0 })
    )
    val shownIssues = if (expanded) allIssues else allIssues.take(8)
    val shownScenes = if (expanded) parse.entries.take(20) else parse.entries.take(3)
    val errorTotal = allIssues.count { it.level == IssueLevel.ERROR }
    val cardBorder = if (errorTotal > 0) PanelErr else if (allIssues.isEmpty()) PanelOk else PanelWarn

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PanelNavy)
            .border(1.dp, cardBorder.copy(alpha = 0.5f), shape)
            .padding(10.dp)
    ) {
        Text(
            text = match.script.name,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        val part = match.part
        Text(
            text = if (part != null) {
                "→ Voiceover part ${part.position + 1}: ${part.name}"
            } else {
                "→ No voiceover part matched"
            },
            color = if (part != null) PanelSoft else PanelWarn,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )

        Text(
            text = "${parse.entries.size} scenes  •  ends at ${ScriptParser.formatTimestamp(parse.totalEndMs)}",
            color = PanelSoft,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp)
        )

        if (allIssues.isEmpty()) {
            Text(
                text = "✓ Script is clean",
                color = PanelOk,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        shownIssues.forEach { issue ->
            val isError = issue.level == IssueLevel.ERROR
            val prefix = if (issue.lineNumber != null) "Line ${issue.lineNumber}: " else ""
            Text(
                text = "${if (isError) "✖" else "⚠"} $prefix${issue.message}",
                color = if (isError) PanelErr else PanelWarn,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 3.dp)
            )
        }

        if (shownScenes.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            shownScenes.forEach { entry ->
                val start = entry.startMs?.let { ScriptParser.formatTimestamp(it) } ?: "?"
                val end = entry.endMs?.let { ScriptParser.formatTimestamp(it) } ?: "?"
                Text(
                    text = "Image ${entry.imageNumber ?: "?"}  •  $start → $end\n${entry.text}",
                    color = Color.White,
                    fontSize = 12.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }

        val hiddenIssues = allIssues.size - shownIssues.size
        val hiddenScenes = parse.entries.size - shownScenes.size
        if (hiddenIssues > 0 || hiddenScenes > 0 || expanded) {
            Text(
                text = if (expanded) "Show less" else "Show all (+$hiddenIssues issues, +$hiddenScenes scenes)",
                color = PanelBlue,
                fontSize = 13.sp,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp, horizontal = 2.dp)
            )
        }

        Text(
            text = "Remove this script",
            color = PanelWarn,
            fontSize = 13.sp,
            modifier = Modifier
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onRemove)
                .padding(vertical = 4.dp, horizontal = 2.dp)
        )
    }
}

@Composable
private fun PanelButton(text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .background(PanelCard)
            .border(1.dp, PanelBlue.copy(alpha = 0.7f), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
