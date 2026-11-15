package com.kamran.stickcut

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private val TrNavy = Color(0xFF050B1F)
private val TrBlue = Color(0xFF2D8CFF)
private val TrCard = Color(0xFF0F2F70)
private val TrDark = Color(0xFF0A1A44)
private val TrOk = Color(0xFF6BE3A0)
private val TrErr = Color(0xFFFF6B6B)
private val TrWarn = Color(0xFFFFC857)
private val TrSoft = Color(0xFFB8D4FF)

private fun secondsText(ms: Long): String =
    String.format(Locale.US, "%.2fs", ms / 1000f)

@Composable
fun TransitionsScreen(
    scenes: List<Scene>,
    onClose: () -> Unit
) {
    val keys = remember(scenes) {
        scenes.map { CutTransitionEngine.sceneKey(it.imageUri) }
    }
    val state = CutTransitionStore.state

    var selected by remember { mutableStateOf(1) }
    var picked by remember { mutableStateOf(CutTransitionKind.FADE) }
    var note by remember { mutableStateOf<String?>(null) }
    var noteIsError by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableStateOf(state.durationMs.toFloat()) }

    BackHandler(enabled = true) { onClose() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TrNavy)
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
                        .clickable { onClose() }
                        .padding(8.dp)
                )
                Text(
                    text = "Transitions",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.size(44.dp))
            }
            Spacer(modifier = Modifier.height(12.dp))

            if (scenes.size < 2) {
                Text(
                    text = "You need at least 2 scenes to use transitions.",
                    color = TrWarn,
                    fontSize = 14.sp
                )
            } else {
                val cutIndex = selected.coerceIn(1, scenes.size - 1)
                val currentKind = CutTransitionEngine.kindAt(state, keys, cutIndex)

                // ---- Which cut? ----
                Text(
                    text = "Select a cut",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "A transition belongs to the scene that arrives. Scene 1 has none.",
                    color = TrSoft,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(scenes) { position, _ ->
                        if (position >= 1) {
                            val kind = CutTransitionEngine.kindAt(state, keys, position)
                            val isSelected = position == cutIndex
                            val shape = RoundedCornerShape(12.dp)
                            Column(
                                modifier = Modifier
                                    .width(60.dp)
                                    .height(56.dp)
                                    .clip(shape)
                                    .background(if (isSelected) TrBlue else TrCard)
                                    .border(
                                        if (isSelected) 2.dp else 1.dp,
                                        if (isSelected) Color.White else TrBlue.copy(alpha = 0.6f),
                                        shape
                                    )
                                    .clickable { selected = position },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "${position + 1}",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = kind.icon,
                                    color = if (kind == CutTransitionKind.CUT) TrSoft else TrOk,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))

                // ---- Transition cards ----
                Text(
                    text = "Transition for scene ${cutIndex + 1}: ${currentKind.label}",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                CutTransitionKind.values().toList().chunked(4).forEach { rowKinds ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowKinds.forEach { kind ->
                            TrKindCard(
                                kind = kind,
                                selected = kind == currentKind,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    picked = kind
                                    CutTransitionStore.state = CutTransitionEngine.withOne(
                                        CutTransitionStore.state, keys, cutIndex, kind
                                    )
                                    note = null
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Spacer(modifier = Modifier.height(6.dp))

                // ---- Duration ----
                val boxShape = RoundedCornerShape(14.dp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(boxShape)
                        .background(TrDark)
                        .border(1.dp, TrBlue.copy(alpha = 0.6f), boxShape)
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Duration",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = secondsText(sliderValue.toLong()),
                            color = TrBlue,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        onValueChangeFinished = {
                            CutTransitionStore.state = CutTransitionEngine.withDuration(
                                CutTransitionStore.state, sliderValue.toLong()
                            )
                            sliderValue = CutTransitionStore.state.durationMs.toFloat()
                        },
                        valueRange = CutTransitionEngine.MIN_MS.toFloat()..CutTransitionEngine.MAX_MS.toFloat(),
                        steps = 16,
                        colors = SliderDefaults.colors(
                            thumbColor = TrBlue,
                            activeTrackColor = TrBlue,
                            inactiveTrackColor = Color(0xFF14264D)
                        )
                    )
                    Text(
                        text = "0.15s to 1.00s. A short picture automatically gets a shorter transition.",
                        color = TrSoft,
                        fontSize = 12.sp
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))

                // ---- Apply to all ----
                TrButton(
                    text = "Apply ${picked.label} to all cuts",
                    modifier = Modifier.fillMaxWidth(),
                    filled = true,
                    onClick = {
                        CutTransitionStore.state = CutTransitionEngine.withAll(
                            CutTransitionStore.state, keys, picked
                        )
                        note = "Applied ${picked.label} to ${scenes.size - 1} cuts."
                        noteIsError = false
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))

                // ---- Random mix ----
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(boxShape)
                        .background(TrDark)
                        .border(1.dp, TrBlue.copy(alpha = 0.6f), boxShape)
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Random mix",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Switch(
                            checked = state.randomMix,
                            onCheckedChange = {
                                CutTransitionStore.state =
                                    CutTransitionStore.state.copy(randomMix = it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = TrBlue
                            )
                        )
                    }
                    if (state.randomMix) {
                        Text(
                            text = "Pick the transitions to mix:",
                            color = TrSoft,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
                        )
                        CutTransitionKind.values().toList().chunked(4).forEach { rowKinds ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowKinds.forEach { kind ->
                                    TrKindCard(
                                        kind = kind,
                                        selected = kind in state.randomPool,
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            CutTransitionStore.state =
                                                CutTransitionEngine.withPoolToggled(
                                                    CutTransitionStore.state, kind
                                                )
                                        }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        TrButton(
                            text = "Apply random mix to video",
                            modifier = Modifier.fillMaxWidth(),
                            filled = true,
                            onClick = {
                                val now = CutTransitionStore.state
                                if (now.randomPool.isEmpty()) {
                                    note = "Select at least one transition for the mix."
                                    noteIsError = true
                                } else {
                                    CutTransitionStore.state = CutTransitionEngine.withRandomMix(
                                        now, keys, System.nanoTime()
                                    )
                                    note = "Random mix applied to ${scenes.size - 1} cuts."
                                    noteIsError = false
                                }
                            }
                        )
                    }
                }

                val text = note
                if (text != null) {
                    Text(
                        text = text,
                        color = if (noteIsError) TrErr else TrOk,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }

                val usedCount = (1 until scenes.size).count {
                    CutTransitionEngine.kindAt(state, keys, it) != CutTransitionKind.CUT
                }
                Text(
                    text = "$usedCount of ${scenes.size - 1} cuts have a transition.",
                    color = TrSoft,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 10.dp)
                )
                Text(
                    text = "Preview and export of transitions come in the next steps.",
                    color = TrWarn,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Text(
                    text = "Transitions build: Step 61",
                    color = Color(0xFF6C85B8),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        CreatorBrand(modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun TrKindCard(
    kind: CutTransitionKind,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .height(74.dp)
            .clip(shape)
            .background(if (selected) TrBlue else TrCard)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) Color.White else TrBlue.copy(alpha = 0.6f),
                shape
            )
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = kind.icon, color = Color.White, fontSize = 22.sp)
        Text(
            text = kind.label,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun TrButton(
    text: String,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .height(50.dp)
            .clip(shape)
            .background(if (filled) TrBlue else TrCard)
            .border(1.dp, TrBlue.copy(alpha = 0.7f), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
    }
}
