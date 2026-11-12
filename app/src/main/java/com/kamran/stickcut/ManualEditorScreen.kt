package com.kamran.stickcut

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.util.Locale

private val MeNavy = Color(0xFF050B1F)
private val MeBlue = Color(0xFF2D8CFF)
private val MeCard = Color(0xFF0F2F70)
private val MeDark = Color(0xFF0A1A44)
private val MeOk = Color(0xFF6BE3A0)
private val MeWarn = Color(0xFFFFC857)
private val MeErr = Color(0xFFFF6B6B)
private val MeSoft = Color(0xFFB8D4FF)

private data class EditorState(
    val scenes: List<Scene>,
    val settings: AnimationSettings
)

private fun fileNameOf(context: Context, uri: Uri): String {
    context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) {
                val name = cursor.getString(index)
                if (!name.isNullOrBlank()) return name
            }
        }
    }
    return uri.lastPathSegment ?: "image"
}

private fun secs(ms: Long): String {
    return String.format(Locale.US, "%.2fs", ms / 1000f)
}

@Composable
fun ManualEditorScreen(
    scenes: List<Scene>,
    totalMs: Long,
    audios: List<ImportedAudio>,
    settings: AnimationSettings,
    onApply: (List<Scene>, AnimationSettings) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current

    var work by remember { mutableStateOf(scenes) }
    var workSettings by remember { mutableStateOf(settings) }
    var selected by remember { mutableStateOf(0) }
    var history by remember { mutableStateOf<List<EditorState>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }

    BackHandler(enabled = true) {
        if (history.isNotEmpty() && !confirmExit) {
            confirmExit = true
        } else {
            onClose()
        }
    }

    fun showMessage(text: String?, isError: Boolean) {
        message = text
        messageIsError = isError
    }

    fun commit(result: EditResult, newSelected: Int) {
        if (!result.ok) {
            showMessage(result.error, true)
            return
        }
        history = (history + EditorState(work, workSettings)).takeLast(50)
        work = result.scenes
        workSettings = ManualOps.remapSettings(workSettings, result.oldToNew)
        selected = newSelected.coerceIn(0, result.scenes.lastIndex)
        showMessage(null, false)
    }

    fun undo() {
        val last = history.lastOrNull() ?: return
        history = history.dropLast(1)
        work = last.scenes
        workSettings = last.settings
        selected = selected.coerceIn(0, last.scenes.lastIndex)
        showMessage(null, false)
    }

    fun moveCut(boundaryPosition: Int, newMs: Long) {
        val result = ManualOps.setBoundary(work, audios, boundaryPosition, newMs)
        if (result.ok && result.scenes == work) {
            showMessage("Cannot move further. Every scene needs at least 0.5 second.", true)
        } else {
            commit(result, selected)
        }
    }

    fun nudgeEnd(deltaMs: Long) {
        if (selected >= work.lastIndex) {
            showMessage("This is the last scene. It always ends at the total length.", true)
        } else {
            moveCut(selected, work[selected].endMs + deltaMs)
        }
    }

    fun nudgeStart(deltaMs: Long) {
        if (selected == 0) {
            showMessage("This is the first scene. It always starts at 0:00.", true)
        } else {
            moveCut(selected - 1, work[selected].startMs + deltaMs)
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && work.isNotEmpty()) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // some pickers do not allow persistent access; ignore
            }
            val name = fileNameOf(context, uri)
            val number = SceneBuilder.imageNumberOf(name)
            commit(ManualOps.replaceImage(work, selected, uri, name, number ?: 0L), selected)
            if (number == null) {
                showMessage(
                    "Image changed. Its file name has no clear number, so it is stored as number 0.",
                    false
                )
            }
        }
    }

    val problems = remember(work, totalMs) { ManualOps.validate(work, totalMs) }
    val scene = work.getOrNull(selected)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MeNavy)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 120.dp)
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
                        .clickable {
                            if (history.isNotEmpty()) confirmExit = true else onClose()
                        }
                        .padding(8.dp)
                )
                Text(
                    text = "Manual Edit",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Done",
                    color = if (problems.isEmpty()) MeOk else MeSoft,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .alpha(if (problems.isEmpty()) 1f else 0.4f)
                        .clickable {
                            if (problems.isEmpty()) {
                                onApply(work, workSettings)
                            } else {
                                showMessage("Fix the red problems first.", true)
                            }
                        }
                        .padding(8.dp)
                )
            }

            if (confirmExit) {
                Spacer(modifier = Modifier.height(8.dp))
                val shape = RoundedCornerShape(14.dp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(MeDark)
                        .border(1.dp, MeWarn.copy(alpha = 0.8f), shape)
                        .padding(12.dp)
                ) {
                    Text(
                        text = "You have unsaved edits. Leave without applying them?",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EdButton(
                            text = "Keep editing",
                            modifier = Modifier.weight(1f),
                            onClick = { confirmExit = false }
                        )
                        EdButton(
                            text = "Discard edits",
                            modifier = Modifier.weight(1f),
                            onClick = onClose
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))

            if (scene == null) {
                Text(
                    text = "No scenes to edit.",
                    color = MeSoft,
                    fontSize = 14.sp
                )
            } else {
                Text(
                    text = "${work.size} scenes  •  total ${ScriptParser.formatTimestamp(totalMs)}",
                    color = MeSoft,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(work, key = { position, _ -> position }) { position, item ->
                        val fraction = if (totalMs > 0) item.durationMs.toFloat() / totalMs.toFloat() else 0f
                        val width = (fraction * 900f).coerceIn(56f, 160f)
                        val isSelected = position == selected
                        val chipShape = RoundedCornerShape(10.dp)
                        val chipRequest = remember(item.imageUri) {
                            ImageRequest.Builder(context)
                                .data(item.imageUri)
                                .size(240)
                                .build()
                        }
                        Box(
                            modifier = Modifier
                                .width(width.dp)
                                .height(64.dp)
                                .clip(chipShape)
                                .border(
                                    if (isSelected) 3.dp else 1.dp,
                                    if (isSelected) Color.White else MeBlue.copy(alpha = 0.5f),
                                    chipShape
                                )
                                .clickable { selected = position }
                        ) {
                            AsyncImage(
                                model = chipRequest,
                                contentDescription = item.imageName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Text(
                                text = "${item.index}",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .background(MeNavy.copy(alpha = 0.75f))
                                    .padding(horizontal = 4.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))

                val bigRequest = remember(scene.imageUri) {
                    ImageRequest.Builder(context)
                        .data(scene.imageUri)
                        .size(900)
                        .build()
                }
                val frameShape = RoundedCornerShape(16.dp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(frameShape)
                        .background(Color.Black)
                        .border(1.dp, MeBlue.copy(alpha = 0.6f), frameShape)
                ) {
                    AsyncImage(
                        model = bigRequest,
                        contentDescription = scene.imageName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Text(
                    text = "Scene #${scene.index}  •  Image ${scene.imageNumber}  •  Part ${scene.partPosition + 1}",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    text = "${ScriptParser.formatTimestamp(scene.startMs)} → ${ScriptParser.formatTimestamp(scene.endMs)}  (${secs(scene.durationMs)})",
                    color = MeBlue,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
                if (scene.text.isNotBlank()) {
                    Text(
                        text = scene.text,
                        color = MeSoft,
                        fontSize = 13.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))

                NudgeRow(
                    label = "Start cut",
                    onDelta = { nudgeStart(it) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                NudgeRow(
                    label = "End cut",
                    onDelta = { nudgeEnd(it) }
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EdButton(
                        text = "✂ Split middle",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            commit(
                                ManualOps.split(
                                    work,
                                    audios,
                                    selected,
                                    scene.startMs + scene.durationMs / 2L
                                ),
                                selected
                            )
                        }
                    )
                    EdButton(
                        text = "🔗 Merge next",
                        modifier = Modifier.weight(1f),
                        onClick = { commit(ManualOps.merge(work, audios, selected), selected) }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EdButton(
                        text = "🖼 Change image",
                        modifier = Modifier.weight(1f),
                        onClick = { imagePicker.launch(arrayOf("image/*")) }
                    )
                    EdButton(
                        text = "🗑 Delete",
                        modifier = Modifier.weight(1f),
                        onClick = { commit(ManualOps.delete(work, audios, selected), selected) }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EdButton(
                        text = "⇄ Swap image ◀",
                        modifier = Modifier.weight(1f),
                        onClick = { commit(ManualOps.swapImages(work, selected, selected - 1), selected) }
                    )
                    EdButton(
                        text = "⇄ Swap image ▶",
                        modifier = Modifier.weight(1f),
                        onClick = { commit(ManualOps.swapImages(work, selected, selected + 1), selected) }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                EdButton(
                    text = if (history.isEmpty()) "↩ Undo" else "↩ Undo (${history.size})",
                    modifier = Modifier.fillMaxWidth(),
                    enabled = history.isNotEmpty(),
                    onClick = { undo() }
                )
            }

            val text = message
            if (text != null) {
                Text(
                    text = text,
                    color = if (messageIsError) MeWarn else MeOk,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            val statusShape = RoundedCornerShape(14.dp)
            val statusColor = if (problems.isEmpty()) MeOk else MeErr
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(statusShape)
                    .background(MeDark)
                    .border(1.dp, statusColor.copy(alpha = 0.7f), statusShape)
                    .padding(12.dp)
            ) {
                Text(
                    text = if (problems.isEmpty()) "✓ Timeline is valid" else "✖ Timeline problems",
                    color = statusColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                problems.take(6).forEach { problem ->
                    Text(
                        text = "✖ $problem",
                        color = MeErr,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        CreatorBrand(modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun NudgeRow(label: String, onDelta: (Long) -> Unit) {
    Column {
        Text(
            text = label,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EdButton(text = "−1s", modifier = Modifier.weight(1f), onClick = { onDelta(-1000L) })
            EdButton(text = "−0.1s", modifier = Modifier.weight(1f), onClick = { onDelta(-100L) })
            EdButton(text = "+0.1s", modifier = Modifier.weight(1f), onClick = { onDelta(100L) })
            EdButton(text = "+1s", modifier = Modifier.weight(1f), onClick = { onDelta(1000L) })
        }
    }
}

@Composable
private fun EdButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .height(44.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(MeCard)
            .border(1.dp, MeBlue.copy(alpha = 0.7f), shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
    }
}
