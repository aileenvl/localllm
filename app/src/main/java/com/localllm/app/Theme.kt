package com.localllm.app

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * App-wide Material 3 color scheme. Single dark palette — the app is intended
 * to be a developer/operator tool, so a system-following dynamic theme adds
 * complexity without much benefit.
 *
 * Design rule: brand teal is reserved for the LIVE status dot, primary CTAs,
 * progress indicators, and the user message bubble. All other interactive
 * surfaces use the neutral tonal scale (surface / surfaceVariant) to avoid
 * teal-on-teal saturation. Tertiary (warm amber) is for warnings / NPU
 * indicators; error (coral) replaces the old red.
 *
 * Contrast: surface (#14181A) vs onSurface (#E2E2E2) ≈ 13.5:1, comfortably
 * above WCAG AA 4.5:1 for normal text. primary (#6BD3CC) vs onPrimary
 * (#003733) ≈ 10.4:1.
 */
val DarkColors = darkColorScheme(
    // Brand accent — softer mint teal so it doesn't paint every button.
    primary = Color(0xFF6BD3CC),
    onPrimary = Color(0xFF003733),
    primaryContainer = Color(0xFF1F4F4B),
    onPrimaryContainer = Color(0xFFB6E9E4),

    // Secondary / tertiary stay distinct but quieter than before.
    secondary = Color(0xFF8FB7BE),
    onSecondary = Color(0xFF003640),
    secondaryContainer = Color(0xFF2A4A50),
    onSecondaryContainer = Color(0xFFCBE3E9),

    tertiary = Color(0xFFE3B273),
    onTertiary = Color(0xFF3F2700),
    tertiaryContainer = Color(0xFF513C18),
    onTertiaryContainer = Color(0xFFFFDDB2),

    error = Color(0xFFEF8F86),
    onError = Color(0xFF5F1410),
    errorContainer = Color(0xFF72241E),
    onErrorContainer = Color(0xFFFFDAD5),

    // Surface tonal scale — most of the "calmer" feel comes from here.
    background = Color(0xFF0E1113),
    onBackground = Color(0xFFE2E2E2),
    surface = Color(0xFF14181A),
    onSurface = Color(0xFFE2E2E2),
    surfaceVariant = Color(0xFF222729),
    onSurfaceVariant = Color(0xFFA7AEB1),
    outline = Color(0xFF3A4145),
    outlineVariant = Color(0xFF252A2D),
    inverseSurface = Color(0xFFE2E2E2),
    inverseOnSurface = Color(0xFF14181A),
    inversePrimary = Color(0xFF195149),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFF6BD3CC),
)
