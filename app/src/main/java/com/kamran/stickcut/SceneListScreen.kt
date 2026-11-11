package com.kamran.stickcut

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest

private val SceneNavy = Color(0xFF050B1F)
private val SceneBlue = Color(0xFF2D8CFF)
private val SceneDark = Color(0xFF0A1A44)
private val SceneOk = Color(0xFF6BE3A0)
private val SceneErr = Color(0xFFFF6B6B)
private val SceneSoft = Color(0xFFB8D4FF)

private fun secondsText(ms: Long): String {
    val whole = ms / 1000L
    val tenth = (ms % 1000L) / 100L
    return "$whole.$tenth s"
}

@Composable
fun SceneListScreen(
    result: SceneBuildResult,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    BackHandler(enabled = true) { onClose() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SceneNavy)
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
                    text = "Scene List",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.size(44.dp))
            }
            Spacer(modifier = Modifier.height(8.dp))

            val summaryShape = RoundedCornerShape(14.dp)
            val summaryColor = if (result.ok) SceneOk else SceneErr
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(summaryShape)
                    .background(SceneDark)
                    .border(1.dp, summaryColor.copy(alpha = 0.7f), summaryShape)
                    .padding(12.dp)
            ) {
                Text(
                    text = if (result.ok) "✓ Scenes created" else "✖ Scenes could not be created",
                    color = summaryColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                if (result.scenes.isNotEmpty()) {
                    Text(
                        text = "${result.scenes.size} scenes  •  total ${ScriptParser.formatTimestamp(result.totalMs)}",
                        color = SceneSoft,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                result.problems.forEach { problem ->
                    Text(
                        text = "✖ $problem",
                        color = SceneErr,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(result.scenes, key = { it.index }) { scene ->
                    val shape = RoundedCornerShape(14.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(SceneDark)
                            .border(1.dp, SceneBlue.copy(alpha = 0.4f), shape)
                            .padding(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(scene.imageUri)
                                .size(240)
                                .build(),
                            contentDescription = scene.imageName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 10.dp)
                        ) {
                            Text(
                                text = "#${scene.index}  •  Image ${scene.imageNumber}  •  Part ${scene.partPosition + 1}",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${ScriptParser.formatTimestamp(scene.startMs)} → ${ScriptParser.formatTimestamp(scene.endMs)}  (${secondsText(scene.durationMs)})",
                                color = SceneBlue,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            Text(
                                text = scene.text,
                                color = SceneSoft,
                                fontSize = 12.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
            }
        }

        CreatorBrand(modifier = Modifier.align(Alignment.BottomCenter))
    }
}
