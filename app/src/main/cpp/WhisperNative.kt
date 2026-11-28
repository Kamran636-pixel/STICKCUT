package com.kamran.stickcut

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// Step 90: bridge to the offline Whisper engine (C++). For now it can only
// report whether the engine loaded. Speech recognition comes in a later step.
// ---------------------------------------------------------------------------

object WhisperNative {
    private var loadError: String? = null
    private var loaded = false

    // Returns null when all is fine, or a short error text.
    fun ensureLoaded(): String? {
        if (loaded) return null
        loadError?.let { return it }
        return try {
            System.loadLibrary("stickcut_whisper")
            loaded = true
            null
        } catch (e: UnsatisfiedLinkError) {
            val text = "The offline engine could not be loaded: ${e.message}"
            loadError = text
            text
        } catch (e: Exception) {
            val text = "The offline engine failed: ${e.message}"
            loadError = text
            text
        }
    }

    external fun systemInfo(): String
}

@Composable
fun OfflineEngineCheck() {
    var result by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }

    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .clip(shape)
            .background(Color(0xFF0A1A44))
            .border(1.dp, Color(0xFF2D8CFF).copy(alpha = 0.5f), shape)
            .padding(14.dp)
    ) {
        Text(
            text = "Offline engine (Whisper, no key)",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Step 90 test only: checks that the offline engine is inside the app. It cannot make captions yet.",
            color = Color(0xFFB8D4FF),
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
        val chipShape = RoundedCornerShape(12.dp)
        Box(
            modifier = Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .height(40.dp)
                .clip(chipShape)
                .background(Color(0xFF2D8CFF))
                .border(2.dp, Color.White, chipShape)
                .clickable {
                    val error = WhisperNative.ensureLoaded()
                    if (error != null) {
                        ok = false
                        result = error
                    } else {
                        try {
                            result = WhisperNative.systemInfo()
                            ok = true
                        } catch (e: UnsatisfiedLinkError) {
                            ok = false
                            result = "The engine loaded but the check function was not found: ${e.message}"
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Check engine",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
        val text = result
        if (text != null) {
            Text(
                text = (if (ok) "✅ Engine loaded.\n" else "⚠ ") + text,
                color = if (ok) Color(0xFF6BE3A0) else Color(0xFFFFC857),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
