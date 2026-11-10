package com.kamran.stickcut

import androidx.compose.foundation.Image
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val HomeNavy = Color(0xFF050B1F)
private val CardBlue = Color(0xFF0F2F70)
private val CardBlueDark = Color(0xFF0A1A44)
private val HomeBlue = Color(0xFF2D8CFF)
private val GlowBorder = Color(0xFF3FA2FF)

@Composable
fun HomeScreen(
    onOpenClassic: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HomeNavy)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 90.dp)
        ) {
            HomeTopBar()
            Spacer(modifier = Modifier.height(16.dp))

            HeroBanner()
            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DashCard(
                    icon = "⚡",
                    title = "Classic\nAutoEditor",
                    subtitle = "Simple & Fast",
                    modifier = Modifier.weight(1f),
                    cardHeight = 150,
                    onClick = onOpenClassic
                )
                DashCard(
                    icon = "🎞",
                    title = "Advanced\nEditor",
                    subtitle = "Timestamp + Image Mapping",
                    modifier = Modifier.weight(1f),
                    cardHeight = 150
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DashCard(
                    icon = "✂️",
                    title = "Manual\nVideo Editor",
                    subtitle = "Full Control",
                    modifier = Modifier.weight(1f),
                    cardHeight = 120
                )
                DashCard(
                    icon = "🕘",
                    title = "Recent\nProjects",
                    subtitle = "Open saved",
                    modifier = Modifier.weight(1f),
                    cardHeight = 120
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DashCard(
                    icon = "🤖",
                    title = "AI Caption\nStudio",
                    subtitle = "Auto captions",
                    modifier = Modifier.weight(1f),
                    cardHeight = 120
                )
                DashCard(
                    icon = "🎬",
                    title = "Animation\nStudio",
                    subtitle = "Motion & transitions",
                    modifier = Modifier.weight(1f),
                    cardHeight = 120
                )
            }
            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                BottomItem(icon = "⚙", label = "Settings")
                BottomItem(icon = "❓", label = "Help &\nTutorials")
                BottomItem(icon = "⋯", label = "More")
            }
        }

        CreatorBrand(modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun HomeTopBar() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "☰", color = Color.White, fontSize = 26.sp)
        Text(
            text = "StickCut",
            color = HomeBlue,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
        Text(text = "⚙", color = HomeBlue, fontSize = 26.sp)
    }
}

@Composable
private fun HeroBanner() {
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.horizontalGradient(listOf(Color(0xFF123A8C), Color(0xFF0A1A44)))
            )
            .border(1.dp, GlowBorder.copy(alpha = 0.6f), shape)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Turn Your Ideas\nInto Amazing Videos",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Smart Editing. Powerful Tools.",
                color = Color(0xFFB8D4FF),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        Image(
            painter = painterResource(id = R.drawable.stickcut_logo),
            contentDescription = "StickCut logo",
            modifier = Modifier
                .size(90.dp)
                .clip(RoundedCornerShape(20.dp))
        )
    }
}

@Composable
private fun DashCard(
    icon: String,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    cardHeight: Int = 120,
    onClick: () -> Unit = {}
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .height(cardHeight.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(CardBlue, CardBlueDark)))
            .border(1.dp, GlowBorder.copy(alpha = 0.6f), shape)
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = icon, fontSize = 30.sp)
        Text(
            text = title,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            text = subtitle,
            color = Color(0xFFB8D4FF),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun BottomItem(
    icon: String,
    label: String,
    onClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = icon, fontSize = 26.sp, color = HomeBlue)
        Text(
            text = label,
            color = Color.White,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
