package com.kamran.stickcut

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ExNavy = Color(0xFF050B1F)
private val ExBlue = Color(0xFF2D8CFF)
private val ExCard = Color(0xFF0F2F70)
private val ExDark = Color(0xFF0A1A44)
private val ExOk = Color(0xFF6BE3A0)
private val ExErr = Color(0xFFFF6B6B)
private val ExSoft = Color(0xFFB8D4FF)

private const val EXPORT_WIDTH = 1280
private const val EXPORT_HEIGHT = 720
private const val EXPORT_FPS = 30

private enum class ExportStatus { IDLE, RUNNING, SAVING, DONE, FAILED }

// Every picture is shown for exactly its scene time; the voiceover parts play back to back.
@OptIn(UnstableApi::class)
private fun buildComposition(
    scenes: List<Scene>,
    audios: List<ImportedAudio>
): Composition {
    val presentation = Presentation.createForWidthAndHeight(
        EXPORT_WIDTH,
        EXPORT_HEIGHT,
        Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
    )
    val imageItems = scenes.map { scene ->
        val mediaItem = MediaItem.Builder()
            .setUri(scene.imageUri)
            .setImageDurationMs(scene.durationMs)
            .build()
        EditedMediaItem.Builder(mediaItem)
            .setFrameRate(EXPORT_FPS)
            .setEffects(Effects(emptyList(), listOf<Effect>(presentation)))
            .build()
    }
    val sequences = mutableListOf(EditedMediaItemSequence(imageItems))
    if (audios.isNotEmpty()) {
        val audioItems = audios.map { audio ->
            EditedMediaItem.Builder(MediaItem.fromUri(audio.uri)).build()
        }
        sequences.add(EditedMediaItemSequence(audioItems))
    }
    return Composition.Builder(sequences).build()
}

// Copies the finished file into the phone's Movies/StickCut folder.
// Returns a readable place name, or null if copying failed.
private fun saveToMovies(context: Context, file: File, displayName: String): String? {
    return try {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/StickCut")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val target: Uri = resolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: return null
            resolver.openOutputStream(target)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } ?: return null
            val done = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            resolver.update(target, done, null, null)
            "Movies/StickCut/$displayName"
        } else {
            val folder = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "StickCut"
            )
            folder.mkdirs()
            val copy = File(folder, displayName)
            file.copyTo(copy, overwrite = true)
            copy.absolutePath
        }
    } catch (e: Exception) {
        null
    }
}

@OptIn(UnstableApi::class)
@Composable
fun ExportScreen(
    scenes: List<Scene>,
    audios: List<ImportedAudio>,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf(ExportStatus.IDLE) }
    var progress by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var transformer by remember { mutableStateOf<Transformer?>(null) }
    var outputFile by remember { mutableStateOf<File?>(null) }

    val running = status == ExportStatus.RUNNING || status == ExportStatus.SAVING
    val totalMs = scenes.lastOrNull()?.endMs ?: 0L

    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    fun cancelExport() {
        try {
            transformer?.cancel()
        } catch (e: Exception) {
            // ignore
        }
        transformer = null
    }

    fun leave() {
        if (status == ExportStatus.RUNNING) cancelExport()
        onClose()
    }

    BackHandler(enabled = true) { leave() }

    LaunchedEffect(status) {
        if (status != ExportStatus.RUNNING) return@LaunchedEffect
        val holder = ProgressHolder()
        while (true) {
            val t = transformer ?: break
            val state = t.getProgress(holder)
            if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                progress = holder.progress
            }
            delay(300)
        }
    }

    fun startExport() {
        if (running || scenes.isEmpty()) return
        message = null
        progress = 0
        val folder = File(context.cacheDir, "export")
        folder.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "StickCut_$stamp.mp4"
        val file = File(folder, name)
        if (file.exists()) file.delete()
        outputFile = file

        val composition = try {
            buildComposition(scenes, audios)
        } catch (e: Exception) {
            status = ExportStatus.FAILED
            message = "Could not prepare the export: ${e.message ?: "unknown error"}"
            return
        }

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                transformer = null
                progress = 100
                status = ExportStatus.SAVING
                scope.launch {
                    val place = withContext(Dispatchers.IO) { saveToMovies(context, file, name) }
                    if (place != null) {
                        status = ExportStatus.DONE
                        message = "Saved to: $place"
                    } else {
                        status = ExportStatus.FAILED
                        message = "The video was made but could not be saved to Movies."
                    }
                }
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                transformer = null
                status = ExportStatus.FAILED
                message = "Export failed: ${exportException.message ?: "unknown error"} " +
                    "(code ${exportException.errorCodeName})"
            }
        }

        try {
            val t = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(listener)
                .build()
            transformer = t
            status = ExportStatus.RUNNING
            t.start(composition, file.absolutePath)
        } catch (e: Exception) {
            transformer = null
            status = ExportStatus.FAILED
            message = "Could not start the export: ${e.message ?: "unknown error"}"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ExNavy)
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
                        .clickable { leave() }
                        .padding(8.dp)
                )
                Text(
                    text = "Export Video",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.size(44.dp))
            }
            Spacer(modifier = Modifier.height(12.dp))

            val infoShape = RoundedCornerShape(14.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(infoShape)
                    .background(ExDark)
                    .border(1.dp, ExBlue.copy(alpha = 0.6f), infoShape)
                    .padding(14.dp)
            ) {
                Text(
                    text = "Output",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "MP4 (H.264 + AAC)  •  ${EXPORT_WIDTH}×${EXPORT_HEIGHT}  •  $EXPORT_FPS fps",
                    color = ExSoft,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    text = "${scenes.size} scenes  •  ${ScriptParser.formatTimestamp(totalMs)}  •  ${audios.size} voiceover part(s)",
                    color = ExSoft,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Text(
                    text = "First version: pictures + voiceover only. Animation, transitions and captions come in the next steps.",
                    color = Color(0xFFFFC857),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            if (audios.isEmpty()) {
                Text(
                    text = "No voiceover is loaded, so the video will have no sound.",
                    color = Color(0xFFFFC857),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
            }

            if (running) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = ExBlue,
                    trackColor = Color(0xFF14264D)
                )
                Text(
                    text = if (status == ExportStatus.SAVING) {
                        "Saving to your phone..."
                    } else {
                        "Exporting... $progress%  (keep the app open)"
                    },
                    color = Color.White,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                if (status == ExportStatus.RUNNING) {
                    ExButton(
                        text = "Cancel export",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            cancelExport()
                            status = ExportStatus.IDLE
                            message = "Export cancelled."
                        }
                    )
                }
            } else {
                ExButton(
                    text = when (status) {
                        ExportStatus.DONE -> "Export again"
                        ExportStatus.FAILED -> "Try again"
                        else -> "▶  Start Export"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    filled = true,
                    onClick = { startExport() }
                )
            }

            val text = message
            if (text != null) {
                Text(
                    text = text,
                    color = when (status) {
                        ExportStatus.DONE -> ExOk
                        ExportStatus.FAILED -> ExErr
                        else -> ExSoft
                    },
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }

        CreatorBrand(modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun ExButton(
    text: String,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(shape)
            .background(if (filled) ExBlue else ExCard)
            .border(1.dp, ExBlue.copy(alpha = 0.7f), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
    }
}
