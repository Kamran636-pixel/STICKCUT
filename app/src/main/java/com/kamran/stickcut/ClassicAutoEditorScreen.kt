package com.kamran.stickcut

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ClassicNavy = Color(0xFF050B1F)
private val ClassicBlue = Color(0xFF2D8CFF)
private val ClassicCard = Color(0xFF0F2F70)
private val ClassicCardDark = Color(0xFF0A1A44)
private val WarnColor = Color(0xFFFFC857)
private val OkColor = Color(0xFF6BE3A0)

data class ImportedImage(val uri: Uri, val name: String)

data class ImportedAudio(val uri: Uri, val name: String, val durationMs: Long)

private fun queryDisplayName(context: Context, uri: Uri): String {
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
    return uri.lastPathSegment ?: "file"
}

private fun readAudioDurationMs(context: Context, uri: Uri): Long? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    } catch (e: Exception) {
        null
    } finally {
        try {
            retriever.release()
        } catch (e: Exception) {
            // ignore
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val tenth = (ms % 1000) / 100
    return "$minutes:${seconds.toString().padStart(2, '0')}.$tenth"
}

private fun splitParts(text: String): List<String> {
    return Regex("\\d+|\\D+").findAll(text.lowercase()).map { it.value }.toList()
}

private fun naturalCompare(a: String, b: String): Int {
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

private val keywordNumberRegex =
    Regex("(?i)(?:image|img|scene|slide|pic|photo|frame|shot|page|part)[\\s_.\\-]*(\\d+)")
private val copyMarkerRegex = Regex("\\s*\\(\\d+\\)\\s*$")
private val leadingNumberRegex = Regex("^\\s*(\\d+)")
private val anyNumberRegex = Regex("\\d+")

// Finds the sequence number in names like "image 1", "image10", "122_abc".
// Returns null when no clear number can be found. It never guesses.
private fun sequenceNumber(fileName: String): Long? {
    val base = fileName.substringBeforeLast('.').replace(copyMarkerRegex, "")

    val keywordMatch = keywordNumberRegex.find(base)
    if (keywordMatch != null) {
        return keywordMatch.groupValues[1].toLongOrNull()
    }

    val leadingMatch = leadingNumberRegex.find(base)
    if (leadingMatch != null) {
        return leadingMatch.groupValues[1].toLongOrNull()
    }

    val groups = anyNumberRegex.findAll(base).toList()
    if (groups.size == 1) {
        return groups[0].value.toLongOrNull()
    }
    return null
}

private fun compareImageNames(a: String, b: String): Int {
    val numberA = sequenceNumber(a)
    val numberB = sequenceNumber(b)
    return when {
        numberA != null && numberB != null -> {
            if (numberA != numberB) numberA.compareTo(numberB) else naturalCompare(a, b)
        }
        numberA != null -> -1
        numberB != null -> 1
        else -> naturalCompare(a, b)
    }
}

private fun sortImages(list: List<ImportedImage>): List<ImportedImage> {
    return list.sortedWith(Comparator { a, b -> compareImageNames(a.name, b.name) })
}

private data class SortReport(
    val total: Int,
    val numbered: Int,
    val unnumbered: Int,
    val duplicates: List<Long>,
    val missing: List<Long>,
    val missingTotal: Int
)

private fun buildReport(images: List<ImportedImage>): SortReport {
    val numbers = images.mapNotNull { sequenceNumber(it.name) }
    val duplicates = numbers.groupingBy { it }.eachCount()
        .filter { it.value > 1 }.keys.sorted()

    val missing = mutableListOf<Long>()
    var missingTotal = 0
    if (numbers.isNotEmpty()) {
        val lowest = numbers.min()
        val highest = numbers.max()
        if (highest - lowest <= 5000L) {
            val present = numbers.toSet()
            var n = lowest
            while (n <= highest) {
                if (n !in present) {
                    missingTotal++
                    if (missing.size < 8) missing.add(n)
                }
                n++
            }
        }
    }
    return SortReport(
        total = images.size,
        numbered = numbers.size,
        unnumbered = images.size - numbers.size,
        duplicates = duplicates,
        missing = missing,
        missingTotal = missingTotal
    )
}

@Composable
fun ClassicAutoEditorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var images by remember { mutableStateOf<List<ImportedImage>>(emptyList()) }
    var showOrderList by remember { mutableStateOf(false) }

    var audio by remember { mutableStateOf<ImportedAudio?>(null) }
    var audioLoading by remember { mutableStateOf(false) }
    var audioError by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }

    fun releasePlayer() {
        try {
            player?.release()
        } catch (e: Exception) {
            // ignore
        }
        player = null
        isPlaying = false
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                player?.release()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    fun togglePlay(current: ImportedAudio) {
        try {
            if (isPlaying) {
                player?.pause()
                isPlaying = false
            } else {
                val existing = player
                val p = if (existing != null) {
                    existing
                } else {
                    val created = MediaPlayer()
                    created.setDataSource(context, current.uri)
                    created.setOnCompletionListener { mp ->
                        isPlaying = false
                        mp.seekTo(0)
                    }
                    created.prepare()
                    created
                }
                player = p
                p.start()
                isPlaying = true
            }
        } catch (e: Exception) {
            releasePlayer()
            audioError = "This audio could not be played on this phone."
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    // some pickers do not allow persistent access; ignore
                }
            }
            val existing = images.map { it.uri }.toSet()
            val added = uris
                .filter { it !in existing }
                .map { ImportedImage(it, queryDisplayName(context, it)) }
            images = sortImages(images + added)
        }
    }

    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // ignore
            }
            releasePlayer()
            audioError = null
            audioLoading = true
            scope.launch {
                val name = withContext(Dispatchers.IO) { queryDisplayName(context, uri) }
                val duration = withContext(Dispatchers.IO) { readAudioDurationMs(context, uri) }
                audioLoading = false
                if (duration == null || duration <= 0L) {
                    audio = null
                    audioError = "\"$name\" could not be read as audio. Try another file or format."
                } else {
                    audio = ImportedAudio(uri, name, duration)
                }
            }
        }
    }

    if (showOrderList) {
        ImageOrderList(
            images = images,
            onImagesChange = { images = it },
            onClose = { showOrderList = false }
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ClassicNavy)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 130.dp)
            ) {
                ClassicTopBar(title = "Classic AutoEditor", onBack = onBack)
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Import your images and voiceover.\nLet StickCut do the magic!",
                    color = Color.White,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Build: Step 25",
                    color = Color(0xFF6C85B8),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))

                PreviewBox(firstImage = images.firstOrNull())
                Spacer(modifier = Modifier.height(12.dp))

                if (images.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Imported Images (${images.size})",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Clear",
                            color = ClassicBlue,
                            fontSize = 15.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { images = emptyList() }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(images) { image ->
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(image.uri)
                                    .size(300)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = image.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(
                                        1.dp,
                                        ClassicBlue.copy(alpha = 0.6f),
                                        RoundedCornerShape(12.dp)
                                    )
                            )
                        }
                    }
                    Text(
                        text = "Order: ${images.first().name} → ${images.last().name}",
                        color = Color(0xFFB8D4FF),
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    SortReportCard(report = buildReport(images))
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SmallButton(
                            text = "View / Reorder",
                            modifier = Modifier.weight(1f),
                            onClick = { showOrderList = true }
                        )
                        SmallButton(
                            text = "Re-sort",
                            modifier = Modifier.weight(1f),
                            onClick = { images = sortImages(images) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                ImportRow(
                    icon = "🖼",
                    label = "Import Images",
                    onClick = { imagePicker.launch(arrayOf("image/*")) }
                )
                Spacer(modifier = Modifier.height(10.dp))
                ImportRow(
                    icon = "🎙",
                    label = if (audio == null) "Import Voiceover" else "Replace Voiceover",
                    onClick = { audioPicker.launch(arrayOf("audio/*", "application/ogg")) }
                )

                if (audioLoading) {
                    Text(
                        text = "Reading audio...",
                        color = Color(0xFFB8D4FF),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                val currentAudio = audio
                if (currentAudio != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    AudioCard(
                        audio = currentAudio,
                        isPlaying = isPlaying,
                        onTogglePlay = { togglePlay(currentAudio) },
                        onRemove = {
                            releasePlayer()
                            audio = null
                            audioError = null
                        }
                    )
                }

                val errorText = audioError
                if (errorText != null) {
                    Text(
                        text = errorText,
                        color = WarnColor,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                ImportRow(icon = "📄", label = "Optional Script")
                Spacer(modifier = Modifier.height(24.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(ClassicBlue)
                        .clickable { },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Start Auto Edit  →",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            CreatorBrand(modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun AudioCard(
    audio: ImportedAudio,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onRemove: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ClassicCardDark)
            .border(1.dp, OkColor.copy(alpha = 0.6f), shape)
            .padding(12.dp)
    ) {
        Text(
            text = audio.name,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "Total length: ${formatDuration(audio.durationMs)}  (${audio.durationMs} ms)",
            color = OkColor,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallButton(
                text = if (isPlaying) "Pause" else "Play test",
                modifier = Modifier.weight(1f),
                onClick = onTogglePlay
            )
            SmallButton(
                text = "Remove",
                modifier = Modifier.weight(1f),
                onClick = onRemove
            )
        }
    }
}

@Composable
private fun SortReportCard(report: SortReport) {
    val hasIssue = report.unnumbered > 0 ||
        report.duplicates.isNotEmpty() ||
        report.missingTotal > 0
    val shape = RoundedCornerShape(14.dp)
    val borderColor = if (hasIssue) WarnColor else ClassicBlue

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ClassicCardDark)
            .border(1.dp, borderColor.copy(alpha = 0.7f), shape)
            .padding(12.dp)
    ) {
        Text(
            text = "Sort check",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Numbered: ${report.numbered}  •  No number: ${report.unnumbered}",
            color = Color(0xFFB8D4FF),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (report.duplicates.isNotEmpty()) {
            Text(
                text = "Duplicate numbers: ${report.duplicates.take(8).joinToString(", ")}",
                color = WarnColor,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (report.missingTotal > 0) {
            val extra = report.missingTotal - report.missing.size
            val extraText = if (extra > 0) " (+$extra more)" else ""
            Text(
                text = "Missing numbers: ${report.missing.joinToString(", ")}$extraText",
                color = WarnColor,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (report.unnumbered > 0) {
            Text(
                text = "Images with no clear number are placed at the end. Use View / Reorder to fix them by hand.",
                color = WarnColor,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (!hasIssue && report.numbered > 0) {
            Text(
                text = "✓ Sequence is complete",
                color = OkColor,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun SmallButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(shape)
            .background(ClassicCard)
            .border(1.dp, ClassicBlue.copy(alpha = 0.7f), shape)
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

@Composable
private fun ImageOrderList(
    images: List<ImportedImage>,
    onImagesChange: (List<ImportedImage>) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    BackHandler(enabled = true) { onClose() }

    fun move(from: Int, to: Int) {
        if (to < 0 || to >= images.size) return
        val mutable = images.toMutableList()
        val item = mutable.removeAt(from)
        mutable.add(to, item)
        onImagesChange(mutable)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ClassicNavy)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp)
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
                    text = "Image Order (${images.size})",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Re-sort",
                    color = ClassicBlue,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onImagesChange(sortImages(images)) }
                        .padding(8.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 90.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(images, key = { _, item -> item.uri.toString() }) { index, image ->
                    val shape = RoundedCornerShape(14.dp)
                    val number = sequenceNumber(image.name)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(ClassicCardDark)
                            .border(1.dp, ClassicBlue.copy(alpha = 0.4f), shape)
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${index + 1}",
                            color = ClassicBlue,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(36.dp)
                        )
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(image.uri)
                                .size(200)
                                .build(),
                            contentDescription = image.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 10.dp)
                        ) {
                            Text(
                                text = image.name,
                                color = Color.White,
                                fontSize = 13.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (number != null) "No. $number" else "no clear number",
                                color = if (number != null) Color(0xFFB8D4FF) else WarnColor,
                                fontSize = 11.sp
                            )
                        }
                        Text(
                            text = "▲",
                            color = Color.White,
                            fontSize = 20.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { move(index, index - 1) }
                                .padding(8.dp)
                        )
                        Text(
                            text = "▼",
                            color = Color.White,
                            fontSize = 20.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { move(index, index + 1) }
                                .padding(8.dp)
                        )
                        Text(
                            text = "✕",
                            color = WarnColor,
                            fontSize = 18.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    onImagesChange(images.filterIndexed { i, _ -> i != index })
                                }
                                .padding(8.dp)
                        )
                    }
                }
            }
        }

        CreatorBrand(modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun ClassicTopBar(title: String, onBack: () -> Unit) {
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
                .clickable(onClick = onBack)
                .padding(8.dp)
        )
        Text(
            text = title,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = "🏠",
            fontSize = 24.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onBack)
                .padding(8.dp)
        )
    }
}

@Composable
private fun PreviewBox(firstImage: ImportedImage?) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(ClassicCard, ClassicCardDark)))
            .border(1.dp, ClassicBlue.copy(alpha = 0.6f), shape),
        contentAlignment = Alignment.Center
    ) {
        if (firstImage == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "▶", color = ClassicBlue, fontSize = 44.sp)
                Text(
                    text = "Your images will appear here",
                    color = Color(0xFFB8D4FF),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        } else {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(firstImage.uri)
                    .size(900)
                    .crossfade(true)
                    .build(),
                contentDescription = firstImage.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun ImportRow(icon: String, label: String, onClick: () -> Unit = {}) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(ClassicCard, ClassicCardDark)))
            .border(1.dp, ClassicBlue.copy(alpha = 0.5f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = icon, fontSize = 24.sp)
        Text(
            text = label,
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 14.dp)
        )
    }
}
