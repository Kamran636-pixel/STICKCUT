package com.kamran.stickcut

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodecList
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
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private val ExNavy = Color(0xFF050B1F)
private val ExBlue = Color(0xFF2D8CFF)
private val ExCard = Color(0xFF0F2F70)
private val ExDark = Color(0xFF0A1A44)
private val ExOk = Color(0xFF6BE3A0)
private val ExErr = Color(0xFFFF6B6B)
private val ExWarn = Color(0xFFFFC857)
private val ExSoft = Color(0xFFB8D4FF)

private enum class ExportStatus { IDLE, RUNNING, SAVING, DONE, FAILED }

private data class ResolutionOption(val label: String, val width: Int, val height: Int)

private val resolutionOptions = listOf(
    ResolutionOption("720p", 1280, 720),
    ResolutionOption("1080p", 1920, 1080),
    ResolutionOption("1440p", 2560, 1440),
    ResolutionOption("4K", 3840, 2160)
)

private val fpsOptions = listOf(24, 30, 60, 90)

// Kept only so older code that may still name it keeps compiling.
object ExportDebug {
    val firstFrameMs = ConcurrentHashMap<Int, Long>()
}

// Writes an error as: full class name, message, and the first lines of the stack.
// It follows the "caused by" chain, so a hidden cause is never lost.
private fun describeError(error: Throwable): String {
    val text = StringBuilder()
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth < 4) {
        if (depth > 0) text.append("\nCaused by: ")
        text.append(current.javaClass.name)
        val detail = current.message
        if (!detail.isNullOrBlank()) {
            text.append(": ").append(detail)
        } else {
            text.append(" (no message)")
        }
        val frames = current.stackTrace
        val shown = minOf(3, frames.size)
        for (i in 0 until shown) {
            text.append("\n   at ").append(frames[i].toString())
        }
        current = current.cause
        depth++
    }
    return text.toString()
}

// Video bitrate in bits per second for a size and frame rate.
private fun targetBitrate(width: Int, height: Int, fps: Int): Int {
    val pixels = width.toLong() * height.toLong()
    val base = when {
        pixels <= 1280L * 720L -> 8_000_000L
        pixels <= 1920L * 1080L -> 16_000_000L
        pixels <= 2560L * 1440L -> 30_000_000L
        else -> 50_000_000L
    }
    val scaled = base * fps / 30L
    return scaled.coerceAtMost(120_000_000L).toInt()
}

// Does this phone have an H.264 encoder that accepts this size and frame rate?
private fun phoneSupports(width: Int, height: Int, fps: Int): Boolean {
    return try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder &&
                info.supportedTypes.any { it.equals(MimeTypes.VIDEO_H264, ignoreCase = true) } &&
                try {
                    val caps = info.getCapabilitiesForType(MimeTypes.VIDEO_H264).videoCapabilities
                    caps != null && caps.areSizeAndRateSupported(width, height, fps.toDouble())
                } catch (e: Exception) {
                    false
                }
        }
    } catch (e: Exception) {
        false
    }
}

// Keeps the bitrate inside what the phone's encoder allows.
private fun clampBitrate(bitrate: Int): Int {
    return try {
        var result = bitrate
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.forEach { info ->
            if (info.isEncoder &&
                info.supportedTypes.any { it.equals(MimeTypes.VIDEO_H264, ignoreCase = true) }
            ) {
                val caps = info.getCapabilitiesForType(MimeTypes.VIDEO_H264).videoCapabilities
                val range = caps?.bitrateRange
                if (range != null) {
                    result = range.clamp(bitrate)
                    return result
                }
            }
        }
        result
    } catch (e: Exception) {
        bitrate
    }
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

private fun animationLine(settings: AnimationSettings): String {
    return when (settings.mode) {
        AnimationMode.GLOBAL -> "Animation: ${settings.global.type.label} (all scenes)"
        AnimationMode.PER_SCENE -> "Animation: per scene (${settings.perScene.size} custom)"
        AnimationMode.RANDOM -> "Animation: random"
    }
}

// 75000 ms -> "1m 15s"
private fun durationText(ms: Long): String {
    val seconds = ms / 1000L
    val minutes = seconds / 60L
    val rest = seconds % 60L
    return if (minutes > 0L) "${minutes}m ${rest}s" else "${rest}s"
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
    val settings = AnimationStore.settings

    var status by remember { mutableStateOf(ExportStatus.IDLE) }
    var progress by remember { mutableStateOf(0) }
    var stage by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var timingNote by remember { mutableStateOf<String?>(null) }
    var resolutionIndex by remember { mutableStateOf(0) }
    var fps by remember { mutableStateOf(30) }
    // Set to true to stop the renderer.
    val renderCancel = remember { AtomicBoolean(false) }

    val running = status == ExportStatus.RUNNING || status == ExportStatus.SAVING
    val totalMs = scenes.lastOrNull()?.endMs ?: 0L

    val resolution = resolutionOptions[resolutionIndex]
    val supported = remember(resolutionIndex, fps) {
        phoneSupports(resolution.width, resolution.height, fps)
    }
    val bitrate = targetBitrate(resolution.width, resolution.height, fps)
    val estimatedMb = (bitrate.toLong() * (totalMs / 1000L) / 8L / 1_000_000L) + 1L

    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    fun leave() {
        if (status == ExportStatus.RUNNING) {
            renderCancel.set(true)
        }
        onClose()
    }

    BackHandler(enabled = true) { leave() }

    // Draws every frame itself, so zoom, transitions, scene fades and captions
    // really appear in the video. Then the voiceover is joined to the picture.
    // Without fades the sound is copied as it is (fast); with fades the sound
    // has to be re-encoded (slower).
    fun startRender() {
        if (running || scenes.isEmpty()) return
        message = null
        timingNote = null
        progress = 0
        stage = "Drawing the video"
        val folder = File(context.cacheDir, "export")
        folder.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "StickCut_${resolution.label}_${fps}fps_$stamp.mp4"
        val videoFile = File(folder, "tmp_video_$stamp.mp4")
        val voiceFile = File(folder, "tmp_voice_$stamp.mp4")
        val finalFile = File(folder, name)

        val width = resolution.width
        val height = resolution.height
        val chosenFps = fps
        val chosenBitrate = clampBitrate(bitrate)
        val animation = AnimationStore.settings
        val cuts = CutTransitionStore.state
        val fades = SceneFadeStore.state
        val voices = audios
        val videoTotalMs = totalMs

        renderCancel.set(false)
        status = ExportStatus.RUNNING
        scope.launch(Dispatchers.Default) {
            fun cleanUp() {
                try {
                    videoFile.delete()
                    voiceFile.delete()
                    finalFile.delete()
                } catch (e: Exception) {
                    // ignore
                }
            }

            val startedAt = System.currentTimeMillis()
            val composer = FrameComposer(context, scenes, animation, cuts, fades, width, height)
            val result = try {
                VideoRenderer.render(
                    composer,
                    width,
                    height,
                    chosenFps,
                    chosenBitrate,
                    videoFile,
                    { percent -> progress = percent },
                    { renderCancel.get() }
                )
            } catch (e: Throwable) {
                VideoRenderer.Result(false, false, "Video render failed:\n" + describeError(e))
            }
            composer.release()
            val drawMs = System.currentTimeMillis() - startedAt

            if (result.cancelled) {
                cleanUp()
                status = ExportStatus.IDLE
                message = "Export cancelled."
                return@launch
            }
            if (!result.ok) {
                cleanUp()
                status = ExportStatus.FAILED
                message = result.message ?: "The render failed."
                return@launch
            }

            var fileToSave = videoFile
            var savedName = name
            var soundNote = ""
            var soundMs = 0L
            var joinMs = 0L
            var soundWay = ""
            if (voices.isNotEmpty()) {
                progress = 100
                val soundStart = System.currentTimeMillis()
                val direct = FastAudio.canCopyDirectly(context, voices, fades, videoTotalMs)

                if (!direct) {
                    stage = "Preparing the voiceover (fades need re-encoding, this takes a while)"
                    val voiceError = try {
                        AudioMixer.exportVoiceover(
                            context, voices, fades, videoTotalMs, voiceFile
                        ) { renderCancel.get() }
                    } catch (e: Throwable) {
                        "Voiceover failed:\n" + describeError(e)
                    }
                    if (voiceError == AudioMixer.CANCELLED) {
                        cleanUp()
                        status = ExportStatus.IDLE
                        message = "Export cancelled."
                        return@launch
                    }
                    if (voiceError != null) {
                        cleanUp()
                        status = ExportStatus.FAILED
                        message = voiceError
                        return@launch
                    }
                }
                soundMs = System.currentTimeMillis() - soundStart
                soundWay = if (direct) "copied as it is" else "re-encoded"

                stage = "Joining sound and video"
                val joinStart = System.currentTimeMillis()
                val mergeError = withContext(Dispatchers.IO) {
                    if (direct) {
                        FastAudio.merge(context, videoFile, voices[0].uri, finalFile)
                    } else {
                        MediaMerger.merge(videoFile, voiceFile, finalFile)
                    }
                }
                joinMs = System.currentTimeMillis() - joinStart
                if (mergeError != null) {
                    cleanUp()
                    status = ExportStatus.FAILED
                    message = mergeError
                    return@launch
                }
                fileToSave = finalFile
            } else {
                savedName = "StickCut_${resolution.label}_${chosenFps}fps_${stamp}_nosound.mp4"
                soundNote = " (no voiceover was loaded, so no sound)"
            }

            status = ExportStatus.SAVING
            val saveStart = System.currentTimeMillis()
            val place = withContext(Dispatchers.IO) { saveToMovies(context, fileToSave, savedName) }
            val saveMs = System.currentTimeMillis() - saveStart
            cleanUp()
            if (place != null) {
                status = ExportStatus.DONE
                message = "Saved to: $place$soundNote"
                timingNote = "Time taken:\n" +
                    "drawing video: ${durationText(drawMs)}\n" +
                    (if (voices.isNotEmpty()) "sound ($soundWay): ${durationText(soundMs)}\n" else "") +
                    (if (voices.isNotEmpty()) "joining: ${durationText(joinMs)}\n" else "") +
                    "saving to Movies: ${durationText(saveMs)}"
            } else {
                status = ExportStatus.FAILED
                message = "The video was made but could not be saved to Movies."
            }
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

            if (!running) {
                Text(
                    text = "Resolution",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    resolutionOptions.forEachIndexed { index, option ->
                        val ok = phoneSupports(option.width, option.height, fps)
                        ExChip(
                            text = if (ok) option.label else "⚠ ${option.label}",
                            selected = index == resolutionIndex,
                            modifier = Modifier.weight(1f),
                            onClick = { resolutionIndex = index }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Frame rate",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    fpsOptions.forEach { option ->
                        val ok = phoneSupports(resolution.width, resolution.height, option)
                        ExChip(
                            text = if (ok) "$option fps" else "⚠ $option fps",
                            selected = option == fps,
                            modifier = Modifier.weight(1f),
                            onClick = { fps = option }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))

                // Fade in / fade out of the whole video (picture and sound).
                SceneFadesPanel(totalMs)
                Text(
                    text = "Fades make the sound step slower: the voiceover must be re-encoded. With fades Off the sound is copied as it is and this step is quick.",
                    color = ExWarn,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

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
                    text = "MP4 (H.264 + AAC)  •  ${resolution.width}×${resolution.height}  •  $fps fps",
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
                    text = "Quality: ${bitrate / 1_000_000} Mbps  •  about $estimatedMb MB",
                    color = ExSoft,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Text(
                    text = animationLine(settings),
                    color = ExOk,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Text(
                    text = if (CaptionStore.style.enabled) "Captions: on" else "Captions: off (turn them on in Captions)",
                    color = ExOk,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
                if (!supported) {
                    Text(
                        text = "⚠ This phone's video encoder does not list this size/frame rate as supported. The export may fail.",
                        color = ExWarn,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                Text(
                    text = "The video includes zoom, transitions, scene fades, captions and sound.",
                    color = ExWarn,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    text = "Export build: Step 75",
                    color = Color(0xFF6C85B8),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            if (audios.isEmpty()) {
                Text(
                    text = "No voiceover is loaded, so the video will have no sound.",
                    color = ExWarn,
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
                    text = when {
                        status == ExportStatus.SAVING -> "Saving to your phone..."
                        stage == "Drawing the video" -> "$stage... $progress%  (keep the app open)"
                        stage.isNotEmpty() -> "$stage...  (keep the app open)"
                        else -> "Exporting... $progress%  (keep the app open)"
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
                        onClick = { renderCancel.set(true) }
                    )
                }
            } else {
                ExButton(
                    text = when (status) {
                        ExportStatus.DONE -> "Export again"
                        ExportStatus.FAILED -> "Try again"
                        else -> "▶  Export Video"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    filled = true,
                    onClick = { startRender() }
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
                    fontSize = if (status == ExportStatus.FAILED) 12.sp else 14.sp,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            val timing = timingNote
            if (timing != null) {
                Text(
                    text = timing,
                    color = Color(0xFF6C85B8),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        CreatorBrand(modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun ExChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(shape)
            .background(if (selected) ExBlue else ExCard)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) Color.White else ExBlue.copy(alpha = 0.6f),
                shape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
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
