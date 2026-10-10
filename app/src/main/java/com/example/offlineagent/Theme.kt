package com.example.offlineagent

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LocalPilotColors = darkColorScheme(
    primary = Color(0xFF8FA8FF),
    onPrimary = Color(0xFF101A3B),
    primaryContainer = Color(0xFF22356D),
    onPrimaryContainer = Color(0xFFDCE3FF),
    secondary = Color(0xFF77DFC0),
    onSecondary = Color(0xFF07382C),
    secondaryContainer = Color(0xFF124E40),
    onSecondaryContainer = Color(0xFFB4F2DE),
    tertiary = Color(0xFFFFCA7A),
    background = Color(0xFF0B1020),
    onBackground = Color(0xFFE7EAF2),
    surface = Color(0xFF111827),
    onSurface = Color(0xFFE7EAF2),
    surfaceVariant = Color(0xFF1A2235),
    onSurfaceVariant = Color(0xFFB8C0D4),
    error = Color(0xFFFF8B8B)
)

@Composable
fun LocalPilotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LocalPilotColors,
        content = content
    )
}
