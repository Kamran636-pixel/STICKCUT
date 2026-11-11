package com.kamran.stickcut

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import java.util.Locale

private val AsNavy = Color(0xFF050B1F)
private val AsBlue = Color(0xFF2D8CFF)
private val AsCard = Color(0xFF0F2F70)
private val AsDark = Color(0xFF0A1A44)
private val AsSoft = Color(0xFFB8D4FF)
private val AsWarn = Color(0xFFFFC857)

private fun secondsLabel(ms: Long): String {
    return String.format(Locale.US, "%.1fs", ms / 1000f)
}

@Composable
fun AnimationSettingsScreen(
    settings: AnimationSettings,
    scenes: List<Scene>,
    onSettingsChange: (AnimationSettings) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    BackHandler(enabled = true) { onClose() }

    var selectedPos by remember { mutableStateOf(0) }
    var playKey by remember { mutableStateOf(0) }

    val mode = settings.mode
    val safePos = if (selectedPos in scenes.indices) selectedPos else 0
    val selectedScene = scenes.getOrNull(safePos)
    val previewScene: Scene? = if (mode == AnimationMode.PER_SCENE) selectedScene else scenes.firstOrNull()

    // What the settings really give for the previewed scene.
    val planned = remember(settings, scenes) { AnimationEngine.plan(scenes, settings) }
    val plannedForPreview = planned.firstOrNull { it.sceneIndex == previewScene?.index }

    val editing: AnimationConfig = if (mode == AnimationMode.PER_SCENE && selectedScene != null) {
        settings.perScene[selectedScene.index] ?: settings.global
    } else {
        settings.global
    }

    val previewConfig = plannedForPreview?.config ?: editing
    val wantedMs = (previewConfig.durationMs.toFloat() / previewConfig.speed.factor).toLong()
    val previewMs = plannedForPreview?.activeMs ?: wantedMs

    fun updateEditing(newConfig: AnimationConfig) {
        val fixed = newConfig.normalized()
        val next = if (mode == AnimationMode.PER_SCENE && selectedScene != null) {
            settings.copy(perScene = settings.perScene + (selectedScene.index to fixed))
        } else {
            settings.copy(global = fixed)
        }
        onSettingsChange(next)
    }

    val progress = remember { Animatable(0f) }
    LaunchedEffect(previewConfig, previewMs, playKey, previewScene?.index) {
        progress.snapTo(0f)
        if (previewConfig.type != AnimationType.NONE && previewMs > 0L) {
            while (true) {
                progress.snapTo(0f)
                progress.animateTo(1f, tween(previewMs.toInt().coerceAtLeast(1), easing = LinearEasing))
                delay(700)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AsNavy)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 130.dp)
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
                    text = "Animation Settings",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.size(44.dp))
            }
            Spacer(modifier = Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnimationMode.values().forEach { m ->
                    AsChip(
                        text = m.label,
                        selected = m == mode,
                        modifier = Modifier.weight(1f),
                        onClick = { onSettingsChange(settings.copy(mode = m)) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            val boxShape = RoundedCornerShape(18.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(boxShape)
                    .background(Brush.verticalGradient(listOf(AsCard, AsDark)))
                    .border(1.dp, AsBlue.copy(alpha = 0.6f), boxShape),
                contentAlignment = Alignment.Center
            ) {
                if (previewScene == null) {
                    Text(
                        text = "Create scenes first (Start Auto Edit),\nthen come back to preview animations.",
                        color = AsSoft,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(previewScene.imageUri)
                            .size(900)
                            .build(),
                        contentDescription = previewScene.imageName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val t = AnimationEngine.transformAt(previewConfig, progress.value)
                                scaleX = t.scale
                                scaleY = t.scale
                                translationX = t.offsetX * size.width
                                translationY = t.offsetY * size.height
                            }
                    )
                }
            }
            Text(
                text = if (previewScene != null) {
                    "Preview: ${previewConfig.type.label}  •  moves for ${secondsLabel(previewMs)}" +
                        if (previewMs < wantedMs) " (scene is shorter)" else ""
                } else {
                    ""
                },
                color = AsSoft,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            AsButton(
                text = "▶  Preview",
                modifier = Modifier.fillMaxWidth(),
                onClick = { playKey += 1 }
            )
            Spacer(modifier = Modifier.height(14.dp))

            if (mode == AnimationMode.PER_SCENE) {
                Text(
                    text = "Choose a scene (${scenes.size})",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                if (scenes.isEmpty()) {
                    Text(
                        text = "No scenes yet.",
                        color = AsWarn,
                        fontSize = 13.sp
                    )
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(scenes, key = { it.index }) { scene ->
                            val isSelected = scene.index == selectedScene?.index
                            val thumbShape = RoundedCornerShape(10.dp)
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(thumbShape)
                                    .border(
                                        if (isSelected) 3.dp else 1.dp,
                                        if (isSelected) Color.White else AsBlue.copy(alpha = 0.5f),
                                        thumbShape
                                    )
                                    .clickable { selectedPos = scenes.indexOf(scene) }
                            ) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(scene.imageUri)
                                        .size(200)
                                        .build(),
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
                                        .background(AsNavy.copy(alpha = 0.75f))
                                        .padding(horizontal = 4.dp)
                                )
                            }
                        }
                    }
                    if (selectedScene != null) {
                        val custom = settings.perScene.containsKey(selectedScene.index)
                        Text(
                            text = if (custom) {
                                "Scene ${selectedScene.index} has its own animation."
                            } else {
                                "Scene ${selectedScene.index} uses the Global animation until you change it."
                            },
                            color = AsSoft,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            if (mode == AnimationMode.RANDOM) {
                Text(
                    text = "Every scene gets a different animation, and the same one never repeats twice in a row. " +
                        "The order stays the same in the preview and in the exported video.",
                    color = AsSoft,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                AsButton(
                    text = "🔀  Shuffle again",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        onSettingsChange(settings.copy(randomSeed = settings.randomSeed + 1L))
                        playKey += 1
                    }
                )
                Spacer(modifier = Modifier.height(14.dp))
            } else {
                Text(
                    text = "Animation Type",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                AnimationType.values().toList().chunked(2).forEach { rowTypes ->
                    Row(
                        modifier = Modifier.padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowTypes.forEach { type ->
                            AsChip(
                                text = type.label,
                                selected = type == editing.type,
                                modifier = Modifier.weight(1f),
                                onClick = { updateEditing(editing.copy(type = type)) }
                            )
                        }
                        if (rowTypes.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            AsSliderRow(
                label = "Duration",
                valueText = secondsLabel(editing.durationMs),
                value = editing.durationMs / 1000f,
                range = 0.5f..10f,
                onChange = { updateEditing(editing.copy(durationMs = (it * 1000f).toLong())) }
            )

            Text(
                text = "Speed",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpeedPreset.values().forEach { preset ->
                    AsChip(
                        text = preset.label,
                        selected = preset == editing.speed,
                        modifier = Modifier.weight(1f),
                        onClick = { updateEditing(editing.copy(speed = preset)) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            AsSliderRow(
                label = "Intensity",
                valueText = String.format(Locale.US, "%.1fx", editing.intensity),
                value = editing.intensity,
                range = AnimationConfig.MIN_INTENSITY..AnimationConfig.MAX_INTENSITY,
                onChange = { updateEditing(editing.copy(intensity = it)) }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AsButton(
                    text = "Apply to All",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onSettingsChange(settings.copy(global = editing.normalized(), perScene = emptyMap()))
                    }
                )
                AsButton(
                    text = "Reset",
                    modifier = Modifier.weight(1f),
                    onClick = { updateEditing(AnimationConfig()) }
                )
            }
            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "Transition",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            TransitionType.values().toList().chunked(2).forEach { rowTypes ->
                Row(
                    modifier = Modifier.padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowTypes.forEach { type ->
                        AsChip(
                            text = type.label,
                            selected = type == settings.transition.type,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onSettingsChange(
                                    settings.copy(transition = settings.transition.copy(type = type))
                                )
                            }
                        )
                    }
                    if (rowTypes.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
            if (settings.transition.type != TransitionType.NONE) {
                AsSliderRow(
                    label = "Transition length",
                    valueText = secondsLabel(settings.transition.durationMs),
                    value = settings.transition.durationMs / 1000f,
                    range = 0.1f..2.0f,
                    onChange = {
                        onSettingsChange(
                            settings.copy(
                                transition = settings.transition.copy(durationMs = (it * 1000f).toLong())
                            )
                        )
                    }
                )
                Text(
                    text = "Very short scenes get a shorter transition automatically, so two transitions never overlap.",
                    color = AsSoft,
                    fontSize = 12.sp
                )
            }
            Spacer(modifier = Modifier.height(20.dp))

            val doneShape = RoundedCornerShape(16.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(doneShape)
                    .background(AsBlue)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Done  ✓",
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
private fun AsSliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(bottom = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = valueText,
                color = AsBlue,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = AsBlue,
                activeTrackColor = AsBlue,
                inactiveTrackColor = Color(0xFF14264D)
            )
        )
    }
}

@Composable
private fun AsChip(
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
            .background(if (selected) AsBlue else AsCard)
            .border(1.dp, AsBlue.copy(alpha = 0.7f), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
    }
}

@Composable
private fun AsButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(shape)
            .background(AsCard)
            .border(1.dp, AsBlue.copy(alpha = 0.7f), shape)
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
