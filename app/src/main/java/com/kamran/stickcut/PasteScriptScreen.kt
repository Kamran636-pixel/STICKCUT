package com.kamran.stickcut

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PasteNavy = Color(0xFF050B1F)
private val PasteBlue = Color(0xFF2D8CFF)
private val PasteCard = Color(0xFF0F2F70)
private val PasteDark = Color(0xFF0A1A44)
private val PasteOk = Color(0xFF6BE3A0)
private val PasteWarn = Color(0xFFFFC857)
private val PasteErr = Color(0xFFFF6B6B)
private val PasteSoft = Color(0xFFB8D4FF)

@Composable
fun PasteScriptScreen(
    parts: List<VoicePartInfo>,
    onSave: (name: String, text: String) -> Unit,
    onClose: () -> Unit
) {
    BackHandler(enabled = true) { onClose() }

    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(if (parts.size == 1) 0 else -1) }
    var message by remember { mutableStateOf<String?>(null) }

    val preview = remember(text) {
        if (text.isBlank()) null else ScriptParser.parse(text)
    }

    fun trySave() {
        if (parts.isEmpty()) {
            message = "Import the voiceover part first, then paste its script."
            return
        }
        if (selected !in parts.indices) {
            message = "Choose which voiceover part this script belongs to."
            return
        }
        if (text.isBlank()) {
            message = "Paste the script text first."
            return
        }
        val part = parts[selected]
        val name = when {
            part.number != null -> "part${part.number}.txt"
            parts.size == 1 -> "pasted_script.txt"
            else -> null
        }
        if (name == null) {
            message = "This voiceover file name has no part number, so StickCut cannot link a pasted script to it. " +
                "Rename the audio like part2.mp3 and import it again."
            return
        }
        onSave(name, text)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PasteNavy)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 90.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "←",
                    color = Color.White,
                    fontSize = 28.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onClose)
                        .padding(8.dp)
                )
                Text(
                    text = "Paste Script",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.size(44.dp))
            }
            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Which voiceover part is this script for?",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(6.dp))

            if (parts.isEmpty()) {
                Text(
                    text = "No voiceover imported yet. Import the voiceover part first.",
                    color = PasteWarn,
                    fontSize = 13.sp
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(parts) { index, part ->
                        val chipShape = RoundedCornerShape(12.dp)
                        val isSelected = index == selected
                        Text(
                            text = "Part ${index + 1}",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .clip(chipShape)
                                .background(if (isSelected) PasteBlue else PasteCard)
                                .border(1.dp, PasteBlue.copy(alpha = 0.7f), chipShape)
                                .clickable {
                                    selected = index
                                    message = null
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
                }
                if (selected in parts.indices) {
                    Text(
                        text = parts[selected].name,
                        color = PasteSoft,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))

            val fieldShape = RoundedCornerShape(14.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(fieldShape)
                    .background(PasteDark)
                    .border(1.dp, PasteBlue.copy(alpha = 0.6f), fieldShape)
                    .padding(12.dp)
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = "Paste your script here.\n\nImage 1\n00:00 - 00:06\nনarration...",
                        color = PasteSoft.copy(alpha = 0.6f),
                        fontSize = 14.sp
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        message = null
                    },
                    textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                    cursorBrush = SolidColor(PasteBlue),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            val result = preview
            if (result != null) {
                val color = when {
                    result.errorCount > 0 -> PasteErr
                    result.warningCount > 0 -> PasteWarn
                    else -> PasteOk
                }
                Text(
                    text = "Found ${result.entries.size} scenes  •  Errors: ${result.errorCount}  •  Warnings: ${result.warningCount}",
                    color = color,
                    fontSize = 13.sp
                )
                result.issues.take(3).forEach { issue ->
                    val prefix = if (issue.lineNumber != null) "Line ${issue.lineNumber}: " else ""
                    Text(
                        text = "${if (issue.level == IssueLevel.ERROR) "✖" else "⚠"} $prefix${issue.message}",
                        color = if (issue.level == IssueLevel.ERROR) PasteErr else PasteWarn,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                if (result.issues.size > 3) {
                    Text(
                        text = "+${result.issues.size - 3} more (shown in full after saving)",
                        color = PasteSoft,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            val messageText = message
            if (messageText != null) {
                Text(
                    text = messageText,
                    color = PasteWarn,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PasteButton(
                    text = "Paste from clipboard",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val clip = clipboard.getText()?.text
                        if (clip.isNullOrBlank()) {
                            message = "The clipboard is empty. Copy your script first."
                        } else {
                            text = clip
                            message = null
                        }
                    }
                )
                PasteButton(
                    text = "Clear",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        text = ""
                        message = null
                    }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            val saveShape = RoundedCornerShape(16.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(saveShape)
                    .background(PasteBlue)
                    .clickable { trySave() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Save script",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        CreatorBrand(modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun PasteButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(shape)
            .background(PasteCard)
            .border(1.dp, PasteBlue.copy(alpha = 0.7f), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
