package com.kamran.stickcut

import android.content.Context
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// Step 80: import button and edit screen for caption files (.srt/.vtt/.txt).
// Step 81.1: Animated (Studio) style choice.
// ---------------------------------------------------------------------------

private val ImpNavy = Color(0xFF050B1F)
private val ImpBlue = Color(0xFF2D8CFF)
private val ImpCard = Color(0xFF0F2F70)
private val ImpCardDark = Color(0xFF0A1A44)
private val ImpWarn = Color(0xFFFFC857)
private val ImpSoft = Color(0xFFB8D4FF)

// Small notes kept while the app is open: the format and any problems found.
object ImportedCaptionNotes {
    var format by mutableStateOf("")
    var messages by mutableStateOf<List<String>>(emptyList())
}

private const val IMP_MAX_BYTES = 3 * 1024 * 1024

private class ImpLoadOutcome(
    val fileName: String,
    val cues: List<CaptionCue>,
    val format: String,
    val messages: List<String>
)

private fun impDisplayName(context: Context, uri: Uri): String {
    try {
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
    } catch (e: Exception) {
        // ignore, a fallback name is used
    }
    return uri.lastPathSegment ?: "captions"
}

// Reads and parses the file. Runs on a background thread.
private fun impLoadFile(context: Context, uri: Uri, audioDurationMs: Long): ImpLoadOutcome {
    val name = impDisplayName(context, uri)
    try {
        val stream = context.contentResolver.openInputStream(uri)
        if (stream == null) {
            return ImpLoadOutcome(name, emptyList(), "empty", listOf("The file could not be opened."))
        }
        val buffer = ByteArray(IMP_MAX_BYTES + 1)
        var total = 0
        stream.use { input ->
            while (total < buffer.size) {
                val count = input.read(buffer, total, buffer.size - total)
                if (count < 0) break
                total += count
            }
        }
        if (total > IMP_MAX_BYTES) {
            return ImpLoadOutcome(
                name,
                emptyList(),
                "empty",
                listOf("The file is too big (more than 3 MB). A caption file is normally much smaller.")
            )
        }
        val text = String(buffer, 0, total, Charsets.UTF_8)
        val duration = if (audioDurationMs > 0L) audioDurationMs else null
        val result = CaptionFileParser.parse(text, duration)
        val messages = result.errors.toMutableList()
        if (text.contains('\uFFFD')) {
            messages.add(0, "Some letters could not be read. Save the file as UTF-8 and import it again.")
        }
        return ImpLoadOutcome(name, result.cues, result.format, messages)
    } catch (e: Exception) {
        return ImpLoadOutcome(
            name,
            emptyList(),
            "empty",
            listOf("The file could not be read: ${e.message ?: "unknown error"}")
        )
    }
}

@Composable
private fun ImpButton(
    text: String,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(shape)
            .background(if (filled) ImpBlue else ImpCard)
            .border(1.dp, ImpBlue.copy(alpha = 0.6f), shape)
            .clickable { onClick() },
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

// ---------------------------------------------------------------------------
// The panel on the Classic AutoEditor screen.
// ---------------------------------------------------------------------------
@Composable
fun ImportedCaptionPanel(audioDurationMs: Long, onEdit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = ImportedCaptionStore.state
    var loading by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            loading = true
            scope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    impLoadFile(context, uri, audioDurationMs)
                }
                loading = false
                if (outcome.cues.isNotEmpty()) {
                    ImportedCaptionStore.load(outcome.fileName, outcome.cues)
                    ImportedCaptionNotes.format = outcome.format
                }
                ImportedCaptionNotes.messages = outcome.messages
            }
        }
    }

    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ImpCardDark)
            .border(1.dp, ImpBlue.copy(alpha = 0.4f), shape)
            .padding(14.dp)
    ) {
        Text(
            text = "💬 Captions from a file",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Optional. Import .srt, .vtt or .txt with times. " +
                "They show in the preview. Showing them in the exported video comes in the next step.",
            color = ImpSoft,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(modifier = Modifier.height(10.dp))

        if (state.cues.isEmpty()) {
            ImpButton(
                text = "Import Captions (.srt / .vtt / .txt)",
                modifier = Modifier.fillMaxWidth(),
                onClick = { picker.launch(arrayOf("*/*")) }
            )
        } else {
            Text(
                text = state.fileName,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val formatText = if (ImportedCaptionNotes.format.isEmpty()) "" else "${ImportedCaptionNotes.format} · "
            Text(
                text = "$formatText${state.cues.size} captions",
                color = ImpSoft,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (state.enabled) "Captions: ON" else "Captions: OFF",
                    color = Color.White,
                    fontSize = 14.sp
                )
                Switch(
                    checked = state.enabled,
                    onCheckedChange = { ImportedCaptionStore.setEnabled(it) }
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ImpButton(
                    text = "Edit",
                    modifier = Modifier.weight(1f),
                    onClick = onEdit
                )
                ImpButton(
                    text = "Replace",
                    modifier = Modifier.weight(1f),
                    filled = false,
                    onClick = { picker.launch(arrayOf("*/*")) }
                )
                ImpButton(
                    text = "Clear",
                    modifier = Modifier.weight(1f),
                    filled = false,
                    onClick = {
                        ImportedCaptionStore.clear()
                        ImportedCaptionNotes.format = ""
                        ImportedCaptionNotes.messages = emptyList()
                    }
                )
            }
        }

        if (loading) {
            Text(
                text = "Reading caption file...",
                color = ImpSoft,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        val notes = ImportedCaptionNotes.messages
        if (notes.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            notes.forEach { message ->
                Text(
                    text = "• $message",
                    color = ImpWarn,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The full edit screen.
// ---------------------------------------------------------------------------
@Composable
fun ImportedCaptionEditScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val state = ImportedCaptionStore.state
    var editingId by remember { mutableStateOf<Long?>(null) }
    val editing = state.cues.firstOrNull { it.id == editingId }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ImpNavy)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "←  Back",
                        color = ImpBlue,
                        fontSize = 16.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onClose() }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                    Text(
                        text = "Edit Captions",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }

            item { ImpStyleTypeCard() }
            if (ImportedCaptionMode.useStudio) {
                item { ImpStudioCard() }
            } else {
                item { ImpLookCard(state) }
                item { ImpSizeCard(state) }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImpButton(
                        text = "+ Add caption",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val start = ImportedCaptionStore.state.cues.maxOfOrNull { it.endMs } ?: 0L
                            ImportedCaptionStore.addCue(start)
                            val newId = ImportedCaptionStore.state.cues.maxOfOrNull { it.id }
                            editingId = newId
                        }
                    )
                    ImpButton(
                        text = "Restore original",
                        modifier = Modifier.weight(1f),
                        filled = false,
                        onClick = { ImportedCaptionStore.restoreOriginal() }
                    )
                }
            }

            item {
                Text(
                    text = "${state.cues.size} captions · tap one to edit. " +
                        "The imported file itself is never changed.",
                    color = ImpSoft,
                    fontSize = 12.sp
                )
            }

            itemsIndexed(state.cues, key = { _, cue -> cue.id }) { index, cue ->
                val next = state.cues.getOrNull(index + 1)
                val overlaps = next != null && cue.endMs > next.startMs
                ImpCueRow(
                    number = index + 1,
                    cue = cue,
                    overlaps = overlaps,
                    onClick = { editingId = cue.id }
                )
            }
        }

        if (editing != null) {
            ImpCueDialog(
                cue = editing,
                onDismiss = { editingId = null }
            )
        }
    }
}

@Composable
private fun ImpLookCard(state: ImportedCaptionState) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ImpCardDark)
            .padding(14.dp)
    ) {
        Text(
            text = "Caption look",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CaptionLook.values().forEach { look ->
                val selected = state.look == look
                val chipShape = RoundedCornerShape(12.dp)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(chipShape)
                        .background(if (selected) ImpCard else ImpNavy)
                        .border(
                            if (selected) 2.dp else 1.dp,
                            if (selected) ImpBlue else ImpBlue.copy(alpha = 0.3f),
                            chipShape
                        )
                        .clickable { ImportedCaptionStore.setLook(look) }
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val sampleModifier = if (look.hasBox) {
                        Modifier
                            .background(Color(CaptionLayoutMath.BOX_ARGB))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    } else {
                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    }
                    Text(
                        text = "Sample",
                        color = Color(look.textColor),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        style = TextStyle(
                            color = Color(look.textColor),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            shadow = if (look.hasOutline) {
                                Shadow(Color.Black, Offset(0f, 0f), 6f)
                            } else {
                                null
                            }
                        ),
                        modifier = sampleModifier
                    )
                    Text(
                        text = look.label,
                        color = Color.White,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
        Text(
            text = "The real look on the video is shown in the preview step. These samples are only a rough guide.",
            color = ImpSoft,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun ImpSizeCard(state: ImportedCaptionState) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ImpCardDark)
            .padding(14.dp)
    ) {
        Text(
            text = "Text size",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CaptionSizeChoice.values().forEach { choice ->
                val selected = state.customScale == null && state.size == choice
                ImpButton(
                    text = choice.label,
                    modifier = Modifier.weight(1f),
                    filled = selected,
                    onClick = { ImportedCaptionStore.setSize(choice) }
                )
            }
        }

        val scale = (state.customScale ?: state.size.heightShare)
            .coerceIn(CaptionLayoutMath.MIN_CUSTOM_SCALE, CaptionLayoutMath.MAX_CUSTOM_SCALE)
        Text(
            text = String.format(Locale.US, "Fine size: %.1f%% of the video height", scale * 100f),
            color = ImpSoft,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 10.dp)
        )
        Slider(
            value = scale,
            onValueChange = { ImportedCaptionStore.setCustomScale(it) },
            valueRange = CaptionLayoutMath.MIN_CUSTOM_SCALE..CaptionLayoutMath.MAX_CUSTOM_SCALE
        )

        val spacing = CaptionLayoutMath.lineSpacing(state)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = String.format(Locale.US, "Line spacing: %.2f", spacing),
                color = ImpSoft,
                fontSize = 12.sp
            )
            Text(
                text = "Default",
                color = ImpBlue,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { ImportedCaptionStore.setLineSpacing(null) }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        Slider(
            value = spacing,
            onValueChange = { ImportedCaptionStore.setLineSpacing(it) },
            valueRange = CaptionLayoutMath.MIN_LINE_SPACING..CaptionLayoutMath.MAX_LINE_SPACING
        )
    }
}

@Composable
private fun ImpCueRow(number: Int, cue: CaptionCue, overlaps: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ImpCardDark)
            .border(
                1.dp,
                if (overlaps) ImpWarn else ImpBlue.copy(alpha = 0.25f),
                shape
            )
            .clickable { onClick() }
            .padding(10.dp)
    ) {
        Text(
            text = "$number · ${CaptionFileParser.formatTime(cue.startMs)} → " +
                CaptionFileParser.formatTime(cue.endMs),
            color = ImpSoft,
            fontSize = 12.sp
        )
        Text(
            text = cue.text,
            color = Color.White,
            fontSize = 14.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
        if (overlaps) {
            Text(
                text = "⚠ This caption ends after the next one starts. The later one will show.",
                color = ImpWarn,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun impFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    cursorColor = Color.White,
    focusedBorderColor = ImpBlue,
    unfocusedBorderColor = Color(0xFF6C85B8),
    focusedLabelColor = ImpBlue,
    unfocusedLabelColor = ImpSoft
)

@Composable
private fun ImpCueDialog(cue: CaptionCue, onDismiss: () -> Unit) {
    var text by remember(cue.id) { mutableStateOf(cue.text) }
    var startText by remember(cue.id) { mutableStateOf(CaptionFileParser.formatTime(cue.startMs)) }
    var endText by remember(cue.id) { mutableStateOf(CaptionFileParser.formatTime(cue.endMs)) }
    var error by remember(cue.id) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ImpCard,
        title = { Text(text = "Edit caption", color = Color.White) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Text") },
                    colors = impFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startText,
                        onValueChange = { startText = it },
                        label = { Text("Start") },
                        singleLine = true,
                        colors = impFieldColors(),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = endText,
                        onValueChange = { endText = it },
                        label = { Text("End") },
                        singleLine = true,
                        colors = impFieldColors(),
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    text = "Time looks like 00:01:05.500 or 1:05.5",
                    color = ImpSoft,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
                val message = error
                if (message != null) {
                    Text(
                        text = message,
                        color = ImpWarn,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val start = CaptionFileParser.parseTime(startText)
                    val end = CaptionFileParser.parseTime(endText)
                    val clean = CaptionFileParser.cleanText(text)
                    if (start == null) {
                        error = "The start time is not valid."
                    } else if (end == null) {
                        error = "The end time is not valid."
                    } else if (end <= start) {
                        error = "The end must be after the start."
                    } else if (clean.isEmpty()) {
                        error = "The text is empty. Use Delete to remove this caption."
                    } else {
                        ImportedCaptionStore.updateCue(cue.id, start, end, clean)
                        onDismiss()
                    }
                }
            ) {
                Text(text = "Save", color = ImpBlue)
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        ImportedCaptionStore.deleteCue(cue.id)
                        onDismiss()
                    }
                ) {
                    Text(text = "Delete", color = ImpWarn)
                }
                TextButton(onClick = onDismiss) {
                    Text(text = "Cancel", color = ImpSoft)
                }
            }
        }
    )
}

// Rows of up to three buttons. The selected one is filled.
@Composable
private fun ImpChoiceRows(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    labels.chunked(3).forEachIndexed { rowIndex, rowLabels ->
        Row(
            modifier = Modifier.padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            rowLabels.forEachIndexed { i, label ->
                val index = rowIndex * 3 + i
                ImpButton(
                    text = label,
                    modifier = Modifier.weight(1f),
                    filled = index == selectedIndex,
                    onClick = { onSelect(index) }
                )
            }
            repeat(3 - rowLabels.size) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ImpStyleTypeCard() {
    val studio = ImportedCaptionMode.useStudio
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ImpCardDark)
            .padding(14.dp)
    ) {
        Text(
            text = "Caption style type",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ImpButton(
                text = "Classic presets",
                modifier = Modifier.weight(1f),
                filled = !studio,
                onClick = { ImportedCaptionMode.useStudio = false }
            )
            ImpButton(
                text = "Animated (Studio)",
                modifier = Modifier.weight(1f),
                filled = studio,
                onClick = { ImportedCaptionMode.useStudio = true }
            )
        }
        Text(
            text = if (studio) {
                "Animation, position, font and size below are the same settings as Caption Studio."
            } else {
                "Classic presets: Classic Outline, Boxed, Yellow Classic. " +
                    "Choose Animated if you want animation, position and font."
            },
            color = ImpSoft,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

// The same style that Caption Studio changes (CaptionStore), so both stay in step.
@Composable
private fun ImpStudioCard() {
    val style = CaptionStore.style
    val shape = RoundedCornerShape(16.dp)
    val fonts = CaptionFont.values().filter { it != CaptionFont.CUSTOM }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ImpCardDark)
            .padding(14.dp)
    ) {
        Text(
            text = "Animation",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        val animations = CaptionAnimation.values().toList()
        ImpChoiceRows(
            labels = animations.map { it.label },
            selectedIndex = animations.indexOf(style.animation),
            onSelect = { CaptionStore.style = CaptionStore.style.copy(animation = animations[it]) }
        )

        Text(
            text = "Position",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
        )
        val positions = CaptionPosition.values().toList()
        ImpChoiceRows(
            labels = positions.map { it.label },
            selectedIndex = positions.indexOf(style.position),
            onSelect = { CaptionStore.style = CaptionStore.style.copy(position = positions[it]) }
        )

        Text(
            text = "Font",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
        )
        ImpChoiceRows(
            labels = fonts.map { it.label },
            selectedIndex = fonts.indexOf(style.font),
            onSelect = { CaptionStore.style = CaptionStore.style.copy(font = fonts[it]) }
        )

        val size = style.sizePercent.coerceIn(CaptionStyle.MIN_SIZE, CaptionStyle.MAX_SIZE)
        Text(
            text = String.format(Locale.US, "Text size: %.1f%% of the video height", size),
            color = ImpSoft,
            fontSize = 12.sp
        )
        Slider(
            value = size,
            onValueChange = { CaptionStore.style = CaptionStore.style.copy(sizePercent = it) },
            valueRange = CaptionStyle.MIN_SIZE..CaptionStyle.MAX_SIZE
        )

        val words = style.maxWords.coerceIn(1, 8)
        Text(
            text = "Words shown together: $words",
            color = ImpSoft,
            fontSize = 12.sp
        )
        Slider(
            value = words.toFloat(),
            onValueChange = { CaptionStore.style = CaptionStore.style.copy(maxWords = it.roundToInt()) },
            valueRange = 1f..8f,
            steps = 6
        )
        Text(
            text = "Colours and the background box can still be changed in Caption Studio.",
            color = ImpSoft,
            fontSize = 11.sp
        )
    }
}
