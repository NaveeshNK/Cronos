package com.cronos.camera.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val scheme = darkColorScheme(
    primary = Color(0xFFE8B86D),
    onPrimary = Color(0xFF1A1208),
    secondary = Color(0xFF7EB8D4),
    background = Color(0xFF0B0F14),
    surface = Color(0xFF121820),
    onBackground = Color(0xFFE8EEF5),
    onSurface = Color(0xFFE8EEF5)
)

@Composable
fun CronosTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
