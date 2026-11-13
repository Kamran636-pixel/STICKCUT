package com.kamran.stickcut

import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File

private val CsNavy = Color(0xFF050B1F)
private val CsBlue = Color(0xFF2D8CFF)
private val CsCard = Color(0xFF0F2F70)
private val CsDark = Color(0xFF0A1A44)
private val CsOk = Color(0xFF6BE3A0)
private val CsWarn = Color(0xFFFFC857)
private val CsSoft = Color(0xFFB8D4FF)

private val textPalette = listOf(
    0xFFFFFFFFL, 0xFFFFD400L, 0xFF00E5FFL, 0xFF6BE3A0L,
    0xFFFF4081L, 0xFFFF9100L, 0xFFFF3B30L, 0xFF000000L
)
private val outlinePalette = listOf(
    0xFF000000L, 0xFFFFFFFFL, 0xFF050B1FL, 0xFFFF3B30L, 0xFF2D8CFFL
)
private val highlightPalette = listOf(
    0xFFFFD400L, 0xFFFF4081L, 0xFF00E5FFL, 0xFF6BE3A0L, 0xFFFF9100L, 0xFFFF3B30L
)

// The font family for a style. Custom fonts are loaded from the saved file.
fun captionFontFamily(style: CaptionStyle): FontFamily {
    return when (style.font) {
        CaptionFont.SANS, CaptionFont.SANS_BOLD -> FontFamily.SansSerif
        CaptionFont.SERIF -> FontFamily.Serif
        CaptionFont.MONO -> FontFamily.Monospace
        CaptionFont.CONDENSED -> FontFamily(Typeface.create("sans-serif-condensed", Typeface.NORMAL))
        CaptionFont.CUSTOM -> {
            val path = style.customFontPath
            try {
                if (path != null && File(path).exists()) {
                    FontFamily(Typeface.createFromFile(path))
                } else {
                    FontFamily.SansSerif
                }
            } catch (e: Exception) {
                FontFamily.SansSerif
            }
        }
    }
}

// Draws one caption on top of a video frame. Fills its parent, so put it inside the frame box.
@Composable
fun CaptionOverlay(
    chunk: CaptionChunk,
    style: CaptionStyle,
    localMs: Long,
    modifier: Modifier = Modifier
) {
    val frame = CaptionAnimator.frameAt(chunk, style, localMs)
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier) {
        val frameHeightPx = with(density) { maxHeight.toPx() }
        val textSizePx = frameHeightPx * style.sizePercent / 100f
        val fontSize = with(density) { textSizePx.toSp() }
        val outlinePx = textSizePx * style.outlineWidth * 2f
        val family = captionFontFamily(style)
        val weight = if (style.font == CaptionFont.SANS_BOLD) FontWeight.Bold else FontWeight.Normal

        val displayWords = chunk.words.map { CaptionEngine.displayText(it, style) }
        val fullText = displayWords.joinToString(" ")
        val shown = if (style.animation == CaptionAnimation.TYPEWRITER) {
            fullText.take(frame.visibleChars)
        } else {
            fullText
        }

        val fillColor = Color(style.textColor)
        val highlight = Color(style.highlightColor)
        val annotated = buildAnnotatedString {
            if (style.animation == CaptionAnimation.WORD_POP) {
                displayWords.forEachIndexed { index, word ->
                    if (index > 0) append(" ")
                    withStyle(SpanStyle(color = if (index == frame.activeWord) highlight else fillColor)) {
                        append(word)
                    }
                }
            } else {
                append(shown)
            }
        }

        val align = when (style.position) {
            CaptionPosition.TOP -> Alignment.TopCenter
            CaptionPosition.MIDDLE -> Alignment.Center
            CaptionPosition.BOTTOM -> Alignment.BottomCenter
        }
        val edge = maxHeight * 0.08f
        val padTop = if (style.position == CaptionPosition.TOP) edge else 0.dp
        val padBottom = if (style.position == CaptionPosition.BOTTOM) edge else 0.dp
        val boxPad = with(density) { (textSizePx * 0.25f).toDp() }

        Box(
            modifier = Modifier
                .align(align)
                .padding(top = padTop, bottom = padBottom)
                .offset(y = maxHeight * frame.offsetY)
                .fillMaxWidth(0.92f)
                .graphicsLayer {
                    alpha = frame.alpha
                    scaleX = frame.scale
                    scaleY = frame.scale
                },
            contentAlignment = Alignment.Center
        ) {
            val boxModifier = if (style.background) {
                Modifier
                    .clip(RoundedCornerShape(boxPad))
                    .background(Color(style.backgroundColor))
                    .padding(horizontal = boxPad * 1.5f, vertical = boxPad)
            } else {
                Modifier
            }
            Box(modifier = boxModifier, contentAlignment = Alignment.Center) {
                if (style.outlineWidth > 0.001f) {
                    Text(
                        text = shown,
                        color = Color(style.outlineColor),
                        fontSize = fontSize,
                        fontFamily = family,
                        fontWeight = weight,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        style = TextStyle(drawStyle = Stroke(width = outlinePx, join = StrokeJoin.Round))
                    )
                }
                Text(
                    text = annotated,
                    color = fillColor,
                    fontSize = fontSize,
                    fontFamily = family,
                    fontWeight = weight,
                    textAlign = TextAlign.Center,
                    maxLines = 3
                )
            }
        }
    }
}

@Composable
fun CaptionStudioScreen(
    scenes: List<Scene>,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    BackHandler(enabled = true) { onClose() }

    val style = CaptionStore.style
    fun update(newStyle: CaptionStyle) {
        CaptionStore.style = newStyle.normalized()
    }

    // The sample uses the first scene that has narration text.
    val sampleScene = remember(scenes) { scenes.firstOrNull { it.text.isNotBlank() } }
    val sampleChunks = remember(sampleScene, style.maxWords, style.maxChars) {
        if (sampleScene == null) {
            emptyList()
        } else {
            CaptionEngine.build(listOf(sampleScene), style).chunks
        }
    }
    val loopMs = if (sampleScene == null) {
        1L
    } else {
        minOf(sampleScene.endMs - sampleScene.startMs, 6000L).coerceAtLeast(1L)
    }

    var clock by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            if (last != 0L) clock += (now - last) / 1_000_000L
            last = now
        }
    }
    val sampleTime = if (sampleScene == null) 0L else sampleScene.startMs + clock % loopMs
    val sampleChunk = CaptionEngine.chunkAt(sampleChunks, sampleTime)

    val allChunks = remember(scenes, style.maxWords, style.maxChars) {
        CaptionEngine.build(scenes, style)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CsNavy)
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
                        .clickable(onClick = onClose)
                        .padding(8.dp)
                )
                Text(
                    text = "Caption Studio",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Done",
                    color = CsOk,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onClose)
                        .padding(8.dp)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))

            CsChip(
                text = if (style.enabled) "Captions: ON  (tap to turn off)" else "Captions: OFF  (tap to turn on)",
                selected = style.enabled,
                modifier = Modifier.fillMaxWidth(),
                onClick = { update(style.copy(enabled = !style.enabled)) }
            )
            Spacer(modifier = Modifier.height(10.dp))

            val frameShape = RoundedCornerShape(16.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(frameShape)
                    .background(Color.Black)
                    .border(1.dp, CsBlue.copy(alpha = 0.6f), frameShape)
            ) {
                if (sampleScene == null) {
                    Text(
                        text = "No scene has narration text, so there is nothing to caption.",
                        color = CsSoft,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp)
                    )
                } else {
                    val request = remember(sampleScene.imageUri) {
                        ImageRequest.Builder(context)
                            .data(sampleScene.imageUri)
                            .size(900)
                            .build()
                    }
                    AsyncImage(
                        model = request,
                        contentDescription = sampleScene.imageName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (sampleChunk != null) {
                        CaptionOverlay(
                            chunk = sampleChunk,
                            style = style,
                            localMs = sampleTime - sampleChunk.startMs,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
            Text(
                text = "Live sample (scene #${sampleScene?.index ?: "-"}). This is how the captions will look.",
                color = CsSoft,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))

            CsTitle("Animation")
            ChipGrid(
                items = CaptionAnimation.values().toList(),
                label = { it.label },
                isSelected = { it == style.animation },
                onSelect = { update(style.copy(animation = it)) }
            )

            CsTitle("Position")
            ChipGrid(
                items = CaptionPosition.values().toList(),
                label = { it.label },
                isSelected = { it == style.position },
                onSelect = { update(style.copy(position = it)) }
            )

            CsTitle("Font")
            val fonts = CaptionFont.values().filter {
                it != CaptionFont.CUSTOM || style.customFontPath != null
            }
            ChipGrid(
                items = fonts,
                label = { it.label },
                isSelected = { it == style.font },
                onSelect = { update(style.copy(font = it)) }
            )

            CsTitle("Size: ${"%.1f".format(style.sizePercent)}% of video height")
            CsSlider(
                value = style.sizePercent,
                range = CaptionStyle.MIN_SIZE..CaptionStyle.MAX_SIZE,
                steps = 0,
                onChange = { update(style.copy(sizePercent = it)) }
            )

            CsTitle("Words together: ${style.maxWords}")
            CsSlider(
                value = style.maxWords.toFloat(),
                range = 1f..8f,
                steps = 6,
                onChange = { update(style.copy(maxWords = it.toInt())) }
            )

            CsTitle("Outline: ${(style.outlineWidth * 100).toInt()}%")
            CsSlider(
                value = style.outlineWidth,
                range = 0f..0.3f,
                steps = 0,
                onChange = { update(style.copy(outlineWidth = it)) }
            )

            CsTitle("Text colour")
            Swatches(
                colors = textPalette,
                selected = style.textColor,
                onSelect = { update(style.copy(textColor = it)) }
            )
            CsTitle("Outline colour")
            Swatches(
                colors = outlinePalette,
                selected = style.outlineColor,
                onSelect = { update(style.copy(outlineColor = it)) }
            )
            if (style.animation == CaptionAnimation.WORD_POP) {
                CsTitle("Highlight colour (active word)")
                Swatches(
                    colors = highlightPalette,
                    selected = style.highlightColor,
                    onSelect = { update(style.copy(highlightColor = it)) }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CsChip(
                    text = if (style.background) "Background box: ON" else "Background box: OFF",
                    selected = style.background,
                    modifier = Modifier.weight(1f),
                    onClick = { update(style.copy(background = !style.background)) }
                )
                CsChip(
                    text = if (style.uppercase) "CAPITALS: ON" else "CAPITALS: OFF",
                    selected = style.uppercase,
                    modifier = Modifier.weight(1f),
                    onClick = { update(style.copy(uppercase = !style.uppercase)) }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            val infoShape = RoundedCornerShape(14.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(infoShape)
                    .background(CsDark)
                    .border(
                        1.dp,
                        (if (allChunks.warnings.isEmpty()) CsOk else CsWarn).copy(alpha = 0.7f),
                        infoShape
                    )
                    .padding(12.dp)
            ) {
                Text(
                    text = "${allChunks.chunks.size} caption pieces for ${scenes.size} scenes",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Timing inside a scene is shared by letter count, because only the scene times are known.",
                    color = CsSoft,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
                allChunks.warnings.forEach { warning ->
                    Text(
                        text = "⚠ $warning",
                        color = CsWarn,
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
private fun CsTitle(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
    )
}

@Composable
private fun <T> ChipGrid(
    items: List<T>,
    label: (T) -> String,
    isSelected: (T) -> Boolean,
    onSelect: (T) -> Unit
) {
    items.chunked(3).forEach { row ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            row.forEach { item ->
                CsChip(
                    text = label(item),
                    selected = isSelected(item),
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(item) }
                )
            }
            repeat(3 - row.size) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CsChip(
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
            .background(if (selected) CsBlue else CsCard)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) Color.White else CsBlue.copy(alpha = 0.6f),
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
private fun CsSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit
) {
    Slider(
        value = value.coerceIn(range.start, range.endInclusive),
        onValueChange = onChange,
        valueRange = range,
        steps = steps,
        colors = SliderDefaults.colors(
            thumbColor = CsBlue,
            activeTrackColor = CsBlue,
            inactiveTrackColor = Color(0xFF14264D)
        )
    )
}

@Composable
private fun Swatches(
    colors: List<Long>,
    selected: Long,
    onSelect: (Long) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        colors.forEach { value ->
            val isSelected = value == selected
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(value))
                    .border(
                        if (isSelected) 3.dp else 1.dp,
                        if (isSelected) Color.White else Color.White.copy(alpha = 0.4f),
                        CircleShape
                    )
                    .clickable { onSelect(value) }
            )
        }
    }
}
