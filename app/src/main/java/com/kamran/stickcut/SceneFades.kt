package com.kamran.stickcut

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

// Fade in at the start and fade out at the end of the WHOLE video.
// The same setting is meant for the picture and for the voiceover sound.
data class SceneFadeState(
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L
)

object SceneFadeEngine {

    const val MIN_MS = 0L
    const val MAX_MS = 2000L
    const val STEP_MS = 100L

    // Snaps to 0.1 s steps inside 0 .. 2 s.
    fun snap(ms: Long): Long {
        val clamped = ms.coerceIn(MIN_MS, MAX_MS)
        return Math.round(clamped.toDouble() / STEP_MS) * STEP_MS
    }

    fun withFadeIn(state: SceneFadeState, ms: Long): SceneFadeState =
        state.copy(fadeInMs = snap(ms))

    fun withFadeOut(state: SceneFadeState, ms: Long): SceneFadeState =
        state.copy(fadeOutMs = snap(ms))

    // The fade lengths that really apply to a video of this length.
    // Neither fade is longer than the video, and together they never are,
    // so the two fades can never overlap.
    fun effective(state: SceneFadeState, totalMs: Long): Pair<Long, Long> {
        if (totalMs <= 0L) return 0L to 0L
        var fadeIn = minOf(state.fadeInMs, totalMs)
        var fadeOut = minOf(state.fadeOutMs, totalMs)
        val sum = fadeIn + fadeOut
        if (sum > totalMs) {
            fadeIn = fadeIn * totalMs / sum
            fadeOut = totalMs - fadeIn
        }
        return fadeIn to fadeOut
    }

    // 0 = fully faded (black / silent), 1 = full picture / full volume.
    // The same value is used for the picture brightness and the sound volume.
    fun gainAt(state: SceneFadeState, totalMs: Long, timeMs: Long): Float {
        val (fadeIn, fadeOut) = effective(state, totalMs)
        var gain = 1f
        if (fadeIn > 0L && timeMs < fadeIn) {
            gain = minOf(gain, timeMs.toFloat() / fadeIn.toFloat())
        }
        if (fadeOut > 0L && timeMs > totalMs - fadeOut) {
            gain = minOf(gain, (totalMs - timeMs).toFloat() / fadeOut.toFloat())
        }
        return gain.coerceIn(0f, 1f)
    }
}

// Holds the current fade settings while the app runs.
// Saving into the project file comes in a later step.
object SceneFadeStore {
    var state by mutableStateOf(SceneFadeState())
}

private val SfBlue = Color(0xFF2D8CFF)
private val SfDark = Color(0xFF0A1A44)
private val SfSoft = Color(0xFFB8D4FF)
private val SfWarn = Color(0xFFFFC857)

private fun fadeText(ms: Long): String {
    return if (ms <= 0L) "Off" else String.format(Locale.US, "%.1fs", ms / 1000f)
}

@Composable
fun SceneFadesPanel(totalMs: Long) {
    val state = SceneFadeStore.state
    var fadeInValue by remember { mutableStateOf(state.fadeInMs.toFloat()) }
    var fadeOutValue by remember { mutableStateOf(state.fadeOutMs.toFloat()) }

    val shape = RoundedCornerShape(14.dp)
    val steps = ((SceneFadeEngine.MAX_MS - SceneFadeEngine.MIN_MS) / SceneFadeEngine.STEP_MS).toInt() - 1
    val range = SceneFadeEngine.MIN_MS.toFloat()..SceneFadeEngine.MAX_MS.toFloat()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(SfDark)
            .border(1.dp, SfBlue.copy(alpha = 0.6f), shape)
            .padding(14.dp)
    ) {
        Text(
            text = "Scene fades",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Fade the whole video in from black at the start and out to black at the end. The voiceover fades with it.",
            color = SfSoft,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Fade in", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(fadeText(fadeInValue.toLong()), color = SfBlue, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = fadeInValue,
            onValueChange = { fadeInValue = it },
            onValueChangeFinished = {
                SceneFadeStore.state = SceneFadeEngine.withFadeIn(
                    SceneFadeStore.state, fadeInValue.toLong()
                )
                fadeInValue = SceneFadeStore.state.fadeInMs.toFloat()
            },
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = SfBlue,
                activeTrackColor = SfBlue,
                inactiveTrackColor = Color(0xFF14264D)
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Fade out", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(fadeText(fadeOutValue.toLong()), color = SfBlue, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = fadeOutValue,
            onValueChange = { fadeOutValue = it },
            onValueChangeFinished = {
                SceneFadeStore.state = SceneFadeEngine.withFadeOut(
                    SceneFadeStore.state, fadeOutValue.toLong()
                )
                fadeOutValue = SceneFadeStore.state.fadeOutMs.toFloat()
            },
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = SfBlue,
                activeTrackColor = SfBlue,
                inactiveTrackColor = Color(0xFF14264D)
            )
        )

        val (usedIn, usedOut) = SceneFadeEngine.effective(state, totalMs)
        if (usedIn < state.fadeInMs || usedOut < state.fadeOutMs) {
            Text(
                text = "The video is short, so the fades are shortened to ${fadeText(usedIn)} in and ${fadeText(usedOut)} out.",
                color = SfWarn,
                fontSize = 12.sp
            )
        }
        Text(
            text = "Preview and export of fades come in the next steps.",
            color = SfWarn,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
