package com.localllm.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localllm.app.LogEntry
import com.localllm.app.R
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

private const val MAX_TAG_CHIPS = 5

@OptIn(FlowPreview::class, ExperimentalFoundationApi::class)
@Composable
fun ConsoleTab(logHistory: List<LogEntry>, listState: LazyListState) {
    val context = LocalContext.current

    // === filter state ===
    var rawQuery by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }
    var levelDebug by remember { mutableStateOf(true) }
    var levelInfo by remember { mutableStateOf(true) }
    var levelWarn by remember { mutableStateOf(true) }
    var levelError by remember { mutableStateOf(true) }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var autoScroll by remember { mutableStateOf(true) }
    var moreTagsExpanded by remember { mutableStateOf(false) }

    // Debounce the search query at 300ms.
    LaunchedEffect(Unit) {
        snapshotFlow { rawQuery }
            .debounce(300)
            .distinctUntilChanged()
            .collect { debouncedQuery = it }
    }

    // Tag frequency — top 5 tags + dropdown for the rest.
    val tagCounts: List<Pair<String, Int>> = remember(logHistory) {
        val freq = HashMap<String, Int>()
        for (entry in logHistory) {
            val tag = extractTag(entry.message) ?: continue
            freq[tag] = (freq[tag] ?: 0) + 1
        }
        freq.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }
    val topTags = tagCounts.take(MAX_TAG_CHIPS).map { it.first }
    val moreTags = if (tagCounts.size > MAX_TAG_CHIPS) tagCounts.drop(MAX_TAG_CHIPS).map { it.first } else emptyList()

    // Filtered list.
    val filtered = remember(
        logHistory, debouncedQuery, levelDebug, levelInfo, levelWarn, levelError, selectedTag
    ) {
        val q = debouncedQuery.trim().lowercase()
        logHistory.filter { entry ->
            val levelOk = when (entry.level) {
                "DEBUG" -> levelDebug
                "INFO" -> levelInfo
                "WARN" -> levelWarn
                "ERROR" -> levelError
                else -> true
            }
            if (!levelOk) return@filter false
            val tagOk = selectedTag?.let { sel -> extractTag(entry.message) == sel } ?: true
            if (!tagOk) return@filter false
            if (q.isEmpty()) true
            else entry.message.lowercase().contains(q) || entry.level.lowercase().contains(q)
        }
    }

    // Local LazyListState so the auto-scroll toggle can disable autoscroll
    // without coordinating with MainActivity's scroll effect.
    val localListState = rememberLazyListState()

    // Auto-scroll: jump to the bottom when filtered grows, but only if user
    // hasn't disabled it.
    LaunchedEffect(filtered.size, autoScroll) {
        if (autoScroll && filtered.isNotEmpty()) {
            localListState.animateScrollToItem(filtered.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF050505))) {
        // ====== Top: search + chips ======
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                value = rawQuery,
                onValueChange = { rawQuery = it },
                placeholder = { Text(stringResource(R.string.console_search_placeholder)) },
                singleLine = true,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null
                    )
                },
                trailingIcon = {
                    if (rawQuery.isNotEmpty()) {
                        IconButton(onClick = { rawQuery = "" }) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Clear"
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            // Level chips + auto-scroll chip, horizontally scrollable.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LevelChip(
                    label = stringResource(R.string.console_level_debug),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    selected = levelDebug,
                    onClick = { levelDebug = !levelDebug }
                )
                LevelChip(
                    label = stringResource(R.string.console_level_info),
                    color = MaterialTheme.colorScheme.onSurface,
                    selected = levelInfo,
                    onClick = { levelInfo = !levelInfo }
                )
                LevelChip(
                    label = stringResource(R.string.console_level_warn),
                    color = MaterialTheme.colorScheme.tertiary,
                    selected = levelWarn,
                    onClick = { levelWarn = !levelWarn }
                )
                LevelChip(
                    label = stringResource(R.string.console_level_error),
                    color = MaterialTheme.colorScheme.error,
                    selected = levelError,
                    onClick = { levelError = !levelError }
                )

                FilterChip(
                    selected = autoScroll,
                    onClick = { autoScroll = !autoScroll },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.VerticalAlignBottom,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    label = { Text(stringResource(R.string.console_autoscroll)) }
                )
            }

            // Tag chips row (only show if any tags exist).
            if (topTags.isNotEmpty() || moreTags.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    topTags.forEach { tag ->
                        FilterChip(
                            selected = selectedTag == tag,
                            onClick = {
                                selectedTag = if (selectedTag == tag) null else tag
                            },
                            label = { Text(tag, fontSize = 12.sp) }
                        )
                    }
                    if (moreTags.isNotEmpty()) {
                        Box {
                            FilterChip(
                                selected = selectedTag != null && selectedTag !in topTags,
                                onClick = { moreTagsExpanded = true },
                                label = {
                                    val sel = selectedTag
                                    val labelText = if (sel != null && sel !in topTags) sel
                                    else stringResource(R.string.console_filter_more)
                                    Text(labelText, fontSize = 12.sp)
                                }
                            )
                            DropdownMenu(
                                expanded = moreTagsExpanded,
                                onDismissRequest = { moreTagsExpanded = false }
                            ) {
                                moreTags.forEach { tag ->
                                    DropdownMenuItem(
                                        text = { Text(tag) },
                                        onClick = {
                                            selectedTag = tag
                                            moreTagsExpanded = false
                                        }
                                    )
                                }
                                if (selectedTag != null) {
                                    DropdownMenuItem(
                                        text = { Text("Clear tag") },
                                        onClick = {
                                            selectedTag = null
                                            moreTagsExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ====== List body ======
        if (filtered.isEmpty()) {
            EmptyState(
                hasAnyFilter = debouncedQuery.isNotEmpty() ||
                        !levelDebug || !levelInfo || !levelWarn || !levelError ||
                        selectedTag != null,
                onClear = {
                    rawQuery = ""
                    levelDebug = true; levelInfo = true; levelWarn = true; levelError = true
                    selectedTag = null
                }
            )
        } else {
            LazyColumn(
                state = localListState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                items(filtered) { log ->
                    LogRow(log = log, context = context)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogRow(log: LogEntry, context: Context) {
    val levelColor = levelColor(log.level)
    val bodyColor = MaterialTheme.colorScheme.onSurface
    val line = "[${log.formattedTime}] ${log.level} ${log.message}"
    val annotated: AnnotatedString = buildAnnotatedString {
        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
            append("[${log.formattedTime}] ")
        }
        withStyle(SpanStyle(color = levelColor, fontWeight = FontWeight.Bold)) {
            append(log.level.padEnd(5))
        }
        append(" ")
        withStyle(SpanStyle(color = bodyColor)) {
            append(log.message)
        }
    }

    Text(
        text = annotated,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("log", line))
                    Toast.makeText(context, context.getString(R.string.console_copied), Toast.LENGTH_SHORT).show()
                }
            )
            .padding(vertical = 2.dp)
    )
}

@Composable
private fun LevelChip(label: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        leadingIcon = {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(color, shape = CircleShape)
            )
        },
        label = { Text(label, fontSize = 12.sp) }
    )
}

@Composable
private fun EmptyState(hasAnyFilter: Boolean, onClear: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Outlined.SearchOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.console_empty_filtered),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (hasAnyFilter) {
            TextButton(onClick = onClear) {
                Text(stringResource(R.string.console_clear_filters))
            }
        }
    }
}

@Composable
private fun levelColor(level: String): Color = when (level) {
    "DEBUG" -> MaterialTheme.colorScheme.onSurfaceVariant
    "INFO" -> MaterialTheme.colorScheme.onSurface
    "WARN" -> MaterialTheme.colorScheme.tertiary
    "ERROR" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurface
}

/**
 * Extract a "[tag]" prefix from a log message. LogManager wraps every message
 * as "[$tag] $message", so this returns the bracketed token when present.
 */
private fun extractTag(message: String): String? {
    if (!message.startsWith("[")) return null
    val end = message.indexOf(']')
    if (end <= 1) return null
    return message.substring(1, end).takeIf { it.isNotBlank() }
}
