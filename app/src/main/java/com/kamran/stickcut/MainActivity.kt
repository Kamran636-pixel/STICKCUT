package com.kamran.stickcut

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val Navy = Color(0xFF050B1F)
private val ElectricBlue = Color(0xFF2D8CFF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StickCutApp()
        }
    }
}

@Composable
fun StickCutApp() {
    var showSplash by remember { mutableStateOf(true) }

    if (showSplash) {
        SplashScreen(onFinished = { showSplash = false })
    } else {
        HomeScreen()
    }
}

@Composable
fun SplashScreen(onFinished: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(2500)
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Navy)
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.stickcut_logo),
                contentDescription = "StickCut logo",
                modifier = Modifier
                    .size(220.dp)
                    .clip(RoundedCornerShape(48.dp))
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "StickCut",
                color = ElectricBlue,
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Edit • Create • Inspire",
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier.padding(top = 6.dp)
            )

            Spacer(modifier = Modifier.height(36.dp))

            LinearProgressIndicator(
                modifier = Modifier.width(200.dp),
                color = ElectricBlue,
                trackColor = Color(0xFF14264D)
            )
            Text(
                text = "Loading...",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 10.dp)
            )
        }

        CreatorBrand(modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
fun CreatorBrand(modifier: Modifier = Modifier) {
    Text(
        text = "👑 Created by Kamran",
        color = Color.White,
        fontSize = 13.sp,
        modifier = modifier.padding(bottom = 32.dp)
    )
}
