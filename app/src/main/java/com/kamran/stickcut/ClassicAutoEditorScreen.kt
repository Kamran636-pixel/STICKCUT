package com.kamran.stickcut

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest

private val ClassicNavy = Color(0xFF050B1F)
private val ClassicBlue = Color(0xFF2D8CFF)
private val ClassicCard = Color(0xFF0F2F70)
private val ClassicCardDark = Color(0xFF0A1A44)

data class ImportedImage(val uri: Uri, val name: String)

private fun queryDisplayName(context: android.content.Context, uri: Uri): String {
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

@Composable
fun ClassicAutoEditorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var images by remember { mutableStateOf<List<ImportedImage>>(emptyList()) }

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
            images = (images + added).sortedWith(
                Comparator { a, b -> naturalCompare(a.name, b.name) }
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ClassicNavy)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 90.dp)
        ) {
            ClassicTopBar(title = "Classic AutoEditor", onBack = onBack)
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Import your images and voiceover.\nLet StickCut do the magic!",
                color = Color.White,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))

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
                                .border(1.dp, ClassicBlue.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        )
                    }
                }
                Text(
                    text = "Order: ${images.first().name} → ${images.last().name}",
                    color = Color(0xFFB8D4FF),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            ImportRow(
                icon = "🖼",
                label = "Import Images",
                onClick = { imagePicker.launch(arrayOf("image/*")) }
            )
            Spacer(modifier = Modifier.height(10.dp))
            ImportRow(icon = "🎙", label = "Import Voiceover")
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
