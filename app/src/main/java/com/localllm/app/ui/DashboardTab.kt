package com.localllm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localllm.app.R
import com.localllm.app.RequestTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DashboardTab(coroutineScope: CoroutineScope) {
    val queue by RequestTracker.queue.collectAsState()
    val current by RequestTracker.current.collectAsState()
    val history by RequestTracker.history.collectAsState()
    val stats by RequestTracker.stats.collectAsState()

    // Live tick for elapsed-time displays. Only runs while a request is in flight.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(current != null) {
        if (current != null) {
            while (true) {
                now = System.currentTimeMillis()
                delay(250)
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.dash_live), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.weight(1f))
                        QueueBadge(depth = queue.size)
                    }
                    val cur = current
                    if (cur == null) {
                        Text(stringResource(R.string.dash_idle), color = MaterialTheme.colorScheme.secondary)
                    } else {
                        CurrentRequestCard(entry = cur, now = now)
                    }
                    if (queue.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.dash_waiting, queue.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        queue.forEachIndexed { idx, e ->
                            QueuedRequestRow(position = idx + 1, entry = e, now = now)
                        }
                    }
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.dash_stats), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = { coroutineScope.launch { RequestTracker.resetStats() } },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) { Text(stringResource(R.string.dash_reset)) }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile(label = stringResource(R.string.dash_stat_total), value = stats.totalRequests.toString(), modifier = Modifier.weight(1f))
                        StatTile(label = stringResource(R.string.dash_stat_ok), value = stats.totalCompleted.toString(), modifier = Modifier.weight(1f))
                        StatTile(label = stringResource(R.string.dash_stat_err), value = (stats.totalErrors + stats.totalCancelled).toString(), modifier = Modifier.weight(1f))
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile(
                            label = stringResource(R.string.dash_stat_avg_latency),
                            value = if (stats.totalCompleted > 0) "${stats.avgLatencyMs} ms" else "—",
                            modifier = Modifier.weight(1f)
                        )
                        StatTile(
                            label = stringResource(R.string.dash_stat_avg_tokens),
                            value = if (stats.avgChunksPerSec > 0) "%.1f".format(stats.avgChunksPerSec) else "—",
                            modifier = Modifier.weight(1f)
                        )
                        StatTile(
                            label = stringResource(R.string.dash_stat_err_rate),
                            value = if (stats.totalRequests > 0) "${(stats.errorRate * 100).toInt()}%" else "—",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.dash_history, history.size),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                if (history.isNotEmpty()) {
                    TextButton(
                        onClick = { coroutineScope.launch { RequestTracker.clearHistory() } },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) { Text(stringResource(R.string.dash_clear)) }
                }
            }
        }

        if (history.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.dash_no_history),
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(8.dp)
                )
            }
        } else {
            items(history, key = { it.id }) { entry ->
                HistoryRow(entry = entry)
            }
        }
    }
}

@Composable
private fun QueueBadge(depth: Int) {
    val color = when {
        depth == 0 -> MaterialTheme.colorScheme.surfaceVariant
        depth <= 2 -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.errorContainer
    }
    Box(
        modifier = Modifier
            .background(color, shape = MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = stringResource(R.string.dash_queue_label, depth),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun CurrentRequestCard(entry: RequestTracker.Entry, now: Long) {
    val elapsed = entry.inferenceMs(now)
    val waited = entry.queueWaitMs(now)
    val tokPerSec = if (elapsed > 250 && entry.chunkCount > 0) {
        entry.chunkCount.toFloat() * 1000f / elapsed
    } else 0f

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(Color(0xFF4ECDC4), shape = CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "#${entry.id} ${entry.model}${if (entry.stream) " (stream)" else ""}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
        }
        Text(
            text = "elapsed ${formatMs(elapsed)} · waited ${formatMs(waited)} · ${entry.chunkCount} tok · ${"%.1f".format(tokPerSec)} tok/s",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = "prompt: ${entry.messageCount} msgs / ${entry.promptChars} chars",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

@Composable
private fun QueuedRequestRow(position: Int, entry: RequestTracker.Entry, now: Long) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "#$position",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.width(28.dp)
        )
        Text(
            text = entry.model,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${entry.queueWaitMs(now)} ms",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small)
            .padding(vertical = 10.dp, horizontal = 8.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
private fun HistoryRow(entry: RequestTracker.Entry) {
    val (statusColor, statusLabel) = when (entry.state) {
        RequestTracker.State.COMPLETED -> Color(0xFF4ECDC4) to stringResource(R.string.dash_state_ok)
        RequestTracker.State.ERRORED -> Color(0xFFFF4757) to stringResource(R.string.dash_state_err)
        RequestTracker.State.CANCELLED -> Color(0xFFC5C6C7) to stringResource(R.string.dash_state_cxl)
        else -> Color(0xFFC5C6C7) to entry.state.name
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .background(statusColor.copy(alpha = 0.25f), shape = MaterialTheme.shapes.small)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "#${entry.id}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = entry.model,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = relativeTime(entry.completedAt ?: entry.enqueuedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            val infMs = entry.inferenceMs()
            val tps = entry.chunksPerSec
            Text(
                text = "${formatMs(infMs)} · ${entry.chunkCount} tok · ${"%.1f".format(tps)} tok/s · waited ${formatMs(entry.queueWaitMs())}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                fontFamily = FontFamily.Monospace
            )
            if (entry.error != null) {
                Text(
                    text = entry.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

internal fun formatMs(ms: Long): String = when {
    ms < 1000 -> "$ms ms"
    ms < 60_000 -> "%.1fs".format(ms / 1000f)
    else -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
}

internal fun relativeTime(timestamp: Long): String {
    val delta = (System.currentTimeMillis() - timestamp) / 1000
    return when {
        delta < 5 -> "just now"
        delta < 60 -> "${delta}s ago"
        delta < 3600 -> "${delta / 60}m ago"
        delta < 86400 -> "${delta / 3600}h ago"
        else -> "${delta / 86400}d ago"
    }
}
