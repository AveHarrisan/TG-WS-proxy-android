package com.aveharrisan.tgwsproxy.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(
    primary = Color(0xFF1A7FC1), onPrimary = Color.White,
    primaryContainer = Color(0xFFD2E8FA), onPrimaryContainer = Color(0xFF002A44),
    secondary = Color(0xFF4E6272), tertiary = Color(0xFF2E8B57),
    background = Color(0xFFF7F9FC), surface = Color(0xFFF7F9FC),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8CCBFF), onPrimary = Color(0xFF00324F),
    primaryContainer = Color(0xFF004A72), onPrimaryContainer = Color(0xFFD2E8FA),
    secondary = Color(0xFFB5C9DB), tertiary = Color(0xFF7FD8A4),
    background = Color(0xFF0F1418), surface = Color(0xFF0F1418),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
