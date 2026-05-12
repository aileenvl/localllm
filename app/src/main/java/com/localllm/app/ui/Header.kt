package com.localllm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.localllm.app.ServerState

/**
 * Small (8dp) status dot used as the leading element in the app bar. The
 * Activity's [CenterAlignedTopAppBar][androidx.compose.material3.CenterAlignedTopAppBar]
 * embeds this directly. The old multi-line LIVE banner was removed in the
 * 2026 redesign — the dot is the only visual indicator now, and the URL is
 * rendered as the app bar title.
 */
@Composable
fun StatusDot(
    status: ServerState.Status,
    modifier: Modifier = Modifier,
) {
    val color: Color = when (status) {
        ServerState.Status.RUNNING -> MaterialTheme.colorScheme.primary
        ServerState.Status.STARTING -> MaterialTheme.colorScheme.tertiary
        ServerState.Status.ERROR -> MaterialTheme.colorScheme.error
        ServerState.Status.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    Box(
        modifier = modifier
            .size(8.dp)
            .background(color, shape = CircleShape)
    )
}
