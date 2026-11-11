package com.kamran.stickcut

import android.media.MediaPlayer
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest

private val PvNavy = Color(0xFF050B1F)
private val PvBlue = Color(0xFF2D8CFF)
private val PvCard = Color(0xFF0F2F70)
private val PvDark = Color(0xFF0A1A44)
private val PvSoft = Color(0xFFB8D4FF)
private val PvWarn = Color(0xFFFFC857)

// Keeps the imported voiceover parts so the preview can play them.
// Phase 15 (project saving) will store them permanently.
object ProjectStore {
    var audios by mutableStateOf<List<ImportedAudio>>(emptyList())
}

private data class Blend(
    val outIndex: Int,
    val inIndex: Int,
    val q: Float,
    val type: TransitionType
)

private data class LayerParams(
    val alpha: Float,
    val scale: Float,
    val tx: Float
)

private fun findBlend(
    scenes: List<Scene>,
    planned: List<PlannedScene>,
    base: Int,
    positionMs: Long,
    type: TransitionType
): Blend? {
    if (type == TransitionType.NONE) return null
    for (k in intArrayOf(base, base + 1)) {
        if (k < 1 || k >= scenes.size) continue
        val length = planned[k].transitionInMs
        if (length <= 0L) continue
        val windowStart = scenes[k].startMs - length / 2L
        if (positionMs >= windowStart && positionMs < windowStart + length) {
            val q = (positionMs - windowStart).toFloat() / length.toFloat()
            return Blend(k - 1, k, q, type)
        }
    }
    return null
}

// role: 0 = not in a transition, 1 = leaving scene, 2 = arriving scene
private fun layerParams(role: Int, isBase: Boolean, blend: Blend?): LayerParams {
    if (blend == null || role == 0) {
        return LayerParams(if (isBase && blend == null) 1f else 0f, 1f, 0f)
    }
    val q = blend.q
    return when (blend.type) {
        TransitionType.FADE -> if (role == 1) {
            LayerParams(if (q < 0.5f) 1f - 2f * q else 0f, 1f, 0f)
        } else {
            LayerParams(if (q >= 0.5f) 2f * q - 1f else 0f, 1f, 0f)
        }

        TransitionType.CROSS_DISSOLVE -> if (role == 1) {
            LayerParams(1f, 1f, 0f)
        } else {
            LayerParams(q, 1f, 0f)
        }

        TransitionType.SLIDE_LEFT -> if (role == 1) {
            LayerParams(1f, 1f, -q)
        } else {
            LayerParams(1f, 1f, 1f - q)
        }

        TransitionType.SLIDE_RIGHT -> if (role == 1) {
            LayerParams(1f, 1f, q)
        } else {
            LayerParams(1f, 1f, -(1f - q))
        }

        TransitionType.ZOOM -> if (role == 1) {
            LayerParams(1f - q, 1f + 0.3f * q, 0f)
        } else {
            LayerParams(q, 0.85f + 0.15f * q, 0f)
        }

        TransitionType.NONE -> LayerParams(if (isBase) 1f else 0f, 1f, 0f)
    }
}

@Composable
private fun SceneLayer(
    scene: Scene,
    planned: PlannedScene,
    positionMs: Long,
    params: LayerParams
) {
    val context = LocalContext.current
    val request = remember(scene.imageUri) {
        ImageRequest.Builder(context)
            .data(scene.imageUri)
            .size(1080)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = scene.imageName,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val t = AnimationEngine.transformAtTime(
                    planned.config,
                    planned,
                    positionMs - scene.startMs
                )
                alpha = params.alpha
                scaleX = t.scale * params.scale
                scaleY = t.scale * params.scale
                translationX = (t.offsetX + params.tx) * size.width
                translationY = t.offsetY * size.height
            }
    )
}

@Composable
fun PreviewPlayerScreen(
    scenes: List<Scene>,
    audios: List<ImportedAudio>,
    settings: AnimationSettings,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    BackHandler(enabled = true) { onClose() }

    val planned = remember(scenes, settings) { AnimationEngine.plan(scenes, settings) }
    val totalMs = (scenes.lastOrNull()?.endMs ?: 0L).coerceAtLeast(1L)
    val hasAudio = audios.isNotEmpty()
    val partStarts = remember(audios) {
        var running = 0L
        audios.map { audio ->
            val start = running
            running += audio.durationMs
            start
        }
    }

    var positionMs by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var currentPart by remember { mutableStateOf(0) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var scrubbing by remember { mutableStateOf(false) }
    var resumeAfterScrub by remember { mutableStateOf(false) }

    fun releasePlayer() {
        val p = player
        player = null
        if (p != null) {
            try {
                p.release()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    fun startAt(startMs: Long) {
        releasePlayer()
        val target = startMs.coerceIn(0L, totalMs)
        if (!hasAudio) {
            positionMs = target
            isPlaying = true
            return
        }
        val index = partStarts.indexOfLast { it <= target }.coerceAtLeast(0)
        val offset = (target - partStarts[index]).coerceIn(0L, (audios[index].durationMs - 1L).coerceAtLeast(0L))
        try {
            val mp = MediaPlayer()
            mp.setDataSource(context, audios[index].uri)
            mp.setOnCompletionListener { finished ->
                player = null
                try {
                    finished.release()
                } catch (e: Exception) {
                    // ignore
                }
                if (index + 1 < audios.size) {
                    startAt(partStarts[index + 1])
                } else {
                    isPlaying = false
                    positionMs = totalMs
                }
            }
            mp.prepare()
            mp.seekTo(offset.toInt())
            mp.start()
            player = mp
            currentPart = index
            positionMs = target
            isPlaying = true
            errorText = null
        } catch (e: Exception) {
            releasePlayer()
            isPlaying = false
            errorText = "Voiceover part ${index + 1} (${audios[index].name}) could not be played."
        }
    }

    fun pausePlayback() {
        val p = player
        if (p != null) {
            try {
                positionMs = (partStarts[currentPart] + p.currentPosition).coerceIn(0L, totalMs)
            } catch (e: Exception) {
                // keep the last known position
            }
        }
        releasePlayer()
        isPlaying = false
    }

    fun seekTo(ms: Long) {
        val target = ms.coerceIn(0L, totalMs)
        val wasPlaying = isPlaying
        releasePlayer()
        positionMs = target
        if (wasPlaying) startAt(target) else isPlaying = false
    }

    DisposableEffect(Unit) {
        onDispose { releasePlayer() }
    }

    // The clock. With a voiceover, time always comes from the audio itself,
    // so the pictures can never drift away from the narration.
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        var lastNanos = 0L
        while (true) {
            val now = withFrameNanos { it }
            if (hasAudio) {
                val p = player
                if (p != null) {
                    try {
                        positionMs = (partStarts[currentPart] + p.currentPosition).coerceIn(0L, totalMs)
                    } catch (e: Exception) {
                        // ignore one bad read
                    }
                }
            } else {
                if (lastNanos != 0L) {
                    positionMs = (positionMs + (now - lastNanos) / 1_000_000L).coerceAtMost(totalMs)
                }
                lastNanos = now
                if (positionMs >= totalMs) {
                    isPlaying = false
                }
            }
        }
    }

    val baseIndex = scenes.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)
    val currentScene = scenes.getOrNull(baseIndex)
    val blend = if (scenes.isEmpty()) {
        null
    } else {
        findBlend(scenes, planned, baseIndex, positionMs, settings.transition.type)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PvNavy)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 110.dp)
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
                    text = "Preview",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.size(44.dp))
            }
            Spacer(modifier = Modifier.height(10.dp))

            val frameShape = RoundedCornerShape(16.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(frameShape)
                    .background(Color.Black)
                    .border(1.dp, PvBlue.copy(alpha = 0.6f), frameShape)
            ) {
                if (scenes.isEmpty()) {
                    Text(
                        text = "No scenes to preview.",
                        color = PvSoft,
                        fontSize = 14.sp,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    val indices = listOf(baseIndex - 1, baseIndex, baseIndex + 1)
                        .filter { it in scenes.indices }
                    indices.forEach { index ->
                        val role = when {
                            blend == null -> 0
                            index == blend.outIndex -> 1
                            index == blend.inIndex -> 2
                            else -> 0
                        }
                        SceneLayer(
                            scene = scenes[index],
                            planned = planned[index],
                            positionMs = positionMs,
                            params = layerParams(role, index == baseIndex, blend)
                        )
                    }
                }
            }

            if (currentScene != null) {
                Text(
                    text = "Scene #${currentScene.index}  •  Image ${currentScene.imageNumber}  •  Part ${currentScene.partPosition + 1}  •  🎞 ${planned[baseIndex].config.type.label}",
                    color = PvBlue,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    text = currentScene.text,
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            Slider(
                value = positionMs.toFloat().coerceIn(0f, totalMs.toFloat()),
                onValueChange = { value ->
                    if (!scrubbing) {
                        scrubbing = true
                        resumeAfterScrub = isPlaying
                        if (isPlaying) pausePlayback()
                    }
                    positionMs = value.toLong()
                },
                onValueChangeFinished = {
                    scrubbing = false
                    val resume = resumeAfterScrub
                    resumeAfterScrub = false
                    seekTo(positionMs)
                    if (resume) startAt(positionMs)
                },
                valueRange = 0f..totalMs.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = PvBlue,
                    activeTrackColor = PvBlue,
                    inactiveTrackColor = Color(0xFF14264D)
                )
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = ScriptParser.formatTimestamp(positionMs),
                    color = Color.White,
                    fontSize = 13.sp
                )
                Text(
                    text = ScriptParser.formatTimestamp(totalMs),
                    color = PvSoft,
                    fontSize = 13.sp
                )
            }
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PvRoundButton(
                    text = "⏮",
                    size = 52,
                    onClick = {
                        val previous = scenes.getOrNull(baseIndex - 1)
                        seekTo(previous?.startMs ?: 0L)
                    }
                )
                PvRoundButton(
                    text = if (isPlaying) "⏸" else "▶",
                    size = 68,
                    filled = true,
                    onClick = {
                        if (isPlaying) {
                            pausePlayback()
                        } else if (positionMs >= totalMs - 50L) {
                            startAt(0L)
                        } else {
                            startAt(positionMs)
                        }
                    }
                )
                PvRoundButton(
                    text = "⏭",
                    size = 52,
                    onClick = {
                        val next = scenes.getOrNull(baseIndex + 1)
                        if (next != null) seekTo(next.startMs)
                    }
                )
            }

            val message = errorText
            if (message != null) {
                Text(
                    text = message,
                    color = PvWarn,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            if (!hasAudio) {
                Text(
                    text = "No voiceover available here, so the preview plays silently.",
                    color = PvWarn,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }
            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Jump to a scene (${scenes.size})",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(scenes, key = { it.index }) { scene ->
                    val isCurrent = scene.index == currentScene?.index
                    val thumbShape = RoundedCornerShape(10.dp)
                    val thumbRequest = remember(scene.imageUri) {
                        ImageRequest.Builder(context)
                            .data(scene.imageUri)
                            .size(200)
                            .build()
                    }
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(thumbShape)
                            .border(
                                if (isCurrent) 3.dp else 1.dp,
                                if (isCurrent) Color.White else PvBlue.copy(alpha = 0.5f),
                                thumbShape
                            )
                            .clickable { seekTo(scene.startMs) }
                    ) {
                        AsyncImage(
                            model = thumbRequest,
                            contentDescription = scene.imageName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Text(
                            text = "${scene.index}",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .background(PvNavy.copy(alpha = 0.75f))
                                .padding(horizontal = 4.dp)
                        )
                    }
                }
            }
        }

        CreatorBrand(modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun PvRoundButton(
    text: String,
    size: Int,
    filled: Boolean = false,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape((size / 2).dp)
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(shape)
            .background(if (filled) PvBlue else PvCard)
            .border(1.dp, PvBlue.copy(alpha = 0.7f), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = (size / 2.3f).sp
        )
    }
}
