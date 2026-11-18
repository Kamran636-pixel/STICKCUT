package com.kamran.stickcut

import android.content.Context
import android.graphics.Typeface
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// Step 81.2: upload your own fonts (.ttf / .otf) for captions.
// Fonts are copied into the app, so they keep working even if the original
// file is moved. The chosen font is stored in CaptionStore.style
// (font = CUSTOM, customFontPath = file), the same place Caption Studio uses.
// ---------------------------------------------------------------------------
class FontImportResult(val path: String?, val name: String, val error: String?)

object CaptionFontManager {

    private const val MAX_BYTES = 25 * 1024 * 1024
    private const val DIR_NAME = "caption_fonts"

    // Full paths of the fonts uploaded so far.
    var fonts by mutableStateOf<List<String>>(emptyList())

    private val cache = HashMap<String, Typeface>()
    private val unsafeChars = Regex("[^A-Za-z0-9._-]")

    private fun fontDir(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun refresh(context: Context) {
        val files = fontDir(context).listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
        fonts = files.map { it.absolutePath }
    }

    fun displayName(path: String): String {
        return File(path).name.substringBeforeLast('.')
    }

    private fun queryName(context: Context, uri: Uri): String {
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
            // fall through
        }
        return uri.lastPathSegment ?: "font.ttf"
    }

    // TrueType starts with 00 01 00 00 or "true", OpenType with "OTTO".
    private fun looksLikeFont(header: ByteArray): Boolean {
        if (header.size < 4) return false
        val truetype = header[0].toInt() == 0 && header[1].toInt() == 1 &&
            header[2].toInt() == 0 && header[3].toInt() == 0
        val tag = String(header, 0, 4, Charsets.ISO_8859_1)
        return truetype || tag == "OTTO" || tag == "true"
    }

    fun load(path: String): Typeface? {
        val cached = cache[path]
        if (cached != null) return cached
        return try {
            val file = File(path)
            if (!file.exists()) {
                null
            } else {
                val typeface = Typeface.createFromFile(file)
                cache[path] = typeface
                typeface
            }
        } catch (e: Exception) {
            null
        }
    }

    // The typeface the preview and the exported video must use.
    // A missing or broken custom font safely falls back to the normal bold font.
    fun typefaceFor(style: CaptionStyle): Typeface {
        if (style.font == CaptionFont.CUSTOM) {
            val path = style.customFontPath
            if (path != null) {
                val typeface = load(path)
                if (typeface != null) return typeface
            }
        }
        return Typeface.DEFAULT_BOLD
    }

    // Copies a font file into the app and checks that it can be opened.
    // Runs on a background thread.
    fun importFont(context: Context, uri: Uri): FontImportResult {
        val name = queryName(context, uri)
        val lower = name.lowercase()
        if (!lower.endsWith(".ttf") && !lower.endsWith(".otf")) {
            return FontImportResult(null, name, "Choose a .ttf or .otf font file. \"$name\" is not one.")
        }
        try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: return FontImportResult(null, name, "The font file could not be opened.")
            val buffer = ByteArray(MAX_BYTES + 1)
            var total = 0
            stream.use { input ->
                while (total < buffer.size) {
                    val count = input.read(buffer, total, buffer.size - total)
                    if (count < 0) break
                    total += count
                }
            }
            if (total > MAX_BYTES) {
                return FontImportResult(null, name, "The font file is too big (more than 25 MB).")
            }
            if (!looksLikeFont(buffer)) {
                return FontImportResult(null, name, "\"$name\" does not look like a real font file.")
            }

            var safe = name.replace(unsafeChars, "_")
            if (safe.length < 5 || safe.startsWith(".")) {
                safe = "font_" + System.currentTimeMillis() + safe.takeLast(4)
            }
            val target = File(fontDir(context), safe)
            FileOutputStream(target).use { output ->
                output.write(buffer, 0, total)
            }
            cache.remove(target.absolutePath)
            val typeface = load(target.absolutePath)
            if (typeface == null) {
                target.delete()
                return FontImportResult(null, name, "Android could not open this font. Try another file.")
            }
            refresh(context)
            return FontImportResult(target.absolutePath, name, null)
        } catch (e: Exception) {
            return FontImportResult(null, name, "The font could not be added: ${e.message ?: "unknown error"}")
        }
    }

    fun delete(context: Context, path: String) {
        try {
            File(path).delete()
        } catch (e: Exception) {
            // ignore
        }
        cache.remove(path)
        val style = CaptionStore.style
        if (style.customFontPath == path) {
            CaptionStore.style = style.copy(font = CaptionFont.SANS_BOLD, customFontPath = null)
        }
        refresh(context)
    }
}

private val FntBlue = Color(0xFF2D8CFF)
private val FntCard = Color(0xFF0F2F70)
private val FntCardDark = Color(0xFF0A1A44)
private val FntNavy = Color(0xFF050B1F)
private val FntSoft = Color(0xFFB8D4FF)
private val FntWarn = Color(0xFFFFC857)

@Composable
private fun FntButton(
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
            .background(if (filled) FntBlue else FntCard)
            .border(1.dp, FntBlue.copy(alpha = 0.6f), shape)
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

// The "My fonts" card on the caption edit screen.
@Composable
fun CaptionFontCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val style = CaptionStore.style
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        CaptionFontManager.refresh(context)
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    CaptionFontManager.importFont(context, uri)
                }
                busy = false
                val path = result.path
                if (path != null) {
                    CaptionStore.style = CaptionStore.style.copy(
                        font = CaptionFont.CUSTOM,
                        customFontPath = path
                    )
                    isError = false
                    message = "Now using font: ${CaptionFontManager.displayName(path)}"
                } else {
                    isError = true
                    message = result.error
                }
            }
        }
    }

    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(FntCardDark)
            .padding(14.dp)
    ) {
        Text(
            text = "My fonts (upload any .ttf / .otf)",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "A font you upload is used by the preview and by the exported video.",
            color = FntSoft,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FntButton(
                text = if (busy) "Adding..." else "Upload font",
                modifier = Modifier.weight(1f),
                onClick = { if (!busy) picker.launch(arrayOf("*/*")) }
            )
            FntButton(
                text = "Default font",
                modifier = Modifier.weight(1f),
                filled = style.font != CaptionFont.CUSTOM,
                onClick = {
                    CaptionStore.style = CaptionStore.style.copy(
                        font = CaptionFont.SANS_BOLD,
                        customFontPath = null
                    )
                    isError = false
                    message = "Using the default font."
                }
            )
        }

        val fonts = CaptionFontManager.fonts
        fonts.forEach { path ->
            val selected = style.font == CaptionFont.CUSTOM && style.customFontPath == path
            val rowShape = RoundedCornerShape(12.dp)
            Row(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .clip(rowShape)
                    .background(if (selected) FntCard else FntNavy)
                    .border(
                        if (selected) 2.dp else 1.dp,
                        if (selected) FntBlue else FntBlue.copy(alpha = 0.3f),
                        rowShape
                    )
                    .clickable {
                        CaptionStore.style = CaptionStore.style.copy(
                            font = CaptionFont.CUSTOM,
                            customFontPath = path
                        )
                        isError = false
                        message = "Now using font: ${CaptionFontManager.displayName(path)}"
                    }
                    .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = (if (selected) "✓ " else "") + CaptionFontManager.displayName(path),
                    color = Color.White,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "✕",
                    color = FntWarn,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { CaptionFontManager.delete(context, path) }
                        .padding(10.dp)
                )
            }
        }

        // A sample line in the font that is really in use, so you can check it.
        if (style.font == CaptionFont.CUSTOM) {
            val path = style.customFontPath
            val typeface = if (path != null) CaptionFontManager.load(path) else null
            if (typeface != null) {
                Text(
                    text = "আজ সকালে তুমি ঘুম থেকে উঠলে। Sample 123",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontFamily = FontFamily(typeface),
                    modifier = Modifier.padding(top = 10.dp)
                )
                Text(
                    text = "If Bengali letters look like the normal phone font, this font has no Bengali. " +
                        "Android then fills those letters from the phone font.",
                    color = FntSoft,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
                Text(
                    text = "The chosen font file cannot be opened, so the default font is used.",
                    color = FntWarn,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        }

        val text = message
        if (text != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = text,
                color = if (isError) FntWarn else FntSoft,
                fontSize = 12.sp
            )
        }
    }
}
