package com.localllm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localllm.app.LogEntry

@Composable
fun ConsoleTab(logHistory: List<LogEntry>, listState: LazyListState) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050505))
            .padding(8.dp)
    ) {
        items(logHistory) { log ->
            val color = when (log.level) {
                "ERROR" -> Color(0xFFFF4757)
                "DEBUG" -> Color(0xFF66C2BE)
                else -> Color(0xFFC5C6C7)
            }
            Text(
                text = "[${log.formattedTime}] ${log.message}",
                color = color,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
    }
}
