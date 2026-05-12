package com.localllm.app

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * App-wide Material 3 color scheme. Single dark palette — the app is intended
 * to be a developer/operator tool, so a system-following dynamic theme adds
 * complexity without much benefit.
 *
 * Palette rationale: teal-leaning brand. Primary is the existing brand teal
 * (#4ECDC4). Secondary is a softer, cooler teal-blue that pairs without
 * competing. Tertiary is a warm amber used sparingly for highlights/badges so
 * it actually reads as a distinct accent against the cool palette. Error is a
 * warm coral that stays clearly separable from the tertiary amber.
 *
 * Surface (#1A2028) vs onSurface (#E6E1E5) ≈ 13.6:1 — comfortably above the
 * WCAG AA 4.5:1 threshold for normal text. Surface was darkened from the prior
 * #1F2833 to improve contrast further with the new accents and to deepen the
 * panel/background separation against background #0B0C10.
 */
val DarkColors = darkColorScheme(
    primary = Color(0xFF4ECDC4),
    onPrimary = Color(0xFF003D3A),
    primaryContainer = Color(0xFF4ECDC4).copy(alpha = 0.2f),
    secondary = Color(0xFF7AC9D1),
    onSecondary = Color(0xFF00363D),
    tertiary = Color(0xFFFFB45A),
    onTertiary = Color(0xFF3D2400),
    background = Color(0xFF0B0C10),
    surface = Color(0xFF1A2028),
    surfaceVariant = Color(0xFF2C3E50),
    error = Color(0xFFF76C5E),
    onError = Color(0xFF5F0F0A),
    errorContainer = Color(0xFFF76C5E).copy(alpha = 0.2f)
)
