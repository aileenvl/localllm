package com.localllm.app

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * App-wide Material 3 color scheme. Single dark palette — the app is intended
 * to be a developer/operator tool, so a system-following dynamic theme adds
 * complexity without much benefit.
 */
val DarkColors = darkColorScheme(
    primary = Color(0xFF4ECDC4),
    onPrimary = Color(0xFF0B0C10),
    primaryContainer = Color(0xFF4ECDC4).copy(alpha = 0.2f),
    secondary = Color(0xFFC5C6C7),
    background = Color(0xFF0B0C10),
    surface = Color(0xFF1F2833),
    surfaceVariant = Color(0xFF2C3E50),
    error = Color(0xFFFF4757),
    errorContainer = Color(0xFFFF4757).copy(alpha = 0.2f)
)
