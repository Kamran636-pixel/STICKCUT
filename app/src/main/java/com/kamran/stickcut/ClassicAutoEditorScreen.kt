package com.kamran.stickcut

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ClassicNavy = Color(0xFF050B1F)
private val ClassicBlue = Color(0xFF2D8CFF)
private val ClassicCard = Color(0xFF0F2F70)
private val ClassicCardDark = Color(0xFF0A1A44)

@Composable
fun ClassicAutoEditorScreen(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ClassicNavy)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 90.dp)
        ) {
            ClassicTopBar(title = "Classic AutoEditor", onBack = onBack)
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Import your images and voiceover.\nLet StickCut do the magic!",
                color = Color.White,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))

            PreviewBox()
            Spacer(modifier = Modifier.height(20.dp))

            ImportRow(icon = "🖼", label = "Import Images")
            Spacer(modifier = Modifier.height(10.dp))
            ImportRow(icon = "🎙", label = "Import Voiceover")
            Spacer(modifier = Modifier.height(10.dp))
            ImportRow(icon = "📄", label = "Optional Script")
            Spacer(modifier = Modifier.height(24.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(ClassicBlue)
                    .clickable { },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Start Auto Edit  →",
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
private fun ClassicTopBar(title: String, onBack: () -> Unit) {
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
                .clickable(onClick = onBack)
                .padding(8.dp)
        )
        Text(
            text = title,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = "🏠",
            fontSize = 24.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onBack)
                .padding(8.dp)
        )
    }
}

@Composable
private fun PreviewBox() {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(ClassicCard, ClassicCardDark)))
            .border(1.dp, ClassicBlue.copy(alpha = 0.6f), shape),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "▶", color = ClassicBlue, fontSize = 44.sp)
            Text(
                text = "Your images will appear here",
                color = Color(0xFFB8D4FF),
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun ImportRow(icon: String, label: String, onClick: () -> Unit = {}) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(ClassicCard, ClassicCardDark)))
            .border(1.dp, ClassicBlue.copy(alpha = 0.5f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = icon, fontSize = 24.sp)
        Text(
            text = label,
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 14.dp)
        )
    }
}
